package de.timmyrs.varo;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.server.ServerListPingEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public class Varo extends JavaPlugin implements Listener, CommandExecutor
{
	static final ArrayList<Team> teams = new ArrayList<>();
	static Varo instance;
	static World world;
	private static final HashMap<Integer, ItemStack> startItems = new HashMap<>();
	private static int startTimer = 0;

	private static void handleWorldShrinking()
	{
		if(Varo.world == null || !Varo.instance.getConfig().getBoolean("donttouchthis.ongoing"))
		{
			return;
		}
		double worldSize = Varo.instance.getConfig().getDouble("donttouchthis.worldSize");
		if(worldSize > Varo.instance.getConfig().getInt("baseWorldSize"))
		{
			worldSize -= (Varo.instance.getConfig().getDouble("baseWorldShrinkPerSecond") * 4 * Varo.instance.getConfig().getInt("donttouchthis.shrinkFactor"));
			if(worldSize < Varo.instance.getConfig().getInt("baseWorldSize"))
			{
				worldSize = Varo.instance.getConfig().getInt("baseWorldSize");
			}
			Varo.instance.getConfig().set("donttouchthis.worldSize", worldSize);
			Varo.world.getWorldBorder().setSize(worldSize, 4);
		}
	}

	private static void handleAutomaticStarting()
	{
		if(!Varo.instance.getConfig().getBoolean("autostart.enabled") || Varo.instance.getConfig().getBoolean("donttouchthis.ongoing"))
		{
			return;
		}
		if(Varo.instance.getServer().getOnlinePlayers().size() < (Varo.instance.getConfig().getInt("maxTeamSize") * Varo.instance.getConfig().getInt("autostart.minTeams")))
		{
			if(startTimer != 0)
			{
				startTimer = 0;
			}
		}
		else if(startTimer > -1)
		{
			if(startTimer == 0)
			{
				startTimer = Varo.instance.getConfig().getInt("autostart.time");
			}
			if(startTimer >= (Varo.instance.getConfig().getInt("autostart.reducedTime") + 1) && Varo.instance.getServer().getOnlinePlayers().size() >= (Varo.instance.getConfig().getInt("maxTeamSize") * Varo.instance.getConfig().getInt("autostart.optimalTeams")))
			{
				startTimer = Varo.instance.getConfig().getInt("autostart.reducedTime") + 1;
			}
			if(--startTimer == 0)
			{
				startTimer = -1;
				Varo.instance.getServer().dispatchCommand(Varo.instance.getServer().getConsoleSender(), "varo start");
			}
			else
			{
				for(Player p : Varo.instance.getServer().getOnlinePlayers())
				{
					p.sendTitle("", Message.AUTOSTART_TIME.get(p).replace("%", String.valueOf(startTimer)), 0, 35, 0);
				}
			}
		}
	}

	private static void handleLeaveDisqualification()
	{
		if(!Varo.instance.getConfig().getBoolean("donttouchthis.ongoing") || Varo.instance.getConfig().getInt("leaveDisqualificationMinutes") < 1)
		{
			return;
		}
		final long disqualificationTime = System.currentTimeMillis() - (Varo.instance.getConfig().getInt("leaveDisqualificationMinutes") * 60000L);
		final ArrayList<Team> _teams;
		synchronized(teams)
		{
			_teams = new ArrayList<>(teams);
		}
		for(Team t : _teams)
		{
			synchronized(t.players)
			{
				boolean changed;
				do
				{
					changed = false;
					for(Map.Entry<UUID, Integer> entry : t.players.entrySet())
					{
						final OfflinePlayer p = Varo.instance.getServer().getOfflinePlayer(entry.getKey());
						if(p == null || (!p.isOnline() && p.getLastPlayed() < disqualificationTime))
						{
							if(t.handleLeave(entry.getKey()))
							{
								changed = true;
							}
							break;
						}
					}
				}
				while(changed);
			}
		}
	}

	private static void recursivelyDelete(File file)
	{
		if(file.isDirectory())
		{
			for(File f : Objects.requireNonNull(file.listFiles()))
			{
				recursivelyDelete(f);
			}
		}
		//noinspection ResultOfMethodCallIgnored
		file.delete();
	}

	private static Player getPlayer(String username)
	{
		for(Player p : Varo.instance.getServer().getOnlinePlayers())
		{
			if(p.getName().equalsIgnoreCase(username))
			{
				return p;
			}
		}
		return null;
	}

	private static void clearPlayer(Player p)
	{
		p.setHealth(20);
		p.setExhaustion(0);
		p.setFoodLevel(20);
		p.setLevel(0);
		p.setExp(0);
		p.setFireTicks(0);
		for(PotionEffect pe : p.getActivePotionEffects())
		{
			p.removePotionEffect(pe.getType());
		}
	}

	@Override
	public void onEnable()
	{
		Varo.instance = this;
		for(File f : Objects.requireNonNull(new File(".").listFiles()))
		{
			if(f.getName().startsWith("varo") && f.isDirectory())
			{
				if(Varo.world != null || new File(f.getName() + "/DELETE").exists())
				{
					if(getServer().getWorld(f.getName()) == null)
					{
						recursivelyDelete(f);
					}
				}
				else
				{
					Varo.world = getServer().getWorld(f.getName());
					if(Varo.world == null)
					{
						Varo.world = getServer().createWorld(new WorldCreator(f.getName()));
					}
				}
			}
		}
		reloadVaroConfig();
		getServer().getPluginManager().registerEvents(this, this);
		getCommand("varo").setExecutor(this);
		getCommand("team").setExecutor(this);
		getCommand("t").setExecutor(this);
		getServer().getMessenger().registerOutgoingPluginChannel(this, "BungeeCord");
		getServer().getScheduler().scheduleSyncRepeatingTask(this, Varo::handleWorldShrinking, 0, 75);
		getServer().getScheduler().scheduleSyncRepeatingTask(this, Varo::handleAutomaticStarting, 0, 20);
		getServer().getScheduler().scheduleSyncRepeatingTask(this, Varo::handleLeaveDisqualification, 0, 1200);
	}

	private void reloadVaroConfig()
	{
		reloadConfig();
		getConfig().addDefault("info", "This file should NOT be edited WHILE a Varo round is ONGOING.");
		getConfig().addDefault("maxTeamSize", 2);
		getConfig().addDefault("livesPerPlayer", 1);
		getConfig().addDefault("baseWorldSize", 70);
		getConfig().addDefault("extraWorldSizePerPlayer", 150);
		getConfig().addDefault("baseWorldShrinkPerSecond", 0.20D);
		getConfig().addDefault("leaveDisqualificationMinutes", 0);
		getConfig().addDefault("colorNames", true);
		getConfig().addDefault("worldType", "DEFAULT, FLAT, DEFAULT_1_1, LARGEBIOMES, or AMPLIFIED");
		getConfig().addDefault("generateStructures", true);
		getConfig().addDefault("generatorSettings", "");
		getConfig().addDefault("keepInventory", false);
		getConfig().addDefault("doFireTick", true);
		getConfig().addDefault("mobGriefing", true);
		getConfig().addDefault("showDeathMessages", true);
		getConfig().addDefault("autostart.enabled", false);
		getConfig().addDefault("autostart.minTeams", 2);
		getConfig().addDefault("autostart.time", 180);
		getConfig().addDefault("autostart.optimalTeams", 6);
		getConfig().addDefault("autostart.reducedTime", 30);
		getConfig().addDefault("motd.enabled", false);
		getConfig().addDefault("motd.waiting", "WAITING");
		getConfig().addDefault("motd.ongoing", "IN GAME");
		final ArrayList<HashMap<String, Object>> defaultStartItems = new ArrayList<>();
		final HashMap<String, Object> apples = new HashMap<>();
		apples.put("slot", 2);
		apples.put("type", "APPLE");
		apples.put("amount", 5);
		apples.put("durability", 0);
		defaultStartItems.add(apples);
		final HashMap<String, Object> compass = new HashMap<>();
		compass.put("slot", 8);
		compass.put("type", "COMPASS");
		compass.put("amount", 1);
		compass.put("durability", 0);
		defaultStartItems.add(compass);
		getConfig().addDefault("startItems", defaultStartItems);
		getConfig().addDefault("donttouchthis.info", "The following should NEVER be edited.");
		getConfig().addDefault("donttouchthis.ongoing", false);
		getConfig().addDefault("donttouchthis.teams", new ArrayList<HashMap<String, Object>>());
		getConfig().options().copyDefaults(true);
		if(getConfig().getInt("maxTeamSize") < 1)
		{
			getConfig().set("maxTeamSize", 1);
		}
		//noinspection unchecked
		final ArrayList<HashMap<String, Object>> startItems = (ArrayList<HashMap<String, Object>>) getConfig().getList("startItems");
		synchronized(Varo.startItems)
		{
			Varo.startItems.clear();
			if(startItems != null)
			{
				for(HashMap<String, Object> i : startItems)
				{
					final ItemStack item = new ItemStack(Material.valueOf(((String) i.get("type")).toUpperCase()), (Integer) i.get("amount"));
					if(i.containsKey("durability"))
					{
						item.setDurability(((Integer) i.get("durability")).shortValue());
					}
					Varo.startItems.put((Integer) i.get("slot"), item);
				}
			}
		}
		if(getConfig().getBoolean("donttouchthis.ongoing") && Varo.world == null)
		{
			getConfig().set("donttouchthis.ongoing", false);
		}
		//noinspection unchecked
		final ArrayList<HashMap<String, Object>> teams_ = (ArrayList<HashMap<String, Object>>) getConfig().getList("donttouchthis.teams");
		final boolean fixTeams = (teams_ != null || !getConfig().getBoolean("donttouchthis.ongoing"));
		if(teams_ == null)
		{
			Team.updateConfig();
		}
		else
		{
			synchronized(teams)
			{
				teams.clear();
				for(HashMap<String, Object> t : teams_)
				{
					final Team team = new Team();
					//noinspection unchecked
					for(Map.Entry<String, Integer> entry : ((HashMap<String, Integer>) t.get("players")).entrySet())
					{
						team.players.put(UUID.fromString(entry.getKey()), entry.getValue());
					}
					if(t.containsKey("spawnPoint") && Varo.world != null)
					{
						//noinspection unchecked
						final ArrayList<Double> coords = (ArrayList<Double>) t.get("spawnPoint");
						if(coords.size() == 3)
						{
							team.spawnPoint = new Location(Varo.world, coords.get(0), coords.get(1), coords.get(2));
						}
					}
					if(t.containsKey("name"))
					{
						team.name = (String) t.get("name");
					}
					if(t.containsKey("color"))
					{
						team.color = (String) t.get("color");
					}
					teams.add(team);
				}
			}
		}
		if(fixTeams)
		{
			synchronized(teams)
			{
				final ArrayList<Team> _teams = new ArrayList<>(teams);
				for(Team t : _teams)
				{
					synchronized(t.players)
					{
						if(t.players.size() > getConfig().getInt("maxTeamSize"))
						{
							t.handleDelete();
						}
					}
				}
			}
		}
		saveConfig();
	}

	@Override
	public void onDisable()
	{
		saveConfig();
	}

	@Override
	public boolean onCommand(CommandSender s, Command c, String l, String[] a)
	{
		switch(c.getName())
		{
			case "varo":
				if(a.length == 0)
				{
					s.sendMessage("https://github.com/timmyrs/Varo");
				}
				else
				{
					if(a[0].equalsIgnoreCase("tp"))
					{
						if(s instanceof Player)
						{
							final Player p = (Player) s;
							if(a.length != 2)
							{
								Message.SYNTAX_VARO.send(p);
							}
							else
							{

								if(p.getGameMode() == GameMode.SPECTATOR || p.hasPermission("varo.admin"))
								{
									final Player t = getPlayer(a[1]);
									if(t == null)
									{
										p.sendMessage(Message.ERROR_OFFLINE.get(p).replace("%", a[1]));
									}
									else
									{
										p.teleport(t);
									}
								}
								else
								{
									Message.TELEPORT_UNAUTHORIZED.send(p);
								}
							}
						}
						else
						{
							Message.ERROR_PLAYERS_ONLY.send(s);
						}
					}
					else if(a[0].equalsIgnoreCase("tpcenter"))
					{
						if(s instanceof Player)
						{
							final Player p = (Player) s;
							if(p.getGameMode() == GameMode.SPECTATOR || p.hasPermission("varo.admin"))
							{
								final Location center = p.getWorld().getHighestBlockAt(0, 0).getLocation();
								center.setX(center.getX() + .5);
								center.setZ(center.getZ() + .5);
								p.teleport(center);
							}
							else
							{
								Message.TELEPORT_UNAUTHORIZED.send(p);
							}
						}
						else
						{
							Message.ERROR_PLAYERS_ONLY.send(s);
						}
					}
					else if(a[0].equalsIgnoreCase("start"))
					{
						if(s instanceof Player && !s.hasPermission("varo.start"))
						{
							Message.ERROR_UNAUTHORIZED.send(s);
						}
						else if(getConfig().getBoolean("donttouchthis.ongoing"))
						{
							Message.ERROR_ONGOING.send(s);
						}
						else
						{
							final Collection<? extends Player> onlinePlayers = getServer().getOnlinePlayers();
							if(onlinePlayers.size() / getConfig().getInt("maxTeamSize") < 2)
							{
								Message.START_INSUFFICIENT_PLAYERS.send(s);
								return true;
							}
							synchronized(teams)
							{
								final ArrayList<Team> _teams = new ArrayList<>(teams);
								for(Team t : _teams)
								{
									synchronized(t.players)
									{
										boolean changed;
										do
										{
											changed = false;
											for(Map.Entry<UUID, Integer> entry : t.players.entrySet())
											{
												final OfflinePlayer p = getServer().getOfflinePlayer(entry.getKey());
												if(p == null || !p.isOnline())
												{
													t.handleLeave(entry.getKey());
													changed = true;
													break;
												}
											}

										}
										while(changed);
									}
								}
								final ArrayList<Player> teamless = new ArrayList<>();
								for(Player p : onlinePlayers)
								{
									if(Team.of(p) == null)
									{
										teamless.add(p);
									}
								}
								for(Team t : teams)
								{
									synchronized(t.players)
									{
										if(t.players.size() < getConfig().getInt("maxTeamSize"))
										{
											final ArrayList<Player> removedTeamless = new ArrayList<>();
											for(Player p : teamless)
											{
												synchronized(t.players)
												{
													p.sendMessage(Message.TEAM_JOINED.get(p).replace("%", t.getName()));
													for(Map.Entry<UUID, Integer> entry : t.players.entrySet())
													{
														Player tp = getServer().getPlayer(entry.getKey());
														if(tp.isOnline())
														{
															tp.sendMessage(Message.TEAM_JOIN.get(tp).replace("%", p.getName()));
														}
													}
													t.players.put(p.getUniqueId(), 0);
													removedTeamless.add(p);
													if(t.players.size() < getConfig().getInt("maxTeamSize"))
													{
														break;
													}
												}
											}
											for(Player p : removedTeamless)
											{
												teamless.remove(p);
											}
										}
									}
								}
								if(teamless.size() > 0)
								{
									Team t = new Team();
									for(Player p : teamless)
									{
										if(t.players.size() >= getConfig().getInt("maxTeamSize"))
										{
											teams.add(t);
											t = new Team();
										}
										t.players.put(p.getUniqueId(), 0);
									}
									if(t.players.size() > 0)
									{
										teams.add(t);
									}
								}
								Team.updateConfig();
								if(teams.size() < 2)
								{
									Message.START_INSUFFICIENT_PLAYERS.send(s);
									return true;
								}
								final VaroStartEvent e;
								synchronized(Varo.startItems)
								{
									e = new VaroStartEvent(Varo.startItems);
								}
								Varo.instance.getServer().getPluginManager().callEvent(e);
								if(e.isCancelled())
								{
									return true;
								}
								boolean goodWorld = true;
								do
								{
									for(Player p : onlinePlayers)
									{
										if(goodWorld)
										{
											p.sendTitle(Message.GET_READY.get(p), "", 0, 70, 50);
											Message.GET_READY.send(p);
										}
										else
										{
											p.sendTitle(Message.GET_READY.get(p), Message.GET_READY_AGAIN.get(p), 0, 70, 50);
											Message.GET_READY_AGAIN.send(p);
										}
									}
									goodWorld = true;
									if(Varo.world != null)
									{
										getServer().unloadWorld(Varo.world, true);
										final File deleteIndicator = new File(Varo.world.getName() + "/DELETE");
										if(!deleteIndicator.exists())
										{
											try
											{
												//noinspection ResultOfMethodCallIgnored
												deleteIndicator.createNewFile();
											}
											catch(IOException ignored)
											{
											}
										}
										Varo.world = null;
									}
									String name;
									do
									{
										name = "varo" + ThreadLocalRandom.current().nextInt(1000, 10000);
									}
									while(new File(name).exists());
									WorldType wt = WorldType.getByName(getConfig().getString("worldType"));
									if(wt == null)
									{
										wt = WorldType.NORMAL;
									}
									getServer().createWorld(new WorldCreator(name).type(wt).generateStructures(getConfig().getBoolean("generateStructures")).generatorSettings(getConfig().getString("generatorSettings")));
									Varo.world = getServer().getWorld(name);
									final Location worldSpawn = Varo.world.getHighestBlockAt(0, 0).getLocation();
									worldSpawn.setX(worldSpawn.getX() + .5);
									worldSpawn.setZ(worldSpawn.getZ() + .5);
									placeBedrockUnder(worldSpawn);
									Varo.world.setSpawnLocation(worldSpawn);
									Varo.world.setGameRuleValue("announceAdvancements", "false");
									Varo.world.setGameRuleValue("keepInventory", String.valueOf(getConfig().getBoolean("keepInventory")));
									Varo.world.setGameRuleValue("doFireTick", String.valueOf(getConfig().getBoolean("doFireTick")));
									Varo.world.setGameRuleValue("mobGriefing", String.valueOf(getConfig().getBoolean("mobGriefing")));
									Varo.world.setGameRuleValue("showDeathMessages", String.valueOf(getConfig().getBoolean("showDeathMessages")));
									final double worldSize = (getConfig().getInt("extraWorldSizePerPlayer") * getServer().getOnlinePlayers().size());
									getConfig().set("donttouchthis.worldSize", worldSize + getConfig().getInt("baseWorldSize"));
									getConfig().set("donttouchthis.ongoing", true);
									getConfig().set("donttouchthis.shrinkFactor", 1);
									Varo.world.getWorldBorder().setCenter(Varo.world.getSpawnLocation());
									Varo.world.getWorldBorder().setSize(worldSize);
									Varo.world.getWorldBorder().setWarningDistance(getConfig().getInt("baseWorldSize"));
									Varo.world.getWorldBorder().setDamageBuffer(0);
									final int min = (int) Math.round(worldSize * -0.5);
									final int max = (int) Math.round(worldSize * 0.5) + 1;
									final int spawnThreshold = getConfig().getInt("baseWorldSize") / 2;
									if(getConfig().getBoolean("colorNames"))
									{
										final String[] colors = new String[]{"1", "2", "3", "4", "5", "6", "9", "a", "b", "c", "d", "e", "f", "l", "n", "o"};
										if(teams.size() <= colors.length)
										{
											int i = 0;
											for(Team t : teams)
											{
												t.color = colors[i++];
											}
										}
									}
									for(Team t : teams)
									{
										int tries = 0;
										Block highestBlock;
										do
										{
											final int x = ThreadLocalRandom.current().nextInt(min, max);
											final int z = ThreadLocalRandom.current().nextInt(min, max);
											if(Math.abs(x) < spawnThreshold || Math.abs(z) < spawnThreshold)
											{
												continue;
											}
											highestBlock = Varo.world.getHighestBlockAt(x, z);
											if(highestBlock.getType() == Material.LONG_GRASS || highestBlock.getType() == Material.SNOW)
											{
												highestBlock = Varo.world.getBlockAt(x, highestBlock.getY() - 1, z);
											}
											if(highestBlock != null && highestBlock.getType().isSolid())
											{
												break;
											}
											if(++tries == 50000)
											{
												goodWorld = false;
												break;
											}
										}
										while(true);
										if(!goodWorld)
										{
											break;
										}
										final Location spawnPoint = highestBlock.getLocation();
										spawnPoint.setX(spawnPoint.getX() + .5);
										spawnPoint.setZ(spawnPoint.getZ() + .5);
										placeBedrockUnder(spawnPoint);
										t.spawnPoint = spawnPoint;
										t.name = t.getName();
									}
								}
								while(!goodWorld);
								Team.updateConfig();
								for(Player p : onlinePlayers)
								{
									p.setGameMode(GameMode.SURVIVAL);
									Varo.clearPlayer(p);
									p.getInventory().clear();
									final Team t = Team.of(p);
									p.teleport(t.spawnPoint);
									if(t.color != null)
									{
										p.setPlayerListName("§" + t.color + p.getName());
									}
									synchronized(t.players)
									{
										if(t.players.get(p.getUniqueId()) != 0)
										{
											t.players.put(p.getUniqueId(), 0);
										}
									}
									boolean hasCompass = false;
									for(Map.Entry<Integer, ItemStack> i : e.startItems.entrySet())
									{
										p.getInventory().setItem(i.getKey(), i.getValue().clone());
										if(!hasCompass && i.getValue().getType() == Material.COMPASS)
										{
											hasCompass = true;
										}
									}
									p.getInventory().setHeldItemSlot(0);
									if(getConfig().getInt("maxTeamSize") > 1)
									{
										Message.HAVE_FUN_TEAMS.send(p);
									}
									else
									{
										Message.HAVE_FUN.send(p);
									}
									if(hasCompass)
									{
										Message.COMPASS_INFO.send(p);
									}
								}
								startTimer = 0;
								getServer().getPluginManager().callEvent(new VaroStartedEvent());
							}
						}
					}
					else if(a[0].equalsIgnoreCase("end"))
					{
						if(s instanceof Player && !s.hasPermission("varo.admin"))
						{
							Message.ERROR_UNAUTHORIZED.send(s);
						}
						else if(!getConfig().getBoolean("donttouchthis.ongoing"))
						{
							Message.ERROR_NOT_ONGOING.send(s);
						}
						else
						{
							if(!(s instanceof Player))
							{
								Message.PREMATURE_END.send(s);
							}
							for(Player p : getServer().getOnlinePlayers())
							{
								p.sendTitle("", Message.PREMATURE_END.get(p), 0, 50, 20);
								Message.PREMATURE_END.send(p);
								Message.NEW_GAME_SOON.send(p);
							}
							endRound(new ArrayList<>());
						}
					}
					else if(a[0].equalsIgnoreCase("savedefaultitems"))
					{
						if(s instanceof Player)
						{
							final Player p = (Player) s;
							final Inventory i = p.getInventory();
							final ArrayList<HashMap<String, Object>> startItems = new ArrayList<>();
							int slot = 0;
							for(ItemStack is : i.getContents())
							{
								if(is != null)
								{
									final HashMap<String, Object> item = new HashMap<>();
									item.put("slot", slot);
									item.put("type", is.getType().name());
									item.put("amount", is.getAmount());
									if(is.getDurability() != 0)
									{
										item.put("durability", is.getDurability());
									}
									startItems.add(item);
								}
								slot++;
							}
							getConfig().set("startItems", startItems);
							Message.SAVED_DEFAULT_ITEMS.send(p);
						}
						else
						{
							Message.ERROR_PLAYERS_ONLY.send(s);
						}
					}
					else if(a[0].equalsIgnoreCase("flush"))
					{
						if(s instanceof Player && !s.hasPermission("varo.admin"))
						{
							Message.ERROR_UNAUTHORIZED.send(s);
						}
						else
						{
							saveConfig();
							Message.FLUSH_OK.send(s);
						}
					}
					else if(a[0].equalsIgnoreCase("reload"))
					{
						if(s instanceof Player && !s.hasPermission("varo.admin"))
						{
							Message.ERROR_UNAUTHORIZED.send(s);
						}
						else
						{
							reloadVaroConfig();
							Message.RELOAD_OK.send(s);
						}
					}
					else
					{
						Message.SYNTAX_VARO.send(s);
					}
				}
				break;
			case "team":
				if(s instanceof Player)
				{
					final Player p = (Player) s;
					if(a.length == 0 || a[0].equalsIgnoreCase("info"))
					{
						final Team t = Team.of(p);
						if(t == null)
						{
							Message.ERROR_NO_TEAM.send(p);
						}
						else
						{
							p.sendMessage(t.getName());
						}
					}
					else if(getConfig().getBoolean("donttouchthis.ongoing"))
					{
						Message.ERROR_ONGOING.send(p);
					}
					else if(getConfig().getInt("maxTeamSize") < 2)
					{
						Message.ERROR_NO_TEAMS.send(p);
					}
					else
					{
						if(a[0].equalsIgnoreCase("invite"))
						{
							if(a.length == 2)
							{
								handleInvite(p, a[1]);
							}
							else
							{
								Message.SYNTAX_TEAM.send(p);
							}
						}
						else if(a[0].equalsIgnoreCase("requests"))
						{
							final UUID u = p.getUniqueId();
							ArrayList<TeamRequest> requests = TeamRequest.from(u);
							if(requests.size() == 0)
							{
								Message.TEAMREQ_OUT_NONE.send(p);
							}
							else
							{
								Team t = new Team();
								for(TeamRequest r : requests)
								{
									t.players.put(r.to, 0);
								}
								p.sendMessage(Message.TEAMREQ_OUT + " " + t.getName());
							}
							requests = TeamRequest.to(u);
							if(requests.size() == 0)
							{
								Message.TEAMREQ_IN_NONE.send(p);
							}
							else
							{
								Team t = new Team();
								for(TeamRequest r : requests)
								{
									t.players.put(r.from, 0);
								}
								p.sendMessage(Message.TEAMREQ_IN + " " + t.getName());
							}
						}
						else if(a[0].equalsIgnoreCase("leave"))
						{
							final Team t = Team.of(p);
							if(t == null)
							{
								Message.ERROR_NO_TEAM.send(p);
							}
							else
							{
								t.handleLeave(p);
								Message.TEAM_LEFT.send(p);
							}
						}
						else if(a.length == 1 && !a[0].equalsIgnoreCase("help"))
						{
							handleInvite(p, a[0]);
						}
						else
						{
							Message.SYNTAX_TEAM.send(p);
						}
					}
				}
				else
				{
					Message.ERROR_PLAYERS_ONLY.send(s);
				}
				break;
			case "t":
				if(s instanceof Player)
				{
					final Player p = (Player) s;
					if(a.length == 0)
					{
						Message.SYNTAX_TEAMMESSAGE.send(p);
					}
					else
					{
						final Team t = Team.of(p);
						if(t == null)
						{
							Message.ERROR_NO_TEAM.send(p);
						}
						else
						{
							StringBuilder msg = new StringBuilder("[").append(p.getName()).append("]");
							for(String arg : a)
							{
								msg.append(" ").append(arg);
							}
							synchronized(t.players)
							{
								for(Map.Entry<UUID, Integer> entry : t.players.entrySet())
								{
									Player tp = getServer().getPlayer(entry.getKey());
									if(tp.isOnline())
									{
										tp.sendMessage(msg.toString());
									}
								}
							}
						}
					}
				}
				else
				{
					Message.ERROR_PLAYERS_ONLY.send(s);
				}
				break;
		}
		return true;
	}

	static void endRound(ArrayList<OfflinePlayer> winners)
	{
		Varo.startTimer = -1;
		Varo.instance.getConfig().set("donttouchthis.ongoing", false);
		for(Player p : Varo.instance.getServer().getOnlinePlayers())
		{
			p.setGameMode(GameMode.SPECTATOR);
			if(Varo.instance.getConfig().getBoolean("colorNames"))
			{
				p.setPlayerListName(p.getName());
			}
			Varo.clearPlayer(p);
			p.getInventory().clear();
		}
		final File deleteIndicator = new File(Varo.world.getName() + "/DELETE");
		if(!deleteIndicator.exists())
		{
			try
			{
				//noinspection ResultOfMethodCallIgnored
				deleteIndicator.createNewFile();
			}
			catch(IOException ignored)
			{
			}
		}
		Varo.world = null;
		Varo.instance.getServer().getScheduler().scheduleSyncDelayedTask(Varo.instance, ()->Varo.startTimer = 0, 80);
		Varo.instance.getServer().getPluginManager().callEvent(new VaroEndedEvent(winners));
	}

	private void placeBedrockUnder(Location location)
	{
		if(location.getBlock().getType().isSolid())
		{
			location.getBlock().setType(Material.BEDROCK);
			location.setY(location.getY() + 1);
		}
		else
		{
			location.setY(location.getY() - 1);
			location.getBlock().setType(Material.BEDROCK);
			location.setY(location.getY() + 2);
		}
	}

	private void handleInvite(Player s, String a)
	{
		final Player p = getPlayer(a);
		if(p == null)
		{
			s.sendMessage(Message.ERROR_OFFLINE.get(s).replace("%", a));
		}
		else if(p.equals(s))
		{
			Message.ERROR_SELFTEAM.send(s);
		}
		else
		{
			final UUID su = s.getUniqueId();
			final UUID pu = p.getUniqueId();
			synchronized(TeamRequest.teamRequests)
			{
				TeamRequest r = TeamRequest.get(pu, su);
				if(r != null)
				{
					Team t = Team.of(pu);
					final Team _t = Team.of(su);
					if(t == null)
					{
						synchronized(teams)
						{
							t = new Team();
							t.players.put(pu, 0);
							t.players.put(su, 0);
							teams.add(t);
						}
						if(p.isOnline())
						{
							p.sendMessage(Message.TEAM_JOIN.get(s).replace("%", s.getName()));
						}
						s.sendMessage(Message.TEAM_JOINED.get(s).replace("%", p.getName()));
					}
					else
					{
						synchronized(t.players)
						{
							if(t.players.size() == getConfig().getInt("maxTeamSize"))
							{
								s.sendMessage(Message.ERROR_TEAM_FULL.get(p).replace("%", p.getName()));
							}
							else
							{
								s.sendMessage(Message.TEAM_JOINED.get(s).replace("%", t.getName()));
								for(Map.Entry<UUID, Integer> entry : t.players.entrySet())
								{
									Player tp = getServer().getPlayer(entry.getKey());
									if(tp.isOnline())
									{
										tp.sendMessage(Message.TEAM_JOIN.get(tp).replace("%", p.getName()));
									}
								}
								t.players.put(su, 0);
							}
						}
					}
					if(_t != null && _t != t)
					{
						_t.handleLeave(su);
					}
					TeamRequest.teamRequests.remove(r);
					Team.updateConfig();
				}
				else
				{
					if(TeamRequest.get(su, pu) == null)
					{
						TeamRequest.teamRequests.add(new TeamRequest(su, pu));
					}
					s.sendMessage(Message.TEAMREQ_SENT_1.get(s).replace("%", p.getName()));
					s.sendMessage(Message.TEAMREQ_SENT_2.get(s).replace("%", s.getName()));
				}
			}
		}
	}

	@EventHandler
	public void onServerListPing(ServerListPingEvent e)
	{
		if(getConfig().getBoolean("motd.enabled"))
		{
			if(getConfig().getBoolean("donttouchthis.ongoing"))
			{
				e.setMotd(getConfig().getString("motd.ongoing"));
			}
			else
			{
				e.setMotd(getConfig().getString("motd.waiting"));
			}
		}
	}

	@EventHandler
	public void onPlayerJoin(PlayerJoinEvent e)
	{
		final Player p = e.getPlayer();
		if(Varo.world != null && !p.getWorld().equals(Varo.world))
		{
			p.teleport(Varo.world.getSpawnLocation());
		}
		if(getConfig().getBoolean("donttouchthis.ongoing"))
		{
			final Team t = Team.of(p);
			if(t == null)
			{
				p.setGameMode(GameMode.SPECTATOR);
				getServer().getScheduler().scheduleSyncDelayedTask(this, ()->
				{
					Message.JOIN_SPECTATE.send(p);
					Message.SPECTATE.send(p);
				}, 40);
			}
			else
			{
				p.setGameMode(GameMode.SURVIVAL);
				if(t.color != null)
				{
					p.setPlayerListName("§" + t.color + p.getName());
				}
				getServer().getScheduler().scheduleSyncDelayedTask(this, ()->Message.JOIN_CONTINUE.send(p), 40);
			}
			handleLeaveDisqualification();
		}
		else
		{
			p.setGameMode(GameMode.SPECTATOR);
			getServer().getScheduler().scheduleSyncDelayedTask(this, ()->
			{
				Message.NEW_GAME_SOON.send(p);
				if(getConfig().getInt("maxTeamSize") > 1)
				{
					Message.TEAM_INFO_1.send(p);
					Message.TEAM_INFO_2.send(p);
				}
			}, 40);
		}
	}

	@EventHandler(priority = EventPriority.LOWEST)
	public void onPlayerQuit(PlayerQuitEvent e)
	{
		handleLeaveDisqualification();
	}

	@EventHandler
	public void onEntityDamage(EntityDamageEvent e)
	{
		if(!(e.getEntity() instanceof Player))
		{
			return;
		}
		if(!getConfig().getBoolean("donttouchthis.ongoing"))
		{
			e.setCancelled(true);
			return;
		}
		final Player p = (Player) e.getEntity();
		final Team t = Team.of(p);
		if(e instanceof EntityDamageByEntityEvent)
		{
			final EntityDamageByEntityEvent ee = (EntityDamageByEntityEvent) e;
			if(ee.getDamager() instanceof Player)
			{
				final Player d = ((Player) ee.getDamager());
				synchronized(t.players)
				{
					if(t.players.containsKey(d.getUniqueId()))
					{
						if((p.getHealth() - e.getFinalDamage()) < 1)
						{
							ee.setCancelled(true);
						}
						else
						{
							e.setDamage(0);
						}
					}
				}
			}
		}
	}

	@EventHandler
	public void onPlayerDeath(PlayerDeathEvent e)
	{
		if(!getConfig().getBoolean("donttouchthis.ongoing"))
		{
			return;
		}
		final Player p = e.getEntity();
		final Team t = Team.of(p);
		if(t != null)
		{
			synchronized(t.players)
			{
				int deaths = t.players.get(p.getUniqueId()) + 1;
				if(deaths < getConfig().getInt("livesPerPlayer"))
				{
					p.sendMessage(Message.DEATH.get(p).replace("%", String.valueOf(getConfig().getInt("livesPerPlayer") - deaths)));
					t.players.put(p.getUniqueId(), deaths);
					Team.updateConfig();
					getConfig().set("donttouchthis.shrinkFactor", getConfig().getInt("donttouchthis.shrinkFactor") + 1);
				}
				else
				{
					p.setGameMode(GameMode.SPECTATOR);
					if(getConfig().getBoolean("colorNames"))
					{
						p.setPlayerListName(p.getName());
					}
					Message.DEATH_FINAL.send(p);
					Message.SPECTATE.send(p);
					t.handleLeave(p);
					p.getInventory().clear();
					handleLeaveDisqualification();
				}
			}
		}
	}

	@EventHandler
	public void onPlayerRespawn(PlayerRespawnEvent e)
	{
		final Player p = e.getPlayer();
		final Team t = Team.of(p);
		if(!getConfig().getBoolean("donttouchthis.ongoing"))
		{
			return;
		}
		Varo.clearPlayer(p);
		if(t == null || t.players.get(p.getUniqueId()) == getConfig().getInt("livesPerPlayer"))
		{
			e.setRespawnLocation(Varo.world.getSpawnLocation());
			if(t != null)
			{
				for(Map.Entry<UUID, Integer> entry : t.players.entrySet())
				{
					Player m = getServer().getPlayer(entry.getKey());
					if(m.isOnline())
					{
						p.teleport(m);
						p.setSpectatorTarget(m);
						break;
					}
				}
			}
		}
		else
		{
			if(t.spawnPoint != null && Varo.world.getWorldBorder().isInside(t.spawnPoint))
			{
				e.setRespawnLocation(t.spawnPoint);
			}
			else
			{
				e.setRespawnLocation(Varo.world.getSpawnLocation());
			}
			if(Varo.world.getGameRuleValue("keepInventory").equals("false"))
			{
				synchronized(Varo.startItems)
				{
					for(Map.Entry<Integer, ItemStack> i : Varo.startItems.entrySet())
					{
						p.getInventory().setItem(i.getKey(), i.getValue().clone());
					}
				}
			}
		}
	}

	@EventHandler(priority = EventPriority.HIGHEST)
	public void onPlayerChangedWorld(PlayerChangedWorldEvent e)
	{
		if(Varo.world != null && !e.getPlayer().getWorld().equals(Varo.world))
		{
			e.getPlayer().teleport(Varo.world.getSpawnLocation());
		}
	}

	@EventHandler
	public void onPlayerKick(PlayerKickEvent e)
	{
		final Player p = e.getPlayer();
		if(getServer().getBannedPlayers().contains(p))
		{
			final Team t = Team.of(p);
			if(t != null)
			{
				t.handleLeave(p);
			}
		}
	}
}

