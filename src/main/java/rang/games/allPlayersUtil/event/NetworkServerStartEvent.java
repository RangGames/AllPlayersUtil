package rang.games.allPlayersUtil.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

public class NetworkServerStartEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final String serverName;

    public NetworkServerStartEvent(String serverName) {
        this.serverName = serverName;
    }

    public String getServerName() {
        return serverName;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}