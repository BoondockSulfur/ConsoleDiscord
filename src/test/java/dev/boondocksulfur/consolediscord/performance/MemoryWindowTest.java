package dev.boondocksulfur.consolediscord.performance;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryWindowTest {

    @Test
    void notFullUntilEnoughSamples() {
        PerformanceMonitor.MemoryWindow window = new PerformanceMonitor.MemoryWindow(3);
        window.add(95);
        window.add(95);
        assertFalse(window.isFull());
        window.add(95);
        assertTrue(window.isFull());
    }

    @Test
    void singleLowSampleAfterGcKeepsMinimumLow() {
        PerformanceMonitor.MemoryWindow window = new PerformanceMonitor.MemoryWindow(3);
        window.add(95);
        window.add(40);
        window.add(96);
        assertEquals(40, window.minimum());
    }

    @Test
    void oldSamplesFallOut() {
        PerformanceMonitor.MemoryWindow window = new PerformanceMonitor.MemoryWindow(2);
        window.add(10);
        window.add(95);
        window.add(97);
        assertEquals(95, window.minimum());
    }
}