enum Message
{
	JOIN_SPECTATE("You can no longer partake in this Varo round.", "Du kannst in dieser Varo Runde leider nicht mehr mitmachen.", "Je kan niet langer deelnemen in deze ronde van Varo"),
	JOIN_CONTINUE("Welcome back. §cThe Varo round is still ongoing!", "Willkommen zurück. §cDie Varo Runde ist immernoch am laufen!", "Welkom terug. De Varo ronde is nog steeds gaande."),
	SPECTATE("Just spectate in the meantime. Teleportation: §6/varo tp <player>", "Schau solange doch einfach zu. Teleportation: §6/varo tp <Spieler>", "Bekijk het gevecht in de tussentijd. Teleportatie: §6/varo tp <speler>"),
	NEW_GAME_SOON("A new Varo round will start soon.", "Eine neue Varo Runde wird bald starten.", "Een nieuwe ronde van Varo zal zometeen plaatsvinden."),
	TEAM_INFO_1("In the meantime, use §6/team <player>§r to build a team.", "Bis dahin kannst du mit §6/team <Spieler>§r ein Team bauen.", "In de tussentijd, gebruik §6/team <speler>§r om een team te maken."),
	TEAM_INFO_2("If you're not in a team at start, you will be assigned one.", "Wenn du beim Start in keinem Team bist, wird dir eins zugewiesen.", "Als je voor de ronde nog niet in een team zit wordt je automatisch in een team in gezet."),
	DEATH("§cYou died. You still have % live(s)!", "§cDu bist gestorben. Du hast noch % Leben!", "§cJe bent gestorven. Je hebt nog % leven(s)!"),
	DEATH_FINAL("§cYou died and will only be able to partake again in the next game.", "§cDu bist gestorben§r und kannst wieder in der nächsten Runde mitspielen.", "§cJe bent gestorven en zal niet mee mogen doe in de volgende ronde."),
	WIN_SINGULAR("§a% has won!", "§a% hat gewonnen!", "§a% heeft gewonnen!"),
	WIN_MULTIPLE("§a% have won!", "§a% haben gewonnen!", "§a% hebben gewonnen"),
	TEAM_DISBAND("§cYour team has been disbanded.", "§cDein Team hat sich aufgelöst.", "§cJe team is ontbonden."),
	ERROR_UNAUTHORIZED("§cYou are not authorized to use this command.", "§cDu bist nicht authorisiert, diesen Befehl auszuführen.", "§cJe hebt geen toestemming om dit commando uit te voeren."),
	ERROR_ONGOING("§cThe Varo round has already started.", "§cDie Varo Runde ist bereits am laufen.", "§cDe Varo ronde is al begonnen."),
	ERROR_NOT_ONGOING("§cNo Varo round is ongoing.", "§cEs ist keine Varo Runde am laufen.", "§cEr is geen Varo ronde gaande."),
	ERROR_NO_TEAM("§cYou're not in a team.", "§cDu bist in keinem Team.", "§cJe zit nog niet in een team."),
	ERROR_NO_TEAMS("§cThere will be no teams in this Varo round.", "§cIn dieser Varo Runde wird es keine Teams geben.", "§cEr zullen geen teams zijn in deze Varo ronde."),
	ERROR_OFFLINE("§c% is not online.", "§c% is not online.", "§c% is niet online."),
	ERROR_TEAM_FULL("§cUnfortunately, %'s team is already full.", "§cDas Team von % ist leider schon voll.", "§cHelaas, het team van % is al vol."),
	ERROR_SELFTEAM("§cYou will always be in a team with yourself.", "§cDu wirst immer mit dir selbst in einem Team sein.", "§cJe zult altijd in een team met jezelf zitten."),
	ERROR_PLAYERS_ONLY("§cThis command is only for players.", "§cDieser Befehl ist nur für Spieler", "§cDit commando is bestemd voor spelers."),
	TEAM_JOINED("§aYou're now in a team with %.", "§aDu bist nun in einem Team mit %.", "§aJe zit nu in een team met %."),
	TEAM_JOIN("§a% is now in your team.", "§a% ist nun in deinem Team.", "§a% zit nu in je team."),
	SYNTAX_TEAM("§cSyntax: /team [[info]|[invite ]<player>|help|requests|leave]", "§cSyntax: /team [[info]|[invite ]<Spieler>|help|requests|leave]", "§cUitvoering: /team [[info]|[invite ]<speler>|help|requests|leave]"),
	TEAMREQ_SENT_1("§aYou've sent a team request to %.", "§aDu hast eine Team-Anfrage an % gesendet.", "§aJe hebt een team-aanvraag naar % getuurd."),
	TEAMREQ_SENT_2("They must now run §6/team %§r to join.", "Diese(r) muss nun §6/team %§r ausführen, um beizutreten.", "Deze speler moet nu §6/team %§r uitvoeren."),
	TEAMREQ_OUT_NONE("Outgoing Team Requests: None", "Ausgehende Team-Anfragen: Keine", "Uitgaande team uitnodigingen: Geen"),
	TEAMREQ_OUT("Outgoing Team Requests to:", "Ausgehende Team-Anfragen an:", "Uitgaande team uitnodigingen naar:"),
	TEAMREQ_IN_NONE("Incoming Team Requests: None", "Eingehende Team-Anfragen: Keine", "Inkomende team uitnodigingen van: Niemand"),
	TEAMREQ_IN("Incoming Team Requests from:", "Eingehende Team-Anfragen von:", "Inkomende team uitnodigingen van:"),
	SYNTAX_VARO("§cSyntax: /varo [tp <player>|tpcenter|start|end|savedefaultitems|flush|reload]", "§cSyntax: /varo [tp <Spieler>|tpcenter|start|end|savedefaultitems|flush|reload]", "§cUitvoering: /varo [tp <speler>|tpcenter|start|end|savedefaultitems|flush|reload]"),
	TELEPORT_UNAUTHORIZED("§cOnly spectators and admins are allowed to teleport.", "§cNur Zuschauer und Admins dürfen sich teleportieren.", "§cAlleen de doden en Admins mogen teleporteren."),
	SAVED_DEFAULT_ITEMS("§aYour inventory has been saved as the start inventory for new Varo rounds.", "§aDein Inventar wurde als das Start-Inventar für neue Varo Runden gespeichert.", "§aJe inventory is opgleslagen als je start inventory voor de volgende ronde."),
	FLUSH_OK("§aFlushed configuration to disk.", "§aDie Konfiguration wurde auf die Platte geschrieben.", "§aFlushed-configuratie naar schijf."),
	RELOAD_OK("§aReloaded configuration from disk.", "§aDie Konfiguration wurde von der Platte geladen.", "§aConfiguratie van schijf herladen."),
	SYNTAX_TEAMMESSAGE("§cSyntax: /t <message>", "§cSyntax: /t <Nachricht>", "§cUitvoering: /t <bericht>"),
	TEAM_LEFT("§aYou are no longer in a team.", "§aDu bist nun in keinem Team mehr.", "§aJe zit niet langer in een team."),
	START_INSUFFICIENT_PLAYERS("§cThere are not enough players to start.", "§cEs sind nicht genug Spieler zum starten da.", "§cEr zijn niet genoeg spelers om te starten."),
	AUTOSTART_TIME("A new Varo round will start in % seconds.", "Eine neue Varo Runde wird in % Sekunden starten.", "Een nieuwe Varo begint in % seconden."),
	GET_READY("§eGet ready!", "§eMach dich bereit!", "§eMaak je klaar!"),
	GET_READY_AGAIN("Sorry, that was a bad world.", "Entschuldigung, das war eine schlechte Welt.", "Sorry, dat was een slechte wereld."),
	HAVE_FUN("§aHave fun!", "§aViel Spaß!", "§aVeel plezier!"),
	HAVE_FUN_TEAMS("§aHave fun and communicate with your team using §6/t!", "§aViel Spaß und kommuniziere mit deinem Team mit §6/t!", "§aVeel plezier en communiceer met je team met behulp van §6/t!"),
	COMPASS_INFO("Your compass points to the center (0, 0) where you're safe from the shrinking border.", "Dein Kompass zeigt auf die Mitte (0, 0) bei der du von der schrumpfenden Worldborder sicher bist.", "Je kompas wijst naar het midden (0, 0) waar je veilig bent voor de krimpende rand."),
	PREMATURE_END("The Varo round has been terminated prematurely.", "Die Varo Runde wurde frühzeitig beendet.", "Deze Varo ronde is vroegtijdig beëindigd.");

