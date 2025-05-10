package rang.games.allPlayersUtil;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.JedisPubSub;
import redis.clients.jedis.exceptions.JedisConnectionException;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

public class RedisClient {
    private static volatile RedisClient instance;
    private JedisPool jedisPool;
    private final Set<NetworkEventListener> eventListeners = new CopyOnWriteArraySet<>();
    private static final Logger logger = Logger.getLogger(RedisClient.class.getName());
    private static String host;
    private static int port;
    private static Object plugin;
    private static SchedulerService scheduler;
    private final Set<Object> scheduledTasks = new CopyOnWriteArraySet<>();
    private JedisPubSub pubSub;
    private RedisPlayerAPI playerAPI;
    private final AtomicBoolean isShuttingDown = new AtomicBoolean(false);
    private final Set<CompletableFuture<?>> activeTasks = new CopyOnWriteArraySet<>();
    private final AtomicBoolean isInitialized = new AtomicBoolean(false);

    private Object subscriberTask = null;
    private Object keyRefreshTask = null;

    public interface SchedulerService {
        Object scheduleAsyncRepeatingTask(Runnable task, long initialDelay, long period);
        void executePlatformEvent(NetworkEventListener listener, NetworkEventType type, String id, String name, String fromServer, String toServer);
        void cancelTask(Object task);
    }

    public enum NetworkEventType {
        NETWORK_JOIN,
        NETWORK_QUIT,
        SERVER_SWITCH,
        NETWORK_SERVER_START,
        NETWORK_SERVER_SHUTDOWN
    }

    public interface NetworkEventListener {
        void onNetworkEvent(NetworkEventType type, String id, String name, String fromServer, String toServer);
    }

    public static synchronized void initialize(String host, int port, Object plugin, SchedulerService scheduler) {
        if (instance != null && instance.isInitialized.get() && !instance.isShuttingDown.get()) {
            logger.warning("RedisClient is already initialized and running. Skipping re-initialization.");
            return;
        }
        RedisClient.host = host;
        RedisClient.port = port;
        RedisClient.plugin = plugin;
        RedisClient.scheduler = scheduler;
        if (instance != null) {
            instance.isShuttingDown.set(true);
        }
        instance = null;
    }

    private RedisClient(String host, int port, Object pluginInstance, SchedulerService schedulerInstance) {
        RedisClient.host = host;
        RedisClient.port = port;
        RedisClient.plugin = pluginInstance;
        RedisClient.scheduler = schedulerInstance;

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

        try {
            this.jedisPool = new JedisPool(poolConfig, host, port, 2000);
            try (Jedis jedis = this.jedisPool.getResource()) {
                jedis.ping();
            }
            RedisPlayerAPI.initialize(jedisPool);
            this.playerAPI = RedisPlayerAPI.getInstance();
            isInitialized.set(true);
            startSubscriber();
            startKeyRefreshTask();
            logger.info("RedisClient initialized successfully and connected to " + host + ":" + port);
        } catch (JedisConnectionException e) {
            logger.severe("Failed to connect to Redis server at " + host + ":" + port + ". RedisClient will not be functional. Error: " + e.getMessage());
            isInitialized.set(false);
            if (this.jedisPool != null) {
                try { this.jedisPool.close(); } catch (Exception ex) { logger.warning("Error closing jedisPool on connection exception: " + ex.getMessage());}
                this.jedisPool = null;
            }
            this.playerAPI = null;
        } catch (Exception e) {
            logger.severe("An unexpected error occurred during RedisClient initialization: " + e.getMessage());
            e.printStackTrace();
            isInitialized.set(false);
            if (this.jedisPool != null && !this.jedisPool.isClosed()) {
                try { this.jedisPool.close(); } catch (Exception ex) { logger.warning("Error closing jedisPool on general exception: " + ex.getMessage());}
            }
            this.playerAPI = null;
        }
    }

