package rang.games.allPlayersUtil.platform;

import rang.games.allPlayersUtil.RedisClient;

public interface PlatformHandler {
    void initialize(RedisClient redisClient, String serverName);

    void disable();

    void registerEvents();

    void broadcast(String message);

    String getServerName();
}