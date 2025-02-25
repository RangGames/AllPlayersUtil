package rang.games.allPlayersUtil.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

public class ServerSwitchEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final String playerUuid;
    private final String playerName;
    private final String fromServer;
    private final String toServer;

    public ServerSwitchEvent(String playerUuid, String playerName, String fromServer, String toServer) {
        this.playerUuid = playerUuid;
        this.playerName = playerName;
        this.fromServer = fromServer;
        this.toServer = toServer;
    }

    public String getPlayerUuid() {
        return playerUuid;
    }

    public String getPlayerName() {
        return playerName;
    }

    public String getFromServer() {
        return fromServer;
    }

    public String getToServer() {
        return toServer;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}