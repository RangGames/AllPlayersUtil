package rang.games.allPlayersUtil;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.JedisPubSub;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

public class RedisClient {
    private static volatile RedisClient instance;
    private JedisPool jedisPool;
    private final Set<NetworkEventListener> eventListeners = new CopyOnWriteArraySet<>();
    private static final Logger logger = Logger.getLogger(RedisClient.class.getName());
    private static String host;
    private static int port;
    private boolean isSubscribed = false;
    private static final int MAX_RECONNECT_ATTEMPTS = 5;
    private static final long RECONNECT_DELAY_MS = 5000;
    private static Object plugin;
    private static SchedulerService scheduler;
    private final Set<Object> scheduledTasks = new CopyOnWriteArraySet<>();
    private JedisPubSub pubSub;
    private final RedisPlayerAPI playerAPI;
    private final AtomicBoolean isShuttingDown = new AtomicBoolean(false);
    private Object reconnectTask = null;
    private final Set<CompletableFuture<?>> activeTasks = new CopyOnWriteArraySet<>();
    private final AtomicBoolean isInitialized = new AtomicBoolean(false);

    public interface SchedulerService {
        Object scheduleAsyncRepeatingTask(Runnable task, long initialDelay, long period);
        void executePlatformEvent(NetworkEventListener listener, NetworkEventType type, String uuid, String name, String fromServer, String toServer);
        void cancelTask(Object task);
    }
    public enum NetworkEventType {
        NETWORK_JOIN,
        NETWORK_QUIT,
        SERVER_SWITCH
    }

    public interface NetworkEventListener {
        void onNetworkEvent(NetworkEventType type, String playerUuid, String playerName, String fromServer, String toServer);
    }

    public static synchronized void initialize(String host, int port, Object plugin, SchedulerService scheduler) {
        if (instance != null) {
            throw new IllegalStateException("RedisClient is already initialized!");
        }
        RedisClient.host = host;
        RedisClient.port = port;
        RedisClient.plugin = plugin;
        RedisClient.scheduler = scheduler;
    }
    private RedisClient(String host, int port, Object plugin, SchedulerService scheduler) {
        this.host = host;
        this.port = port;
        this.plugin = plugin;
        this.scheduler = scheduler;

        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(8);
        poolConfig.setMaxIdle(8);
        poolConfig.setMinIdle(2);
        poolConfig.setTestOnBorrow(true);
        poolConfig.setTestOnReturn(true);
        poolConfig.setTestWhileIdle(true);
        poolConfig.setMinEvictableIdleTimeMillis(60000);
        poolConfig.setTimeBetweenEvictionRunsMillis(30000);
        poolConfig.setNumTestsPerEvictionRun(3);
        poolConfig.setBlockWhenExhausted(true);
        poolConfig.setMaxWaitMillis(10000);

        this.jedisPool = new JedisPool(poolConfig, host, port, 2000);
        RedisPlayerAPI.initialize(jedisPool);
        this.playerAPI = RedisPlayerAPI.getInstance();

        isInitialized.set(true);
        startSubscriber();
        startKeyRefreshTask();
    }

    public static RedisClient getInstance() {
        if (instance == null) {
            synchronized (RedisClient.class) {
                if (instance == null) {
                    validateParameters();
                    instance = new RedisClient(host, port, plugin, scheduler);
                }
            }
        }
        return instance;
    }

    private static void validateParameters() {
        if (host == null || scheduler == null) {
            throw new IllegalStateException("RedisClient must be initialized first!");
        }
    }
    private void startSubscriber() {
        if (isShuttingDown.get() || !isInitialized.get()) {
            return;
        }

        Object task = scheduler.scheduleAsyncRepeatingTask(() -> {
            if (!isShuttingDown.get()) {
                try (Jedis jedis = jedisPool.getResource()) {
                    if (pubSub == null || !pubSub.isSubscribed()) {
                        pubSub = createPubSub();
                        logger.info("Subscribing to Redis channels...");
                        jedis.subscribe(pubSub, "network-events");
                    }
                } catch (Exception e) {
                    if (!isShuttingDown.get()) {
                        logger.warning("Redis subscription error: " + e.getMessage());
                    }
                }
            }
        }, 0L, 5000L);
    }

