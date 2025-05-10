package rang.games.allPlayersUtil;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import rang.games.allPlayersUtil.platform.*;
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
        enable();
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        disable();
    }

    public void onBukkitEnable(org.bukkit.plugin.java.JavaPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.dataDirectory = plugin.getDataFolder().toPath();
        enable();
    }

    public void onBukkitDisable(org.bukkit.plugin.java.JavaPlugin plugin) {
        disable();
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
                plugin,
                isVelocity ?
                        new VelocitySchedulerService((ProxyServer) proxyServer, plugin) :
                        new BukkitSchedulerService((org.bukkit.plugin.java.JavaPlugin) plugin));
        this.redisClient = RedisClient.getInstance();
        this.platformHandler = isVelocity ?
                new VelocityHandler(proxyServer, plugin, logger) :
                new BukkitHandler((org.bukkit.plugin.java.JavaPlugin) plugin);
        platformHandler.initialize(redisClient, serverName);
        platformHandler.registerEvents();

        logger.info("AllPlayersUtil has been enabled!");
    }

    private void disable() {
        if (redisClient != null) {
            Config config = loadConfig();
            String serverName = config.getString("server.name", "default-server");
            redisClient.cleanupServerAsync(serverName);
        }
        logger.info("AllPlayersUtil has been disabled!");
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
        return new Config(dataDirectory.resolve("config.yml").toFile());
    }

    private static class Config {
        private final Properties properties = new Properties();
        private org.bukkit.configuration.file.YamlConfiguration bukkitConfig;
        private final boolean isVelocity;

        public Config(File file) {
            boolean velocityCheck = false;
            try {
                Class.forName(VELOCITY_CLASS);
                velocityCheck = true;
            } catch (ClassNotFoundException e) {
                velocityCheck = false;
            }
            this.isVelocity = velocityCheck;

            try {
                if (this.isVelocity) {
                    File propsFile = new File(file.getParentFile(), "config.properties");
                    if (propsFile.exists()) {
                        try (FileReader reader = new FileReader(propsFile)) {
                            properties.load(reader);
                        }
                    }
                } else {
                    bukkitConfig = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        public boolean isDebugEnabled() {
            if (isVelocity) {
                return Boolean.parseBoolean(properties.getProperty("debug", "false"));
            } else {
                return bukkitConfig.getBoolean("debug", false);
            }
        }

        public String getString(String path, String def) {
            if (isVelocity) {
                return properties.getProperty(path.replace(".", "_"), def);
            } else {
                return bukkitConfig.getString(path, def);
            }
        }

        public int getInt(String path, int def) {
            if (isVelocity) {
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

    public static final class BukkitMain extends org.bukkit.plugin.java.JavaPlugin {
        private AllPlayersUtil plugin;

        @Override
        public void onEnable() {
            plugin = new AllPlayersUtil();
            plugin.onBukkitEnable(this);
        }

        @Override
        public void onDisable() {
            if (plugin != null) {
                plugin.onBukkitDisable(this);
            }
        }
    }
}