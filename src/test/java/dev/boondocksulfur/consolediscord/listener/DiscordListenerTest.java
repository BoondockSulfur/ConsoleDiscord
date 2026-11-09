package dev.boondocksulfur.consolediscord.listener;

import dev.boondocksulfur.consolediscord.ConsoleDiscordPlugin;
import dev.boondocksulfur.consolediscord.audit.AuditLogger;
import dev.boondocksulfur.consolediscord.i18n.Messages;
import dev.boondocksulfur.consolediscord.scheduler.SchedulerAdapter;
import dev.boondocksulfur.consolediscord.security.CommandSecurity;
import dev.boondocksulfur.consolediscord.security.RateLimiter;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.unions.MessageChannelUnion;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.interactions.AutoCompleteQuery;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.Command;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.requests.restaction.CommandListUpdateAction;
import net.dv8tion.jda.api.requests.restaction.MessageCreateAction;
import net.dv8tion.jda.api.requests.restaction.WebhookMessageCreateAction;
import net.dv8tion.jda.api.requests.restaction.interactions.AutoCompleteCallbackAction;
import net.dv8tion.jda.api.requests.restaction.interactions.ReplyCallbackAction;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.command.ConsoleCommandSender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DiscordListenerTest {

    private static final String CHANNEL = "555";
    private static final String USER = "42";

    private MockedStatic<SchedulerAdapter> scheduler;
    private MockedStatic<Bukkit> bukkit;
    private ConsoleDiscordPlugin plugin;
    private AuditLogger audit;
    private ConsoleCommandSender console;
    private DiscordListener listener;

    @BeforeEach
    void setUp() {
        CommandSecurity.configure(true, List.of());
        CommandSecurity.setLabelResolver(null);
        CommandSecurity.setAliasExpander(null);

        // Server-thread and delayed tasks run right away
        scheduler = mockStatic(SchedulerAdapter.class);
        scheduler.when(() -> SchedulerAdapter.runGlobal(any(), any())).thenAnswer(inv -> {
            inv.<Runnable>getArgument(1).run();
            return null;
        });
        scheduler.when(() -> SchedulerAdapter.runAsyncLater(any(), any(), anyLong())).thenAnswer(inv -> {
            inv.<Runnable>getArgument(1).run();
            return null;
        });

        console = mock(ConsoleCommandSender.class);
        Server server = mock(Server.class);
        when(server.getConsoleSender()).thenReturn(console);
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(Bukkit::getServer).thenReturn(server);

        Messages messages = mock(Messages.class);
        when(messages.getRaw(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(messages.get(anyString(), any(String[].class))).thenAnswer(inv -> inv.getArgument(0));

        JDA jda = mock(JDA.class);
        when(jda.getStatus()).thenReturn(JDA.Status.CONNECTED);

        audit = mock(AuditLogger.class);
        plugin = mock(ConsoleDiscordPlugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        when(plugin.getMessages()).thenReturn(messages);
        when(plugin.getJda()).thenReturn(jda);
        when(plugin.getAuditLogger()).thenReturn(audit);
        when(plugin.getCommandChannelId()).thenReturn(CHANNEL);
        when(plugin.getCommandAliases()).thenReturn(Map.of());
        when(plugin.getRateLimiter()).thenReturn(new RateLimiter(5, 60));
        when(plugin.isUserAllowed(eq(USER), any())).thenReturn(true);
        when(plugin.getPermittedCommands(anyString(), any())).thenReturn(Set.of("*"));

        listener = new DiscordListener(plugin);
    }

    @AfterEach
    void tearDown() {
        scheduler.close();
        bukkit.close();
        CommandSecurity.configure(true, List.of());
    }

    // ------------------------------------------------------------
    // Slash command
    // ------------------------------------------------------------

    private static final class Slash {
        final SlashCommandInteractionEvent event = mock(SlashCommandInteractionEvent.class);
        final ReplyCallbackAction reply = mock(ReplyCallbackAction.class, Answers.RETURNS_SELF);
        final ReplyCallbackAction defer = mock(ReplyCallbackAction.class);
        final WebhookMessageCreateAction<Message> followUp = mock(WebhookMessageCreateAction.class, Answers.RETURNS_SELF);
        final ArgumentCaptor<String> replyText = ArgumentCaptor.forClass(String.class);
        final ArgumentCaptor<String> followUpText = ArgumentCaptor.forClass(String.class);

        @SuppressWarnings("unchecked")
        Slash(String userId, String channelId, String command) {
            User user = mock(User.class);
            when(user.getId()).thenReturn(userId);
            when(user.getName()).thenReturn("tester");
            MessageChannelUnion channel = mock(MessageChannelUnion.class);
            when(channel.getId()).thenReturn(channelId);
            OptionMapping option = mock(OptionMapping.class);
            when(option.getAsString()).thenReturn(command);

            when(event.getName()).thenReturn("mc");
            when(event.getUser()).thenReturn(user);
            when(event.getChannel()).thenReturn(channel);
            when(event.getOption("command")).thenReturn(option);
            when(event.reply(replyText.capture())).thenReturn(reply);
            when(event.deferReply(anyBoolean())).thenReturn(defer);
            // Deferring succeeds immediately
            doAnswer(inv -> {
                inv.<Consumer<Object>>getArgument(0).accept(null);
                return null;
            }).when(defer).queue(any(), any());

            InteractionHook hook = mock(InteractionHook.class);
            when(event.getHook()).thenReturn(hook);
            when(hook.sendMessage(followUpText.capture())).thenReturn((WebhookMessageCreateAction) followUp);
        }
    }

    private Slash slash(String command) {
        Slash slash = new Slash(USER, CHANNEL, command);
        listener.onSlashCommandInteraction(slash.event);
        return slash;
    }

    @Test
    void slashInWrongChannelIsRejectedPrivately() {
        Slash slash = new Slash(USER, "999", "list");
        listener.onSlashCommandInteraction(slash.event);

        assertEquals("discord_command.wrong_channel", slash.replyText.getValue());
        verify(slash.reply).setEphemeral(true);
        verify(slash.event, never()).deferReply(anyBoolean());
        bukkit.verify(() -> Bukkit.dispatchCommand(any(), anyString()), never());
    }

    @Test
    void slashFromUnknownUserIsAuditedAndRejected() {
        Slash slash = new Slash("7", CHANNEL, "list");
        listener.onSlashCommandInteraction(slash.event);

        verify(audit).logDenied("7", "tester", "list", "NOT_ALLOWED");
        assertEquals("discord_command.no_permission", slash.replyText.getValue());
        verify(slash.reply).setEphemeral(true);
    }

    @Test
    void blockedCommandDoesNotUseRateLimit() {
        RateLimiter limiter = new RateLimiter(1, 60);
        when(plugin.getRateLimiter()).thenReturn(limiter);

        Slash slash = slash("op Steve");
        verify(audit).logDenied(USER, "tester", "op Steve", "BLOCKED");
        assertEquals("discord_command.blocked", slash.replyText.getValue());
        assertEquals(0, limiter.getCommandCount(USER));
    }

    @Test
    void secondCommandOverLimitIsRateLimited() {
        when(plugin.getRateLimiter()).thenReturn(new RateLimiter(1, 60));
        slash("list");
        Slash second = slash("list");

        verify(audit).logDenied(USER, "tester", "list", "RATE_LIMITED");
        assertEquals("discord_command.rate_limited", second.replyText.getValue());
    }

    @Test
    void repliesArePrivateByDefault() {
        Slash slash = slash("list");
        verify(slash.event).deferReply(true);
        verify(slash.followUp).setEphemeral(true);
    }

    @Test
    void repliesArePublicWhenEnabled() {
        when(plugin.isPublicReplies()).thenReturn(true);
        Slash slash = slash("list");
        verify(slash.event).deferReply(false);
        verify(slash.followUp).setEphemeral(false);
    }

    @Test
    void allowedCommandIsDispatchedAndAudited() {
        Slash slash = slash("list");

        bukkit.verify(() -> Bukkit.dispatchCommand(console, "list"));
        verify(audit).logCommand(USER, "tester", "list", true);
        assertEquals("discord_command.executing", slash.followUpText.getValue());
    }

    @Test
    void commandOutsideRolePermissionsIsNotRun() {
        when(plugin.getPermittedCommands(anyString(), any())).thenReturn(Set.of("list"));
        Slash slash = slash("kick Steve");

        verify(audit).logDenied(USER, "tester", "kick Steve", "NOT_PERMITTED");
        assertEquals("discord_command.not_permitted", slash.followUpText.getValue());
        bukkit.verify(() -> Bukkit.dispatchCommand(any(), anyString()), never());
    }

    @Test
    void rolePermissionsAlsoApplyInsideExecute() {
        when(plugin.getPermittedCommands(anyString(), any())).thenReturn(Set.of("execute", "say"));
        slash("execute as @a run kick Steve");

        verify(audit).logDenied(eq(USER), eq("tester"), anyString(), eq("NOT_PERMITTED"));
        bukkit.verify(() -> Bukkit.dispatchCommand(any(), anyString()), never());
    }

    @Test
    void aliasIsResolvedBeforeRunning() {
        when(plugin.getCommandAliases()).thenReturn(Map.of("day", "time set day"));
        slash("day");
        bukkit.verify(() -> Bukkit.dispatchCommand(console, "time set day"));
    }

    @Test
    void aliasToBlockedCommandIsBlocked() {
        when(plugin.getCommandAliases()).thenReturn(Map.of("bye", "stop"));
        Slash slash = slash("bye");

        verify(audit).logDenied(USER, "tester", "stop", "BLOCKED");
        assertEquals("discord_command.blocked", slash.replyText.getValue());
        bukkit.verify(() -> Bukkit.dispatchCommand(any(), anyString()), never());
    }

    @Test
    void serverAliasToBlockedCommandIsBlockedOnServerThread() {
        // commands.yml alias, only known on the server thread
        CommandSecurity.setAliasExpander(label -> label.equals("shutdown") ? List.of("stop") : null);
        Slash slash = slash("shutdown");

        verify(audit).logDenied(USER, "tester", "shutdown", "BLOCKED");
        assertEquals("discord_command.blocked", slash.followUpText.getValue());
        bukkit.verify(() -> Bukkit.dispatchCommand(any(), anyString()), never());
    }

    // ------------------------------------------------------------
    // !mc prefix command
    // ------------------------------------------------------------

    private static final class Prefix {
        final MessageReceivedEvent event = mock(MessageReceivedEvent.class);
        final Message message = mock(Message.class);
        final MessageChannelUnion channel = mock(MessageChannelUnion.class);
        final MessageCreateAction reply = mock(MessageCreateAction.class);
        final MessageCreateAction sent = mock(MessageCreateAction.class);

        Prefix(String userId, String channelId, String content, boolean bot, boolean guild) {
            User author = mock(User.class);
            when(author.getId()).thenReturn(userId);
            when(author.getName()).thenReturn("tester");
            when(author.isBot()).thenReturn(bot);
            when(channel.getId()).thenReturn(channelId);
            when(channel.sendMessage(anyString())).thenReturn(sent);
            when(message.getContentRaw()).thenReturn(content);
            when(message.reply(anyString())).thenReturn(reply);

            when(event.getAuthor()).thenReturn(author);
            when(event.isFromGuild()).thenReturn(guild);
            when(event.getMessage()).thenReturn(message);
            when(event.getChannel()).thenReturn(channel);
        }
    }

    private Prefix prefix(String userId, String channelId, String content) {
        Prefix prefix = new Prefix(userId, channelId, content, false, true);
        listener.onMessageReceived(prefix.event);
        return prefix;
    }

    @Test
    void botsAndDirectMessagesAreIgnored() {
        Prefix bot = new Prefix(USER, CHANNEL, "!mc list", true, true);
        Prefix dm = new Prefix(USER, CHANNEL, "!mc list", false, false);
        listener.onMessageReceived(bot.event);
        listener.onMessageReceived(dm.event);

        verify(bot.message, never()).reply(anyString());
        verify(dm.message, never()).reply(anyString());
        bukkit.verify(() -> Bukkit.dispatchCommand(any(), anyString()), never());
    }

    @Test
    void otherMessagesAreIgnored() {
        Prefix chat = prefix(USER, CHANNEL, "hello !mc list");
        verify(chat.message, never()).reply(anyString());
        verifyNoInteractions(audit);
    }

    @Test
    void wrongChannelReplyIsLimitedToOncePerMinute() {
        Prefix first = prefix(USER, "999", "!mc list");
        Prefix second = prefix(USER, "999", "!mc list");

        verify(first.message).reply("discord_command.wrong_channel");
        verify(second.message, never()).reply(anyString());
    }

    @Test
    void deniedPrefixIsAuditedEveryTimeButAnsweredOnce() {
        Prefix first = prefix("7", CHANNEL, "!mc list");
        Prefix second = prefix("7", CHANNEL, "!mc list");

        verify(audit, times(2)).logDenied("7", "tester", "list", "NOT_ALLOWED");
        verify(first.message).reply("discord_command.no_permission");
        verify(second.message, never()).reply(anyString());
    }

    @Test
    void emptyPrefixCommandIsAnswered() {
        Prefix empty = prefix(USER, CHANNEL, "!mc    ");
        // "!mc    " has no command after the prefix
        verify(empty.message).reply("discord_command.empty");
        bukkit.verify(() -> Bukkit.dispatchCommand(any(), anyString()), never());
    }

    @Test
    void prefixCommandRunsAndAnswersInChannel() {
        Prefix run = prefix(USER, CHANNEL, "!MC list");

        bukkit.verify(() -> Bukkit.dispatchCommand(console, "list"));
        verify(audit).logCommand(USER, "tester", "list", true);
        verify(run.channel).sendMessage("discord_command.executing");
    }

    // ------------------------------------------------------------
    // Autocomplete and registration
    // ------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private List<Command.Choice> autocomplete(String userId, String input) {
        CommandAutoCompleteInteractionEvent event = mock(CommandAutoCompleteInteractionEvent.class);
        User user = mock(User.class);
        when(user.getId()).thenReturn(userId);
        AutoCompleteQuery query = mock(AutoCompleteQuery.class);
        when(query.getName()).thenReturn("command");
        when(query.getValue()).thenReturn(input);
        when(event.getName()).thenReturn("mc");
        when(event.getUser()).thenReturn(user);
        when(event.getFocusedOption()).thenReturn(query);
        ArgumentCaptor<Collection<Command.Choice>> choices = ArgumentCaptor.forClass(Collection.class);
        when(event.replyChoices(choices.capture())).thenReturn(mock(AutoCompleteCallbackAction.class));

        listener.onCommandAutoCompleteInteraction(event);
        return List.copyOf(choices.getValue());
    }

    @Test
    void unknownUsersGetNoSuggestions() {
        when(plugin.getCommandAliases()).thenReturn(Map.of("secret", "whitelist add x"));
        assertTrue(autocomplete("7", "").isEmpty());
    }

    @Test
    void suggestionsStayWithinDiscordLimits() {
        String longTarget = "say " + "x".repeat(200);
        String longName = "a".repeat(101);
        when(plugin.getCommandAliases()).thenReturn(Map.of("shout", longTarget, longName, "list"));

        List<Command.Choice> choices = autocomplete(USER, "");
        assertTrue(choices.size() <= 25);
        assertTrue(choices.stream().allMatch(c -> c.getName().length() <= 100 && c.getAsString().length() <= 100));
        assertTrue(choices.stream().anyMatch(c -> c.getAsString().equals("shout")));
        assertTrue(choices.stream().noneMatch(c -> c.getAsString().equals(longName)));
    }

    private SlashCommandData registeredCommand() {
        ReadyEvent event = mock(ReadyEvent.class);
        JDA jda = mock(JDA.class);
        CommandListUpdateAction update = mock(CommandListUpdateAction.class, Answers.RETURNS_SELF);
        when(event.getJDA()).thenReturn(jda);
        when(jda.updateCommands()).thenReturn(update);

        listener.onReady(event);

        ArgumentCaptor<CommandData> captor = ArgumentCaptor.forClass(CommandData.class);
        verify(update).addCommands(captor.capture());
        return (SlashCommandData) captor.getValue();
    }

    @Test
    void slashCommandIsHiddenForNonAdminsByDefault() {
        when(plugin.isSlashCommandAdminOnly()).thenReturn(true);
        assertEquals(DefaultMemberPermissions.DISABLED.getPermissionsRaw(),
                registeredCommand().getDefaultPermissions().getPermissionsRaw());
    }

    @Test
    void slashCommandIsVisibleWhenAdminOnlyIsOff() {
        when(plugin.isSlashCommandAdminOnly()).thenReturn(false);
        assertEquals(DefaultMemberPermissions.ENABLED.getPermissionsRaw(),
                registeredCommand().getDefaultPermissions().getPermissionsRaw());
    }
}
