package rang.games.allPlayersUtil.platform.Velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import rang.games.allPlayersUtil.RedisClient;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.Transaction;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Logger;

@Plugin(
        id = "all-players-util",
        name = "AllPlayersUtil",
        version = "2.0.4.9-SNAPSHOT",
        description = "Network-wide player tracking utility",
        authors = {"rang"}
)
public class VelocityMain {
    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;
    private RedisClient redisClient;
    private VelocityHandler platformHandler;

    @Inject
    public VelocityMain(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        try {
            Properties config = loadConfig();

            String redisHost = config.getProperty("redis_host", "localhost");
            int redisPort = Integer.parseInt(config.getProperty("redis_port", "6379"));
            String serverName = config.getProperty("server_name", "proxy");

            RedisClient.initialize(
                    redisHost,
                    redisPort,
                    this,
                    new VelocitySchedulerService(server, this)
            );

            this.redisClient = RedisClient.getInstance();

            this.platformHandler = new VelocityHandler(server, this, logger);
            this.platformHandler.initialize(redisClient, serverName);

            logger.info("§aAllPlayersUtil has been enabled!");
        } catch (Exception e) {
            logger.severe("§cFailed to initialize AllPlayersUtil: " + e.getMessage());
            e.printStackTrace();
        }
    }
    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (redisClient != null) {
            try {
                Properties config = loadConfig();
                String serverName = config.getProperty("server_name", "proxy");

                try (Jedis jedis = redisClient.getJedisPool().getResource()) {
                    jedis.del("server_status:" + serverName);

                    Set<String> players = jedis.smembers("server:" + serverName);

                    if (!players.isEmpty()) {
                        Transaction transaction = jedis.multi();

                        for (String uuid : players) {
                            transaction.srem("server:" + serverName, uuid);
                            transaction.srem("online_players", uuid);
                        }

                        transaction.del("server:" + serverName);
                        transaction.exec();
                    }
                }
                redisClient.cleanupServerAsync(serverName).get(5, TimeUnit.SECONDS);
                redisClient.shutdown().get(5, TimeUnit.SECONDS);
                logger.info("AllPlayersUtil shutdown completed successfully");
            } catch (TimeoutException e) {
                logger.warning("Shutdown process did not complete within timeout");
            } catch (Exception e) {
                logger.severe("Error during shutdown: " + e.getMessage());
            }
        }
    }

    private Properties loadConfig() {
        Properties props = new Properties();
        Path configPath = dataDirectory.resolve("config.properties");

        try {
            if (!Files.exists(dataDirectory)) {
                Files.createDirectories(dataDirectory);
            }

            if (!Files.exists(configPath)) {
                try (InputStream in = getClass().getResourceAsStream("/config.properties")) {
                    if (in != null) {
                        Files.copy(in, configPath);
                    }
                }
            }

            if (Files.exists(configPath)) {
                try (InputStream in = Files.newInputStream(configPath)) {
                    props.load(in);
                }
            }
        } catch (IOException e) {
            logger.warning("Failed to load config: " + e.getMessage());
        }

        return props;
    }
}