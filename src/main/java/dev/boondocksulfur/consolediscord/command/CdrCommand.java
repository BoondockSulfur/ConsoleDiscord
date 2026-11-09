package dev.boondocksulfur.consolediscord.command;

import dev.boondocksulfur.consolediscord.ConsoleDiscordPlugin;
import dev.boondocksulfur.consolediscord.i18n.Messages;
import dev.boondocksulfur.consolediscord.scheduler.SchedulerAdapter;
import dev.boondocksulfur.consolediscord.updater.ModrinthUpdateChecker;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The in-game /cdr command: reload, debug, status and cleanup.
 */
public final class CdrCommand implements TabExecutor {

    private final ConsoleDiscordPlugin plugin;

    public CdrCommand(ConsoleDiscordPlugin plugin) {
        this.plugin = plugin;
    }

    private Messages messages() {
        return plugin.getMessages();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            sender.sendMessage(fill("command.usage", NamedTextColor.YELLOW,
                    "usage", Component.text(messages().getRaw("command.usage_cdr"), NamedTextColor.GOLD)));
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> {
                if (permitted(sender, "consolediscord.reload")) {
                    plugin.reload();
                    sendRaw(sender, "command.reload_success", NamedTextColor.GREEN);
                }
            }
            case "debug" -> {
                if (permitted(sender, "consolediscord.debug")) {
                    debug(sender, args);
                }
            }
            case "status" -> {
                if (permitted(sender, "consolediscord.status")) {
                    sendStatus(sender);
                }
            }
            case "cleanup" -> {
                if (permitted(sender, "consolediscord.cleanup")) {
                    if (plugin.triggerCleanup()) {
                        sendRaw(sender, "command.cleanup_started", NamedTextColor.GREEN);
                    } else {
                        sendRaw(sender, "command.cleanup_disabled", NamedTextColor.RED);
                    }
                }
            }
            default -> sendRaw(sender, "command.unknown_subcommand", NamedTextColor.RED);
        }
        return true;
    }

    private boolean permitted(CommandSender sender, String permission) {
        if (sender.hasPermission(permission)) {
            return true;
        }
        sendRaw(sender, "command.no_permission", NamedTextColor.RED);
        return false;
    }

    private void debug(CommandSender sender, String[] args) {
        if (args.length == 1) {
            plugin.setDebugStatusLogging(!plugin.isDebugStatusLogging());
            sendDebugStatus(sender);
            return;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "on" -> {
                plugin.setDebugStatusLogging(true);
                sendDebugStatus(sender);
            }
            case "off" -> {
                plugin.setDebugStatusLogging(false);
                sendDebugStatus(sender);
            }
            case "status" -> sendDebugStatus(sender);
            default -> sendRaw(sender, "command.usage_cdr", NamedTextColor.RED);
        }
    }

    private void sendDebugStatus(CommandSender sender) {
        sender.sendMessage(fill("command.debug_status", NamedTextColor.YELLOW,
                "status", onOff(plugin.isDebugStatusLogging())));
    }

    /**
     * Sends a status overview (connection, channels, queue, whitelist) to the sender.
     */
    private void sendStatus(CommandSender sender) {
        JDA current = plugin.getJda();

        sendRaw(sender, "status.header", NamedTextColor.GOLD);
        sender.sendMessage(fill("status.version", NamedTextColor.YELLOW,
                "version", plugin.getPluginMeta().getVersion()));
        sender.sendMessage(fill("status.server_type", NamedTextColor.YELLOW,
                "type", SchedulerAdapter.isFolia() ? "Folia" : "Paper/Spigot"));

        String jdaStatus = current != null ? current.getStatus().toString() : messages().getRaw("status.not_started");
        boolean connected = current != null && current.getStatus() == JDA.Status.CONNECTED;
        sender.sendMessage(fill("status.discord", NamedTextColor.YELLOW,
                "status", Component.text(jdaStatus, connected ? NamedTextColor.GREEN : NamedTextColor.RED)));

        sender.sendMessage(fill("status.prefix_commands", NamedTextColor.YELLOW,
                "status", plugin.isMessageContentEnabled()
                        ? Component.text(messages().getRaw("status.on"), NamedTextColor.GREEN)
                        : Component.text(messages().getRaw("status.prefix_no_intent"), NamedTextColor.RED)));

        sender.sendMessage(fill("status.log_channel", NamedTextColor.YELLOW,
                "status", describeChannel(current, plugin.getLogChannelId())));
        sender.sendMessage(fill("status.command_channel", NamedTextColor.YELLOW,
                "status", describeChannel(current, plugin.getCommandChannelId())));

        sender.sendMessage(fill("status.log_queue", NamedTextColor.YELLOW,
                "count", String.valueOf(plugin.getLogQueueSize())));

        sender.sendMessage(fill("status.whitelist", NamedTextColor.YELLOW,
                "users", String.valueOf(plugin.getAllowedUserCount()),
                "roles", String.valueOf(plugin.getAllowedRoleCount())));

        ModrinthUpdateChecker checker = plugin.getUpdateChecker();
        if (checker != null && checker.isUpdateAvailable()) {
            sender.sendMessage(fill("status.update_available", NamedTextColor.GOLD,
                    "version", checker.getLatestVersion()));
        }
    }

    /**
     * Describes a configured channel for the status output:
     * not set, valid (with name) or not found.
     */
    private Component describeChannel(JDA current, String channelId) {
        if (channelId == null || channelId.isBlank()) {
            return Component.text(messages().getRaw("status.not_set"), NamedTextColor.GRAY);
        }
        if (current == null || current.getStatus() != JDA.Status.CONNECTED) {
            return Component.text(channelId, NamedTextColor.GRAY);
        }
        TextChannel channel = current.getTextChannelById(channelId);
        if (channel == null) {
            return Component.text(messages().get("status.channel_invalid", "id", channelId), NamedTextColor.RED);
        }
        return Component.text("#" + channel.getName(), NamedTextColor.GREEN);
    }

    private void sendRaw(CommandSender sender, String key, NamedTextColor color) {
        sender.sendMessage(Component.text(messages().getRaw(key), color));
    }

    private Component onOff(boolean on) {
        return on ? Component.text(messages().getRaw("status.on"), NamedTextColor.GREEN)
                  : Component.text(messages().getRaw("status.off"), NamedTextColor.RED);
    }

    /**
     * Builds a message component and fills its {placeholders}; values may be
     * plain strings (shown in the base color) or colored components.
     *
     * @param key The message key
     * @param color The base color
     * @param pairs Placeholder names and values, alternating
     */
    private Component fill(String key, NamedTextColor color, Object... pairs) {
        Component result = Component.text(messages().getRaw(key), color);
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            String placeholder = "{" + pairs[i] + "}";
            Component value = pairs[i + 1] instanceof Component component
                    ? component
                    : Component.text(String.valueOf(pairs[i + 1]));
            result = result.replaceText(builder -> builder.matchLiteral(placeholder).replacement(value));
        }
        return result;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            List<String> subs = new ArrayList<>();
            if (sender.hasPermission("consolediscord.reload")) subs.add("reload");
            if (sender.hasPermission("consolediscord.debug")) subs.add("debug");
            if (sender.hasPermission("consolediscord.status")) subs.add("status");
            if (sender.hasPermission("consolediscord.cleanup")) subs.add("cleanup");
            return filter(subs, args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("debug")
                && sender.hasPermission("consolediscord.debug")) {
            return filter(List.of("on", "off", "status"), args[1]);
        }
        return Collections.emptyList();
    }

    private static List<String> filter(List<String> options, String input) {
        String lower = input.toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        for (String option : options) {
            if (option.startsWith(lower)) {
                result.add(option);
            }
        }
        return result;
    }
}
