package dev.boondocksulfur.consolediscord.logging;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.apache.logging.log4j.message.SimpleMessage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiscordLogAppenderTest {

    private static Log4jLogEvent event(String logger, String text) {
        return event(logger, Level.INFO, text);
    }

    private static Log4jLogEvent event(String logger, Level level, String text) {
        return Log4jLogEvent.newBuilder()
                .setLoggerName(logger)
                .setLevel(level)
                .setMessage(new SimpleMessage(text))
                .build();
    }

    @Test
    void countsLinesDroppedWhenBufferIsFull() {
        DiscordLogAppender appender = DiscordLogAppender.create("test", "ConsoleDiscord");
        for (int i = 0; i < 1005; i++) {
            appender.append(event("Minecraft", "line " + i));
        }
        assertEquals(1000, appender.getQueueSize());
        assertEquals(5, appender.takeDroppedCount());
        assertEquals(0, appender.takeDroppedCount());
        List<LogLine> first = appender.drain(1);
        assertTrue(first.get(0).text().contains("line 5"), first.get(0).text());
    }

    @Test
    void skipsOwnAndBundledLibraryLoggers() {
        DiscordLogAppender appender = DiscordLogAppender.create("test", "ConsoleDiscord");
        appender.append(event("ConsoleDiscord", "own"));
        appender.append(event("dev.boondocksulfur.consolediscord.libs.jda.Requester", "rate limit"));
        appender.append(event("Minecraft", "kept"));
        assertEquals(1, appender.getQueueSize());
    }

    @Test
    void filtersBeforeBuffering() {
        LogFilter filter = new LogFilter(List.of("WARN", "ERROR"), List.of("Can't keep up"), Map.of(), false);
        DiscordLogAppender appender = DiscordLogAppender.create("test", "ConsoleDiscord", filter);
        appender.append(event("Minecraft", Level.INFO, "info line"));
        appender.append(event("Minecraft", Level.WARN, "Can't keep up! Is the server overloaded?"));
        appender.append(event("Minecraft", Level.WARN, "real warning"));
        appender.append(event("Minecraft", Level.ERROR, "real error"));
        assertEquals(2, appender.getQueueSize());
        List<LogLine> lines = appender.drain(10);
        assertEquals(Level.WARN, lines.get(0).level());
        assertEquals(Level.ERROR, lines.get(1).level());
    }

    @Test
    void ignoredLinesDoNotPushOutRealOnes() {
        LogFilter filter = new LogFilter(List.of("INFO"), List.of("spam"), Map.of(), false);
        DiscordLogAppender appender = DiscordLogAppender.create("test", "ConsoleDiscord", filter);
        appender.append(event("Minecraft", "important"));
        for (int i = 0; i < 2000; i++) {
            appender.append(event("Minecraft", "spam " + i));
        }
        assertEquals(1, appender.getQueueSize());
        assertEquals(0, appender.takeDroppedCount());
    }
}
