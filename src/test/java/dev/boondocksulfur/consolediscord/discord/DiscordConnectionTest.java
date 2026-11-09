package dev.boondocksulfur.consolediscord.discord;

import dev.boondocksulfur.consolediscord.ConsoleDiscordPlugin;
import dev.boondocksulfur.consolediscord.i18n.Messages;
import dev.boondocksulfur.consolediscord.scheduler.SchedulerAdapter;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.events.session.ShutdownEvent;
import net.dv8tion.jda.api.requests.CloseCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DiscordConnectionTest {

    private MockedStatic<SchedulerAdapter> scheduler;
    private ConsoleDiscordPlugin plugin;
    private DiscordConnection.Callbacks callbacks;
    private DiscordConnection connection;
    private final Deque<JDA> nextConnections = new ArrayDeque<>();
    private final List<JDA> built = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();

    @BeforeEach
    void setUp() {
        // Async tasks run right away, so connecting is synchronous in tests
        scheduler = mockStatic(SchedulerAdapter.class);
        scheduler.when(() -> SchedulerAdapter.runAsync(any(), any())).thenAnswer(inv -> {
            inv.<Runnable>getArgument(1).run();
            return null;
        });

        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override public void publish(LogRecord record) {
                if (record.getLevel().intValue() >= java.util.logging.Level.WARNING.intValue()) {
                    warnings.add(record.getMessage());
                }
            }
            @Override public void flush() {}
            @Override public void close() {}
        });

        Messages messages = mock(Messages.class);
        when(messages.getRaw(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(messages.get(anyString(), any(String[].class))).thenAnswer(inv -> inv.getArgument(0));

        plugin = mock(ConsoleDiscordPlugin.class);
        when(plugin.getLogger()).thenReturn(logger);
        when(plugin.getMessages()).thenReturn(messages);

        callbacks = mock(DiscordConnection.Callbacks.class);
        connection = new DiscordConnection(plugin, callbacks);
        connection.setFactory(() -> {
            JDA next = nextConnections.poll();
            if (next != null) {
                built.add(next);
            }
            return next;
        });
    }

    @AfterEach
    void tearDown() {
        scheduler.close();
    }

    private static JDA newJda(JDA.Status status) throws InterruptedException {
        JDA jda = mock(JDA.class);
        when(jda.getStatus()).thenReturn(status);
        when(jda.getRegisteredListeners()).thenReturn(List.of());
        when(jda.awaitShutdown(any())).thenReturn(true);
        return jda;
    }

    private JDA connect(JDA jda) {
        nextConnections.add(jda);
        synchronized (connection.lock()) {
            connection.requestConnection();
        }
        return connection.get();
    }

    private void closedByDiscord(JDA jda, CloseCode code) {
        ShutdownEvent event = mock(ShutdownEvent.class);
        when(event.getCloseCode()).thenReturn(code);
        when(event.getJDA()).thenReturn(jda);
        connection.new StatusListener().onShutdown(event);
    }

    private void setLastConnected(Instant instant) throws ReflectiveOperationException {
        Field field = DiscordConnection.class.getDeclaredField("lastConnected");
        field.setAccessible(true);
        field.set(connection, instant);
    }

    @Test
    void installsNewConnection() throws Exception {
        JDA jda = newJda(JDA.Status.CONNECTED);
        assertSame(jda, connect(jda));
        verify(callbacks).onInstalled(jda);
    }

    @Test
    void discardsConnectionBuiltWhileShuttingDown() throws Exception {
        JDA jda = newJda(JDA.Status.CONNECTED);
        connection.setShuttingDown(true);
        assertNull(connect(jda));
        verify(jda).shutdownNow();
        verify(callbacks, never()).onInstalled(any());
    }

    @Test
    void missingIntentReconnectsWithoutMessageContent() throws Exception {
        JDA first = connect(newJda(JDA.Status.SHUTDOWN));
        JDA second = newJda(JDA.Status.CONNECTED);
        nextConnections.add(second);

        closedByDiscord(first, CloseCode.DISALLOWED_INTENTS);

        assertFalse(connection.isMessageContentEnabled());
        assertFalse(connection.isConnectionRejected());
        assertTrue(warnings.contains("discord.intent_missing"), warnings.toString());
        assertSame(second, connection.get());
        verify(first).shutdown();
    }

    @Test
    void secondIntentRejectionStopsTrying() throws Exception {
        JDA first = connect(newJda(JDA.Status.SHUTDOWN));
        JDA second = newJda(JDA.Status.SHUTDOWN);
        nextConnections.add(second);
        closedByDiscord(first, CloseCode.DISALLOWED_INTENTS);

        // Rejected again without Message Content: no endless loop
        closedByDiscord(second, CloseCode.DISALLOWED_INTENTS);
        assertTrue(connection.isConnectionRejected());
        assertEquals(2, built.size());
    }

    @Test
    void permanentCloseCodeIsNotRestartedByWatchdog() throws Exception {
        JDA jda = connect(newJda(JDA.Status.SHUTDOWN));
        closedByDiscord(jda, CloseCode.AUTHENTICATION_FAILED);
        assertTrue(connection.isConnectionRejected());

        setLastConnected(Instant.now().minusSeconds(3600));
        nextConnections.add(newJda(JDA.Status.CONNECTED));
        connection.watchdogTick();

        assertSame(jda, connection.get());
        verify(jda, never()).shutdown();
        assertEquals(1, built.size());
    }

    @Test
    void watchdogRestartsConnectionStuckTooLong() throws Exception {
        JDA stuck = connect(newJda(JDA.Status.ATTEMPTING_TO_RECONNECT));
        JDA fresh = newJda(JDA.Status.CONNECTED);
        nextConnections.add(fresh);

        setLastConnected(Instant.now().minusSeconds(3600));
        connection.watchdogTick();

        verify(stuck).shutdown();
        assertSame(fresh, connection.get());
    }

    @Test
    void watchdogLeavesShortReconnectAlone() throws Exception {
        JDA reconnecting = connect(newJda(JDA.Status.ATTEMPTING_TO_RECONNECT));
        setLastConnected(Instant.now().minusSeconds(60));
        connection.watchdogTick();
        verify(reconnecting, never()).shutdown();
    }

    @Test
    void reconnectableCloseCodeIsIgnored() throws Exception {
        JDA jda = connect(newJda(JDA.Status.DISCONNECTED));
        closedByDiscord(jda, CloseCode.UNKNOWN_ERROR);
        assertFalse(connection.isConnectionRejected());
        assertTrue(connection.isMessageContentEnabled());
    }

    @Test
    void shutdownOfOlderConnectionIsIgnored() throws Exception {
        connect(newJda(JDA.Status.CONNECTED));
        JDA old = newJda(JDA.Status.SHUTDOWN);
        closedByDiscord(old, CloseCode.DISALLOWED_INTENTS);
        assertTrue(connection.isMessageContentEnabled());
        assertFalse(connection.isConnectionRejected());
    }

    @Test
    void reloadForgetsRejections() throws Exception {
        JDA jda = connect(newJda(JDA.Status.SHUTDOWN));
        nextConnections.add(newJda(JDA.Status.SHUTDOWN));
        closedByDiscord(jda, CloseCode.DISALLOWED_INTENTS);
        closedByDiscord(connection.get(), CloseCode.DISALLOWED_INTENTS);

        connection.resetRejections();
        assertTrue(connection.isMessageContentEnabled());
        assertFalse(connection.isConnectionRejected());
    }

    @Test
    void connectedStatusReportsToPlugin() throws Exception {
        JDA jda = connect(newJda(JDA.Status.CONNECTED));
        net.dv8tion.jda.api.events.StatusChangeEvent event = mock(net.dv8tion.jda.api.events.StatusChangeEvent.class);
        when(event.getNewStatus()).thenReturn(JDA.Status.CONNECTED);
        when(event.getJDA()).thenReturn(jda);
        connection.new StatusListener().onStatusChange(event);
        verify(callbacks).onConnected(jda);
    }
}
