package rang.games.allPlayersUtil;

import rang.games.allPlayersUtil.platform.PlatformHandler;

public class AllPlayersUtilCore {
    private RedisClient redisClient;
    private PlatformHandler platformHandler;
    private final String serverName;

    public AllPlayersUtilCore(PlatformHandler platformHandler, String serverName) {
        this.platformHandler = platformHandler;
        this.serverName = serverName;
    }

    public void enable(String redisHost, int redisPort) {
        platformHandler.initialize(redisClient, serverName);
        platformHandler.registerEvents();
    }

    public void disable() {
        if (redisClient != null) {
            redisClient.cleanupServerAsync(serverName);
        }
    }
}