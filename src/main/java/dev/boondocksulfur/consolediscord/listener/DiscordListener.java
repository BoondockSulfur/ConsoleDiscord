package dev.boondocksulfur.consolediscord.listener;

import dev.boondocksulfur.consolediscord.ConsoleDiscordPlugin;
import dev.boondocksulfur.consolediscord.scheduler.SchedulerAdapter;
import dev.boondocksulfur.consolediscord.security.CommandSecurity;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.InteractionContextType;
import net.dv8tion.jda.api.interactions.commands.Command;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Handles Discord events and commands.
 * Listens for slash commands, autocomplete interactions, and prefix commands from Discord users.
 * Supports command aliases for convenient shortcuts.
 */
public class DiscordListener extends ListenerAdapter {

    private final ConsoleDiscordPlugin plugin;

    // Common Minecraft commands for autocomplete
    private static final List<String> COMMON_COMMANDS = Arrays.asList(
            "gamemode creative",
            "gamemode survival",
            "gamemode adventure",
            "gamemode spectator",
            "weather clear",
            "weather rain",
            "weather thunder",
            "time set day",
            "time set night",
            "time set noon",
            "time set midnight",
            "difficulty peaceful",
            "difficulty easy",
            "difficulty normal",
            "difficulty hard",
            "save-all",
            "whitelist list",
            "list",
            "seed",
            "gamerule",
            "effect clear @a"
    );

