package dev.boondocksulfur.consolediscord.logging;

import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.Layout;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.layout.PatternLayout;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Custom Log4j appender that captures server logs for forwarding to Discord.
 * Uses a thread-safe queue to buffer log messages until they are flushed to Discord.
 */
public class DiscordLogAppender extends AbstractAppender {

    /**
     * Maximum number of log messages to keep in the queue.
     * Older messages are discarded when this limit is reached.
     */
    private static final int MAX_QUEUE_LENGTH = 1000;

    /**
     * Thread-safe queue for storing log messages.
     */
    private final Queue<String> queue = new ConcurrentLinkedQueue<>();

    /**
     * O(1) counter to avoid ConcurrentLinkedQueue.size() which is O(n).
     */
    private final AtomicInteger queueCount = new AtomicInteger(0);

    /**
     * Creates a new Discord log appender.
     *
     * @param name The name of the appender
     * @param filter Optional filter for log events
     * @param layout Layout for formatting log messages
     * @param ignoreExceptions Whether to ignore exceptions during logging
     */
    protected DiscordLogAppender(String name,
                                 Filter filter,
                                 Layout<? extends Serializable> layout,
                                 boolean ignoreExceptions) {
        super(name, filter, layout, ignoreExceptions, Property.EMPTY_ARRAY);
    }

    /**
     * Factory method to create a new Discord log appender with default settings.
     *
     * @param name The name of the appender
     * @return A new DiscordLogAppender instance
     */
    public static DiscordLogAppender create(String name) {
        PatternLayout layout = PatternLayout.newBuilder()
                .withPattern("%d{HH:mm:ss} [%t/%p]: %msg%n")
                .build();
        return new DiscordLogAppender(name, null, layout, true);
    }

    /**
     * Appends a log event to the queue.
     * This method is called by Log4j for each log message.
     *
     * @param event The log event to append
     */
    @Override
    public void append(LogEvent event) {
        Layout<? extends Serializable> layout = getLayout();
        if (layout == null) {
            return;
        }

        Serializable serializable = layout.toSerializable(event);
        if (serializable == null) {
            return;
        }

        String line = serializable.toString();
        queue.offer(line);
        int size = queueCount.incrementAndGet();

        // Remove oldest messages if queue is full
        while (size > MAX_QUEUE_LENGTH) {
            if (queue.poll() != null) {
                size = queueCount.decrementAndGet();
            } else {
                break;
            }
        }
    }

    /**
     * Drains up to maxLines messages from the queue.
     * Messages are removed from the queue and returned in order.
     *
     * @param maxLines Maximum number of lines to retrieve
     * @return List of log messages
     */
    public List<String> drain(int maxLines) {
        List<String> result = new ArrayList<>(maxLines);
        for (int i = 0; i < maxLines; i++) {
            String line = queue.poll();
            if (line == null) {
                break;
            }
            queueCount.decrementAndGet();
            result.add(line);
        }
        return result;
    }

    /**
     * Gets the current queue size.
     *
     * @return Number of messages in the queue
     */
    public int getQueueSize() {
        return queueCount.get();
    }

    /**
     * Clears all messages from the queue.
     */
    public void clear() {
        queue.clear();
        queueCount.set(0);
    }
}