	final String en;
	final String de;
	final String nl;

	Message(String en, String de, String nl)
	{
		this.en = en;
		this.de = de;
		this.nl = nl;
	}

	String get(Player recipient)
	{
		switch(recipient.getLocale().substring(0, 2).toLowerCase())
		{
			case "de":
				if(de != null)
				{
					return de;
				}
				break;

			case "nl":
				if(nl != null)
				{
					return nl;
				}
				break;
		}
		return en;
	}

	void send(Player recipient)
	{
		recipient.sendMessage(get(recipient));
	}

	void send(CommandSender recipient)
	{
		recipient.sendMessage(recipient instanceof Player ? get((Player) recipient) : en);
	}
}

class Team
{
	final HashMap<UUID, Integer> players = new HashMap<>();
	String name;
	Location spawnPoint;
	String color;

	Team()
	{
	}

	static void updateConfig()
	{
		final ArrayList<HashMap<String, Object>> teams = new ArrayList<>();
		synchronized(Varo.teams)
		{
			for(Team t : Varo.teams)
			{
				final HashMap<String, Object> team = new HashMap<>();
				final HashMap<String, Integer> players = new HashMap<>();
				for(Map.Entry<UUID, Integer> entry : t.players.entrySet())
				{
					players.put(entry.getKey().toString(), entry.getValue());
				}
				team.put("players", players);
				if(t.name != null)
				{
					team.put("name", t.name);
				}
				if(t.color != null)
				{
					team.put("color", t.color);
				}
				if(t.spawnPoint != null)
				{
					team.put("spawnPoint", new Double[]{t.spawnPoint.getX(), t.spawnPoint.getY(), t.spawnPoint.getZ()});
				}
				teams.add(team);
			}
		}
		Varo.instance.getConfig().set("donttouchthis.teams", teams);
	}

