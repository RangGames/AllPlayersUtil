package rang.games.allPlayersUtil;

import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class RedisPlayerAPI {
    private static final org.slf4j.Logger log = LoggerFactory.getLogger(RedisPlayerAPI.class);
    private static RedisPlayerAPI instance;
    private static String velocityServerName = "proxy";

    private final JedisPool jedisPool;
    private static final Logger logger = Logger.getLogger(RedisPlayerAPI.class.getName());

    private RedisPlayerAPI(JedisPool jedisPool) {
        this.jedisPool = jedisPool;
    }

    public static RedisPlayerAPI getInstance() {
        if (instance == null) {
            throw new IllegalStateException("RedisPlayerAPI has not been initialized!");
        }
        return instance;
    }

    static void initialize(JedisPool jedisPool) {
        if (instance == null) {
            instance = new RedisPlayerAPI(jedisPool);
        } else {
            instance = new RedisPlayerAPI(jedisPool);
        }
    }

    /**
     * Asynchronously retrieves the UUIDs of all online players across the network.
     * It iterates through all Redis keys matching the "server:*" pattern (excluding the
     * Velocity proxy's own server key, if defined by `velocityServerName`), and aggregates
     * the player UUIDs stored in those server-specific sets.
     *
     * @return A {@code CompletableFuture<Set<String>>} that, upon completion, will contain a set
     *         of all online player UUIDs. If an error occurs during the Redis operation,
     *         an empty set is returned, and the error is logged.
     */
    public CompletableFuture<Set<String>> getOnlinePlayersAsync() {
        return CompletableFuture.supplyAsync(() -> {
            try (Jedis jedis = jedisPool.getResource()) {
                Set<String> allPlayers = new HashSet<>();
                Set<String> serverKeys = jedis.keys("server:*");

                for (String serverKey : serverKeys) {
                    if (serverKey.equals("server:" + velocityServerName)) {
                        continue;
                    }
                    Set<String> players = jedis.smembers(serverKey);
                    if (players != null) {
                        allPlayers.addAll(players);
                    }
                }

                return allPlayers;
            } catch (Exception e) {
                logger.severe("§cError fetching online players: " + e.getMessage());
                e.printStackTrace();
                return new HashSet<>();
            }
        });
    }

    /**
     * Asynchronously checks if a specific game server is currently marked as online in Redis.
     * It looks for a key "server_status:{serverName}" with the value "online" and a positive TTL.
     *
     * @param serverName The name of the server to check.
     * @return A {@code CompletableFuture<Boolean>} that, upon completion, will be {@code true}
     *         if the server is considered online (status key exists, value is "online", and TTL > 0),
     *         and {@code false} otherwise or if an error occurs.
     */
    public CompletableFuture<Boolean> isServerOnline(String serverName) {
        return CompletableFuture.supplyAsync(() -> {
            try (Jedis jedis = jedisPool.getResource()) {
                String key = "server_status:" + serverName;
                String status = jedis.get(key);
                if ("online".equals(status)) {
                    long ttl = jedis.ttl(key);
                    if (ttl <= 0) {
                        return false; // status key exists but has no TTL, considering offline
                    }
                    return true;
                }
                return false;
            } catch (Exception e) {
                logger.severe("Error checking server status: " + e.getMessage());
                return false;
            }
        });
    }

    /**
     * Asynchronously retrieves the UUIDs of all players currently on a specific game server.
     * It fetches members from the Redis set stored at "server:{serverName}".
     *
     * @param serverName The name of the server for which to retrieve player UUIDs.
     * @return A {@code CompletableFuture<Set<String>>} that, upon completion, will contain a set
     *         of player UUIDs on the specified server. If the server does not exist or an
     *         error occurs, an empty set is returned.
     */
    public CompletableFuture<Set<String>> getServerPlayersAsync(String serverName) {
        return CompletableFuture.supplyAsync(() -> {
            try (Jedis jedis = jedisPool.getResource()) {
                return jedis.smembers("server:" + serverName);
            } catch (Exception e) {
                logger.severe("§cError fetching server players: " + e.getMessage());
                return new HashSet<>();
            }
        });
    }

    /**
     * Asynchronously determines the current game server a specific player is on.
     * It iterates through all Redis keys matching "server:*" (excluding the Velocity proxy's
     * own server key) and checks if the player's UUID is a member of that server's player set.
     *
     * @param uuid The UUID of the player whose server is to be found.
     * @return A {@code CompletableFuture<String>} that, upon completion, will contain the
     *         name of the server the player is on. If the player is not found on any
     *         server or an error occurs, it completes with {@code null}.
     *         The server name is derived by removing the "server:" prefix from the Redis key.
     */
    public CompletableFuture<String> getPlayerServerAsync(String uuid) {
        return CompletableFuture.supplyAsync(() -> {
            try (Jedis jedis = jedisPool.getResource()) {
                Set<String> serverKeys = jedis.keys("server:*");
                for (String serverKey : serverKeys) {
                    if (serverKey.equals("server:" + velocityServerName)) {
                        continue;
                    }
                    if (jedis.sismember(serverKey, uuid)) {
                        return serverKey.substring(7);
                    }
                }
                return null;
            } catch (Exception e) {
                logger.severe("§cError fetching player server: " + e.getMessage());
                return null;
            }
        });
    }
}