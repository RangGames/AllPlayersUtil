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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
        this.isEnabled = true;

        plugin.getLogger().info("Initializing BukkitHandler for server: " + serverName);

        this.redisNetworkEventListener = (type, id, name, fromServer, toServer) -> {
            if (!isEnabled || plugin == null || !plugin.isEnabled()) return;
            if (Bukkit.isPrimaryThread()) {
                processNetworkEvent(type, id, name, fromServer, toServer);
            } else {
                Bukkit.getScheduler().runTask(plugin, () -> processNetworkEvent(type, id, name, fromServer, toServer));
            }
        };
        this.redisClient.addListener(this.redisNetworkEventListener);

        this.bukkitPlayerListener.setRedisClient(redisClient);
        this.bukkitPlayerListener.setServerName(serverName);
        this.bukkitPlayerListener.setPlugin(plugin);
        this.bukkitPlayerListener.setEnabled(true);

        registerEvents();
        startPlayerUpdateTask();

        if (this.redisClient.isFunctional()) {
            this.redisClient.publishNetworkServerStart(this.serverName)
                    .exceptionally(throwable -> {
                        plugin.getLogger().severe("Failed to publish server start event: " + throwable.getMessage());
                        return null;
                    });
        } else {
            plugin.getLogger().severe("RedisClient is not functional. Cannot publish NetworkServerStart event.");
        }
    }

    private void processNetworkEvent(RedisClient.NetworkEventType type, String id, String name, String fromServer, String toServer) {
        if (!isEnabled || plugin == null || !plugin.isEnabled()) return;
        plugin.getLogger().info("Processing network event: " + type + " for " + (name.equals("null") || name.isEmpty() ? id : name));
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
            plugin.getLogger().info("BukkitHandler already disabled.");
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
            playerUpdateTask = null;
        }

        if (this.bukkitPlayerListener != null) {
            try {
                HandlerList.unregisterAll(this.bukkitPlayerListener);
                plugin.getLogger().info("BukkitPlayerListener unregistered.");
            } catch (Exception e) {
                plugin.getLogger().warning("Error unregistering BukkitPlayerListener: " + e.getMessage());
            }
            this.bukkitPlayerListener.setEnabled(false);
        }

        if (this.redisClient != null && this.redisNetworkEventListener != null) {
            try {
                this.redisClient.removeListener(this.redisNetworkEventListener);
                plugin.getLogger().info("Redis network event listener removed.");
            } catch (Exception e) {
                plugin.getLogger().warning("Error removing Redis network event listener: " + e.getMessage());
            }
            this.redisNetworkEventListener = null;
        }

        if (this.redisClient != null && this.redisClient.isFunctional() && this.serverName != null) {
            plugin.getLogger().info("Publishing server shutdown and cleaning up Redis data for " + serverName);
            try {
                this.redisClient.publishNetworkServerShutdown(this.serverName).get(3, TimeUnit.SECONDS);
                plugin.getLogger().info("NetworkServerShutdown event published for " + serverName);

                this.redisClient.cleanupServerAsync(this.serverName).get(3, TimeUnit.SECONDS);
                plugin.getLogger().info("cleanupServerAsync completed for " + serverName);
            } catch (TimeoutException e) {
                plugin.getLogger().warning("Timeout during Redis server shutdown/cleanup for " + serverName + ": " + e.getMessage());
            } catch (InterruptedException e) {
                plugin.getLogger().warning("Interrupted during Redis server shutdown/cleanup for " + serverName + ": " + e.getMessage());
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                plugin.getLogger().severe("Error during Redis server shutdown/cleanup for " + serverName + ": " + e.getMessage());
                e.printStackTrace();
            }
        } else {
            plugin.getLogger().warning("RedisClient not functional or serverName is null, skipping Redis shutdown tasks for BukkitHandler.");
        }
        plugin.getLogger().info("BukkitHandler disable sequence finished for server: " + serverName);
    }

    @Override
    public void registerEvents() {
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
        if (playerUpdateTask != null && !playerUpdateTask.isCancelled()) {
            playerUpdateTask.cancel();
        }
        playerUpdateTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            if (!isEnabled || plugin == null || !plugin.isEnabled() || redisClient == null || !redisClient.isFunctional()) {
                if (playerUpdateTask != null && !playerUpdateTask.isCancelled() && !isEnabled) {
                    playerUpdateTask.cancel();
                }
                return;
            }
            Bukkit.getOnlinePlayers().forEach(player -> {
                String uuid = player.getUniqueId().toString();
                redisClient.addPlayerAsync(uuid, serverName)
                        .exceptionally(throwable -> {
                            if (isEnabled) plugin.getLogger().severe("Error updating player data for " + player.getName() + ": " + throwable.getMessage());
                            return null;
                        });
            });
            redisClient.updateServerStatus(serverName)
                    .exceptionally(throwable -> {
                        if (isEnabled) plugin.getLogger().severe("Error updating server status: " + throwable.getMessage());
                        return null;
                    });
        }, 100L, 600L);
    }

    private static class BukkitPlayerListener implements Listener {
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

        @EventHandler
        public void onPlayerJoin(PlayerJoinEvent event) {
            if (!isEnabled || redisClient == null || !redisClient.isFunctional() || serverName == null || plugin == null || !plugin.isEnabled()) return;

            String uuid = event.getPlayer().getUniqueId().toString();
            String name = event.getPlayer().getName();

            redisClient.getPlayerServerAsync(uuid).thenAcceptAsync(previousServer -> {
                if (!isEnabled) return;
                if (previousServer == null) {
                    redisClient.addPlayerAsync(uuid, serverName)
                            .thenRunAsync(() -> {
                                if (!isEnabled) return;
                                NetworkJoinEvent networkJoinEvent = new NetworkJoinEvent(uuid, name);
                                Bukkit.getScheduler().runTask(plugin, () -> Bukkit.getPluginManager().callEvent(networkJoinEvent));
                            }, Bukkit.getScheduler().getMainThreadExecutor(plugin))
                            .exceptionally(throwable -> {
                                if (isEnabled) plugin.getLogger().severe("Error processing new network join for " + name + ": " + throwable.getMessage());
                                return null;
                            });
                } else {
                    redisClient.addPlayerAsync(uuid, serverName)
                            .exceptionally(throwable -> {
                                if (isEnabled) plugin.getLogger().severe("Error updating " + name + "'s server in Redis on join: " + throwable.getMessage());
                                return null;
                            });
                }
            }, ForkJoinPool.commonPool()).exceptionally(throwable -> {
                if (isEnabled) plugin.getLogger().severe("Error checking player server for " + name + " on join: " + throwable.getMessage());
                return null;
            });
        }

        @EventHandler
        public void onPlayerQuit(PlayerQuitEvent event) {
            if (!isEnabled || redisClient == null || !redisClient.isFunctional() || serverName == null || plugin == null || !plugin.isEnabled()) return;

            String uuid = event.getPlayer().getUniqueId().toString();
            redisClient.removePlayerAsync(uuid, serverName)
                    .exceptionally(throwable -> {
                        if (isEnabled) plugin.getLogger().severe("Error removing " + event.getPlayer().getName() + " from Redis on quit: " + throwable.getMessage());
                        return null;
                    });
        }
    }
}