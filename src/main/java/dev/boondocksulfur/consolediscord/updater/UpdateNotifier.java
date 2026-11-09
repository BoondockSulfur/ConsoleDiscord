package dev.boondocksulfur.consolediscord.updater;

import dev.boondocksulfur.consolediscord.ConsoleDiscordPlugin;
import dev.boondocksulfur.consolediscord.i18n.Messages;
import dev.boondocksulfur.consolediscord.scheduler.SchedulerAdapter;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.awt.Color;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs the Modrinth update check and tells the console, the Discord log
 * channel and online operators about a new version — each once per version.
 */
public final class UpdateNotifier {

    private final ConsoleDiscordPlugin plugin;

    private volatile ModrinthUpdateChecker checker;
    private SchedulerAdapter.CancellableTask task;

    /**
     * Version the Discord channel was last told about, so the periodic
     * re-check doesn't repeat it. Claimed before sending (a check and a
     * reconnect at the same moment send only once) and given back if
     * sending fails.
     */
    private final AtomicReference<String> notifiedDiscordVersion = new AtomicReference<>();

    /**
     * Version online operators were last told about; operators joining
     * later are told by UpdateNotifyListener.
     */
    private volatile String notifiedIngameVersion;

    public UpdateNotifier(ConsoleDiscordPlugin plugin) {
        this.plugin = plugin;
    }

    private Messages messages() {
        return plugin.getMessages();
    }

    /**
     * Schedules the update check: once shortly after startup and then
     * periodically (default every 24h), so long-running servers still
     * learn about new versions. interval-hours 0 disables the re-check.
     */
    public void start(FileConfiguration cfg) {
        stop();
        if (!cfg.getBoolean("update-checker.enabled", true)) {
            checker = null;
            return;
        }

        checker = new ModrinthUpdateChecker(plugin);

        long intervalHours = cfg.getLong("update-checker.interval-hours", 24);
        if (intervalHours > 0) {
            long intervalTicks = intervalHours * 60 * 60 * 20;
            task = SchedulerAdapter.runAsyncTimer(plugin, this::check, 40L, intervalTicks);
        } else {
            SchedulerAdapter.runAsyncLater(plugin, this::check, 40L);
        }
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    /**
     * The current checker, or null if the update check is disabled.
     */
    public ModrinthUpdateChecker getChecker() {
        return checker;
    }

    private void check() {
        ModrinthUpdateChecker current = checker;
        if (current == null) {
            return;
        }

        switch (current.checkForUpdates()) {
            case UPDATE_AVAILABLE -> {
                String latest = current.getLatestVersion();
                plugin.getLogger().warning(messages().get("update.console_available",
                        "latest", latest, "current", current.getCurrentVersion()));
                plugin.getLogger().warning(messages().get("update.console_links",
                        "modrinth", ModrinthUpdateChecker.MODRINTH_URL,
                        "curseforge", ModrinthUpdateChecker.CURSEFORGE_URL));

                // Discord and online operators hear about each version once.
                // If Discord isn't connected yet, it is sent on connect.
                notifyDiscord();
                if (!latest.equals(notifiedIngameVersion)) {
                    notifiedIngameVersion = latest;
                    SchedulerAdapter.runGlobal(plugin, () -> {
                        for (Player player : Bukkit.getOnlinePlayers()) {
                            notifyPlayer(player);
                        }
                    });
                }
            }
            case UP_TO_DATE -> plugin.getLogger().info(messages().get("update.console_latest",
                    "current", current.getCurrentVersion()));
            case NO_COMPATIBLE_VERSION -> plugin.getLogger().info(messages().get("update.console_no_compatible",
                    "mc", current.getMinecraftVersion()));
            case FAILED -> plugin.getLogger().warning(messages().get("update.console_failed",
                    "error", String.valueOf(current.getLastError())));
        }
    }

    /**
     * Posts a found update to the Discord log channel, once per version.
     * Called after each check and whenever Discord (re)connects.
     */
    public void notifyDiscord() {
        ModrinthUpdateChecker current = checker;
        if (current == null) {
            return;
        }
        String latest = current.getLatestVersion();
        String previous = notifiedDiscordVersion.get();
        if (!current.isUpdateAvailable() || latest == null || latest.equals(previous)) {
            return;
        }

        JDA jda = plugin.getJda();
        String channelId = plugin.getLogChannelId();
        if (jda == null || jda.getStatus() != JDA.Status.CONNECTED
                || channelId == null || channelId.isBlank()) {
            return;
        }

        TextChannel channel = jda.getTextChannelById(channelId);
        if (channel == null) {
            return;
        }

        MessageEmbed embed = new EmbedBuilder()
                .setTitle(messages().getRaw("notification.update_title"))
                .setColor(Color.ORANGE)
                .setDescription(messages().getRaw("notification.update_description"))
                .addField(messages().getRaw("notification.current_version"), current.getCurrentVersion(), true)
                .addField(messages().getRaw("notification.latest_version"), latest, true)
                .addField(messages().getRaw("notification.download"),
                        "[Modrinth](" + ModrinthUpdateChecker.MODRINTH_URL + ") · "
                                + "[CurseForge](" + ModrinthUpdateChecker.CURSEFORGE_URL + ")", false)
                .setTimestamp(Instant.now())
                .build();

        if (!notifiedDiscordVersion.compareAndSet(previous, latest)) {
            return;
        }
        channel.sendMessageEmbeds(embed).queue(
                v -> {},
                error -> {
                    notifiedDiscordVersion.compareAndSet(latest, previous);
                    plugin.getLogger().warning("Failed to send update notification to Discord: " + error.getMessage());
                }
        );
    }

    /**
     * Tells a player about an available update, with clickable download
     * links. Only players with consolediscord.update (operators by default)
     * receive it.
     *
     * @param player The player to notify
     */
    public void notifyPlayer(Player player) {
        ModrinthUpdateChecker current = checker;
        if (current == null || !current.isUpdateAvailable()
                || !player.hasPermission("consolediscord.update")) {
            return;
        }

        Component prefix = Component.text("[", NamedTextColor.DARK_GRAY)
                .append(Component.text("ConsoleDiscord", NamedTextColor.GOLD))
                .append(Component.text("] ", NamedTextColor.DARK_GRAY));

        player.sendMessage(prefix.append(Component.text(messages().get("update.ingame_available",
                "current", current.getCurrentVersion(),
                "latest", current.getLatestVersion()), NamedTextColor.YELLOW)));
        player.sendMessage(prefix
                .append(linkLabel("Modrinth", ModrinthUpdateChecker.MODRINTH_URL))
                .append(Component.text(" "))
                .append(linkLabel("CurseForge", ModrinthUpdateChecker.CURSEFORGE_URL)));
    }

    private Component linkLabel(String label, String url) {
        return Component.text()
                .append(Component.text("[", NamedTextColor.DARK_GRAY))
                .append(Component.text(label, NamedTextColor.AQUA, TextDecoration.UNDERLINED))
                .append(Component.text("]", NamedTextColor.DARK_GRAY))
                .clickEvent(ClickEvent.openUrl(url))
                .hoverEvent(HoverEvent.showText(Component.text(
                        messages().get("update.ingame_hover", "url", url), NamedTextColor.GRAY)))
                .build();
    }
}
