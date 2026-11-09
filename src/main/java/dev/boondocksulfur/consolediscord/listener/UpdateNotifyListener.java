package dev.boondocksulfur.consolediscord.listener;

import dev.boondocksulfur.consolediscord.updater.UpdateNotifier;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * Tells joining operators about an available plugin update.
 */
public class UpdateNotifyListener implements Listener {

    private final UpdateNotifier notifier;

    /**
     * Creates a new update notification listener.
     *
     * @param notifier The update notifier
     */
    public UpdateNotifyListener(UpdateNotifier notifier) {
        this.notifier = notifier;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        notifier.notifyPlayer(event.getPlayer());
    }
}
