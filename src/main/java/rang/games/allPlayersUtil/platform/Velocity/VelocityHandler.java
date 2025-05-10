package rang.games.allPlayersUtil.platform.Velocity;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import rang.games.allPlayersUtil.RedisClient;
import rang.games.allPlayersUtil.platform.PlatformHandler;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.Transaction;

import java.util.Set;
import java.util.logging.Logger;

public class VelocityHandler implements PlatformHandler {
    private final ProxyServer server;
    private final Object plugin;
    private RedisClient redisClient;
    private String serverName;
    private final Logger logger;
    private volatile boolean isEnabled = false;

    public VelocityHandler(ProxyServer server, Object plugin, Logger logger) {
        this.server = server;
        this.plugin = plugin;
        this.logger = logger;
    }

    @Override
    public void initialize(RedisClient redisClient, String serverName) {
        this.redisClient = redisClient;
        this.serverName = serverName;
        this.isEnabled = true;
        logger.info("§aInitializing VelocityHandler for server: " + serverName);
        registerEvents();
    }

    @Override
    public void disable() {
        this.isEnabled = false;
    }

    @Override
    public void registerEvents() {
        int maxRetries = 3;
        int retryCount = 0;

        while (retryCount < maxRetries) {
            try {
                server.getEventManager().register(plugin, this);
                logger.info("§aEvents registered successfully");
                return;
            } catch (Exception e) {
                retryCount++;
                logger.severe("§cEvent registration failed (Attempt " + retryCount + "): " + e.getMessage());
                if (retryCount >= maxRetries) {
                    logger.severe("§cDisabling plugin due to event registration failure");
                    disable();
                    return;
                }
            }
        }
    }

    @Subscribe
    public void onLogin(LoginEvent event) {
        if (!isEnabled) return;
        //logger.info("§aLogin event detected for: " + event.getPlayer().getUsername());
        String uuid = event.getPlayer().getUniqueId().toString();
        String name = event.getPlayer().getUsername();
        if (redisClient != null && serverName != null) {
            redisClient.publishNetworkJoin(uuid, name, serverName)
                    //.thenRun(() -> logger.info("§aPublished network join for: " + name))
                    .exceptionally(throwable -> {
                        logger.severe("§cError publishing network join: " + throwable.getMessage());
                        return null;
                    });
        }
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        if (!isEnabled) return;

        String uuid = event.getPlayer().getUniqueId().toString();
        String name = event.getPlayer().getUsername();

        if (redisClient != null) {
            redisClient.getPlayerServerAsync(uuid)
                    .thenAccept(currentServer -> {
                        if (currentServer != null) {
                            try (Jedis jedis = redisClient.getJedisPool().getResource()) {
                                Set<String> serverKeys = jedis.keys("server:*");

                                Transaction transaction = jedis.multi();

                                for (String serverKey : serverKeys) {
                                    transaction.srem(serverKey, uuid);
                                }
                                transaction.srem("online_players", uuid);
                                transaction.exec();

                                redisClient.publishNetworkQuit(uuid, name, currentServer)
                                        .exceptionally(throwable -> {
                                            logger.severe("Error publishing network quit: " + throwable.getMessage());
                                            return null;
                                        });
                            } catch (Exception e) {
                                logger.severe("Error during disconnect cleanup: " + e.getMessage());
                            }
                        }
                    })
                    .exceptionally(throwable -> {
                        logger.severe("Error during disconnect: " + throwable.getMessage());
                        return null;
                    });
        }
    }

    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        if (!isEnabled) return;

        String uuid = event.getPlayer().getUniqueId().toString();
        String name = event.getPlayer().getUsername();
        String toServer = event.getServer().getServerInfo().getName();
        if (event.getPreviousServer().isPresent()) {
            String fromServer = event.getPreviousServer().get().getServerInfo().getName();
            //this.logger.info("§eServer switch event detected for: " + event.getPlayer().getUsername());
            if (this.redisClient != null) {
                this.redisClient.publishServerSwitch(uuid, name, fromServer, toServer).thenRun(() -> {
                    //this.logger.info("§aPublished server switch event for " + name);
                }).exceptionally((throwable) -> {
                    this.logger.severe("§cError publishing server switch: " + throwable.getMessage());
                    return null;
                });
            }
        }

        if (this.redisClient != null) {
            this.redisClient.addPlayerAsync(uuid, toServer).thenRun(() -> {
                if (event.getPreviousServer().isPresent()) {
                    String fromServer = event.getPreviousServer().get().getServerInfo().getName();
                    this.redisClient.removePlayerAsync(uuid, fromServer).thenRun(() -> {
                        //this.logger.info("§aPlayer " + name + " moved from " + fromServer + " to " + toServer);
                    });
                }

            }).exceptionally((throwable) -> {
                this.logger.severe("§cError updating player location: " + throwable.getMessage());
                return null;
            });
        }


    }

    @Override
    public void broadcast(String message) {
        if (!isEnabled) return;
        server.getAllPlayers().forEach(player ->
                player.sendMessage(Component.text(message)));
    }

    @Override
    public String getServerName() {
        return serverName;
    }
}