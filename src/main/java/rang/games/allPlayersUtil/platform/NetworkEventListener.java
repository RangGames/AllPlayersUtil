package rang.games.allPlayersUtil.platform;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import rang.games.allPlayersUtil.event.NetworkJoinEvent;
import rang.games.allPlayersUtil.event.NetworkQuitEvent;
import rang.games.allPlayersUtil.event.ServerSwitchEvent;

public class NetworkEventListener implements Listener {
    private final JavaPlugin plugin;

    public NetworkEventListener(JavaPlugin plugin) {
        this.plugin = plugin;
        plugin.getLogger().info("§aInitializing NetworkEventListener");
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onNetworkJoin(NetworkJoinEvent event) {
        plugin.getLogger().info("§a[NetworkEvent] Received NetworkJoinEvent for: " + event.getPlayerName());
        plugin.getLogger().info("§a[NetworkEvent] Player joined network: " + event.getPlayerName() + " (UUID: " + event.getPlayerUuid() + ")");
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onNetworkQuit(NetworkQuitEvent event) {
        plugin.getLogger().info("§c[NetworkEvent] Player left network: " + event.getPlayerName() + " (UUID: " + event.getPlayerUuid() + ")");
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onServerSwitch(ServerSwitchEvent event) {
        plugin.getLogger().info("§e[NetworkEvent] Player switched server: " +
                event.getPlayerName() + " (UUID: " + event.getPlayerUuid() + ") " +
                "from " + event.getFromServer() + " to " + event.getToServer());
    }
}