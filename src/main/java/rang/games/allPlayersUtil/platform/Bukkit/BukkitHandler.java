package rang.games.allPlayersUtil.platform.Bukkit;

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
import rang.games.allPlayersUtil.event.NetworkServerShutdownEvent;
import rang.games.allPlayersUtil.event.NetworkServerStartEvent;
import rang.games.allPlayersUtil.event.ServerSwitchEvent;
import rang.games.allPlayersUtil.platform.PlatformHandler;

import java.util.concurrent.ForkJoinPool;

public class BukkitHandler implements PlatformHandler {
    private final JavaPlugin plugin;
    private RedisClient redisClient;
    private String serverName;
    private BukkitPlayerListener bukkitPlayerListener;
    private volatile boolean isEnabled = false;
    private BukkitTask playerUpdateTask;
    private RedisClient.NetworkEventListener redisNetworkEventListener;

    public BukkitHandler(JavaPlugin plugin) {
        this.plugin = plugin;
        this.bukkitPlayerListener = new BukkitPlayerListener();
    }

    @Override
    public void initialize(RedisClient redisClient, String serverName) {
        if (this.isEnabled) {
            plugin.getLogger().warning("BukkitHandler is already initialized.");
            return;
        }
        this.redisClient = redisClient;
        this.serverName = serverName;

        if (this.redisClient == null || !this.redisClient.isFunctional()) {
            plugin.getLogger().severe("RedisClient is not functional. BukkitHandler will operate in a limited mode or may fail.");
        }

        this.isEnabled = true;
        plugin.getLogger().info("Initializing BukkitHandler for server: " + serverName);

        this.redisNetworkEventListener = (type, id, name, fromServer, toServer) -> {
            if (!isEnabled || plugin == null || !plugin.isEnabled()) return;
            if (Bukkit.isPrimaryThread()) {
                processNetworkEvent(type, id, name, fromServer, toServer);
            } else {
                if (plugin.isEnabled()) {
                    Bukkit.getScheduler().runTask(plugin, () -> processNetworkEvent(type, id, name, fromServer, toServer));
                }
            }
        };
        if (this.redisClient != null && this.redisClient.isFunctional()) {
            this.redisClient.addListener(this.redisNetworkEventListener);
        } else {
            plugin.getLogger().warning("RedisClient not functional. RedisNetworkEventListener not added.");
        }

        this.bukkitPlayerListener.setRedisClient(this.redisClient);
        this.bukkitPlayerListener.setServerName(this.serverName);
        this.bukkitPlayerListener.setPlugin(this.plugin);
        this.bukkitPlayerListener.setEnabled(true);

        registerEvents();
        startPlayerUpdateTask();

        if (this.redisClient != null && this.redisClient.isFunctional()) {
            this.redisClient.publishNetworkServerStart(this.serverName)
                    .exceptionally(throwable -> {
                        if (this.isEnabled && plugin.isEnabled()) plugin.getLogger().severe("Failed to publish server start event: " + throwable.getMessage());
                        return null;
                    });
        } else {
            plugin.getLogger().severe("RedisClient is not functional. Cannot publish NetworkServerStart event.");
        }
    }

