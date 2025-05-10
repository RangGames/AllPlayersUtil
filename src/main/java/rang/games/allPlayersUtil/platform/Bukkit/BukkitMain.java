package rang.games.allPlayersUtil.platform.Bukkit;

import org.bukkit.plugin.java.JavaPlugin;
import rang.games.allPlayersUtil.RedisClient;
import rang.games.allPlayersUtil.platform.NetworkEventListener;
import java.util.concurrent.TimeUnit;

public class BukkitMain extends JavaPlugin {

    private RedisClient redisClient;
    private BukkitHandler platformHandler;
    private String serverNameConfig;

    @Override
    public void onEnable() {
        try {
            saveDefaultConfig();
            reloadConfig();

            String redisHost = getConfig().getString("redis.host", "localhost");
            int redisPort = getConfig().getInt("redis.port", 6379);
            this.serverNameConfig = getConfig().getString("server.name", "unknown-bukkit-server");

            RedisClient.initialize(redisHost,
                    redisPort,
                    this,
                    new BukkitSchedulerService(this));
            this.redisClient = RedisClient.getInstance();

            this.platformHandler = new BukkitHandler(this);
            this.platformHandler.initialize(redisClient, this.serverNameConfig);

            getServer().getPluginManager().registerEvents(new NetworkEventListener(this), this);

            getLogger().info("AllPlayersUtil has been enabled on Bukkit server: " + this.serverNameConfig);
        } catch (Exception e) {
            getLogger().severe("Failed to initialize AllPlayersUtil: " + e.getMessage());
            e.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        getLogger().info("Disabling AllPlayersUtil plugin...");

        if (platformHandler != null) {
            getLogger().info("Calling platformHandler.disable()...");
            try {
                platformHandler.disable();
                getLogger().info("platformHandler.disable() completed.");
            } catch (Exception e) {
                getLogger().severe("Error during platformHandler.disable(): " + e.getMessage());
                e.printStackTrace();
            }
        } else {
            getLogger().warning("platformHandler is null, cannot call disable().");
        }

        if (redisClient != null) {
            getLogger().info("Calling redisClient.shutdown()...");
            try {
                redisClient.shutdown().get(5, TimeUnit.SECONDS);
                getLogger().info("redisClient.shutdown() completed.");
            } catch (InterruptedException e) {
                getLogger().warning("RedisClient shutdown was interrupted: " + e.getMessage());
                Thread.currentThread().interrupt();
            } catch (java.util.concurrent.TimeoutException e) {
                getLogger().warning("RedisClient shutdown timed out: " + e.getMessage());
            } catch (Exception e) {
                getLogger().severe("Error during redisClient.shutdown(): " + e.getMessage());
                e.printStackTrace();
            }
        } else {
            getLogger().warning("redisClient is null, cannot call shutdown().");
        }

        this.platformHandler = null;
        this.redisClient = null;
        this.serverNameConfig = null;

        getLogger().info("AllPlayersUtil plugin has been disabled!");
    }
}