package dev.boondocksulfur.consolediscord.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link RateLimiter}.
 * Tests stay within the time window (no sleeps) to remain deterministic.
 */
class RateLimiterTest {

    @Test
    void allowsUpToMaxThenBlocks() {
        RateLimiter limiter = new RateLimiter(3, 60);

        assertTrue(limiter.allowCommand("user1"));
        assertTrue(limiter.allowCommand("user1"));
        assertTrue(limiter.allowCommand("user1"));
        assertFalse(limiter.allowCommand("user1"), "4th command within window must be blocked");
    }

    @Test
    void tracksCommandCount() {
        RateLimiter limiter = new RateLimiter(5, 60);

        limiter.allowCommand("user1");
        limiter.allowCommand("user1");

        assertEquals(2, limiter.getCommandCount("user1"));
        assertEquals(0, limiter.getCommandCount("unknown"));
    }

    @Test
    void usersAreTrackedIndependently() {
        RateLimiter limiter = new RateLimiter(1, 60);

        assertTrue(limiter.allowCommand("user1"));
        assertFalse(limiter.allowCommand("user1"));
        // A different user has their own budget.
        assertTrue(limiter.allowCommand("user2"));
    }

    @Test
    void resetClearsUserBudget() {
        RateLimiter limiter = new RateLimiter(1, 60);

        assertTrue(limiter.allowCommand("user1"));
        assertFalse(limiter.allowCommand("user1"));

        limiter.reset("user1");

        assertEquals(0, limiter.getCommandCount("user1"));
        assertTrue(limiter.allowCommand("user1"));
    }

    @Test
    void secondsUntilResetIsWithinWindow() {
        RateLimiter limiter = new RateLimiter(1, 60);
        limiter.allowCommand("user1");

        long seconds = limiter.getSecondsUntilReset("user1");
        assertTrue(seconds > 0 && seconds <= 60, "expected 0 < seconds <= 60 but was " + seconds);

        assertEquals(0, limiter.getSecondsUntilReset("unknown"));
    }

    @Test
    void purgeExpiredKeepsActiveEntriesIntact() {
        RateLimiter limiter = new RateLimiter(3, 60);
        limiter.allowCommand("user1");
        limiter.allowCommand("user1");

        // Within the window nothing should be removed.
        limiter.purgeExpired();

        assertEquals(2, limiter.getCommandCount("user1"));
    }
}
