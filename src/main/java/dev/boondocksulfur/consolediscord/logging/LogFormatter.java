package dev.boondocksulfur.consolediscord.logging;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import org.apache.logging.log4j.Level;

import java.awt.Color;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Formats log messages for Discord, supporting both code blocks and embeds.
 * Provides color coding and emoji support for different log levels.
 */
public class LogFormatter {

    private static final Pattern LOG_LEVEL_PATTERN = Pattern.compile("\\[(\\w+)/(\\w+)\\]");

    private final boolean useEmbeds;
    private final boolean useEmojis;
    private final int embedBatchSize;

    /**
     * Creates a new log formatter.
     *
     * @param useEmbeds Whether to use Discord embeds
     * @param useEmojis Whether to use emojis for log levels
     * @param embedBatchSize Number of log lines per embed
     */
    public LogFormatter(boolean useEmbeds, boolean useEmojis, int embedBatchSize) {
        this.useEmbeds = useEmbeds;
        this.useEmojis = useEmojis;
        this.embedBatchSize = Math.max(1, Math.min(embedBatchSize, 25)); // Discord limit
    }

    /**
     * Formats log lines as code blocks (legacy format).
     *
     * @param lines The log lines to format
     * @return Formatted string ready for Discord
     */
    public String formatAsCodeBlock(List<String> lines) {
        if (lines.isEmpty()) {
            return "";
        }

        StringBuilder content = new StringBuilder("```");

        for (String line : lines) {
            String formattedLine = useEmojis ? addEmojiToLine(line) : line;

            // Truncate single lines that could never fit into one message
            int maxLineLength = 2000 - 6; // opening and closing backticks
            if (formattedLine.length() > maxLineLength) {
                formattedLine = formattedLine.substring(0, maxLineLength - 2) + "…\n";
            }

            // Ensure we don't exceed Discord's 2000 char limit
            if (content.length() + formattedLine.length() + 3 > 2000) {
                break;
            }
            content.append(formattedLine);
        }

        // Only backticks left means nothing fit — don't send an empty block
        if (content.length() == 3) {
            return "";
        }

        content.append("```");
        return content.toString();
    }

    /**
     * Discord limit: total characters across all embeds in a single message.
     */
    private static final int MAX_TOTAL_EMBED_SIZE = 6000;

    /**
     * Formats log lines as Discord embeds, split into groups that each
     * respect Discord's 6000 character total embed limit per message.
     *
     * @param lines The log lines to format
     * @return List of embed groups, each group safe to send in one message
     */
    public List<List<MessageEmbed>> formatAsEmbedGroups(List<String> lines) {
        List<List<MessageEmbed>> groups = new ArrayList<>();

        if (lines.isEmpty()) {
            return groups;
        }

        List<LogEntry> entries = new ArrayList<>();
        for (String line : lines) {
            entries.add(parseLogLine(line));
        }

        List<MessageEmbed> currentGroup = new ArrayList<>();
        int currentGroupSize = 0;

        for (int i = 0; i < entries.size(); i += embedBatchSize) {
            int end = Math.min(i + embedBatchSize, entries.size());
            List<LogEntry> batch = entries.subList(i, end);

            MessageEmbed embed = createEmbedForBatch(batch).build();
            int embedLength = embed.getLength();

            // If this single embed already exceeds the limit, send it alone
            if (embedLength >= MAX_TOTAL_EMBED_SIZE) {
                if (!currentGroup.isEmpty()) {
                    groups.add(currentGroup);
                    currentGroup = new ArrayList<>();
                    currentGroupSize = 0;
                }
                groups.add(List.of(embed));
                continue;
            }

            // If adding this embed would exceed the limit, start a new group
            if (currentGroupSize + embedLength > MAX_TOTAL_EMBED_SIZE || currentGroup.size() >= 10) {
                if (!currentGroup.isEmpty()) {
                    groups.add(currentGroup);
                }
                currentGroup = new ArrayList<>();
                currentGroupSize = 0;
            }

            currentGroup.add(embed);
            currentGroupSize += embedLength;
        }

        if (!currentGroup.isEmpty()) {
            groups.add(currentGroup);
        }

        return groups;
    }

