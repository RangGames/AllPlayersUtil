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
import java.util.logging.Level;
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
    private static final int SHUTDOWN_TASK_WAIT_SECONDS = 7;

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

    public static synchronized void initialize(String host, int port, Object pluginInstance, SchedulerService schedulerInstance) {
        if (instance != null && instance.isInitialized.get() && !instance.isShuttingDown.get()) {
            return;
        }
        RedisClient.host = host;
        RedisClient.port = port;
        RedisClient.plugin = pluginInstance;
        RedisClient.scheduler = schedulerInstance;

        if (instance != null) {
            instance.isShuttingDown.set(true);
        }
        instance = null;
    }

    private RedisClient(String host, int port, Object pluginInstance, SchedulerService schedulerInstance) {
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(10);
        poolConfig.setMaxIdle(10);
        poolConfig.setMinIdle(2);
        poolConfig.setTestOnBorrow(true);
        poolConfig.setTestOnReturn(true);
        poolConfig.setTestWhileIdle(true);
        poolConfig.setMinEvictableIdleTimeMillis(TimeUnit.SECONDS.toMillis(60));
        poolConfig.setTimeBetweenEvictionRunsMillis(TimeUnit.SECONDS.toMillis(30));
        poolConfig.setNumTestsPerEvictionRun(3);
        poolConfig.setBlockWhenExhausted(true);
        poolConfig.setMaxWaitMillis(TimeUnit.SECONDS.toMillis(10));

        try {
            this.jedisPool = new JedisPool(poolConfig, RedisClient.host, RedisClient.port, 2000);
            try (Jedis jedis = this.jedisPool.getResource()) {
                jedis.ping();
            }
            RedisPlayerAPI.initialize(jedisPool);
            this.playerAPI = RedisPlayerAPI.getInstance();
            isInitialized.set(true);
            isShuttingDown.set(false);

            startSubscriber();
            startKeyRefreshTask();
            logger.info("RedisClient initialized successfully and connected to " + RedisClient.host + ":" + RedisClient.port);
        } catch (JedisConnectionException e) {
            logger.log(Level.SEVERE, "Failed to connect to Redis server at " + RedisClient.host + ":" + RedisClient.port + ". RedisClient will not be functional.", e);
            isInitialized.set(false);
            if (this.jedisPool != null) {
                try { this.jedisPool.close(); } catch (Exception ex) { logger.log(Level.WARNING, "Error closing jedisPool on connection exception.", ex);}
                this.jedisPool = null;
            }
            this.playerAPI = null;
        } catch (Exception e) {
            logger.log(Level.SEVERE, "An unexpected error occurred during RedisClient initialization.", e);
            isInitialized.set(false);
            if (this.jedisPool != null && !this.jedisPool.isClosed()) {
                try { this.jedisPool.close(); } catch (Exception ex) { logger.log(Level.WARNING, "Error closing jedisPool on general exception.", ex);}
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
                        logger.info("Previous RedisClient instance was shutting down. Creating new instance.");
                        instance = new RedisClient(host, port, plugin, scheduler);
                    } else if (instance == null) {
                        instance = new RedisClient(host, port, plugin, scheduler);
                    }
                    if (instance != null && !instance.isInitialized.get()) {
                        logger.severe("RedisClient getInstance() called, but initialization failed. Returning a non-functional instance or null.");
                    }
                }
            }
        }
        return instance;
    }

    private static void validateParameters() {
        if (host == null || scheduler == null || plugin == null) {
            throw new IllegalStateException("RedisClient static parameters (host, scheduler, plugin) not set. Call RedisClient.initialize() first.");
        }
    }

    private void startSubscriber() {
        if (isShuttingDown.get() || !isInitialized.get() || scheduler == null) {
            logger.warning("Subscriber task not started: Shutting down, not initialized, or scheduler is null.");
            return;
        }
        if (subscriberTask != null) {
            try { scheduler.cancelTask(subscriberTask); scheduledTasks.remove(subscriberTask); } catch (Exception ignored) {}
            subscriberTask = null;
        }

        subscriberTask = scheduler.scheduleAsyncRepeatingTask(() -> {
            if (isShuttingDown.get()) {
                if (subscriberTask != null) {
                    try { scheduler.cancelTask(subscriberTask); scheduledTasks.remove(subscriberTask); } catch (Exception ignored) {}
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
                        try { pubSub.unsubscribe(); } catch (Exception e) { logger.warning("Error unsubscribing existing pubSub: " + e.getMessage()); }
                    }
                    pubSub = createPubSub();
                    logger.info("Attempting to subscribe to Redis channel: network-events");
                    jedis.subscribe(pubSub, "network-events");
                    logger.info("Redis channel subscription ended (either by unsubscribe or error).");
                }
            } catch (JedisConnectionException e) {
                if (!isShuttingDown.get()) {
                    logger.warning("Redis connection error in subscriber task: " + e.getMessage() + ". Will attempt to resubscribe on next iteration.");
                    if (pubSub != null) {
                        if (pubSub.isSubscribed()) try { pubSub.unsubscribe(); } catch (Exception ignored) {}
                        pubSub = null;
                    }
                    reconnectJedisPool();
                }
            } catch (Exception e) {
                if (!isShuttingDown.get()) {
                    logger.log(Level.WARNING, "Error in Redis subscriber task: " + e.getMessage(), e);
                    if (pubSub != null) {
                        if (pubSub.isSubscribed()) try { pubSub.unsubscribe(); } catch (Exception ignored) {}
                        pubSub = null;
                    }
                }
            }
        }, 0L, TimeUnit.SECONDS.toMillis(5));

        if (subscriberTask != null) {
            scheduledTasks.add(subscriberTask);
        } else {
            logger.severe("Failed to schedule subscriber task!");
        }
    }

    private void startKeyRefreshTask() {
        if (isShuttingDown.get() || !isInitialized.get() || scheduler == null) {
            logger.warning("Key refresh task not started: Shutting down, not initialized, or scheduler is null.");
            return;
        }
        if (keyRefreshTask != null) {
            try { scheduler.cancelTask(keyRefreshTask); scheduledTasks.remove(keyRefreshTask); } catch (Exception ignored) {}
            keyRefreshTask = null;
        }

        keyRefreshTask = scheduler.scheduleAsyncRepeatingTask(() -> {
            if (isShuttingDown.get()) {
                if (keyRefreshTask != null) {
                    try { scheduler.cancelTask(keyRefreshTask); scheduledTasks.remove(keyRefreshTask); } catch (Exception ignored) {}
                    keyRefreshTask = null;
                }
                return;
            }
            if (jedisPool == null || jedisPool.isClosed() || !isInitialized.get()) {
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
                Set<String> statusKeys = jedis.keys("server_status:*");
                for (String statusKey : statusKeys) {
                    if (jedis.exists(statusKey)) {
                        jedis.expire(statusKey, 45);
                    }
                }
            } catch (JedisConnectionException e) {
                if (!isShuttingDown.get()) {
                    logger.warning("Redis connection error during key refresh: " + e.getMessage());
                    reconnectJedisPool();
                }
            } catch (Exception e) {
                if (!isShuttingDown.get()) {
                    logger.log(Level.SEVERE, "Error refreshing Redis keys: " + e.getMessage(), e);
                }
            }
        }, TimeUnit.SECONDS.toMillis(30), TimeUnit.SECONDS.toMillis(30));

        if (keyRefreshTask != null) {
            scheduledTasks.add(keyRefreshTask);
        } else {
            logger.severe("Failed to schedule key refresh task!");
        }
    }

    private synchronized void reconnectJedisPool() {
        if (isShuttingDown.get()) return;

        logger.info("Attempting to reconnect JedisPool...");
        if (jedisPool != null) {
            try {
                if (!jedisPool.isClosed()) jedisPool.close();
            } catch (Exception e) {
                logger.warning("Error closing old JedisPool during reconnect: " + e.getMessage());
            }
            jedisPool = null;
        }
        playerAPI = null;
        isInitialized.set(false);

        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(10); poolConfig.setMaxIdle(10); poolConfig.setMinIdle(2);
        poolConfig.setTestOnBorrow(true); poolConfig.setTestWhileIdle(true);
        poolConfig.setMinEvictableIdleTimeMillis(TimeUnit.SECONDS.toMillis(60));
        poolConfig.setTimeBetweenEvictionRunsMillis(TimeUnit.SECONDS.toMillis(30));
        poolConfig.setNumTestsPerEvictionRun(3); poolConfig.setBlockWhenExhausted(true);
        poolConfig.setMaxWaitMillis(TimeUnit.SECONDS.toMillis(10));

        try {
            jedisPool = new JedisPool(poolConfig, host, port, 2000);
            try (Jedis jedis = jedisPool.getResource()) {
                jedis.ping();
            }
            RedisPlayerAPI.initialize(jedisPool);
            this.playerAPI = RedisPlayerAPI.getInstance();
            isInitialized.set(true);
            logger.info("JedisPool reconnected successfully.");

            if (pubSub == null || !pubSub.isSubscribed()) {
                logger.info("PubSub was not active, attempting to restart subscriber task after reconnect.");
                startSubscriber();
            }
        } catch (Exception e) {
            logger.severe("Failed to reconnect JedisPool: " + e.getMessage());
            if (jedisPool != null) {
                try { if (!jedisPool.isClosed()) jedisPool.close(); } catch (Exception ex) { logger.warning("Error closing jedisPool on reconnect failure: " + ex.getMessage()); }
                jedisPool = null;
            }
            this.playerAPI = null;
            isInitialized.set(false);
        }
    }

    public CompletableFuture<Void> shutdown() {
        if (!isShuttingDown.compareAndSet(false, true)) {
            logger.info("Redis client shutdown already in progress or completed.");
            return CompletableFuture.completedFuture(null);
        }
        isInitialized.set(false);

        logger.info("Starting Redis client shutdown process...");

        if (pubSub != null) {
            if (pubSub.isSubscribed()) {
                logger.info("Unsubscribing from Redis channels...");
                try {
                    pubSub.unsubscribe();
                } catch (Exception e) {
                    logger.warning("Error during pubSub.unsubscribe(): " + e.getMessage());
                }
            }
            pubSub = null;
        }

        if (scheduler != null) {
            logger.info("Cancelling core scheduled tasks (subscriber, key refresh)...");
            if (subscriberTask != null) {
                try { scheduler.cancelTask(subscriberTask); } catch (Exception e) {logger.warning("Error cancelling subscriber task: " + e.getMessage());}
                scheduledTasks.remove(subscriberTask);
                subscriberTask = null;
            }
            if (keyRefreshTask != null) {
                try { scheduler.cancelTask(keyRefreshTask); } catch (Exception e) {logger.warning("Error cancelling key refresh task: " + e.getMessage());}
                scheduledTasks.remove(keyRefreshTask);
                keyRefreshTask = null;
            }

            if (!scheduledTasks.isEmpty()) {
                logger.info("Cancelling " + scheduledTasks.size() + " other tasks in scheduledTasks set...");
                for (Object task : new HashSet<>(scheduledTasks)) {
                    try {
                        scheduler.cancelTask(task);
                    } catch (Exception e) {
                        logger.warning("Error cancelling a task from scheduledTasks set: " + e.getMessage());
                    }
                }
            }
            scheduledTasks.clear();
        } else {
            logger.warning("Scheduler is null, cannot cancel scheduled tasks.");
        }

        if (!activeTasks.isEmpty()) {
            logger.info("Waiting for " + activeTasks.size() + " active one-off tasks to complete (timeout: " + SHUTDOWN_TASK_WAIT_SECONDS + "s)...");
            CompletableFuture<?>[] tasksToWaitForArray = activeTasks.toArray(new CompletableFuture<?>[0]);
            CompletableFuture<Void> allActiveTasksFuture = CompletableFuture.allOf(tasksToWaitForArray);
            try {
                allActiveTasksFuture.get(SHUTDOWN_TASK_WAIT_SECONDS, TimeUnit.SECONDS);
                logger.info("All active one-off tasks completed or timed out.");
            } catch (TimeoutException e) {
                logger.warning(tasksToWaitForArray.length + " active tasks did not complete within timeout. Attempting to cancel them.");
                for (CompletableFuture<?> future : tasksToWaitForArray) {
                    if (!future.isDone()) {
                        future.cancel(true);
                    }
                }
            } catch (InterruptedException e) {
                logger.warning("Interrupted while waiting for active tasks. Cancelling remaining tasks.");
                for (CompletableFuture<?> future : tasksToWaitForArray) {
                    if (!future.isDone()) {
                        future.cancel(true);
                    }
                }
                Thread.currentThread().interrupt();
            } catch (CancellationException e) {
                logger.info("Some active tasks were cancelled during shutdown wait.");
            } catch (Exception e) {
                logger.log(Level.WARNING, "Error waiting for active tasks to complete: " + e.getMessage(), e);
            }
        } else {
            logger.info("No active one-off tasks to wait for.");
        }
        activeTasks.clear();

        if (jedisPool != null) {
            logger.info("Closing JedisPool...");
            try {
                if (!jedisPool.isClosed()) {
                    jedisPool.close();
                }
            } catch (Exception e) {
                logger.log(Level.SEVERE, "Error closing JedisPool: " + e.getMessage(), e);
            }
            jedisPool = null;
        }

        playerAPI = null;

        logger.info("Redis client shutdown process completed.");
        return CompletableFuture.completedFuture(null);
    }

    private <T> CompletableFuture<T> trackTask(CompletableFuture<T> future) {
        if (isShuttingDown.get()) {
            future.completeExceptionally(new IllegalStateException("RedisClient is shutting down. Task not executed."));
            return future;
        }
        if (!isInitialized.get()) {
            future.completeExceptionally(new IllegalStateException("RedisClient not initialized. Task not executed."));
            return future;
        }

        activeTasks.add(future);
        return future.whenComplete((result, ex) -> {
            activeTasks.remove(future);
            if (ex != null && !(ex instanceof CancellationException)) {
                if (isShuttingDown.get() && (ex instanceof JedisConnectionException || ex.getCause() instanceof JedisConnectionException)) {
                    logger.warning("Tracked task failed during shutdown: " + ex.getMessage());
                } else if (!isShuttingDown.get()){
                    logger.log(Level.SEVERE, "Tracked task failed with error: " + ex.getMessage(), ex);
                }
            }
        });
    }

    public boolean isFunctional() {
        return isInitialized.get() && !isShuttingDown.get() && jedisPool != null && !jedisPool.isClosed();
    }

    public boolean isShuttingDown() {
        return isShuttingDown.get();
    }

    public CompletableFuture<Void> publishNetworkJoin(String uuid, String playerName, String serverName) {
        if (!isFunctional()) {
            logger.warning("publishNetworkJoin called but RedisClient not functional.");
            return CompletableFuture.failedFuture(new IllegalStateException("RedisClient not functional."));
        }
        return trackTask(CompletableFuture.runAsync(() -> {
            try (Jedis jedis = jedisPool.getResource()) {
                jedis.sadd("online_players", uuid);
                jedis.expire("online_players", 300);
                jedis.sadd("server:" + serverName, uuid);
                jedis.expire("server:" + serverName, 300);

                String message = String.format("%s:%s:%s:%s:%s", NetworkEventType.NETWORK_JOIN, uuid, playerName, "null", serverName);
                jedis.publish("network-events", message);
            } catch (Exception e) {
                logger.log(Level.SEVERE, "Error publishing network join for " + playerName + ": " + e.getMessage(), e);
                throw new CompletionException(e);
            }
        }, ForkJoinPool.commonPool()));
    }

    public CompletableFuture<Void> publishNetworkQuit(String uuid, String playerName, String serverName) {
        if (!isFunctional()) {
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
        if (!isFunctional()) {
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
        if (!isFunctional()) {
            return CompletableFuture.failedFuture(new IllegalStateException("RedisClient not ready."));
        }
        return trackTask(
                updateServerStatus(serverName)
                        .thenComposeAsync(voidResult -> {
                            if (!isFunctional()) {
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
        if (!isFunctional()) {
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
        if (!isFunctional()) {
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
        if (!isFunctional()) {
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

    public CompletableFuture<Void> updateServerStatus(String serverName) {
        if (!isFunctional()) {
            return CompletableFuture.failedFuture(new IllegalStateException("RedisClient not ready."));
        }
        return trackTask(CompletableFuture.runAsync(() -> {
            try (Jedis jedis = jedisPool.getResource()) {
                String key = "server_status:" + serverName;
                jedis.set(key, "online");
                jedis.expire(key, 45);
            } catch (Exception e) {
                logger.severe("Error updating server status for " + serverName + ": " + e.getMessage());
                throw new CompletionException(e);
            }
        }));
    }

    public CompletableFuture<Void> cleanupServerAsync(String serverName) {
        if (!isFunctional()) {
            return CompletableFuture.failedFuture(new IllegalStateException("RedisClient not ready for cleanup."));
        }
        return trackTask(CompletableFuture.runAsync(() -> {
            try (Jedis jedis = jedisPool.getResource()) {
                jedis.del("server:" + serverName);
                jedis.del("server_status:" + serverName);
                logger.info("Cleaned up Redis keys for server: " + serverName);
            } catch (Exception e) {
                logger.severe("Error cleaning up server keys for " + serverName + ": " + e.getMessage());
                throw new CompletionException(e);
            }
        }));
    }

    public CompletableFuture<Boolean> isServerOnline(String serverName) {
        if (playerAPI == null || !isFunctional()) {
            return CompletableFuture.completedFuture(false);
        }
        return playerAPI.isServerOnline(serverName);
    }

    public CompletableFuture<Set<String>> getOnlinePlayersAsync() {
        if (playerAPI == null || !isFunctional()) {
            return CompletableFuture.completedFuture(new HashSet<>());
        }
        return playerAPI.getOnlinePlayersAsync();
    }

    public CompletableFuture<Set<String>> getServerPlayersAsync(String serverName) {
        if (playerAPI == null || !isFunctional()) {
            return CompletableFuture.completedFuture(new HashSet<>());
        }
        return playerAPI.getServerPlayersAsync(serverName);
    }

    public CompletableFuture<String> getPlayerServerAsync(String uuid) {
        if (playerAPI == null || !isFunctional()) {
            return CompletableFuture.completedFuture(null);
        }
        return playerAPI.getPlayerServerAsync(uuid);
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
        if (isShuttingDown.get() || !isInitialized.get() || scheduler == null || RedisClient.plugin == null) {
            return;
        }
        boolean pluginEnabled = true;
        try {
            if (RedisClient.plugin instanceof org.bukkit.plugin.java.JavaPlugin) {
                pluginEnabled = ((org.bukkit.plugin.java.JavaPlugin) RedisClient.plugin).isEnabled();
            }
        } catch (Exception e) {
            logger.warning("Could not determine plugin enabled state: " + e.getMessage());
        }

        if (!pluginEnabled) {
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

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }
}