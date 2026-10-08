package dev.boondocksulfur.consolediscord.listener;

import dev.boondocksulfur.consolediscord.ConsoleDiscordPlugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * Tells joining operators about an available plugin update.
 */
public class UpdateNotifyListener implements Listener {

    private final ConsoleDiscordPlugin plugin;

    /**
     * Creates a new update notification listener.
     *
     * @param plugin The plugin instance
     */
    public UpdateNotifyListener(ConsoleDiscordPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        plugin.sendUpdateMessage(event.getPlayer());
    }
}
