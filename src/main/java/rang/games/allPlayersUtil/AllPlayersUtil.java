package rang.games.allPlayersUtil;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import rang.games.allPlayersUtil.platform.PlatformHandler;
import rang.games.allPlayersUtil.platform.Bukkit.BukkitHandler;
import rang.games.allPlayersUtil.platform.Bukkit.BukkitSchedulerService;
import rang.games.allPlayersUtil.platform.Velocity.VelocityHandler;
import rang.games.allPlayersUtil.platform.Velocity.VelocitySchedulerService;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Logger;

public final class AllPlayersUtil {
    private static final String VELOCITY_CLASS = "com.velocitypowered.api.proxy.ProxyServer";

    private RedisClient redisClient;
    private PlatformHandler platformHandler;
    private final boolean isVelocity;

    private ProxyServer proxyServer;
    private Logger logger;
    private Path dataDirectory;
    private Object plugin;

    @Inject
    public AllPlayersUtil(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxyServer = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
        this.isVelocity = true;
        this.plugin = this;
    }

    public AllPlayersUtil() {
        this.isVelocity = false;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        this.plugin = this;
        enable();
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        logger.info("AllPlayersUtil (Velocity) generic shutdown sequence initiated.");
        shutdownLogic();
    }

    public void onBukkitEnable(org.bukkit.plugin.java.JavaPlugin bukkitPlugin) {
        this.plugin = bukkitPlugin;
        this.logger = bukkitPlugin.getLogger();
        this.dataDirectory = bukkitPlugin.getDataFolder().toPath();
        enable();
    }

    public void onBukkitDisable() {
        logger.info("AllPlayersUtil (Bukkit) core shutdown sequence initiated.");
        shutdownLogic();
    }

    private void enable() {
        createDefaultConfig();
        Config config = loadConfig();

        String redisHost = config.getString("redis.host", "localhost");
        int redisPort = config.getInt("redis.port", 6379);
        String serverName = config.getString("server.name", "default-server");

        logger.info("§aInitializing AllPlayersUtil...");
        logger.info("§aServer name: " + serverName);
        logger.info("§aRedis host: " + redisHost);
        logger.info("§aRedis port: " + redisPort);

        RedisClient.initialize(redisHost,
                redisPort,
                this.plugin,
                isVelocity ?
                        new VelocitySchedulerService(this.proxyServer, this.plugin) :
                        new BukkitSchedulerService((org.bukkit.plugin.java.JavaPlugin) this.plugin));
        this.redisClient = RedisClient.getInstance();

        if (this.redisClient == null || !this.redisClient.isFunctional()) {
            logger.severe("RedisClient failed to initialize or is not functional. AllPlayersUtil features relying on Redis will be unavailable.");
        }

        this.platformHandler = isVelocity ?
                new VelocityHandler(this.proxyServer, this.plugin, this.logger) :
                new BukkitHandler((org.bukkit.plugin.java.JavaPlugin) this.plugin);

        platformHandler.initialize(this.redisClient, serverName);

        logger.info("AllPlayersUtil has been enabled!");
    }

    private void shutdownLogic() {
        if (logger == null) {
            System.out.println("[AllPlayersUtil] Logger not available for shutdown messages.");
        } else {
            logger.info("AllPlayersUtil shutdownLogic() initiated.");
        }

        if (platformHandler != null) {
            if (logger!=null) logger.info("Calling platformHandler.disable()...");
            try {
                platformHandler.disable();
                if (logger!=null) logger.info("platformHandler.disable() completed.");
            } catch (Exception e) {
                if (logger!=null) logger.severe("Error during platformHandler.disable(): " + e.getMessage());
                if (logger!=null) e.printStackTrace(); else e.printStackTrace(System.err);
            }
        } else {
            if (logger!=null) logger.warning("PlatformHandler is null, cannot call disable().");
        }

        if (redisClient != null) {
            if (redisClient.isFunctional() || redisClient.isShuttingDown()) {
                if (logger!=null) logger.info("Calling redisClient.shutdown()...");
                try {
                    redisClient.shutdown().get(7, TimeUnit.SECONDS);
                    if (logger!=null) logger.info("redisClient.shutdown() completed.");
                } catch (TimeoutException e) {
                    if (logger!=null) logger.warning("RedisClient shutdown timed out: " + e.getMessage());
                } catch (InterruptedException e) {
                    if (logger!=null) logger.warning("RedisClient shutdown was interrupted: " + e.getMessage());
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    if (logger!=null) logger.severe("Error during redisClient.shutdown(): " + e.getMessage());
                    if (logger!=null) e.printStackTrace(); else e.printStackTrace(System.err);
                }
            } else {
                if (logger!=null) logger.warning("RedisClient is not functional and not shutting down, skipping RedisClient.shutdown() call.");
            }
        } else {
            if (logger!=null) logger.warning("RedisClient is null, cannot call shutdown().");
        }

        if (logger!=null) logger.info("AllPlayersUtil shutdownLogic() finished.");
    }

    private void createDefaultConfig() {
        try {
            if (!Files.exists(dataDirectory)) {
                Files.createDirectories(dataDirectory);
            }

            if (isVelocity) {
                Path configPath = dataDirectory.resolve("config.properties");
                if (!Files.exists(configPath)) {
                    try (InputStream in = getClass().getResourceAsStream("/config.properties")) {
                        if (in != null) {
                            Files.copy(in, configPath);
                        }
                    }
                }
            } else {
                Path configPath = dataDirectory.resolve("config.yml");
                if (!Files.exists(configPath)) {
                    try (InputStream in = getClass().getResourceAsStream("/config.yml")) {
                        if (in != null) {
                            Files.copy(in, configPath);
                        }
                    }
                }
            }
        } catch (IOException e) {
            logger.severe("Could not create config file: " + e.getMessage());
        }
    }

    private Config loadConfig() {
        if (isVelocity) {
            return new Config(dataDirectory.resolve("config.properties").toFile());
        } else {
            return new Config(dataDirectory.resolve("config.yml").toFile());
        }
    }

    private static class Config {
        private final Properties properties = new Properties();
        private org.bukkit.configuration.file.YamlConfiguration bukkitConfig;
        private final boolean isVelocityConfig;

        public Config(File file) {
            if (file.getName().endsWith(".properties")) {
                isVelocityConfig = true;
                if (file.exists()) {
                    try (FileReader reader = new FileReader(file)) {
                        properties.load(reader);
                    } catch (IOException e) {
                        System.err.println("Failed to load properties file: " + file.getAbsolutePath() + " - " + e.getMessage());
                    }
                }
            } else {
                isVelocityConfig = false;
                bukkitConfig = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file);
            }
        }
        public String getString(String path, String def) {
            if (isVelocityConfig) {
                return properties.getProperty(path.replace(".", "_"), def);
            } else {
                return bukkitConfig.getString(path, def);
            }
        }

        public int getInt(String path, int def) {
            if (isVelocityConfig) {
                try {
                    return Integer.parseInt(properties.getProperty(path.replace(".", "_"), String.valueOf(def)));
                } catch (NumberFormatException e) {
                    return def;
                }
            } else {
                return bukkitConfig.getInt(path, def);
            }
        }
    }
}