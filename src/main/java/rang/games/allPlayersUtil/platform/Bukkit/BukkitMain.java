package rang.games.allPlayersUtil.platform.Bukkit;

import org.bukkit.plugin.java.JavaPlugin;
import rang.games.allPlayersUtil.AllPlayersUtil;

public class BukkitMain extends JavaPlugin {

    private AllPlayersUtil allPlayersUtilInstance;

    @Override
    public void onEnable() {
        getLogger().info("BukkitMain onEnable started.");
        try {
            this.allPlayersUtilInstance = new AllPlayersUtil();
            this.allPlayersUtilInstance.onBukkitEnable(this);

            getServer().getPluginManager().registerEvents(new rang.games.allPlayersUtil.platform.NetworkEventListener(this), this);

            getLogger().info("AllPlayersUtil (via BukkitMain) has been enabled on Bukkit server: " + getConfig().getString("server.name", "unknown-bukkit-server"));
        } catch (Exception e) {
            getLogger().severe("Failed to initialize AllPlayersUtil via BukkitMain: " + e.getMessage());
            e.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        getLogger().info("BukkitMain onDisable started. Disabling AllPlayersUtil plugin...");

        if (allPlayersUtilInstance != null) {
            allPlayersUtilInstance.onBukkitDisable();
        } else {
            getLogger().warning("allPlayersUtilInstance is null during BukkitMain.onDisable(). Cannot delegate disable logic.");
        }

        this.allPlayersUtilInstance = null;

        getLogger().info("AllPlayersUtil plugin (via BukkitMain) has been disabled!");
    }
}