package rang.games.allPlayersUtil.platform;

import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import rang.games.allPlayersUtil.RedisClient;
import rang.games.allPlayersUtil.event.NetworkJoinEvent;
import rang.games.allPlayersUtil.event.NetworkQuitEvent;
import rang.games.allPlayersUtil.event.ServerSwitchEvent;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class PurpurHandler implements PlatformHandler {
    private final JavaPlugin plugin;
    private RedisClient redisClient;
    private String serverName;
    private BukkitListener listener;
    private volatile boolean isEnabled = false;
    private BukkitTask playerUpdateTask;

    public PurpurHandler(JavaPlugin plugin) {
        this.plugin = plugin;
        this.listener = new BukkitListener();
    }

    @Override
    public void initialize(RedisClient redisClient, String serverName) {
        this.redisClient = redisClient;
        this.serverName = serverName;
        this.isEnabled = true;

        plugin.getLogger().info("§aInitializing PurpurHandler for server: " + serverName);

        this.redisClient.addListener((type, playerUuid, playerName, fromServer, toServer) -> {
            if (!isEnabled || plugin == null) return;

            plugin.getLogger().info("§aReceived network event: " + type + " for " + playerName);

            Bukkit.getScheduler().runTask(plugin, () -> {
                try {
                    switch (type) {
                        case NETWORK_JOIN:
                            //plugin.getLogger().info("§aDispatching NetworkJoinEvent for " + playerName);
                            NetworkJoinEvent event = new NetworkJoinEvent(playerUuid, playerName);
                            Bukkit.getPluginManager().callEvent(event);
                            //plugin.getLogger().info("§aNetworkJoinEvent dispatched for " + playerName);
                            break;

                        case NETWORK_QUIT:
                            //plugin.getLogger().info("§cDispatching NetworkQuitEvent for " + playerName);
                            Bukkit.getPluginManager().callEvent(new NetworkQuitEvent(playerUuid, playerName, fromServer));
                            //plugin.getLogger().info("§cNetworkQuitEvent dispatched for " + playerName);
                            break;

                        case SERVER_SWITCH:
                            //plugin.getLogger().info("§eDispatching ServerSwitchEvent for " + playerName);
                            Bukkit.getPluginManager().callEvent(new ServerSwitchEvent(playerUuid, playerName, fromServer, toServer));
                            //plugin.getLogger().info("§eServerSwitchEvent dispatched for " + playerName);
                            break;
                    }
                } catch (Exception e) {
                    plugin.getLogger().severe("§cError dispatching event: " + e.getMessage());
                    e.printStackTrace();
                }
            });
        });

        this.listener.setRedisClient(redisClient);
        this.listener.setServerName(serverName);
        this.listener.setPlugin(plugin);
        this.listener.setEnabled(true);
        startPlayerUpdateTask();
    }

    public void disable() {
        this.isEnabled = false;
        if (this.redisClient != null) {
            this.redisClient.removeListener(null);
            try {
                this.redisClient.shutdown().get(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                plugin.getLogger().severe("Error during Redis shutdown: " + e.getMessage());
            }
        }
        if (this.listener != null) {
            HandlerList.unregisterAll(this.listener);
        }
    }
    @Override
    public void registerEvents() {
        plugin.getLogger().info("§aRegistering events for server: " + serverName);
        Bukkit.getPluginManager().registerEvents(listener, plugin);
    }

    @Override
    public void broadcast(String message) {
        if (!isEnabled) return;
        Bukkit.broadcastMessage(message);
    }

    @Override
    public String getServerName() {
        return serverName;
    }

    private void startPlayerUpdateTask() {
        playerUpdateTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            if (!isEnabled) return;

            Bukkit.getOnlinePlayers().forEach(player -> {
                String uuid = player.getUniqueId().toString();
                redisClient.addPlayerAsync(uuid, serverName)
                        .exceptionally(throwable -> {
                            plugin.getLogger().severe("Error updating player data: " + throwable.getMessage());
                            return null;
                        });
            });
        }, 1200L, 1200L);
    }

    private static class BukkitListener implements Listener {
        private RedisClient redisClient;
        private String serverName;
        private JavaPlugin plugin;
        private volatile boolean isEnabled = false;

        public void setRedisClient(RedisClient redisClient) {
            this.redisClient = redisClient;
        }

        public void setServerName(String serverName) {
            this.serverName = serverName;
        }

        public void setPlugin(JavaPlugin plugin) {
            this.plugin = plugin;
        }

        public void setEnabled(boolean enabled) {
            this.isEnabled = enabled;
        }
        /*
                @EventHandler
                public void onPlayerJoin(PlayerJoinEvent event) {
                    if (!isEnabled) return;

                    if (redisClient != null && serverName != null) {
                        String uuid = event.getPlayer().getUniqueId().toString();
                        String name = event.getPlayer().getName();
                        plugin.getLogger().info("§aPlayer joined local server: " + name);
                        redisClient.addPlayerAsync(uuid, serverName)
                                .thenRun(() -> plugin.getLogger().info("§aAdded " + name + " to Redis"))
                                .exceptionally(throwable -> {
                                    plugin.getLogger().severe("§cError adding player to Redis: " + throwable.getMessage());
                                    return null;
                                });
                    }
                }
        */
        @EventHandler
        public void onPlayerJoin(PlayerJoinEvent event) {
            if (!isEnabled) return;

            if (redisClient != null && serverName != null) {
                String uuid = event.getPlayer().getUniqueId().toString();
                String name = event.getPlayer().getName();
                redisClient.getPlayerServerAsync(uuid).thenAccept(previousServer -> {
                    if (previousServer == null) {
                        plugin.getLogger().info("§aNew network connection detected for: " + name);

                        redisClient.addPlayerAsync(uuid, serverName)
                                .thenRun(() -> {
                                    plugin.getLogger().info("§aAdded " + name + " to Redis");

                                    NetworkJoinEvent networkJoinEvent = new NetworkJoinEvent(
                                            uuid,
                                            name
                                    );
                                    Bukkit.getPluginManager().callEvent(networkJoinEvent);
                                    plugin.getLogger().info("§aFired NetworkJoinEvent for: " + name);
                                });
                    } else {
                        plugin.getLogger().info("§aPlayer " + name + " moved from " + previousServer + " to " + serverName);
                        redisClient.addPlayerAsync(uuid, serverName).thenRun(() -> plugin.getLogger().info("§aUpdated " + name + "'s server in Redis"));
                    }
                }).exceptionally(throwable -> {
                    plugin.getLogger().severe("§cError checking player server: " + throwable.getMessage());
                    return null;
                });
            }
        }
        @EventHandler
        public void onPlayerQuit(PlayerQuitEvent event) {
            if (!isEnabled) return;

            if (redisClient != null && serverName != null) {
                String uuid = event.getPlayer().getUniqueId().toString();
                String name = event.getPlayer().getName();
                redisClient.removePlayerAsync(uuid, serverName)
                        //.thenRun(() -> plugin.getLogger().info("§cRemoved " + name + " from Redis"))
                        .exceptionally(throwable -> {
                            plugin.getLogger().severe("§cError removing player from Redis: " + throwable.getMessage());
                            return null;
                        });
            }
        }
    }
}