	static Team of(UUID u)
	{
		synchronized(Varo.teams)
		{
			for(Team t : Varo.teams)
			{
				if(t.players.containsKey(u))
				{
					return t;
				}
			}
		}
		return null;
	}

	static Team of(Player p)
	{
		return Team.of(p.getUniqueId());
	}

	String getName()
	{
		if(name != null)
		{
			return name;
		}
		synchronized(players)
		{
			if(players.size() == 1)
			{
				return Varo.instance.getServer().getPlayer(players.entrySet().iterator().next().getKey()).getName();
			}
			StringBuilder name = new StringBuilder();
			int remaining = players.size();
			for(Map.Entry<UUID, Integer> entry : players.entrySet())
			{
				name.append(Varo.instance.getServer().getPlayer(entry.getKey()).getName());
				if(--remaining > 0)
				{
					if(remaining == 1)
					{
						name.append(", & ");
					}
					else
					{
						name.append(", ");
					}
				}
			}
			return name.toString();
		}
	}

	boolean handleLeave(UUID u)
	{
		boolean ret = true;
		synchronized(players)
		{
			players.remove(u);
			if(players.size() < (Varo.instance.getConfig().getBoolean("donttouchthis.ongoing") ? 1 : 2))
			{
				handleDelete();
				ret = false;
			}
			Team.updateConfig();
		}
		if(Varo.instance.getConfig().getBoolean("donttouchthis.ongoing"))
		{
			Varo.instance.getConfig().set("donttouchthis.shrinkFactor", Varo.instance.getConfig().getInt("donttouchthis.shrinkFactor") + 1);
		}
		return ret;
	}

