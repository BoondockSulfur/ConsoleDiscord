package dev.boondocksulfur.consolediscord.logging;

import dev.boondocksulfur.consolediscord.i18n.Messages;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.exceptions.InsufficientPermissionException;
import net.dv8tion.jda.api.requests.restaction.MessageCreateAction;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.apache.logging.log4j.message.SimpleMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LogForwarderTest {

    private static final String MAIN = "100";
    private static final String JOINS = "200";

    private JDA jda;
    private TextChannel mainChannel;
    private TextChannel joinChannel;
    private DiscordLogAppender appender;
    private LogFilter filter;
    private final List<CompletableFuture<Message>> pending = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();
    private Logger logger;
    private Messages messages;

    @BeforeEach
    void setUp() {
        logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override public void publish(LogRecord record) {
                warnings.add(record.getMessage());
            }
            @Override public void flush() {}
            @Override public void close() {}
        });

        messages = mock(Messages.class);
        when(messages.getRaw(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(messages.get(anyString(), any(String[].class))).thenAnswer(inv -> inv.getArgument(0));

        filter = new LogFilter(List.of("INFO", "WARN", "ERROR"), List.of(),
                Map.of("joins", new LogFilter.CategoryFilter("joins", JOINS, List.of("joined the game"))), true);
        appender = DiscordLogAppender.create("test", "ConsoleDiscord", filter);

        jda = mock(JDA.class);
        mainChannel = channel(MAIN);
        joinChannel = channel(JOINS);
    }

    /**
     * A channel whose messages stay "on their way" until the test completes them.
     */
    private TextChannel channel(String id) {
        TextChannel channel = mock(TextChannel.class);
        when(jda.getTextChannelById(id)).thenReturn(channel);
        when(channel.sendMessage(anyString())).thenAnswer(inv -> {
            MessageCreateAction action = mock(MessageCreateAction.class);
            CompletableFuture<Message> future = new CompletableFuture<>();
            pending.add(future);
            when(action.submit()).thenReturn(future);
            return action;
        });
        return channel;
    }

    private LogForwarder forwarder(String logChannelId) {
        return new LogForwarder(logger, messages, appender, filter,
                new LogFormatter(false, false, 10), logChannelId);
    }

    private void log(String text) {
        appender.append(Log4jLogEvent.newBuilder()
                .setLoggerName("Minecraft")
                .setLevel(Level.INFO)
                .setMessage(new SimpleMessage(text))
                .build());
    }

    @Test
    void routesCategorizedLinesToTheirChannel() {
        LogForwarder forwarder = forwarder(MAIN);
        log("Steve joined the game");
        log("Saving the game");
        forwarder.flush(jda);

        ArgumentCaptor<String> main = ArgumentCaptor.forClass(String.class);
        verify(mainChannel).sendMessage(main.capture());
        assertTrue(main.getValue().contains("Saving the game"));
        assertFalse(main.getValue().contains("joined"));

        ArgumentCaptor<String> joins = ArgumentCaptor.forClass(String.class);
        verify(joinChannel).sendMessage(joins.capture());
        assertTrue(joins.getValue().contains("Steve joined the game"));
    }

    @Test
    void stopsTakingLinesWhileRequestsArePending() {
        LogForwarder forwarder = forwarder(MAIN);
        for (int round = 0; round < 5; round++) {
            log("line " + round);
            forwarder.flush(jda);
        }
        // Three unanswered requests: the rest waits in the bounded buffer
        assertEquals(3, forwarder.getPendingRequests());
        assertEquals(2, appender.getQueueSize());

        pending.get(0).complete(mock(Message.class));
        assertEquals(2, forwarder.getPendingRequests());
        forwarder.flush(jda);
        assertEquals(3, forwarder.getPendingRequests());
        assertEquals(0, appender.getQueueSize());
    }

    @Test
    void failedRequestIsNoLongerPending() {
        LogForwarder forwarder = forwarder(MAIN);
        log("line");
        forwarder.flush(jda);
        pending.get(0).completeExceptionally(new RuntimeException("timeout"));
        assertEquals(0, forwarder.getPendingRequests());
    }

    @Test
    void pausesOnlyTheChannelWithoutPermission() {
        when(mainChannel.getGuild()).thenReturn(mock(Guild.class));
        InsufficientPermissionException noAccess =
                new InsufficientPermissionException(mainChannel, Permission.MESSAGE_SEND);
        when(mainChannel.sendMessage(anyString())).thenThrow(noAccess);
        LogForwarder forwarder = forwarder(MAIN);

        log("first");
        log("Alex joined the game");
        forwarder.flush(jda);
        pending.forEach(f -> f.complete(mock(Message.class)));

        log("second");
        log("Steve joined the game");
        forwarder.flush(jda);

        verify(mainChannel, times(1)).sendMessage(anyString());
        verify(joinChannel, times(2)).sendMessage(anyString());
        assertEquals(1, warnings.stream().filter("log.channel_paused"::equals).count());
    }

    @Test
    void categoriesWorkWithoutMainChannel() {
        LogForwarder forwarder = forwarder("");
        log("Steve joined the game");
        log("Saving the game");
        forwarder.flush(jda);

        verify(joinChannel).sendMessage(anyString());
        verify(mainChannel, never()).sendMessage(anyString());
    }

    @Test
    void reportsDroppedLinesFirst() {
        LogForwarder forwarder = forwarder(MAIN);
        for (int i = 0; i < 1003; i++) {
            log("line " + i);
        }
        forwarder.flush(jda);

        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(mainChannel, atLeastOnce()).sendMessage(sent.capture());
        String first = sent.getAllValues().get(0);
        assertTrue(first.indexOf("log.lines_dropped") < first.indexOf("line 3"), first);
        assertEquals(0, appender.takeDroppedCount());
    }

    @Test
    void remainingLinesAreSentOnShutdown() {
        LogForwarder forwarder = forwarder(MAIN);
        log("last words");
        forwarder.flushRemaining(jda, java.time.Duration.ofMillis(50));

        verify(mainChannel).sendMessage(contains("last words"));
        assertEquals(0, appender.getQueueSize());
    }
}