    private void startKeyRefreshTask() {
        scheduler.scheduleAsyncRepeatingTask(() -> {
            try (Jedis jedis = jedisPool.getResource()) {
                Set<String> serverKeys = jedis.keys("server:*");
                for (String serverKey : serverKeys) {
                    if (jedis.exists(serverKey)) {
                        jedis.expire(serverKey, 300);
                    }
                }

                if (jedis.exists("online_players")) {
                    jedis.expire("online_players", 300);
                }
            } catch (Exception e) {
                logger.severe("Error refreshing Redis keys: " + e.getMessage());
            }
        }, 60000, 60000);
    }
    private void reconnectIfNeeded() {
        if (jedisPool != null && !jedisPool.isClosed()) {
            try (Jedis jedis = jedisPool.getResource()) {
                jedis.ping();
            } catch (Exception e) {
                logger.warning("Redis connection lost, attempting to reconnect...");
                synchronized (this) {
                    if (jedisPool != null) {
                        try {
                            jedisPool.close();
                        } catch (Exception ex) {
                            logger.severe("Error closing old pool: " + ex.getMessage());
                        }
                        createNewPool();
                    }
                }
            }
        }
    }

    private void createNewPool() {
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        jedisPool = new JedisPool(poolConfig, host, port, 2000);
        RedisPlayerAPI.initialize(jedisPool);
    }

    public CompletableFuture<Void> shutdown() {
        return CompletableFuture.runAsync(() -> {
            try {
                isShuttingDown.set(true);
                logger.info("Starting Redis client shutdown process...");

                if (pubSub != null && pubSub.isSubscribed()) {
                    try {
                        pubSub.unsubscribe();
                    } catch (Exception e) {
                        logger.warning("Error during unsubscribe: " + e.getMessage());
                    }
                }

                CompletableFuture<Void> allTasks = CompletableFuture.allOf(
                        activeTasks.toArray(new CompletableFuture[0])
                );

                try {
                    allTasks.get(5, TimeUnit.SECONDS);
                } catch (TimeoutException e) {
                    logger.warning("Some tasks did not complete within timeout");
                }

                if (jedisPool != null && !jedisPool.isClosed()) {
                    jedisPool.close();
                }

                isInitialized.set(false);
                logger.info("Redis client shutdown completed");
            } catch (Exception e) {
                logger.severe("Error during Redis client shutdown: " + e.getMessage());
                throw new CompletionException(e);
            }
        });
    }
    public String getHost() { return host; }
    public int getPort() { return port; }

    private <T> CompletableFuture<T> trackTask(CompletableFuture<T> future) {
        if (!isShuttingDown.get()) {
            activeTasks.add(future);
            return future.whenComplete((result, ex) -> {
                activeTasks.remove(future);
                if (ex != null) {
                    logger.severe("Task failed with error: " + ex.getMessage());
                }
            }).exceptionally(throwable -> {
                logger.severe("Task failed with error: " + throwable.getMessage());
                throw new CompletionException(throwable);
            });
        }
        return future;
    }


    private JedisPubSub createPubSub() {
        return new JedisPubSub() {
            @Override
            public void onMessage(String channel, String message) {
                if (!channel.equals("network-events") || isShuttingDown.get()) {
                    return;
                }
                processMessage(message);
            }

            @Override
            public void onSubscribe(String channel, int subscribedChannels) {
                logger.info("Subscribed to Redis channel: " + channel);
                isSubscribed = true;
            }

            @Override
            public void onUnsubscribe(String channel, int subscribedChannels) {
                logger.info("Unsubscribed from Redis channel: " + channel);
                isSubscribed = false;
            }
        };
    }

