package rang.games.allPlayersUtil.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

public class NetworkJoinEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final String playerUuid;
    private final String playerName;

    public NetworkJoinEvent(String playerUuid, String playerName) {
        this.playerUuid = playerUuid;
        this.playerName = playerName;
    }

    public String getPlayerUuid() { return playerUuid; }
    public String getPlayerName() { return playerName; }

    @Override
    public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}