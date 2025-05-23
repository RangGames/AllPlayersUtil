# AllPlayersUtil Plugin: RedisPlayerAPI Usage Guide

The `AllPlayersUtil` plugin provides the `RedisPlayerAPI` to share player and server information across your network using Redis. This guide is for plugin developers who want to leverage this API to easily query and utilize data between a Velocity proxy and connected Bukkit servers.

## Table of Contents

1.  [AllPlayersUtil Plugin Configuration](#1-allplayersutil-plugin-configuration)
    *   [1.1. Configuration Files](#11-configuration-files)
    *   [1.2. Redis Connection Settings](#12-redis-connection-settings)
    *   [1.3. Server Name Configuration](#13-server-name-configuration)
    *   [1.4. Debug Mode](#14-debug-mode)
2.  [Using the `RedisPlayerAPI`](#2-using-the-redisplayerapi)
    *   [2.1. Getting the `RedisPlayerAPI` Instance](#21-getting-the-redisplayerapi-instance)
    *   [2.2. API Method Details](#22-api-method-details)
        *   [2.2.1. `getOnlinePlayersAsync()`](#221-getonlineplayersasync)
        *   [2.2.2. `isServerOnline(String serverName)`](#222-isserveronlinestring-servername)
        *   [2.2.3. `getServerPlayersAsync(String serverName)`](#223-getserverplayersasyncstring-servername)
        *   [2.2.4. `getPlayerServerAsync(String uuid)`](#224-getplayerserverasyncstring-uuid)
3.  [Note: Redis Data Structure](#3-note-redis-data-structure)

---

## 1. `AllPlayersUtil` Plugin Configuration

For the `RedisPlayerAPI` to function correctly, the `AllPlayersUtil` plugin must be properly configured on each server where it's installed (both the Velocity proxy and all Bukkit servers). **Crucially, all instances must connect to the same Redis server.**

### 1.1. Configuration Files

The `AllPlayersUtil` plugin uses different configuration file formats depending on the server environment:

*   **Bukkit/Spigot/Paper Servers:** Uses `config.yml` located in `plugins/AllPlayersUtil/config.yml`.
*   **Velocity Proxy:** Uses `config.properties` (or a similar properties file, depending on how the plugin is structured for Velocity) typically located in `plugins/AllPlayersUtil/config.properties`.

You need to configure both types of files appropriately.

**Example `config.yml` for Bukkit servers:**

```yaml
redis:
  host: "localhost"
  port: 6379
  # password: "your_redis_password" # If your Redis server is password-protected
  # timeout: 2000 # Redis connection timeout in milliseconds

server:
  # The unique name of THIS Bukkit server.
  # Examples: "lobby", "survival", "minigame-hub"
  name: "example-server" # MUST BE UNIQUE for each Bukkit server

debug: false
```

**Example `config.properties` for Velocity proxy:**

```properties
redis_host=localhost
redis_port=6379
# redis_password=your_redis_password
# redis_timeout=2000

# The name for the Velocity proxy itself.
# This should generally be "proxy" to match the RedisPlayerAPI.velocityServerName default.
server_name=proxy

debug=false
```

### 1.2. Redis Connection Settings

These settings tell the plugin how to connect to your Redis server. **They must be identical across all your Bukkit `config.yml` files and your Velocity `config.properties` file to ensure all plugin instances communicate through the same Redis database.**

*   **Bukkit (`config.yml`):**
    *   `redis.host`: Hostname or IP of your Redis server.
    *   `redis.port`: Port of your Redis server.
    *   `redis.password` (Optional): Password for Redis, if any.
    *   `redis.timeout` (Optional): Connection timeout in milliseconds.

*   **Velocity (`config.properties`):**
    *   `redis_host`: Hostname or IP of your Redis server.
    *   `redis_port`: Port of your Redis server.
    *   `redis_password` (Optional): Password for Redis, if any.
    *   `redis_timeout` (Optional): Connection timeout in milliseconds.

**Ensure these values point to the same Redis instance for all servers.**

### 1.3. Server Name Configuration

This setting identifies the current server instance within the network.

*   **Bukkit (`config.yml` -> `server.name`):**
    *   Set `server.name` to a **unique identifier** for that specific Bukkit server (e.g., `"lobby"`, `"survival"`, `"skyblock1"`).
    *   This name is used in Redis keys like `server:<serverName>` and `server_status:<serverName>`.
    *   **This name MUST be unique across all your Bukkit servers.** Do not use "proxy" here.

*   **Velocity (`config.properties` -> `server_name`):**
    *   Set `server_name` to identify the Velocity proxy.
    *   It's highly recommended to set this to `"proxy"`. This value is used by `RedisPlayerAPI` (specifically its internal `velocityServerName` variable, which defaults to "proxy") to correctly filter out the proxy itself when performing network-wide player lookups.
    *   If you were to change the `RedisPlayerAPI.velocityServerName` constant in the code (not recommended unless you have a specific reason), this `server_name` in Velocity's config would need to match that custom value.

**Summary of `server.name` / `server_name`:**
*   **Velocity:** `server_name=proxy` (recommended)
*   **Bukkit Server 1:** `server.name: lobby` (example)
*   **Bukkit Server 2:** `server.name: survival-main` (example)
*   ...and so on, each Bukkit server having a unique name.

### 1.4. Debug Mode

Enables more verbose logging for troubleshooting.

*   **Bukkit (`config.yml` -> `debug`):** `true` or `false`.
*   **Velocity (`config.properties` -> `debug`):** `true` or `false`.

Set to `true` on any server if you need to diagnose issues with `AllPlayersUtil` on that specific instance.

---

## 2. Using the `RedisPlayerAPI`

To use the features provided by `AllPlayersUtil` in your own plugin, first obtain an instance of `RedisPlayerAPI`, then call its methods. All major data retrieval methods return a `CompletableFuture` for asynchronous processing.

### 2.1. Getting the `RedisPlayerAPI` Instance

After the `AllPlayersUtil` plugin has loaded and enabled on the server, you can get the singleton instance of `RedisPlayerAPI` using the following static method:

```java
import rang.games.allPlayersUtil.RedisPlayerAPI;

// ... inside your plugin's code ...

RedisPlayerAPI api = null;
try {
    api = RedisPlayerAPI.getInstance();
} catch (IllegalStateException e) {
    // AllPlayersUtil plugin might not be initialized yet or is missing.
    getLogger().severe("Could not initialize RedisPlayerAPI. Ensure AllPlayersUtil plugin is installed and enabled.");
    // Handle the case where API is not available
    return;
}

// Now you can use the 'api' variable to call methods
if (api != null) {
    // Use the API
}
```

**Note:** `RedisPlayerAPI.getInstance()` will only return a valid instance after `RedisPlayerAPI.initialize()` has been successfully called within `AllPlayersUtil` (typically during its `onEnable` phase). If `AllPlayersUtil` hasn't loaded correctly or failed to initialize, an `IllegalStateException` might be thrown. It's recommended to declare `AllPlayersUtil` as a `depend` or `softdepend` in your plugin's `plugin.yml`.

### 2.2. API Method Details

All asynchronous methods return a `CompletableFuture`. To use the results, utilize `CompletableFuture` chaining methods like `.thenAccept(result -> { ... })`, `.thenApply(result -> { ... })`, or `.exceptionally(error -> { ... })`.

#### 2.2.1. `getOnlinePlayersAsync()`

Asynchronously retrieves a set of UUIDs for all online players across the entire network (all connected Bukkit servers).

*   **Purpose:** To get the UUIDs of all players currently online on actual game servers in the network.
*   **Signature:** `public CompletableFuture<Set<String>> getOnlinePlayersAsync()`
*   **Returns:** `CompletableFuture<Set<String>>`
    *   **On success:** A `Set<String>` containing the UUIDs of all online players across the network. This set will not contain duplicate UUIDs.
    *   **On failure or Redis error:** Returns an empty `Set<String>`, and an error is logged internally by the `AllPlayersUtil` plugin.
*   **How it Works:**
    1.  Queries Redis for all keys matching the pattern `"server:*"`.
    2.  For each key, if it corresponds to the server designated as the Velocity proxy (identified by `RedisPlayerAPI.velocityServerName`, default "proxy", which should match the `server_name` in the proxy's `AllPlayersUtil` config), its player list is excluded.
    3.  Aggregates the player lists (which are Redis Sets) from all other (game) servers into a single `Set`.
*   **Usage Example:**

    ```java
    RedisPlayerAPI api = RedisPlayerAPI.getInstance();
    api.getOnlinePlayersAsync().thenAccept(allPlayerUUIDs -> {
        getLogger().info("Total online players across the network: " + allPlayerUUIDs.size());
        allPlayerUUIDs.forEach(uuid -> getLogger().info("Online player UUID: " + uuid));
    }).exceptionally(ex -> {
        getLogger().severe("Error fetching online player list: " + ex.getMessage());
        return null; // Mark exception as handled
    });
    ```

#### 2.2.2. `isServerOnline(String serverName)`

Asynchronously checks if a specific game server is currently marked as online in Redis.

*   **Purpose:** To determine if a game server with the given name is currently active and reporting its status.
*   **Signature:** `public CompletableFuture<Boolean> isServerOnline(String serverName)`
*   **Parameters:**
    *   `serverName` (String): The name of the server to check. This name must match the `server.name` configured in the `AllPlayersUtil` plugin's `config.yml` on that specific Bukkit server (e.g., "lobby", "survival").
*   **Returns:** `CompletableFuture<Boolean>`
    *   **On success:**
        *   `true`: If the Redis key `server_status:<serverName>` exists, its value is `"online"`, and it has a positive TTL (Time-To-Live).
        *   `false`: If any of the above conditions are not met, or if the key does not exist.
    *   **On failure or Redis error:** Returns `false`, and an error is logged internally by `AllPlayersUtil`.
*   **How it Works:**
    1.  Queries Redis for the key `server_status:<serverName>`.
    2.  Checks if the value of the key is `"online"`.
    3.  Checks if the key has a TTL greater than 0 (a non-existent or expired TTL implies offline).
*   **Usage Example:**

    ```java
    RedisPlayerAPI api = RedisPlayerAPI.getInstance();
    String targetServer = "survival"; // This should be a 'server.name' from a Bukkit config
    api.isServerOnline(targetServer).thenAccept(isOnline -> {
        if (isOnline) {
            getLogger().info(targetServer + " server is currently online.");
        } else {
            getLogger().info(targetServer + " server is offline or not responding.");
        }
    }).exceptionally(ex -> {
        getLogger().severe("Error checking server status for " + targetServer + ": " + ex.getMessage());
        return null;
    });
    ```

#### 2.2.3. `getServerPlayersAsync(String serverName)`

Asynchronously retrieves a set of UUIDs for all players currently on a specific game server.

*   **Purpose:** To get the list of player UUIDs on a named game server.
*   **Signature:** `public CompletableFuture<Set<String>> getServerPlayersAsync(String serverName)`
*   **Parameters:**
    *   `serverName` (String): The name of the server for which to retrieve player UUIDs. This must match the `server.name` in that Bukkit server's `AllPlayersUtil/config.yml`.
*   **Returns:** `CompletableFuture<Set<String>>`
    *   **On success:** A `Set<String>` containing the UUIDs of players on the specified server. Returns an empty set if no players are on that server.
    *   **If the server does not exist (no such key in Redis) or on Redis error:** Returns an empty `Set<String>`, and an error is logged internally by `AllPlayersUtil`.
*   **How it Works:**
    1.  Queries Redis for all members (player UUIDs) of the Set stored at the key `server:<serverName>`.
*   **Usage Example:**

    ```java
    RedisPlayerAPI api = RedisPlayerAPI.getInstance();
    String targetServer = "lobby"; // This should be a 'server.name' from a Bukkit config
    api.getServerPlayersAsync(targetServer).thenAccept(playerUUIDsOnServer -> {
        getLogger().info("Players on " + targetServer + ": " + playerUUIDsOnServer.size());
        playerUUIDsOnServer.forEach(uuid -> getLogger().info(targetServer + " player: " + uuid));
    }).exceptionally(ex -> {
        getLogger().severe("Error fetching player list for " + targetServer + ": " + ex.getMessage());
        return null;
    });
    ```

#### 2.2.4. `getPlayerServerAsync(String uuid)`

Asynchronously determines the current game server a specific player (by UUID) is on.

*   **Purpose:** To find out which game server a player with a given UUID is currently connected to.
*   **Signature:** `public CompletableFuture<String> getPlayerServerAsync(String uuid)`
*   **Parameters:**
    *   `uuid` (String): The UUID of the player whose server is to be found.
*   **Returns:** `CompletableFuture<String>`
    *   **On success:** The name of the server the player is on (derived by removing the "server:" prefix from the Redis key, e.g., "lobby"). This will be one of the Bukkit server names.
    *   **If the player is not found on any server or on Redis error:** Returns `null`, and an error is logged internally by `AllPlayersUtil`.
*   **How it Works:**
    1.  Queries Redis for all keys matching the pattern `"server:*"`.
    2.  For each key, if it corresponds to the Velocity proxy server (identified by `RedisPlayerAPI.velocityServerName`), it's excluded from the search.
    3.  For the remaining server keys, checks if the given `uuid` is a member of that server's player Set in Redis.
    4.  If the `uuid` is found, the server's name (extracted from the key) is returned.
*   **Usage Example:**

    ```java
    RedisPlayerAPI api = RedisPlayerAPI.getInstance();
    String playerUuidToFind = "some_player_uuid_string"; // Replace with actual UUID
    api.getPlayerServerAsync(playerUuidToFind).thenAccept(serverName -> {
        if (serverName != null) {
            getLogger().info("Player " + playerUuidToFind + " is currently on server: " + serverName);
        } else {
            getLogger().info("Player " + playerUuidToFind + " could not be found on any online server.");
        }
    }).exceptionally(ex -> {
        getLogger().severe("Error fetching server for player " + playerUuidToFind + ": " + ex.getMessage());
        return null;
    });
    ```

---

## 3. Note: Redis Data Structure

The `AllPlayersUtil` plugin uses the following data structures in Redis. API users generally do not need to interact with these directly, but understanding them can help in comprehending how the API works.

1.  **Player Lists (Per Server):**
    *   **Key Format:** `server:<serverName>` (e.g., `server:lobby`, `server:survival`)
        *   `<serverName>` is the value of `server.name` (from Bukkit's `config.yml`) or `server_name` (from Velocity's `config.properties`).
    *   **Type:** Redis `Set`
    *   **Value:** A set of strings, where each string is a player's UUID.
    *   **Managed by:** The `AllPlayersUtil` plugin instance on each Bukkit server updates its corresponding Set when players join or leave. The Velocity instance typically does not manage a player list under `server:proxy` in the same way for *actual* players, but the key might exist if other mechanisms populate it; `RedisPlayerAPI` is designed to ignore it for player lookups.

2.  **Server Status:**
    *   **Key Format:** `server_status:<serverName>` (e.g., `server_status:lobby`)
        *   `<serverName>` refers to Bukkit server names.
    *   **Type:** Redis `String`
    *   **Value:** Typically `"online"`.
    *   **TTL (Time-To-Live):** This key is set with a TTL. Bukkit servers periodically refresh their status. If a server shuts down improperly or stops responding, its TTL will expire, and it can be considered offline.
    *   **Managed by:** The `AllPlayersUtil` plugin instance on each Bukkit server periodically updates its status key and refreshes its TTL.

3.  **`velocityServerName` (Internal Variable):**
    *   Defined within the `RedisPlayerAPI` class as `private static String velocityServerName = "proxy";`.
    *   This value is used by `getOnlinePlayersAsync()` and `getPlayerServerAsync()` to filter out Redis keys associated with the proxy server itself (e.g., `server:proxy`).
    *   It's important that the `server_name` in the Velocity proxy's `AllPlayersUtil` configuration matches this `velocityServerName` value (default "proxy") for correct filtering.