    public static RedisClient getInstance() {
        if (instance == null || !instance.isInitialized.get()) {
            synchronized (RedisClient.class) {
                if (instance == null || !instance.isInitialized.get()) {
                    validateParameters();
                    if (instance != null && !instance.isInitialized.get() && instance.isShuttingDown.get()) {
                        instance = new RedisClient(host, port, plugin, scheduler);
                    } else if (instance == null) {
                        instance = new RedisClient(host, port, plugin, scheduler);
                    }
                    if (instance != null && !instance.isInitialized.get()) {
                        logger.severe("RedisClient getInstance() called, but initialization failed. Returning a non-functional instance.");
                    }
                }
            }
        }
        return instance;
    }

    private static void validateParameters() {
        if (host == null || scheduler == null || plugin == null) {
            throw new IllegalStateException("RedisClient must be initialized with host, scheduler, and plugin before getInstance() is called.");
        }
    }

    private void startSubscriber() {
        if (isShuttingDown.get() || !isInitialized.get() || scheduler == null) {
            return;
        }
        if (subscriberTask != null) {
            try { scheduler.cancelTask(subscriberTask); } catch (Exception ignored) {}
        }
        subscriberTask = scheduler.scheduleAsyncRepeatingTask(() -> {
            if (isShuttingDown.get()) {
                if (subscriberTask != null) {
                    try { scheduler.cancelTask(subscriberTask); } catch (Exception ignored) {}
                    subscriberTask = null;
                }
                return;
            }
            if (jedisPool == null || jedisPool.isClosed()) {
                logger.warning("JedisPool is not available for subscriber task. Attempting to reconnect/reinitialize pool.");
                reconnectJedisPool();
                if (jedisPool == null || jedisPool.isClosed()) return;
            }

            try (Jedis jedis = jedisPool.getResource()) {
                if (pubSub == null || !pubSub.isSubscribed()) {
                    if (pubSub != null && pubSub.isSubscribed()) {
                        try {
                            pubSub.unsubscribe();
                        } catch (Exception e) {
                            logger.warning("Error unsubscribing existing pubSub: " + e.getMessage());
                        }
                    }
                    pubSub = createPubSub();
                    logger.info("Subscribing to Redis channel: network-events");
                    jedis.subscribe(pubSub, "network-events");
                }
            } catch (JedisConnectionException e) {
                if (!isShuttingDown.get()) {
                    logger.warning("Redis connection error in subscriber task: " + e.getMessage() + ". Will attempt to resubscribe.");
                    if (pubSub != null && pubSub.isSubscribed()) {
                        try { pubSub.unsubscribe(); } catch (Exception ignored) {}
                    }
                    pubSub = null;
                    reconnectJedisPool();
                }
            } catch (Exception e) {
                if (!isShuttingDown.get()) {
                    logger.warning("Error in Redis subscriber task: " + e.getMessage());
                    e.printStackTrace();
                }
            }
        }, 0L, 5000L);
        scheduledTasks.add(subscriberTask);
    }