    /**
     * Formats log lines as Discord embeds.
     * Note: The returned list may exceed Discord's 6000 char total limit.
     * Prefer {@link #formatAsEmbedGroups(List)} for safe sending.
     *
     * @param lines The log lines to format
     * @return List of embeds ready for Discord
     */
    public List<MessageEmbed> formatAsEmbeds(List<String> lines) {
        List<List<MessageEmbed>> groups = formatAsEmbedGroups(lines);
        List<MessageEmbed> all = new ArrayList<>();
        for (List<MessageEmbed> group : groups) {
            all.addAll(group);
        }
        return all;
    }

    /**
     * Creates an embed for a batch of log entries.
     */
    private EmbedBuilder createEmbedForBatch(List<LogEntry> entries) {
        if (entries.isEmpty()) {
            return new EmbedBuilder().setDescription("No logs");
        }

        // Determine dominant log level for color
        Level dominantLevel = getDominantLevel(entries);
        Color color = getColorForLevel(dominantLevel);

        EmbedBuilder embed = new EmbedBuilder()
                .setColor(color)
                .setTimestamp(Instant.now());

        // Add entries as description
        StringBuilder description = new StringBuilder();
        for (LogEntry entry : entries) {
            String emoji = useEmojis ? getEmojiForLevel(entry.level) : "";
            String formatted = String.format("%s `%s` %s\n",
                    emoji, entry.level.name(), entry.message);

            // Discord embed description limit is 4096
            if (description.length() + formatted.length() > 4000) {
                description.append("*...truncated*");
                break;
            }
            description.append(formatted);
        }

        embed.setDescription(description.toString());
        return embed;
    }

    /**
     * Parses a log line into a LogEntry.
     */
    private LogEntry parseLogLine(String line) {
        Matcher matcher = LOG_LEVEL_PATTERN.matcher(line);

        if (matcher.find()) {
            String levelStr = matcher.group(2);
            Level level = parseLevel(levelStr);
            String message = line.substring(matcher.end()).trim();
            return new LogEntry(level, message);
        }

        return new LogEntry(Level.INFO, line);
    }

    /**
     * Parses a string to a Log4j Level.
     */
    private Level parseLevel(String levelStr) {
        return switch (levelStr.toUpperCase()) {
            case "TRACE" -> Level.TRACE;
            case "DEBUG" -> Level.DEBUG;
            case "INFO" -> Level.INFO;
            case "WARN", "WARNING" -> Level.WARN;
            case "ERROR" -> Level.ERROR;
            case "FATAL" -> Level.FATAL;
            default -> Level.INFO;
        };
    }

    /**
     * Gets the dominant (most severe) log level from entries.
     */
    private Level getDominantLevel(List<LogEntry> entries) {
        Level highest = Level.INFO;

        for (LogEntry entry : entries) {
            if (entry.level.isMoreSpecificThan(highest)) {
                highest = entry.level;
            }
        }

        return highest;
    }

    /**
     * Gets the Discord color for a log level.
     */
    private Color getColorForLevel(Level level) {
        if (level == Level.FATAL || level == Level.ERROR) {
            return Color.RED;
        } else if (level == Level.WARN) {
            return Color.ORANGE;
        } else if (level == Level.DEBUG || level == Level.TRACE) {
            return Color.GRAY;
        } else {
            return Color.GREEN;
        }
    }

    /**
     * Gets the emoji for a log level.
     */
    public static String getEmojiForLevel(Level level) {
        if (level == Level.FATAL) {
            return "💀";
        } else if (level == Level.ERROR) {
            return "❌";
        } else if (level == Level.WARN) {
            return "⚠️";
        } else if (level == Level.INFO) {
            return "ℹ️";
        } else if (level == Level.DEBUG) {
            return "🔍";
        } else if (level == Level.TRACE) {
            return "🔬";
        }
        return "📝";
    }

    /**
     * Adds emoji to a log line based on its level.
     */
    private String addEmojiToLine(String line) {
        Matcher matcher = LOG_LEVEL_PATTERN.matcher(line);

        if (matcher.find()) {
            String levelStr = matcher.group(2);
            Level level = parseLevel(levelStr);
            String emoji = getEmojiForLevel(level);

            // Insert emoji after the level bracket
            return line.substring(0, matcher.end()) + " " + emoji + " " + line.substring(matcher.end());
        }

        return line;
    }

    /**
     * Checks if embeds are enabled.
     */
    public boolean isUsingEmbeds() {
        return useEmbeds;
    }

    /**
     * Simple container for a log entry.
     */
    private static class LogEntry {
        final Level level;
        final String message;

        LogEntry(Level level, String message) {
            this.level = level;
            this.message = message;
        }
    }
}
