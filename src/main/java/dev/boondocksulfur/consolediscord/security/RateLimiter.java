package dev.boondocksulfur.consolediscord.security;

import java.time.Instant;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Rate limiter to prevent command spam from Discord users.
 * Uses a sliding window algorithm to track command usage.
 */
public class RateLimiter {

    /**
     * How many {@link #allowCommand(String)} calls to serve between automatic
     * purges of stale, idle-user entries. Keeps the backing map bounded on busy
     * public servers without needing an external scheduler.
     */
    private static final int PURGE_INTERVAL = 100;

    private final int maxCommands;
    private final long windowSeconds;
    private final Map<String, Queue<Instant>> userCommands = new ConcurrentHashMap<>();
    private final AtomicInteger callsSincePurge = new AtomicInteger(0);

    /**
     * Creates a new rate limiter.
     *
     * @param maxCommands Maximum number of commands allowed in the time window
     * @param windowSeconds Time window in seconds
     */
    public RateLimiter(int maxCommands, long windowSeconds) {
        this.maxCommands = maxCommands;
        this.windowSeconds = windowSeconds;
    }

    /**
     * Checks if a user can execute a command without exceeding the rate limit.
     *
     * @param userId The Discord user ID
     * @return true if the command is allowed, false if rate limited
     */
    public boolean allowCommand(String userId) {
        // Periodically drop entries for users who have gone idle so the map
        // stays bounded on public servers with many distinct command users.
        if (callsSincePurge.incrementAndGet() >= PURGE_INTERVAL) {
            callsSincePurge.set(0);
            purgeExpired();
        }

        Instant now = Instant.now();
        Queue<Instant> timestamps = userCommands.computeIfAbsent(userId, k -> new ConcurrentLinkedQueue<>());

        // Remove old timestamps outside the window
        Instant windowStart = now.minusSeconds(windowSeconds);
        timestamps.removeIf(timestamp -> timestamp.isBefore(windowStart));

        // Check if user has exceeded the limit
        if (timestamps.size() >= maxCommands) {
            return false;
        }

        // Add current timestamp
        timestamps.offer(now);
        return true;
    }

    /**
     * Removes tracking data for users whose command timestamps have all expired.
     * Called automatically every {@link #PURGE_INTERVAL} commands to prevent
     * unbounded growth of the backing map; safe to call manually as well.
     */
    public void purgeExpired() {
        Instant windowStart = Instant.now().minusSeconds(windowSeconds);
        userCommands.entrySet().removeIf(entry -> {
            Queue<Instant> timestamps = entry.getValue();
            timestamps.removeIf(timestamp -> timestamp.isBefore(windowStart));
            return timestamps.isEmpty();
        });
    }

    /**
     * Gets the number of commands a user has used in the current window.
     *
     * @param userId The Discord user ID
     * @return The number of commands used
     */
    public int getCommandCount(String userId) {
        Queue<Instant> timestamps = userCommands.get(userId);
        if (timestamps == null) {
            return 0;
        }

        // Clean up old timestamps
        Instant windowStart = Instant.now().minusSeconds(windowSeconds);
        timestamps.removeIf(timestamp -> timestamp.isBefore(windowStart));

        return timestamps.size();
    }

    /**
     * Gets the time in seconds until the user can execute commands again.
     *
     * @param userId The Discord user ID
     * @return Seconds until rate limit resets, or 0 if not rate limited
     */
    public long getSecondsUntilReset(String userId) {
        Queue<Instant> timestamps = userCommands.get(userId);
        if (timestamps == null || timestamps.isEmpty()) {
            return 0;
        }

        Instant oldest = timestamps.peek();
        if (oldest == null) {
            return 0;
        }

        long secondsSinceOldest = Instant.now().getEpochSecond() - oldest.getEpochSecond();
        long remaining = windowSeconds - secondsSinceOldest;

        return Math.max(0, remaining);
    }

    /**
     * Resets the rate limit for a specific user.
     *
     * @param userId The Discord user ID
     */
    public void reset(String userId) {
        userCommands.remove(userId);
    }

    /**
     * Clears all rate limit data.
     */
    public void resetAll() {
        userCommands.clear();
    }
}