    /**
     * Creates a new Discord listener.
     *
     * @param plugin The plugin instance
     */
    public DiscordListener(ConsoleDiscordPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Called when the Discord bot is ready and connected.
     * Registers slash commands with Discord.
     *
     * @param event The ready event
     */
    @Override
    public void onReady(@NotNull ReadyEvent event) {
        SlashCommandData mc = Commands.slash("mc", "Executes a Minecraft command")
                .setContexts(InteractionContextType.GUILD) // no DMs — channel restriction must be able to apply
                .addOption(OptionType.STRING, "command", "The Minecraft command without slash", true, true); // Autocomplete enabled

        event.getJDA().updateCommands().addCommands(mc).queue(
                v -> plugin.getLogger().info(plugin.getMessages().getRaw("discord.slash_registered")),
                t -> plugin.getLogger().warning(plugin.getMessages().get("discord.slash_failed", "error", t.getMessage()))
        );
    }

    /**
     * Handles autocomplete interactions for slash commands.
     *
     * @param event The autocomplete event
     */
    @Override
    public void onCommandAutoCompleteInteraction(@NotNull CommandAutoCompleteInteractionEvent event) {
        if (!event.getName().equals("mc")) {
            return;
        }

        if (!event.getFocusedOption().getName().equals("command")) {
            return;
        }

        String userInput = event.getFocusedOption().getValue().toLowerCase();

        // Build suggestions list
        List<Command.Choice> choices = new ArrayList<>();

        // Add command aliases first
        Map<String, String> aliases = plugin.getCommandAliases();
        for (Map.Entry<String, String> entry : aliases.entrySet()) {
            if (entry.getKey().toLowerCase().startsWith(userInput)) {
                String display = entry.getKey() + " → " + entry.getValue();
                // Discord choice names are limited to 100 chars
                if (display.length() > 100) {
                    display = display.substring(0, 97) + "...";
                }
                choices.add(new Command.Choice(display, entry.getKey()));
            }
        }

        // Add common commands
        for (String cmd : COMMON_COMMANDS) {
            if (cmd.toLowerCase().startsWith(userInput) && choices.size() < 25) {
                choices.add(new Command.Choice(cmd, cmd));
            }
        }

        // If no matches, suggest the input as-is
        if (choices.isEmpty() && !userInput.isBlank()) {
            choices.add(new Command.Choice(userInput, userInput));
        }

        // Discord allows max 25 choices
        List<Command.Choice> limited = choices.stream().limit(25).collect(Collectors.toList());

        event.replyChoices(limited).queue();
    }

    /**
     * Handles slash command interactions from Discord.
     *
     * @param event The slash command event
     */
    @Override
    public void onSlashCommandInteraction(@NotNull SlashCommandInteractionEvent event) {
        if (!event.getName().equals("mc")) {
            return;
        }

        // Check if command is in correct channel
        String configuredChannel = plugin.getCommandChannelId();
        if (configuredChannel != null && !configuredChannel.isBlank()
                && !event.getChannel().getId().equals(configuredChannel)) {
            event.reply(plugin.getMessages().getRaw("discord_command.wrong_channel"))
                    .setEphemeral(true)
                    .queue();
            return;
        }

        // Check if user has permission (directly or via a whitelisted role)
        User user = event.getUser();
        if (!plugin.isUserAllowed(user.getId(), event.getMember())) {
            event.reply(plugin.getMessages().getRaw("discord_command.no_permission"))
                    .setEphemeral(true)
                    .queue();
            return;
        }

        String cmd = event.getOption("command").getAsString();

        // Resolve command alias
        final String resolvedCommand = resolveAlias(cmd);

        // Check command security before the rate limit, so a blocked
        // command doesn't consume the user's quota
        if (!CommandSecurity.isSafeCommand(resolvedCommand)) {
            String baseCommand = CommandSecurity.getBaseCommand(resolvedCommand);
            event.reply(plugin.getMessages().get("discord_command.blocked", "command", baseCommand))
                    .setEphemeral(true)
                    .queue();
            return;
        }

        // Check rate limit
        if (!plugin.getRateLimiter().allowCommand(user.getId())) {
            long seconds = plugin.getRateLimiter().getSecondsUntilReset(user.getId());
            int current = plugin.getRateLimiter().getCommandCount(user.getId());
            int max = plugin.getMaxCommandsPerMinute();

            event.reply(plugin.getMessages().get(
                    "discord_command.rate_limited",
                    "seconds", String.valueOf(seconds),
                    "current", String.valueOf(current),
                    "max", String.valueOf(max)
            )).setEphemeral(true).queue();
            return;
        }

        // Defer reply to prevent timeout
        event.deferReply(true).queue(
                v -> runMinecraftCommand(resolvedCommand, event.getChannel(), true, event, user.getId(), user.getName()),
                t -> plugin.getLogger().warning("Could not defer interaction: " + t.getMessage())
        );
    }

    /**
     * Handles prefix commands (!mc) from Discord messages.
     *
     * @param event The message received event
     */
    @Override
    public void onMessageReceived(@NotNull MessageReceivedEvent event) {
        if (event.getAuthor().isBot()) {
            return;
        }

        // Ignore direct messages: console commands are only accepted in
        // guild channels, where the channel restriction can apply.
        if (!event.isFromGuild()) {
            return;
        }

        String raw = event.getMessage().getContentRaw();
        String prefix = "!mc ";

        if (!raw.toLowerCase(Locale.ROOT).startsWith(prefix)) {
            return;
        }

        // Check if command is in correct channel
        String configuredChannel = plugin.getCommandChannelId();
        if (configuredChannel != null && !configuredChannel.isBlank()
                && !event.getChannel().getId().equals(configuredChannel)) {
            event.getMessage().reply(plugin.getMessages().getRaw("discord_command.wrong_channel")).queue();
            return;
        }

        // Check if user has permission (directly or via a whitelisted role)
        if (!plugin.isUserAllowed(event.getAuthor().getId(), event.getMember())) {
            event.getMessage().reply(plugin.getMessages().getRaw("discord_command.no_permission")).queue();
            return;
        }

        String cmd = raw.substring(prefix.length()).trim();
        if (cmd.isEmpty()) {
            event.getMessage().reply(plugin.getMessages().getRaw("discord_command.empty")).queue();
            return;
        }

        // Resolve command alias
        cmd = resolveAlias(cmd);

        // Check command security before the rate limit, so a blocked
        // command doesn't consume the user's quota
        if (!CommandSecurity.isSafeCommand(cmd)) {
            String baseCommand = CommandSecurity.getBaseCommand(cmd);
            event.getMessage().reply(plugin.getMessages().get("discord_command.blocked", "command", baseCommand)).queue();
            return;
        }

        // Check rate limit
        String userId = event.getAuthor().getId();
        if (!plugin.getRateLimiter().allowCommand(userId)) {
            long seconds = plugin.getRateLimiter().getSecondsUntilReset(userId);
            int current = plugin.getRateLimiter().getCommandCount(userId);
            int max = plugin.getMaxCommandsPerMinute();

            event.getMessage().reply(plugin.getMessages().get(
                    "discord_command.rate_limited",
                    "seconds", String.valueOf(seconds),
                    "current", String.valueOf(current),
                    "max", String.valueOf(max)
            )).queue();
            return;
        }

        runMinecraftCommand(cmd, event.getChannel(), false, null, userId, event.getAuthor().getName());
    }

    /**
     * Resolves a command alias to its full command.
     *
     * @param command The command (possibly an alias)
     * @return The resolved command
     */
    private String resolveAlias(String command) {
        Map<String, String> aliases = plugin.getCommandAliases();

        // Check if the command starts with an alias
        for (Map.Entry<String, String> entry : aliases.entrySet()) {
            String alias = entry.getKey();
            if (command.equalsIgnoreCase(alias) || command.toLowerCase().startsWith(alias + " ")) {
                // Replace alias with actual command
                return entry.getValue() + command.substring(alias.length());
            }
        }

        return command;
    }

    /**
     * Executes a Minecraft command on the main server thread.
     *
     * @param command The command to execute
     * @param channel The Discord channel to send responses to
     * @param slash Whether this is a slash command (vs prefix command)
     * @param slashEvent The slash command event (if applicable)
     * @param userId The Discord user ID
     * @param username The Discord username
     */
    private void runMinecraftCommand(
            String command,
            MessageChannel channel,
            boolean slash,
            SlashCommandInteractionEvent slashEvent,
            String userId,
            String username
    ) {
        SchedulerAdapter.runGlobal(plugin, () -> {
            boolean executed = false;
            List<String> output = Collections.synchronizedList(new ArrayList<>());
            try {
                CommandSender sender;
                if (plugin.isCommandFeedbackEnabled()) {
                    // Console-privileged sender that captures command feedback,
                    // so the actual output can be sent back to Discord.
                    sender = Bukkit.getServer().createCommandSender(component ->
                            output.add(PlainTextComponentSerializer.plainText().serialize(component)));
                } else {
                    sender = Bukkit.getServer().getConsoleSender();
                }

                // Bukkit.dispatchCommand() returns false for many vanilla commands
                // that actually executed successfully (e.g. "time set day"), so its
                // return value is not a reliable success indicator. We treat "no
                // exception thrown" as executed.
                Bukkit.dispatchCommand(sender, command);
                executed = true;

            } catch (Exception ex) {
                String msg = plugin.getMessages().get("discord_command.failed", "command", command);
                sendResponse(slash, slashEvent, channel, msg);
                plugin.getLogger().warning("Error executing Discord command: " + ex.getMessage());
            } finally {
                // Log to audit
                if (plugin.getAuditLogger() != null) {
                    plugin.getAuditLogger().logCommand(userId, username, command, executed);
                }
            }

            if (!executed) {
                return;
            }

            if (plugin.isCommandFeedbackEnabled()) {
                // Some feedback arrives a moment after dispatch (e.g. from async
                // command handling); collect briefly before replying with the output.
                SchedulerAdapter.runAsyncLater(plugin,
                        () -> sendCommandOutput(command, output, slash, slashEvent, channel),
                        Math.max(1L, plugin.getFeedbackCollectTicks()));
            } else {
                String msg = plugin.getMessages().get("discord_command.executing", "command", command);
                sendResponse(slash, slashEvent, channel, msg);
            }
        });
    }

    /**
     * Sends the captured command output back to Discord, wrapped in a code
     * block and truncated to fit Discord's 2000 character message limit.
     *
     * @param command The executed command
     * @param output The captured feedback lines (synchronized list)
     * @param slash Whether this is a slash command response
     * @param slashEvent The slash command event (if applicable)
     * @param channel The Discord channel to send to
     */
    private void sendCommandOutput(
            String command,
            List<String> output,
            boolean slash,
            SlashCommandInteractionEvent slashEvent,
            MessageChannel channel
    ) {
        String msg;
        synchronized (output) {
            if (output.isEmpty()) {
                msg = plugin.getMessages().get("discord_command.no_output", "command", command);
            } else {
                StringBuilder sb = new StringBuilder(
                        plugin.getMessages().get("discord_command.output", "command", command));
                sb.append("\n```\n");
                for (String line : output) {
                    if (line.length() > 400) {
                        line = line.substring(0, 400) + "…";
                    }
                    if (sb.length() + line.length() + 5 > 1900) {
                        sb.append("…\n");
                        break;
                    }
                    sb.append(line).append('\n');
                }
                sb.append("```");
                msg = sb.toString();
            }
        }
        sendResponse(slash, slashEvent, channel, msg);
    }

    /**
     * Sends a response message to Discord.
     * Handles both slash command replies and regular channel messages.
     *
     * @param slash Whether this is a slash command response
     * @param slashEvent The slash command event (if applicable)
     * @param channel The Discord channel to send to
     * @param msg The message to send
     */
    private void sendResponse(boolean slash, SlashCommandInteractionEvent slashEvent, MessageChannel channel, String msg) {
        if (slash && slashEvent != null) {
            // Check if JDA is still active
            if (plugin.getJda() == null || plugin.getJda().getStatus() == JDA.Status.SHUTDOWN
                    || plugin.getJda().getStatus() == JDA.Status.SHUTTING_DOWN) {
                return;
            }
            try {
                // Use hook since we already called deferReply()
                slashEvent.getHook().sendMessage(msg).setEphemeral(true).queue(
                        v -> {},
                        t -> plugin.getLogger().warning("Could not send response: " + t.getMessage())
                );
            } catch (Exception e) {
                plugin.getLogger().warning("Could not send response: " + e.getMessage());
            }
        } else {
            channel.sendMessage(msg).queue();
        }
    }
}