    private void processMessage(String message) {
        if (isShuttingDown.get() || !isInitialized.get()) {
            return;
        }

        try {
            String[] parts = message.split(":");
            if (parts.length != 5) {
                logger.warning("Invalid message format: " + message);
                return;
            }

            NetworkEventType type = NetworkEventType.valueOf(parts[0]);
            String uuid = parts[1];
            String name = parts[2];
            String fromServer = parts[3];
            String toServer = parts[4];

            Set<NetworkEventListener> listeners = new HashSet<>(eventListeners);
            for (NetworkEventListener listener : listeners) {
                if (!isShuttingDown.get()) {
                    try {
                        if (scheduler != null && plugin != null) {
                            try {
                                Class.forName("org.bukkit.plugin.Plugin");
                                if (plugin instanceof org.bukkit.plugin.Plugin) {
                                    if (!((org.bukkit.plugin.Plugin) plugin).isEnabled()) {
                                        continue;
                                    }
                                }
                            } catch (ClassNotFoundException ignored) {
                            }

                            scheduler.executePlatformEvent(listener, type, uuid, name, fromServer, toServer);
                        }
                    } catch (Exception e) {
                        logger.warning("Error in event listener (skipping): " + e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            logger.warning("Error processing message: " + e.getMessage());
        }
    }
    private void handleSubscriptionError(Exception e, AtomicInteger attempts, AtomicBoolean taskShouldRun) {
        int currentAttempt = attempts.incrementAndGet();
        logger.severe(String.format("Redis connection attempt %d/%d failed: %s",
                currentAttempt, MAX_RECONNECT_ATTEMPTS, e.getMessage()));

        if (currentAttempt >= MAX_RECONNECT_ATTEMPTS) {
            taskShouldRun.set(false);
            logger.severe("Max reconnection attempts reached. Redis subscription failed.");
        }

        isSubscribed = false;
        if (pubSub != null && pubSub.isSubscribed()) {
            try {
                pubSub.unsubscribe();
            } catch (Exception ignored) {}
        }
    }

    public void addListener(NetworkEventListener listener) {
        eventListeners.add(listener);
    }

    public void removeListener(NetworkEventListener listener) {
        eventListeners.remove(listener);
    }

    public CompletableFuture<Void> publishNetworkJoin(String uuid, String playerName, String serverName) {
        return trackTask(CompletableFuture.runAsync(() -> {
            try (Jedis jedis = jedisPool.getResource()) {
                //logger.info("§aPublishing network join for " + playerName + " on server " + serverName);

                jedis.sadd("online_players", uuid);
                jedis.sadd("server:" + serverName, uuid);
                jedis.expire("server:" + serverName, 60);

                String message = String.format("%s:%s:%s:%s:%s",
                        NetworkEventType.NETWORK_JOIN, uuid, playerName, "null", serverName);
                jedis.publish("network-events", message);

                //logger.info("§aSuccessfully published network join event");
            } catch (Exception e) {
                logger.severe("§cError publishing network join: " + e.getMessage());
                e.printStackTrace();
            }
        }));

    }
    public JedisPool getJedisPool() {
        return this.jedisPool;
    }
    public CompletableFuture<Void> publishNetworkQuit(String uuid, String playerName, String serverName) {
        //logger.info("Publishing network quit: UUID=" + uuid + ", Name=" + playerName + ", Server=" + serverName);

        return trackTask(removePlayerAsync(uuid, serverName)
                .thenCompose(v -> CompletableFuture.runAsync(() -> {
                    try (Jedis jedis = jedisPool.getResource()) {
                        if (!jedis.sismember("online_players", uuid)) {
                            String message = String.format("%s:%s:%s:%s:%s",
                                    NetworkEventType.NETWORK_QUIT, uuid, playerName, serverName, "null");
                            jedis.publish("network-events", message);
                            //logger.info("Published quit event for " + playerName);
                        } else {
                            //logger.info("Player " + playerName + " is still online in another server");
                        }
                    }
                }))
                .exceptionally(e -> {
                    logger.severe("Error in publishNetworkQuit: " + e.getMessage());
                    throw new CompletionException(e);
                }));
    }

    public CompletableFuture<Void> publishServerSwitch(String uuid, String playerName, String fromServer, String toServer) {
        return trackTask(CompletableFuture.runAsync(() -> {
            try (Jedis jedis = jedisPool.getResource()) {
                String message = String.format("%s:%s:%s:%s:%s",
                        NetworkEventType.SERVER_SWITCH, uuid, playerName, fromServer, toServer);
                jedis.publish("network-events", message);
                logger.info("§aPublished server switch event for " + playerName);
            } catch (Exception e) {
                logger.severe("§cError publishing server switch event: " + e.getMessage());
                e.printStackTrace();
            }
        }));
    }
    public CompletableFuture<Void> addPlayerAsync(String uuid, String serverName) {
        return trackTask(CompletableFuture.runAsync(() -> {
            try (Jedis jedis = jedisPool.getResource()) {
                //logger.info("§aAdding player " + uuid + " to Redis (server: " + serverName + ")");
                jedis.sadd("online_players", uuid);
                jedis.sadd("server:" + serverName, uuid);
                jedis.expire("server:" + serverName, 300);
                jedis.expire("online_players", 300);
                //logger.info("§aSuccessfully added player to Redis");
            } catch (Exception e) {
                logger.severe("§cError adding player to Redis: " + e.getMessage());
                e.printStackTrace();
            }
        }));
    }
    public CompletableFuture<Void> removePlayerAsync(String uuid, String serverName) {
        return trackTask(CompletableFuture.runAsync(() -> {
            try (Jedis jedis = jedisPool.getResource()) {
                jedis.srem("online_players", uuid);
                jedis.srem("server:" + serverName, uuid);
            }
        }));
    }

    public CompletableFuture<Set<String>> getOnlinePlayersAsync() {
        return playerAPI.getOnlinePlayersAsync();
    }

    public CompletableFuture<Set<String>> getServerPlayersAsync(String serverName) {
        return playerAPI.getServerPlayersAsync(serverName);
    }

    public CompletableFuture<String> getPlayerServerAsync(String uuid) {
        return playerAPI.getPlayerServerAsync(uuid);
    }

    public CompletableFuture<Void> cleanupServerAsync(String serverName) {
        return CompletableFuture.runAsync(() -> {
            try (Jedis jedis = jedisPool.getResource()) {
                jedis.del("server:" + serverName);
            }
        });
    }
}