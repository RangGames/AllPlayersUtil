package rang.games.allPlayersUtil.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

public class NetworkQuitEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final String playerUuid;
    private final String playerName;
    private final String serverName;

    public NetworkQuitEvent(String playerUuid, String playerName, String serverName) {
        this.playerUuid = playerUuid;
        this.playerName = playerName;
        this.serverName = serverName;

    }

    public String getPlayerUuid() { return playerUuid; }
    public String getPlayerName() { return playerName; }
    public String getServerName() { return serverName; }

    @Override
    public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}