    private void processNetworkEvent(RedisClient.NetworkEventType type, String id, String name, String fromServer, String toServer) {
        if (!isEnabled || plugin == null || !plugin.isEnabled()) return;
        try {
            switch (type) {
                case NETWORK_JOIN:
                    Bukkit.getPluginManager().callEvent(new NetworkJoinEvent(id, name));
                    break;
                case NETWORK_QUIT:
                    Bukkit.getPluginManager().callEvent(new NetworkQuitEvent(id, name, fromServer));
                    break;
                case SERVER_SWITCH:
                    Bukkit.getPluginManager().callEvent(new ServerSwitchEvent(id, name, fromServer, toServer));
                    break;
                case NETWORK_SERVER_START:
                    Bukkit.getPluginManager().callEvent(new NetworkServerStartEvent(id));
                    break;
                case NETWORK_SERVER_SHUTDOWN:
                    Bukkit.getPluginManager().callEvent(new NetworkServerShutdownEvent(id));
                    break;
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Error dispatching Bukkit event for " + type + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Override
    public void disable() {
        plugin.getLogger().info("Disabling BukkitHandler for server: " + serverName);
        if (!this.isEnabled) {
            plugin.getLogger().info("BukkitHandler already disabled or was not fully enabled.");
            return;
        }
        this.isEnabled = false;

        if (playerUpdateTask != null && !playerUpdateTask.isCancelled()) {
            try {
                playerUpdateTask.cancel();
                plugin.getLogger().info("Player update task cancelled.");
            } catch (Exception e) {
                plugin.getLogger().warning("Error cancelling player update task: " + e.getMessage());
            }
        }
        playerUpdateTask = null;

        if (this.bukkitPlayerListener != null) {
            try {
                HandlerList.unregisterAll(this.bukkitPlayerListener);
                plugin.getLogger().info("BukkitPlayerListener unregistered.");
            } catch (Exception e) {
                plugin.getLogger().warning("Error unregistering BukkitPlayerListener: " + e.getMessage());
            }
            this.bukkitPlayerListener.setEnabled(false);
        }

        if (this.redisClient != null && this.redisClient.isFunctional()) {
            if (this.redisNetworkEventListener != null) {
                try {
                    this.redisClient.removeListener(this.redisNetworkEventListener);
                    plugin.getLogger().info("Redis network event listener removed.");
                } catch (Exception e) {
                    plugin.getLogger().warning("Error removing Redis network event listener: " + e.getMessage());
                }
            }
            this.redisNetworkEventListener = null;

            if (this.serverName != null) {
                plugin.getLogger().info("Initiating Redis server shutdown publications for " + serverName);
                this.redisClient.publishNetworkServerShutdown(this.serverName)
                        .exceptionally(throwable -> {
                            if (plugin.isEnabled()) plugin.getLogger().severe("Error publishing network server shutdown for " + serverName + " during disable: " + throwable.getMessage());
                            return null;
                        });

                this.redisClient.cleanupServerAsync(this.serverName)
                        .exceptionally(throwable -> {
                            if (plugin.isEnabled()) plugin.getLogger().severe("Error cleaning up server keys for " + serverName + " during disable: " + throwable.getMessage());
                            return null;
                        });
                plugin.getLogger().info("NetworkServerShutdown event and cleanupServerAsync initiated for " + serverName);
            } else {
                plugin.getLogger().warning("serverName is null, skipping Redis shutdown publications for BukkitHandler.");
            }
        } else {
            plugin.getLogger().warning("RedisClient not functional or null during disable, skipping Redis-specific shutdown tasks for BukkitHandler.");
        }
        plugin.getLogger().info("BukkitHandler disable sequence finished for server: " + serverName);
    }

    @Override
    public void registerEvents() {
        if (plugin == null || bukkitPlayerListener == null) {
            System.err.println("[BukkitHandler] Cannot register events: plugin or bukkitPlayerListener is null.");
            return;
        }
        plugin.getLogger().info("Registering BukkitPlayerListener for server: " + serverName);
        Bukkit.getPluginManager().registerEvents(bukkitPlayerListener, plugin);
    }

    @Override
    public void broadcast(String message) {
        if (!isEnabled || plugin == null || !plugin.isEnabled()) return;
        Bukkit.broadcastMessage(message);
    }

    @Override
    public String getServerName() {
        return serverName;
    }

    private void startPlayerUpdateTask() {
        if (plugin == null) {
            System.err.println("[BukkitHandler] Cannot start player update task: plugin is null.");
            return;
        }
        if (playerUpdateTask != null && !playerUpdateTask.isCancelled()) {
            playerUpdateTask.cancel();
        }
        playerUpdateTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            if (!isEnabled || plugin == null || !plugin.isEnabled()) {
                if (playerUpdateTask != null && !playerUpdateTask.isCancelled()) {
                    playerUpdateTask.cancel();
                }
                return;
            }
            if (redisClient == null || !redisClient.isFunctional()) {
                return;
            }

            Bukkit.getOnlinePlayers().forEach(player -> {
                String uuid = player.getUniqueId().toString();
                redisClient.addPlayerAsync(uuid, serverName)
                        .exceptionally(throwable -> {
                            if (isEnabled && plugin.isEnabled()) plugin.getLogger().severe("Error updating player data for " + player.getName() + ": " + throwable.getMessage());
                            return null;
                        });
            });
            redisClient.updateServerStatus(serverName)
                    .exceptionally(throwable -> {
                        if (isEnabled && plugin.isEnabled()) plugin.getLogger().severe("Error updating server status: " + throwable.getMessage());
                        return null;
                    });
        }, 100L, 600L);
    }

    private static class BukkitPlayerListener implements Listener {
        private RedisClient redisClient;
        private String serverName;
        private JavaPlugin plugin;
        private volatile boolean isEnabled = false;

        public void setRedisClient(RedisClient redisClient) { this.redisClient = redisClient; }
        public void setServerName(String serverName) { this.serverName = serverName; }
        public void setPlugin(JavaPlugin plugin) { this.plugin = plugin; }
        public void setEnabled(boolean enabled) { this.isEnabled = enabled; }

        @EventHandler
        public void onPlayerJoin(PlayerJoinEvent event) {
            if (!this.isEnabled || plugin == null || !plugin.isEnabled() || serverName == null) return;
            if (redisClient == null || !redisClient.isFunctional()) {
                return;
            }

            String uuid = event.getPlayer().getUniqueId().toString();
            String name = event.getPlayer().getName();

            redisClient.getPlayerServerAsync(uuid).thenAcceptAsync(previousServer -> {
                if (!this.isEnabled || !plugin.isEnabled()) return;
                if (previousServer == null) {
                    redisClient.addPlayerAsync(uuid, this.serverName)
                            .thenRunAsync(() -> {
                                if (!this.isEnabled || !plugin.isEnabled()) return;
                                NetworkJoinEvent networkJoinEvent = new NetworkJoinEvent(uuid, name);
                                if (plugin.isEnabled()) {
                                    Bukkit.getScheduler().runTask(plugin, () -> Bukkit.getPluginManager().callEvent(networkJoinEvent));
                                }
                            }, Bukkit.getScheduler().getMainThreadExecutor(plugin))
                            .exceptionally(throwable -> {
                                if (this.isEnabled && plugin.isEnabled()) plugin.getLogger().severe("Error processing new network join for " + name + ": " + throwable.getMessage());
                                return null;
                            });
                } else {
                    redisClient.addPlayerAsync(uuid, this.serverName)
                            .exceptionally(throwable -> {
                                if (this.isEnabled && plugin.isEnabled()) plugin.getLogger().severe("Error updating " + name + "'s server in Redis on join: " + throwable.getMessage());
                                return null;
                            });
                }
            }, ForkJoinPool.commonPool()).exceptionally(throwable -> {
                if (this.isEnabled && plugin.isEnabled()) plugin.getLogger().severe("Error checking player server for " + name + " on join: " + throwable.getMessage());
                return null;
            });
        }

        @EventHandler
        public void onPlayerQuit(PlayerQuitEvent event) {
            if (!this.isEnabled || plugin == null || !plugin.isEnabled() || serverName == null) return;
            if (redisClient == null || !redisClient.isFunctional()) {
                return;
            }

            String uuid = event.getPlayer().getUniqueId().toString();
            redisClient.removePlayerAsync(uuid, this.serverName)
                    .exceptionally(throwable -> {
                        if (this.isEnabled && plugin.isEnabled()) plugin.getLogger().severe("Error removing " + event.getPlayer().getName() + " from Redis on quit: " + throwable.getMessage());
                        return null;
                    });
        }
    }
}