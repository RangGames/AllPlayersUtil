package rang.games.allPlayersUtil.platform;

import org.bukkit.plugin.java.JavaPlugin;
import rang.games.allPlayersUtil.AllPlayersUtilCore;
import rang.games.allPlayersUtil.RedisClient;

import java.util.concurrent.TimeUnit;

public class BukkitMain extends JavaPlugin {
    private AllPlayersUtilCore core;

    private RedisClient redisClient;
    private PurpurHandler platformHandler;
    private String serverName;
    @Override
    public void onEnable() {
        try {
            saveDefaultConfig();
            reloadConfig();

            String redisHost = getConfig().getString("redis.host", "localhost");
            int redisPort = getConfig().getInt("redis.port", 6379);
            String serverName = getConfig().getString("server.name", "unknown-server");

            RedisClient.initialize(redisHost,
                    redisPort,
                    this,
                    new BukkitSchedulerService(this));
            this.redisClient = RedisClient.getInstance();
            platformHandler = new PurpurHandler(this);
            platformHandler.initialize(redisClient, serverName);

            //getServer().getPluginManager().registerEvents(new NetworkEventListener(this), this);

            core = new AllPlayersUtilCore(platformHandler, serverName);
            core.enable(redisHost, redisPort);

            getLogger().info("§aAllPlayersUtil has been enabled!");
        } catch (Exception e) {
            getLogger().severe("§cFailed to initialize AllPlayersUtil: " + e.getMessage());
            e.printStackTrace();
        }
    }
    @Override
    public void onDisable() {
        try {
            if (redisClient != null) {
                if (serverName != null) {
                    redisClient.cleanupServerAsync(serverName).get(5, TimeUnit.SECONDS);
                }
                redisClient.shutdown();
            }
        } catch (Exception e) {
            getLogger().severe("§cError during plugin shutdown: " + e.getMessage());
        }
        getLogger().info("AllPlayersUtil has been disabled!");
    }
}