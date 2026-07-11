package dev.boondocksulfur.consolediscord.logging;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link LogFilter}.
 * Log lines use the appender format: "HH:mm:ss [Thread/LEVEL]: message".
 */
class LogFilterTest {

    private static final String INFO_LINE = "12:00:00 [Server thread/INFO]: Player joined";
    private static final String WARN_LINE = "12:00:01 [Server thread/WARN]: Something odd";
    private static final String DEBUG_LINE = "12:00:02 [Server thread/DEBUG]: Debug detail";

    private LogFilter filter(List<String> levels, List<String> ignorePatterns) {
        return new LogFilter(levels, ignorePatterns, Map.of(), false);
    }

    @Test
    void allowedLevelsPassAndOthersAreFiltered() {
        LogFilter filter = filter(List.of("INFO", "WARN"), List.of());
        assertTrue(filter.shouldSendLog(INFO_LINE));
        assertTrue(filter.shouldSendLog(WARN_LINE));
        assertFalse(filter.shouldSendLog(DEBUG_LINE));
    }

    @Test
    void warningIsAcceptedAsAliasForWarn() {
        LogFilter filter = filter(List.of("WARNING"), List.of());
        assertTrue(filter.shouldSendLog(WARN_LINE));
        assertFalse(filter.shouldSendLog(INFO_LINE));
    }

    @Test
    void emptyLevelListAllowsAllLevels() {
        LogFilter filter = filter(List.of(), List.of());
        assertTrue(filter.shouldSendLog(INFO_LINE));
        assertTrue(filter.shouldSendLog(DEBUG_LINE));
        assertTrue(filter.shouldSendLog(WARN_LINE));
    }

    @Test
    void linesWithoutRecognizableLevelAreAllowed() {
        LogFilter filter = filter(List.of("INFO"), List.of());
        assertTrue(filter.shouldSendLog("some free-form line without level"));
    }

    @Test
    void ignorePatternsFilterMatchingLines() {
        LogFilter filter = filter(List.of(),
                List.of("Can't keep up! Is the server overloaded\\?"));
        assertFalse(filter.shouldSendLog(
                "12:00:00 [Server thread/WARN]: Can't keep up! Is the server overloaded? Running 2000ms behind"));
        assertTrue(filter.shouldSendLog(INFO_LINE));
    }

    @Test
    void ignorePatternsUseFindSoNoWildcardWrappingIsNeeded() {
        LogFilter filter = filter(List.of(), List.of("UUID of player"));
        assertFalse(filter.shouldSendLog(
                "12:00:00 [Server thread/INFO]: UUID of player Notch is abc-123"));
    }

    @Test
    void invalidRegexPatternsAreSkippedWithoutCrashing() {
        LogFilter filter = filter(List.of(), List.of("[invalid(regex"));
        // The invalid pattern is ignored; everything still passes.
        assertTrue(filter.shouldSendLog(INFO_LINE));
    }

    @Test
    void categoriesRouteMatchingLines() {
        Map<String, LogFilter.CategoryFilter> categories = Map.of(
                "security", new LogFilter.CategoryFilter(
                        "security", "111", List.of("Banned player")));
        LogFilter filter = new LogFilter(List.of(), List.of(), categories, true);

        assertEquals("security", filter.getCategory(
                "12:00:00 [Server thread/INFO]: Banned player Notch"));
        assertNull(filter.getCategory(INFO_LINE));
        assertEquals("111", filter.getCategoryChannelId("security"));
        assertNull(filter.getCategoryChannelId("unknown"));
    }

    @Test
    void disabledCategoriesReturnNull() {
        Map<String, LogFilter.CategoryFilter> categories = Map.of(
                "security", new LogFilter.CategoryFilter(
                        "security", "111", List.of("Banned player")));
        LogFilter filter = new LogFilter(List.of(), List.of(), categories, false);

        assertNull(filter.getCategory("12:00:00 [Server thread/INFO]: Banned player Notch"));
    }
}
