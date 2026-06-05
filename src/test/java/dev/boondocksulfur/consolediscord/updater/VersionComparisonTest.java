package dev.boondocksulfur.consolediscord.updater;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ModrinthUpdateChecker#isNewerVersion(String, String)}.
 */
class VersionComparisonTest {

    @Test
    void higherPatchIsNewer() {
        assertTrue(ModrinthUpdateChecker.isNewerVersion("1.4.2", "1.4.1"));
    }

    @Test
    void higherMinorIsNewer() {
        assertTrue(ModrinthUpdateChecker.isNewerVersion("1.5.0", "1.4.9"));
    }

    @Test
    void higherMajorIsNewer() {
        assertTrue(ModrinthUpdateChecker.isNewerVersion("2.0.0", "1.9.9"));
    }

    @Test
    void equalVersionsAreNotNewer() {
        assertFalse(ModrinthUpdateChecker.isNewerVersion("1.4.1", "1.4.1"));
    }

    @Test
    void lowerVersionIsNotNewer() {
        assertFalse(ModrinthUpdateChecker.isNewerVersion("1.4.0", "1.4.1"));
        assertFalse(ModrinthUpdateChecker.isNewerVersion("1.3.9", "1.4.0"));
    }

    @Test
    void differingSegmentCountsCompareCorrectly() {
        // "1.4.1.1" > "1.4.1"
        assertTrue(ModrinthUpdateChecker.isNewerVersion("1.4.1.1", "1.4.1"));
        // "1.4" == "1.4.0" -> not newer
        assertFalse(ModrinthUpdateChecker.isNewerVersion("1.4", "1.4.0"));
    }

    @Test
    void nonNumericSuffixFallsBackToStringCompare() {
        // Parsing "1-beta" fails -> lexical fallback; the suffixed string sorts after.
        assertTrue(ModrinthUpdateChecker.isNewerVersion("1.4.1-beta", "1.4.1"));
    }
}