    private void startKeyRefreshTask() {
        if (isShuttingDown.get() || !isInitialized.get() || scheduler == null) {
            return;
        }
        if (keyRefreshTask != null) {
            try { scheduler.cancelTask(keyRefreshTask); } catch (Exception ignored) {}
        }
        keyRefreshTask = scheduler.scheduleAsyncRepeatingTask(() -> {
            if (isShuttingDown.get()) {
                if (keyRefreshTask != null) {
                    try { scheduler.cancelTask(keyRefreshTask); } catch (Exception ignored) {}
                    keyRefreshTask = null;
                }
                return;
            }
            if (jedisPool == null || jedisPool.isClosed()) {
                logger.warning("JedisPool is not available for key refresh task.");
                return;
            }
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
            } catch (JedisConnectionException e) {
                if (!isShuttingDown.get()) {
                    logger.warning("Redis connection error during key refresh: " + e.getMessage());
                    reconnectJedisPool();
                }
            } catch (Exception e) {
                logger.severe("Error refreshing Redis keys: " + e.getMessage());
            }
        }, 30000L, 30000L);
        scheduledTasks.add(keyRefreshTask);
    }

    private synchronized void reconnectJedisPool() {
        if (isShuttingDown.get()) return;
        logger.info("Attempting to reconnect JedisPool...");
        if (jedisPool != null && !jedisPool.isClosed()) {
            try {
                jedisPool.close();
            } catch (Exception e) {
                logger.warning("Error closing old JedisPool during reconnect: " + e.getMessage());
            }
        }
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(8);
        poolConfig.setMaxIdle(8);
        poolConfig.setMinIdle(2);
        poolConfig.setTestOnBorrow(true);
        try {
            jedisPool = new JedisPool(poolConfig, host, port, 2000);
            try (Jedis jedis = jedisPool.getResource()) {
                jedis.ping();
            }
            RedisPlayerAPI.initialize(jedisPool);
            this.playerAPI = RedisPlayerAPI.getInstance();
            logger.info("JedisPool reconnected successfully.");
            if (pubSub == null || !pubSub.isSubscribed()) {
                if (subscriberTask != null) {
                    try { scheduler.cancelTask(subscriberTask); } catch (Exception ignored) {}
                    subscriberTask = null;
                    startSubscriber();
                }
            }
        } catch (Exception e) {
            logger.severe("Failed to reconnect JedisPool: " + e.getMessage());
            if (jedisPool != null) {
                try { jedisPool.close(); } catch (Exception ex) { logger.warning("Error closing jedisPool on reconnect failure: " + ex.getMessage()); }
                jedisPool = null;
            }
            this.playerAPI = null;
        }
    }

    public CompletableFuture<Void> updateServerStatus(String serverName) {
        if (isShuttingDown.get() || !isInitialized.get() || jedisPool == null || jedisPool.isClosed()) {
            return CompletableFuture.failedFuture(new IllegalStateException("RedisClient not ready."));
        }
        return CompletableFuture.runAsync(() -> {
            try (Jedis jedis = jedisPool.getResource()) {
                String key = "server_status:" + serverName;
                jedis.set(key, "online");
                jedis.expire(key, 45);
            } catch (Exception e) {
                logger.severe("Error updating server status for " + serverName + ": " + e.getMessage());
                throw new CompletionException(e);
            }
        });
    }

    public CompletableFuture<Void> shutdown() {
        return CompletableFuture.runAsync(() -> {
            if (!isShuttingDown.compareAndSet(false, true)) {
                logger.info("Redis client shutdown already in progress or completed.");
                return;
            }
            logger.info("Starting Redis client shutdown process...");

            if (scheduler != null) {
                logger.info("Cancelling scheduled tasks...");
                if (subscriberTask != null) {
                    try { scheduler.cancelTask(subscriberTask); } catch (Exception ignored) {}
                    subscriberTask = null;
                }
                if (keyRefreshTask != null) {
                    try { scheduler.cancelTask(keyRefreshTask); } catch (Exception ignored) {}
                    keyRefreshTask = null;
                }
                for (Object task : scheduledTasks) {
                    try {
                        scheduler.cancelTask(task);
                    } catch (Exception e) {
                        logger.warning("Error cancelling a scheduled task: " + e.getMessage());
                    }
                }
                scheduledTasks.clear();
            }


            if (pubSub != null && pubSub.isSubscribed()) {
                logger.info("Unsubscribing from Redis channels...");
                try {
                    pubSub.unsubscribe();
                } catch (Exception e) {
                    logger.warning("Error during unsubscribe: " + e.getMessage());
                }
                pubSub = null;
            }

            if (!activeTasks.isEmpty()) {
                logger.info("Waiting for active tasks to complete...");
                CompletableFuture<Void> allTasks = CompletableFuture.allOf(
                        activeTasks.toArray(new CompletableFuture[0])
                );
                try {
                    allTasks.get(5, TimeUnit.SECONDS);
                } catch (TimeoutException e) {
                    logger.warning("Some tasks did not complete within timeout during shutdown.");
                    activeTasks.forEach(future -> future.cancel(true));
                } catch (InterruptedException e) {
                    logger.warning("Interrupted while waiting for active tasks to complete.");
                    activeTasks.forEach(future -> future.cancel(true));
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    logger.warning("Error waiting for active tasks to complete: " + e.getMessage());
                }
            }
            activeTasks.clear();

            if (jedisPool != null && !jedisPool.isClosed()) {
                logger.info("Closing JedisPool...");
                try {
                    jedisPool.close();
                } catch (Exception e) {
                    logger.severe("Error closing JedisPool: " + e.getMessage());
                }
                jedisPool = null;
            }

            isInitialized.set(false);
            logger.info("Redis client shutdown completed.");
        }).exceptionally(ex -> {
            logger.severe("Exception during Redis client shutdown sequence: " + ex.getMessage());
            ex.printStackTrace();
            isShuttingDown.set(true);
            isInitialized.set(false);
            if (jedisPool != null && !jedisPool.isClosed()) {
                try { jedisPool.close(); } catch (Exception e) { /* ignore */ }
            }
            jedisPool = null;
            this.playerAPI = null;
            return null;
        });
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    private <T> CompletableFuture<T> trackTask(CompletableFuture<T> future) {
        if (isShuttingDown.get() || !isInitialized.get()) {
            future.cancel(true);
            return CompletableFuture.failedFuture(new IllegalStateException("RedisClient is shutting down or not initialized. Task cancelled."));
        }
        activeTasks.add(future);
        return future.whenComplete((result, ex) -> {
            activeTasks.remove(future);
            if (ex != null && !(ex instanceof CancellationException)) {
                logger.severe("Tracked task failed with error: " + ex.getMessage());
            }
        });
    }

    private JedisPubSub createPubSub() {
        return new JedisPubSub() {
            @Override
            public void onMessage(String channel, String message) {
                if (!"network-events".equals(channel) || isShuttingDown.get() || !isInitialized.get()) {
                    return;
                }
                processMessage(message);
            }

            @Override
            public void onSubscribe(String channel, int subscribedChannels) {
                logger.info("Successfully subscribed to Redis channel: " + channel);
            }

            @Override
            public void onUnsubscribe(String channel, int subscribedChannels) {
                logger.info("Unsubscribed from Redis channel: " + channel);
            }
        };
    }

    private void processMessage(String message) {
        if (isShuttingDown.get() || !isInitialized.get() || scheduler == null || plugin == null) {
            return;
        }
        try {
            String[] parts = message.split(":", 5);
            if (parts.length < 2) {
                logger.warning("Invalid message format received: " + message);
                return;
            }

            NetworkEventType type;
            try {
                type = NetworkEventType.valueOf(parts[0]);
            } catch (IllegalArgumentException e) {
                logger.warning("Unknown network event type: " + parts[0]);
                return;
            }

            String id = parts[1];
            String name = (parts.length > 2) ? parts[2] : "null";
            String fromServer = (parts.length > 3) ? parts[3] : "null";
            String toServer = (parts.length > 4) ? parts[4] : "null";

            Set<NetworkEventListener> listenersCopy = new HashSet<>(eventListeners);
            for (NetworkEventListener listener : listenersCopy) {
                if (isShuttingDown.get()) break;
                try {
                    boolean isBukkitPlugin = false;
                    try {
                        Class.forName("org.bukkit.plugin.Plugin");
                        isBukkitPlugin = plugin instanceof org.bukkit.plugin.Plugin;
                    } catch (ClassNotFoundException ignored) {}

                    if (isBukkitPlugin && !((org.bukkit.plugin.Plugin) plugin).isEnabled()) {
                        continue;
                    }
                    scheduler.executePlatformEvent(listener, type, id, name, fromServer, toServer);
                } catch (Exception e) {
                    logger.warning("Error in event listener while processing message: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            logger.warning("Error processing message '" + message + "': " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void addListener(NetworkEventListener listener) {
        if (listener != null) {
            eventListeners.add(listener);
        }
    }

    public void removeListener(NetworkEventListener listener) {
        if (listener != null) {
            eventListeners.remove(listener);
        }
    }

    public JedisPool getJedisPool() {
        if (jedisPool == null || jedisPool.isClosed()) {
            logger.warning("Attempted to get JedisPool, but it's null or closed.");
        }
        return this.jedisPool;
    }

    public CompletableFuture<Void> publishNetworkJoin(String uuid, String playerName, String serverName) {
        if (isShuttingDown.get() || !isInitialized.get() || jedisPool == null || jedisPool.isClosed()) {
            return CompletableFuture.failedFuture(new IllegalStateException("RedisClient not ready."));
        }
        return trackTask(CompletableFuture.runAsync(() -> {
            try (Jedis jedis = jedisPool.getResource()) {
                jedis.sadd("online_players", uuid);
                jedis.sadd("server:" + serverName, uuid);
                jedis.expire("server:" + serverName, 60);
                String message = String.format("%s:%s:%s:%s:%s", NetworkEventType.NETWORK_JOIN, uuid, playerName, "null", serverName);
                jedis.publish("network-events", message);
            } catch (Exception e) {
                logger.severe("Error publishing network join for " + playerName + ": " + e.getMessage());
                throw new CompletionException(e);
            }
        }));
    }

    public CompletableFuture<Void> publishNetworkQuit(String uuid, String playerName, String serverName) {
        if (isShuttingDown.get() || !isInitialized.get() || jedisPool == null || jedisPool.isClosed()) {
            return CompletableFuture.failedFuture(new IllegalStateException("RedisClient not ready."));
        }
        return trackTask(removePlayerAsync(uuid, serverName)
                .thenComposeAsync(v -> {
                    if (jedisPool == null || jedisPool.isClosed()) {
                        return CompletableFuture.failedFuture(new IllegalStateException("JedisPool closed during operation"));
                    }
                    return CompletableFuture.runAsync(() -> {
                        try (Jedis jedis = jedisPool.getResource()) {
                            if (!jedis.sismember("online_players", uuid)) {
                                String message = String.format("%s:%s:%s:%s:%s", NetworkEventType.NETWORK_QUIT, uuid, playerName, serverName, "null");
                                jedis.publish("network-events", message);
                            }
                        } catch (Exception e) {
                            logger.severe("Error publishing network quit for " + playerName + ": " + e.getMessage());
                            throw new CompletionException(e);
                        }
                    }, ForkJoinPool.commonPool());
                })
                .exceptionally(e -> {
                    logger.severe("Error in publishNetworkQuit chain for " + playerName + ": " + e.getMessage());
                    if (e instanceof CompletionException && e.getCause() != null) {
                        throw (CompletionException)e;
                    }
                    throw new CompletionException(e);
                }));
    }

    public CompletableFuture<Void> publishServerSwitch(String uuid, String playerName, String fromServer, String toServer) {
        if (isShuttingDown.get() || !isInitialized.get() || jedisPool == null || jedisPool.isClosed()) {
            return CompletableFuture.failedFuture(new IllegalStateException("RedisClient not ready."));
        }
        return trackTask(CompletableFuture.runAsync(() -> {
            try (Jedis jedis = jedisPool.getResource()) {
                String message = String.format("%s:%s:%s:%s:%s", NetworkEventType.SERVER_SWITCH, uuid, playerName, fromServer, toServer);
                jedis.publish("network-events", message);
            } catch (Exception e) {
                logger.severe("Error publishing server switch for " + playerName + ": " + e.getMessage());
                throw new CompletionException(e);
            }
        }));
    }

    public CompletableFuture<Void> publishNetworkServerStart(String serverName) {
        if (isShuttingDown.get() || !isInitialized.get() || jedisPool == null || jedisPool.isClosed()) {
            return CompletableFuture.failedFuture(new IllegalStateException("RedisClient not ready."));
        }
        return trackTask(
                updateServerStatus(serverName)
                        .thenComposeAsync(voidResult -> {
                            if (isShuttingDown.get() || !isInitialized.get() || jedisPool == null || jedisPool.isClosed()) {
                                return CompletableFuture.failedFuture(new IllegalStateException("RedisClient became unready during operation."));
                            }
                            return CompletableFuture.runAsync(() -> {
                                try (Jedis jedis = jedisPool.getResource()) {
                                    String message = String.format("%s:%s:%s:%s:%s", NetworkEventType.NETWORK_SERVER_START, serverName, "null", "null", "null");
                                    jedis.publish("network-events", message);
                                    logger.info("Published network server start event for: " + serverName);
                                } catch (Exception e) {
                                    logger.severe("Error publishing network server start for " + serverName + " after status update: " + e.getMessage());
                                    throw new CompletionException(e);
                                }
                            }, ForkJoinPool.commonPool());
                        })
                        .exceptionally(e -> {
                            logger.severe("Error in publishNetworkServerStart chain for " + serverName + ": " + e.getMessage());
                            if (e instanceof CompletionException && e.getCause() != null) {
                                throw (CompletionException)e;
                            }
                            throw new CompletionException(e);
                        })
        );
    }

    public CompletableFuture<Void> publishNetworkServerShutdown(String serverName) {
        if (isShuttingDown.get() || !isInitialized.get() || jedisPool == null || jedisPool.isClosed()) {
            return CompletableFuture.failedFuture(new IllegalStateException("RedisClient not ready."));
        }
        return trackTask(CompletableFuture.runAsync(() -> {
            try (Jedis jedis = jedisPool.getResource()) {
                String message = String.format("%s:%s:%s:%s:%s", NetworkEventType.NETWORK_SERVER_SHUTDOWN, serverName, "null", "null", "null");
                jedis.publish("network-events", message);
                logger.info("Published network server shutdown event for: " + serverName);
            } catch (Exception e) {
                logger.severe("Error publishing network server shutdown for " + serverName + ": " + e.getMessage());
                throw new CompletionException(e);
            }
        }));
    }

    public CompletableFuture<Void> addPlayerAsync(String uuid, String serverName) {
        if (isShuttingDown.get() || !isInitialized.get() || jedisPool == null || jedisPool.isClosed()) {
            return CompletableFuture.failedFuture(new IllegalStateException("RedisClient not ready."));
        }
        return trackTask(CompletableFuture.runAsync(() -> {
            try (Jedis jedis = jedisPool.getResource()) {
                jedis.sadd("online_players", uuid);
                jedis.sadd("server:" + serverName, uuid);
                jedis.expire("server:" + serverName, 300);
                jedis.expire("online_players", 300);
            } catch (Exception e) {
                logger.severe("Error adding player " + uuid + " to Redis for server " + serverName + ": " + e.getMessage());
                throw new CompletionException(e);
            }
        }));
    }

    public CompletableFuture<Void> removePlayerAsync(String uuid, String serverName) {
        if (isShuttingDown.get() || !isInitialized.get() || jedisPool == null || jedisPool.isClosed()) {
            return CompletableFuture.failedFuture(new IllegalStateException("RedisClient not ready."));
        }
        return trackTask(CompletableFuture.runAsync(() -> {
            try (Jedis jedis = jedisPool.getResource()) {
                jedis.srem("online_players", uuid);
                jedis.srem("server:" + serverName, uuid);
            } catch (Exception e) {
                logger.severe("Error removing player " + uuid + " from Redis for server " + serverName + ": " + e.getMessage());
                throw new CompletionException(e);
            }
        }));
    }

    public CompletableFuture<Boolean> isServerOnline(String serverName) {
        if (playerAPI == null) return CompletableFuture.completedFuture(false);
        return playerAPI.isServerOnline(serverName);
    }

    public CompletableFuture<Set<String>> getOnlinePlayersAsync() {
        if (playerAPI == null) return CompletableFuture.completedFuture(new HashSet<>());
        return playerAPI.getOnlinePlayersAsync();
    }

    public CompletableFuture<Set<String>> getServerPlayersAsync(String serverName) {
        if (playerAPI == null) return CompletableFuture.completedFuture(new HashSet<>());
        return playerAPI.getServerPlayersAsync(serverName);
    }

    public CompletableFuture<String> getPlayerServerAsync(String uuid) {
        if (playerAPI == null) return CompletableFuture.completedFuture(null);
        return playerAPI.getPlayerServerAsync(uuid);
    }

    public CompletableFuture<Void> cleanupServerAsync(String serverName) {
        if (isShuttingDown.get() || !isInitialized.get() || jedisPool == null || jedisPool.isClosed()) {
            return CompletableFuture.failedFuture(new IllegalStateException("RedisClient not ready for cleanup."));
        }
        return CompletableFuture.runAsync(() -> {
            try (Jedis jedis = jedisPool.getResource()) {
                jedis.del("server:" + serverName);
                jedis.del("server_status:" + serverName);
                logger.info("Cleaned up Redis keys for server: " + serverName);
            } catch (Exception e) {
                logger.severe("Error cleaning up server keys for " + serverName + ": " + e.getMessage());
                throw new CompletionException(e);
            }
        });
    }

    public boolean isFunctional() {
        return isInitialized.get() && !isShuttingDown.get() && jedisPool != null && !jedisPool.isClosed();
    }
}