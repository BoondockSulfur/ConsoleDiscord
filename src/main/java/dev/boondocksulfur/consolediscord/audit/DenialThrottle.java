package dev.boondocksulfur.consolediscord.audit;

import dev.boondocksulfur.consolediscord.security.RateLimiter;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Limits how many denied attempts per user are written to the audit log,
 * so a guild member spamming "!mc" can't flood the log and rotate the real
 * entries away. Suppressed attempts are counted and reported with the next
 * entry that is written for that user.
 */
public class DenialThrottle {

    /** Upper bound for the suppressed-count map; counts are dropped beyond it. */
    private static final int MAX_TRACKED_USERS = 10_000;

    private final RateLimiter limiter;
    private final Map<String, AtomicInteger> suppressed = new ConcurrentHashMap<>();

    /**
     * @param maxEntries Denied entries written per user and window
     * @param windowSeconds Window length in seconds
     */
    public DenialThrottle(int maxEntries, long windowSeconds) {
        this.limiter = new RateLimiter(maxEntries, windowSeconds);
    }

    /**
     * Registers a denied attempt.
     *
     * @param userId The Discord user ID
     * @return -1 if the entry must be suppressed, otherwise the number of
     *         attempts suppressed since the last written entry (0 if none)
     */
    public int tryAcquire(String userId) {
        if (!limiter.allowCommand(userId)) {
            if (suppressed.size() > MAX_TRACKED_USERS) {
                suppressed.clear();
            }
            suppressed.computeIfAbsent(userId, k -> new AtomicInteger()).incrementAndGet();
            return -1;
        }
        AtomicInteger count = suppressed.remove(userId);
        return count == null ? 0 : count.get();
    }
}
