package rang.games.allPlayersUtil.platform.Velocity;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import rang.games.allPlayersUtil.RedisClient;

import java.time.Duration;

public class VelocitySchedulerService implements RedisClient.SchedulerService {
    private final ProxyServer server;
    private final Object plugin;

    public VelocitySchedulerService(ProxyServer server, Object plugin) {
        this.server = server;
        this.plugin = plugin;
    }

    @Override
    public Object scheduleAsyncRepeatingTask(Runnable task, long initialDelay, long period) {
        return server.getScheduler()
                .buildTask(plugin, task)
                .repeat(Duration.ofMillis(period))
                .delay(Duration.ofMillis(initialDelay))
                .schedule();
    }

    @Override
    public void executePlatformEvent(RedisClient.NetworkEventListener listener, RedisClient.NetworkEventType type, String uuid, String name, String fromServer, String toServer) {
        server.getScheduler().buildTask(plugin, () ->
                listener.onNetworkEvent(type, uuid, name, fromServer, toServer)
        ).schedule();
    }

    @Override
    public void cancelTask(Object task) {
        if (task instanceof ScheduledTask) {
            ((ScheduledTask) task).cancel();
        }
    }
}