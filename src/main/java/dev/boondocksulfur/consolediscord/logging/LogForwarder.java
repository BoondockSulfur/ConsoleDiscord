package dev.boondocksulfur.consolediscord.logging;

import dev.boondocksulfur.consolediscord.i18n.Messages;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.exceptions.InsufficientPermissionException;
import net.dv8tion.jda.api.requests.ErrorResponse;
import org.apache.logging.log4j.Level;

import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * Takes buffered console lines from the appender, filters and routes them,
 * and sends them to the Discord log and category channels.
 * One instance per configuration; /cdr reload creates a new one.
 */
public class LogForwarder {

    /**
     * Lines taken from the buffer per flush.
     */
    private static final int LINES_PER_FLUSH = 50;

    /**
     * New lines are only taken while fewer requests than this are still on
     * their way. Discord allows about one message per second and channel;
     * without this limit a log flood would pile up in JDA's unbounded
     * request queue and arrive hours late. The backlog stays in the
     * appender's bounded buffer instead.
     */
    private static final int MAX_PENDING_REQUESTS = 3;

    /**
     * Lines sent when the plugin shuts down.
     */
    private static final int LINES_ON_SHUTDOWN = 200;

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final Logger logger;
    private final Messages messages;
    private final DiscordLogAppender appender;
    private final LogFilter filter;
    private final LogFormatter formatter;
    private final String logChannelId;

    /**
     * Channels the bot can't write to; skipped until the next reload.
     */
    private final Set<String> pausedChannels = ConcurrentHashMap.newKeySet();
    private final AtomicInteger pendingRequests = new AtomicInteger();

    public LogForwarder(Logger logger, Messages messages, DiscordLogAppender appender,
                        LogFilter filter, LogFormatter formatter, String logChannelId) {
        this.logger = logger;
        this.messages = messages;
        this.appender = appender;
        this.filter = filter;
        this.formatter = formatter;
        this.logChannelId = logChannelId == null ? "" : logChannelId;
    }

    /**
     * Sends the next batch of buffered lines, unless earlier messages are
     * still waiting for Discord.
     *
     * @param jda A connected JDA instance
     */
    public void flush(JDA jda) {
        if (pendingRequests.get() >= MAX_PENDING_REQUESTS) {
            return;
        }
        List<LogLine> lines = appender.drain(LINES_PER_FLUSH);
        int dropped = appender.takeDroppedCount();
        if (dropped > 0) {
            lines.add(0, droppedNotice(dropped));
        }
        if (!lines.isEmpty()) {
            send(jda, lines);
        }
    }

    /**
     * Sends what is left in the buffer and waits for it, so the last lines
     * before a shutdown reach Discord.
     *
     * @param jda A connected JDA instance
     * @param timeout How long to wait at most
     */
    public void flushRemaining(JDA jda, Duration timeout) {
        List<LogLine> lines = appender.drain(LINES_ON_SHUTDOWN);
        if (lines.isEmpty()) {
            return;
        }
        List<CompletableFuture<?>> sent = send(jda, lines);
        try {
            CompletableFuture.allOf(sent.toArray(new CompletableFuture[0]))
                    .get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            // Timeout or send error while shutting down: nothing more to do
        }
    }

    private LogLine droppedNotice(int dropped) {
        return new LogLine(Level.WARN, LocalTime.now().format(TIME) + " [ConsoleDiscord/WARN]: "
                + messages.get("log.lines_dropped", "count", String.valueOf(dropped)));
    }

    private List<CompletableFuture<?>> send(JDA jda, List<LogLine> lines) {
        // Level and ignore patterns were applied by the appender already
        List<LogLine> mainLines = new ArrayList<>();
        Map<String, List<LogLine>> categorized = new LinkedHashMap<>();

        for (LogLine line : lines) {
            String category = filter.getCategory(line.text());
            if (category != null) {
                categorized.computeIfAbsent(category, k -> new ArrayList<>()).add(line);
            } else {
                mainLines.add(line);
            }
        }

        List<CompletableFuture<?>> futures = new ArrayList<>();
        // Without a main log channel, uncategorized lines are dropped while
        // category channels keep working.
        if (!mainLines.isEmpty() && !logChannelId.isBlank()) {
            futures.addAll(sendToChannel(jda, logChannelId, mainLines));
        }
        for (Map.Entry<String, List<LogLine>> entry : categorized.entrySet()) {
            String channelId = filter.getCategoryChannelId(entry.getKey());
            if (channelId != null && !channelId.isBlank()) {
                futures.addAll(sendToChannel(jda, channelId, entry.getValue()));
            }
        }
        return futures;
    }

    private List<CompletableFuture<?>> sendToChannel(JDA jda, String channelId, List<LogLine> lines) {
        if (pausedChannels.contains(channelId)) {
            return List.of();
        }
        TextChannel channel = jda.getTextChannelById(channelId);
        if (channel == null) {
            return List.of();
        }

        List<CompletableFuture<?>> futures = new ArrayList<>();
        try {
            if (formatter.isUsingEmbeds()) {
                for (List<MessageEmbed> group : formatter.formatLinesAsEmbedGroups(lines)) {
                    if (!group.isEmpty()) {
                        futures.add(track(channelId, channel.sendMessageEmbeds(group).submit()));
                    }
                }
            } else {
                for (String content : formatter.formatLinesAsCodeBlocks(lines)) {
                    futures.add(track(channelId, channel.sendMessage(content).submit()));
                }
            }
        } catch (InsufficientPermissionException e) {
            // JDA checks cached permissions before queueing and throws right away
            handleSendError(channelId, e);
        } catch (RuntimeException e) {
            // A formatting or request error must not kill the timer task
            handleSendError(channelId, e);
        }
        return futures;
    }

    private CompletableFuture<?> track(String channelId, CompletableFuture<?> future) {
        pendingRequests.incrementAndGet();
        return future.whenComplete((result, error) -> {
            pendingRequests.decrementAndGet();
            if (error != null) {
                handleSendError(channelId, error instanceof CompletionException && error.getCause() != null
                        ? error.getCause() : error);
            }
        });
    }

    /**
     * Pauses forwarding to a single channel the bot can't write to, instead of
     * stopping the whole log task; the other channels keep working.
     * /cdr reload clears the pause.
     */
    private void handleSendError(String channelId, Throwable error) {
        boolean noAccess = error instanceof InsufficientPermissionException
                || (error instanceof ErrorResponseException ere
                    && (ere.getErrorResponse() == ErrorResponse.MISSING_ACCESS
                        || ere.getErrorResponse() == ErrorResponse.MISSING_PERMISSIONS
                        || ere.getErrorResponse() == ErrorResponse.UNKNOWN_CHANNEL));
        if (noAccess) {
            if (pausedChannels.add(channelId)) {
                logger.warning(messages.get("log.channel_paused", "id", channelId));
            }
        } else {
            logger.log(java.util.logging.Level.WARNING, messages.getRaw("log.send_failed"), error);
        }
    }

    /**
     * Number of requests sent but not yet answered by Discord.
     *
     * @return The pending request count
     */
    public int getPendingRequests() {
        return pendingRequests.get();
    }
}