	void handleLeave(Player p)
	{
		handleLeave(p.getUniqueId());
	}

	void handleDelete()
	{
		synchronized(Varo.teams)
		{
			Varo.teams.remove(this);
		}
		Team.updateConfig();
		synchronized(players)
		{
			for(Map.Entry<UUID, Integer> entry : players.entrySet())
			{
				Player p = Varo.instance.getServer().getPlayer(entry.getKey());
				if(p != null && p.isOnline())
				{
					Message.TEAM_DISBAND.send(p);
				}
			}
		}
		if(Varo.instance.getConfig().getBoolean("donttouchthis.ongoing"))
		{
			synchronized(Varo.teams)
			{
				if(Varo.teams.size() == 1)
				{
					Team t = Varo.teams.get(0);
					synchronized(t.players)
					{
						Varo.instance.getServer().getScheduler().scheduleSyncDelayedTask(Varo.instance, ()->
						{
							for(Player p : Varo.instance.getServer().getOnlinePlayers())
							{
								final String winMessage;
								if(t.getName().contains(" & "))
								{
									winMessage = Message.WIN_MULTIPLE.get(p).replace("%", t.getName());
									p.sendTitle("", winMessage, 0, 50, 50);
								}
								else
								{
									winMessage = Message.WIN_SINGULAR.get(p).replace("%", t.getName());
									p.sendTitle(winMessage, Message.NEW_GAME_SOON.get(p), 0, 50, 50);
								}
								p.sendMessage(winMessage);
								Message.NEW_GAME_SOON.send(p);
							}
						}, 1);
						final ArrayList<OfflinePlayer> winners = new ArrayList<>();
						for(UUID u : t.players.keySet())
						{
							winners.add(Varo.instance.getServer().getOfflinePlayer(u));
						}
						Varo.endRound(winners);
					}
				}
			}
		}
	}
}

class TeamRequest
{
	static final ArrayList<TeamRequest> teamRequests = new ArrayList<>();
	final UUID from;
	final UUID to;

	TeamRequest(UUID from, UUID to)
	{
		this.from = from;
		this.to = to;
	}

	static TeamRequest get(UUID from, UUID to)
	{
		synchronized(teamRequests)
		{
			for(TeamRequest r : teamRequests)
			{
				if(r.from.equals(from) && r.to.equals(to))
				{
					return r;
				}
			}
		}
		return null;
	}

	static ArrayList<TeamRequest> from(UUID from)
	{
		final ArrayList<TeamRequest> requests = new ArrayList<>();
		synchronized(teamRequests)
		{
			for(TeamRequest r : teamRequests)
			{
				if(r.from.equals(from))
				{
					requests.add(r);
				}
			}
		}
		return requests;
	}

	static ArrayList<TeamRequest> to(UUID to)
	{
		final ArrayList<TeamRequest> requests = new ArrayList<>();
		synchronized(teamRequests)
		{
			for(TeamRequest r : teamRequests)
			{
				if(r.to.equals(to))
				{
					requests.add(r);
				}
			}
		}
		return requests;
	}
}
