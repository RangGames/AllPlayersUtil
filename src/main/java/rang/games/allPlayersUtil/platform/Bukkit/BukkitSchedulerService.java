package rang.games.allPlayersUtil.platform.Bukkit;

import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import rang.games.allPlayersUtil.RedisClient;

public class BukkitSchedulerService implements RedisClient.SchedulerService {
    private final JavaPlugin plugin;

    public BukkitSchedulerService(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public Object scheduleAsyncRepeatingTask(Runnable task, long initialDelay, long period) {
        return new BukkitRunnable() {
            @Override
            public void run() {
                task.run();
            }
        }.runTaskTimerAsynchronously(plugin, initialDelay / 50, period / 50);
    }

    @Override
    public void executePlatformEvent(RedisClient.NetworkEventListener listener, RedisClient.NetworkEventType type, String uuid, String name, String fromServer, String toServer) {
        new BukkitRunnable() {
            @Override
            public void run() {
                listener.onNetworkEvent(type, uuid, name, fromServer, toServer);
            }
        }.runTask(plugin);
    }

    @Override
    public void cancelTask(Object task) {
        if (task instanceof BukkitTask) {
            ((BukkitTask) task).cancel();
        }
    }
}