package rang.games.allPlayersUtil.platform.Velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import rang.games.allPlayersUtil.RedisClient;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Logger;

@Plugin(
        id = "all-players-util",
        name = "AllPlayersUtil",
        version = "2.0.4.12-SNAPSHOT",
        description = "Network-wide player tracking utility",
        authors = {"rang"}
)
public class VelocityMain {
    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;
    private RedisClient redisClient;
    private VelocityHandler platformHandler;
    private String serverName;

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
            this.serverName = config.getProperty("server_name", "proxy");

            RedisClient.initialize(
                    redisHost,
                    redisPort,
                    this,
                    new VelocitySchedulerService(server, this)
            );
            this.redisClient = RedisClient.getInstance();

            if (this.redisClient == null || !this.redisClient.isFunctional()) {
                logger.severe("RedisClient failed to initialize for Velocity. Plugin may not work correctly.");
            }

            this.platformHandler = new VelocityHandler(server, this, logger);
            this.platformHandler.initialize(this.redisClient, this.serverName);

            if (this.redisClient != null && this.redisClient.isFunctional()) {
                this.redisClient.publishNetworkServerStart(this.serverName)
                        .exceptionally(throwable -> {
                            logger.severe("Failed to publish proxy server start event: " + throwable.getMessage());
                            return null;
                        });
            }

            logger.info("§aAllPlayersUtil has been enabled on Velocity!");
        } catch (Exception e) {
            logger.severe("§cFailed to initialize AllPlayersUtil on Velocity: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        logger.info("AllPlayersUtil onProxyShutdown (Velocity) initiated...");

        if (platformHandler != null) {
            logger.info("Calling platformHandler.disable() for Velocity...");
            try {
                platformHandler.disable();
                logger.info("Velocity platformHandler.disable() completed.");
            } catch (Exception e) {
                logger.severe("Error during Velocity platformHandler.disable(): " + e.getMessage());
                e.printStackTrace();
            }
        }

        if (redisClient != null && redisClient.isFunctional() && this.serverName != null) {
            logger.info("Performing Velocity-specific Redis cleanup for server: " + this.serverName);
            try {
                redisClient.publishNetworkServerShutdown(this.serverName).get(5, TimeUnit.SECONDS);
                logger.info("Published network server shutdown for proxy: " + this.serverName);

                redisClient.cleanupServerAsync(this.serverName).get(5, TimeUnit.SECONDS);
                logger.info("Redis cleanup for proxy server " + this.serverName + " completed.");

            } catch (TimeoutException e) {
                logger.warning("Timeout during Velocity Redis cleanup/publish operations: " + e.getMessage());
            } catch (InterruptedException e) {
                logger.warning("Interrupted during Velocity Redis cleanup/publish: " + e.getMessage());
                Thread.currentThread().interrupt();
            } catch (ExecutionException e) {
                logger.severe("ExecutionException during Velocity Redis cleanup/publish: " + e.getCause().getMessage());
            } catch (Exception e) {
                logger.severe("Error during Velocity Redis cleanup/publish: " + e.getMessage());
                e.printStackTrace();
            }
        } else {
            logger.warning("RedisClient not functional or serverName null during Velocity shutdown, skipping specific Redis tasks.");
        }

        if (redisClient != null) {
            if (redisClient.isFunctional() || redisClient.isShuttingDown()) {
                logger.info("Calling redisClient.shutdown() for Velocity...");
                try {
                    redisClient.shutdown().get(7, TimeUnit.SECONDS);
                    logger.info("RedisClient shutdown completed for Velocity.");
                } catch (TimeoutException e) {
                    logger.warning("RedisClient shutdown timed out on Velocity: " + e.getMessage());
                } catch (InterruptedException e) {
                    logger.warning("RedisClient shutdown interrupted on Velocity: " + e.getMessage());
                    Thread.currentThread().interrupt();
                } catch (ExecutionException e) {
                    logger.severe("ExecutionException during RedisClient shutdown on Velocity: " + e.getCause().getMessage());
                }catch (Exception e) {
                    logger.severe("Error during RedisClient shutdown on Velocity: " + e.getMessage());
                    e.printStackTrace();
                }
            } else {
                logger.warning("RedisClient not functional and not shutting down, skipping RedisClient.shutdown() on Velocity.");
            }
        }
        logger.info("AllPlayersUtil onProxyShutdown (Velocity) finished.");
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
                    } else {
                        logger.warning("Default Velocity config.properties not found in resources. Creating a basic one.");
                        Files.writeString(configPath, "redis_host=localhost\nredis_port=6379\nserver_name=proxy\n");
                    }
                }
            }
            if (Files.exists(configPath)) {
                try (InputStream in = Files.newInputStream(configPath)) {
                    props.load(in);
                }
            }
        } catch (IOException e) {
            logger.warning("Failed to load/create Velocity config.properties: " + e.getMessage() + ". Using defaults.");
            props.setProperty("redis_host", "localhost");
            props.setProperty("redis_port", "6379");
            props.setProperty("server_name", "proxy");
        }
        return props;
    }
}