package dev.boondocksulfur.consolediscord.logging;

import org.apache.logging.log4j.Level;

import java.util.*;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Filters log messages based on log levels and regex patterns.
 * Supports both whitelist (allowed levels) and blacklist (ignored patterns).
 */
public class LogFilter {

    private static final Logger LOGGER = Logger.getLogger("ConsoleDiscord");

    private final Set<Level> allowedLevels;
    private final List<Pattern> ignorePatterns;
    private final Map<String, CategoryFilter> categoryFilters;
    private final boolean categoriesEnabled;

    /**
     * Creates a new log filter.
     *
     * @param allowedLevelNames Names of allowed log levels
     * @param ignorePatterns Regex patterns to ignore
     * @param categoryFilters Category filters for routing logs
     * @param categoriesEnabled Whether category filtering is enabled
     */
    public LogFilter(List<String> allowedLevelNames,
                     List<String> ignorePatterns,
                     Map<String, CategoryFilter> categoryFilters,
                     boolean categoriesEnabled) {
        this.allowedLevels = parseLevels(allowedLevelNames);
        this.ignorePatterns = compilePatterns(ignorePatterns);
        this.categoryFilters = categoryFilters != null ? categoryFilters : new HashMap<>();
        this.categoriesEnabled = categoriesEnabled;
    }

    /**
     * Checks if a log line should be sent to Discord.
     *
     * @param line The log line to check
     * @return true if the line should be sent, false if filtered out
     */
    public boolean shouldSendLog(String line) {
        // Check log level
        Level level = extractLevel(line);
        if (level != null && !allowedLevels.contains(level)) {
            return false;
        }

        // Check ignore patterns (uses find() so patterns don't need .* wrapping)
        for (Pattern pattern : ignorePatterns) {
            if (pattern.matcher(line).find()) {
                return false;
            }
        }

        return true;
    }

    /**
     * Gets the category for a log line, or null if it doesn't match any category.
     *
     * @param line The log line
     * @return The category name, or null
     */
    public String getCategory(String line) {
        if (!categoriesEnabled) {
            return null;
        }

        for (Map.Entry<String, CategoryFilter> entry : categoryFilters.entrySet()) {
            if (entry.getValue().matches(line)) {
                return entry.getKey();
            }
        }

        return null;
    }

    /**
     * Gets the channel ID for a category.
     *
     * @param category The category name
     * @return The channel ID, or null if not found
     */
    public String getCategoryChannelId(String category) {
        CategoryFilter filter = categoryFilters.get(category);
        return filter != null ? filter.channelId : null;
    }

    /**
     * Extracts the log level from a log line.
     */
    private Level extractLevel(String line) {
        // Expected format: "HH:mm:ss [Thread/LEVEL]: message"
        int levelStart = line.indexOf('/');
        int levelEnd = line.indexOf(']', levelStart);

        if (levelStart == -1 || levelEnd == -1) {
            return null;
        }

        String levelStr = line.substring(levelStart + 1, levelEnd).trim();

        return switch (levelStr.toUpperCase()) {
            case "TRACE" -> Level.TRACE;
            case "DEBUG" -> Level.DEBUG;
            case "INFO" -> Level.INFO;
            case "WARN", "WARNING" -> Level.WARN;
            case "ERROR" -> Level.ERROR;
            case "FATAL" -> Level.FATAL;
            default -> null;
        };
    }

    /**
     * Parses level names to Log4j Levels.
     */
    private Set<Level> parseLevels(List<String> levelNames) {
        Set<Level> levels = new HashSet<>();

        for (String name : levelNames) {
            switch (name.toUpperCase()) {
                case "TRACE" -> levels.add(Level.TRACE);
                case "DEBUG" -> levels.add(Level.DEBUG);
                case "INFO" -> levels.add(Level.INFO);
                case "WARN", "WARNING" -> levels.add(Level.WARN);
                case "ERROR" -> levels.add(Level.ERROR);
                case "FATAL" -> levels.add(Level.FATAL);
            }
        }

        // If empty, allow all levels
        if (levels.isEmpty()) {
            levels.addAll(Arrays.asList(Level.TRACE, Level.DEBUG, Level.INFO, Level.WARN, Level.ERROR, Level.FATAL));
        }

        return levels;
    }

    /**
     * Compiles regex patterns from strings.
     */
    private List<Pattern> compilePatterns(List<String> patternStrings) {
        List<Pattern> patterns = new ArrayList<>();

        for (String patternStr : patternStrings) {
            try {
                patterns.add(Pattern.compile(patternStr));
            } catch (PatternSyntaxException e) {
                LOGGER.warning("Invalid regex pattern: " + patternStr);
            }
        }

        return patterns;
    }

    /**
     * Category filter for routing logs to specific channels.
     */
    public static class CategoryFilter {
        private final String categoryName;
        private final String channelId;
        private final List<Pattern> patterns;

        public CategoryFilter(String categoryName, String channelId, List<String> patternStrings) {
            this.categoryName = categoryName;
            this.channelId = channelId;
            this.patterns = compilePatterns(patternStrings);
        }

        /**
         * Checks if a log line matches this category.
         */
        public boolean matches(String line) {
            for (Pattern pattern : patterns) {
                if (pattern.matcher(line).find()) {
                    return true;
                }
            }
            return false;
        }

        private static List<Pattern> compilePatterns(List<String> patternStrings) {
            List<Pattern> patterns = new ArrayList<>();
            for (String patternStr : patternStrings) {
                try {
                    patterns.add(Pattern.compile(patternStr));
                } catch (PatternSyntaxException e) {
                    Logger.getLogger("ConsoleDiscord").warning("Invalid regex pattern in category: " + patternStr);
                }
            }
            return patterns;
        }

        public String getCategoryName() {
            return categoryName;
        }

        public String getChannelId() {
            return channelId;
        }
    }
}
