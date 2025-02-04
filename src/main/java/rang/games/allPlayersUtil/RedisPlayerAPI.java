package rang.games.allPlayersUtil;

import com.Zrips.CMI.PlayerManager;
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
        }
    }


    /**
     * 현재 네트워크의 모든 온라인 플레이어 UUID를 가져옵니다.
     *
     * @return CompletableFuture<Set<String>> - 온라인 플레이어 UUID 세트
     */
/*    public CompletableFuture<Set<String>> getOnlinePlayersAsync() {
        return CompletableFuture.supplyAsync(() -> {
            try (Jedis jedis = jedisPool.getResource()) {
                Set<String> players = jedis.smembers("online_players");
                if (players == null || players.isEmpty()) {
                    Set<String> allPlayers = new HashSet<>();
                    Set<String> serverKeys = jedis.keys("server:*");
                    for (String serverKey : serverKeys) {
                        allPlayers.addAll(jedis.smembers(serverKey));
                    }
                    if (!allPlayers.isEmpty()) {
                        String[] playerArray = allPlayers.toArray(new String[0]);
                        jedis.sadd("online_players", playerArray);
                        jedis.expire("online_players", 300);
                    }
                    return allPlayers;
                }
                return players;
            } catch (Exception e) {
                logger.severe("§cError fetching online players: " + e.getMessage());
                e.printStackTrace();
                return new HashSet<>();
            }
        });
    }*/
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
     * 특정 서버의 모든 플레이어 UUID를 가져옵니다.
     *
     * @param serverName 서버 이름
     * @return CompletableFuture<Set<String>> - 해당 서버의 플레이어 UUID 세트
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
     * 특정 플레이어의 현재 서버를 가져옵니다.
     *
     * @param uuid 플레이어 UUID
     * @return CompletableFuture<String> - 서버 이름 (없으면 null)
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