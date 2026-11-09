package dev.boondocksulfur.consolediscord.audit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DenialThrottleTest {

    @Test
    void writesFirstEntriesThenSuppresses() {
        DenialThrottle throttle = new DenialThrottle(3, 60);
        assertEquals(0, throttle.tryAcquire("u1"));
        assertEquals(0, throttle.tryAcquire("u1"));
        assertEquals(0, throttle.tryAcquire("u1"));
        assertEquals(-1, throttle.tryAcquire("u1"));
        assertEquals(-1, throttle.tryAcquire("u1"));
    }

    @Test
    void usersAreThrottledIndependently() {
        DenialThrottle throttle = new DenialThrottle(1, 60);
        assertEquals(0, throttle.tryAcquire("u1"));
        assertEquals(-1, throttle.tryAcquire("u1"));
        assertEquals(0, throttle.tryAcquire("u2"));
    }

    @Test
    void reportsSuppressedCountWithNextEntry() throws InterruptedException {
        DenialThrottle throttle = new DenialThrottle(1, 1);
        assertEquals(0, throttle.tryAcquire("u1"));
        assertEquals(-1, throttle.tryAcquire("u1"));
        assertEquals(-1, throttle.tryAcquire("u1"));
        Thread.sleep(1100);
        assertEquals(2, throttle.tryAcquire("u1"));
    }
}
