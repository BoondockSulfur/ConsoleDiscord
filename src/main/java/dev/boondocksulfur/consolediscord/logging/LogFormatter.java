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

    /**
     * Matches the "[Thread/LEVEL]:" header of the appender format. Thread names
     * may contain spaces and slashes ("Server thread", "RCON Client /0:0:0:0:0:0:0:1 #2"),
     * so the level is taken after the last slash.
     */
    static final Pattern LOG_LEVEL_PATTERN = Pattern.compile("\\[([^\\]]+)/(\\w+)\\]:?");

    /** Minecraft formatting codes (§6, §l, §x§f§f…) and ANSI escape sequences. */
    private static final Pattern COLOR_CODES = Pattern.compile("§[0-9A-FK-ORXa-fk-orx]|\u001B\\[[0-9;]*[A-Za-z]");

    /** URLs are left as they are: Discord doesn't apply markdown inside links. */
    private static final Pattern URL = Pattern.compile("https?://\\S+");

    /** Markdown that only takes effect at the start of a line (quote, heading, list, subtext). */
    private static final Pattern LINE_START_MARKDOWN = Pattern.compile("(?m)^(\\s*)([>#-])");

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
     * Embed descriptions may hold 4096 characters; leave some headroom.
     */
    private static final int MAX_DESCRIPTION_LENGTH = 4000;

    private static final String TRUNCATED_MARKER = "*...truncated*";

    /**
     * Maximum length of a Discord message.
     */
    private static final int MAX_MESSAGE_LENGTH = 2000;

    /**
     * Formats log lines as code blocks, split into as many messages as needed
     * so no line is dropped. Each message stays within Discord's 2000 character limit.
     *
     * @param lines The log lines to format
     * @return The messages ready for Discord, empty if there is nothing to send
     */
    public List<String> formatAsCodeBlocks(List<String> lines) {
        List<String> messages = new ArrayList<>();
        // Room for the opening and closing fences
        int maxContent = MAX_MESSAGE_LENGTH - 6;
        StringBuilder content = new StringBuilder();

        for (String line : lines) {
            String plain = stripColorCodes(line);
            String formattedLine = escapeCodeBlock(useEmojis ? addEmojiToLine(plain) : plain);

            // Truncate single lines that could never fit into one message
            if (formattedLine.length() > maxContent) {
                formattedLine = formattedLine.substring(0, maxContent - 2) + "…\n";
            }

            if (content.length() + formattedLine.length() > maxContent) {
                messages.add("```" + content + "```");
                content.setLength(0);
            }
            content.append(formattedLine);
        }

        if (!content.isEmpty()) {
            messages.add("```" + content + "```");
        }
        return messages;
    }

    /**
     * Formats log lines as a single code block message. Lines that don't fit
     * into one message are left out; prefer {@link #formatAsCodeBlocks(List)}.
     *
     * @param lines The log lines to format
     * @return The first message, or an empty string if there is nothing to send
     */
    public String formatAsCodeBlock(List<String> lines) {
        List<String> messages = formatAsCodeBlocks(lines);
        return messages.isEmpty() ? "" : messages.get(0);
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

        for (List<LogEntry> batch : splitIntoBatches(entries)) {
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
     * Splits entries into embed batches of at most embedBatchSize entries whose
     * text fits into one description, so a long entry (e.g. a stack trace)
     * starts a new embed instead of pushing the following entries out.
     */
    private List<List<LogEntry>> splitIntoBatches(List<LogEntry> entries) {
        int room = MAX_DESCRIPTION_LENGTH;
        List<List<LogEntry>> batches = new ArrayList<>();
        List<LogEntry> batch = new ArrayList<>();
        int batchChars = 0;

        for (LogEntry entry : entries) {
            int length = formatEntry(entry).length();
            if (!batch.isEmpty() && (batch.size() >= embedBatchSize || batchChars + length > room)) {
                batches.add(batch);
                batch = new ArrayList<>();
                batchChars = 0;
            }
            batch.add(entry);
            batchChars += length;
        }
        if (!batch.isEmpty()) {
            batches.add(batch);
        }
        return batches;
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
            String formatted = formatEntry(entry);

            // Discord embed description limit is 4096
            if (description.length() + formatted.length() > MAX_DESCRIPTION_LENGTH) {
                // Only a single oversized entry gets here (see splitIntoBatches):
                // cut it instead of leaving it out entirely
                int keep = MAX_DESCRIPTION_LENGTH - description.length() - TRUNCATED_MARKER.length();
                if (keep > 0) {
                    description.append(formatted, 0, Math.min(keep, formatted.length()));
                }
                description.append(TRUNCATED_MARKER);
                break;
            }
            description.append(formatted);
        }

        embed.setDescription(description.toString());
        return embed;
    }

    /**
     * Formats one entry: level emoji and badge, followed by the full console
     * line (time, thread and level as in the server log).
     */
    private String formatEntry(LogEntry entry) {
        String emoji = useEmojis ? getEmojiForLevel(entry.level) : "";
        String text = neutralizeMaskedLinks(escapeMarkdown(stripColorCodes(entry.line)));
        return String.format("%s `%s` %s\n", emoji, entry.level.name(), text);
    }

    /**
     * Removes Minecraft color codes and ANSI sequences, which Discord would
     * show as raw characters.
     */
    static String stripColorCodes(String text) {
        return COLOR_CODES.matcher(text).replaceAll("");
    }

    /**
     * Escapes markdown so the console line is shown literally, e.g.
     * "dark_oak_button" instead of an italic "oak". URLs are kept intact.
     */
    static String escapeMarkdown(String text) {
        StringBuilder out = new StringBuilder(text.length() + 16);
        Matcher url = URL.matcher(text);
        int pos = 0;
        while (url.find()) {
            appendEscaped(out, text.substring(pos, url.start()));
            out.append(url.group());
            pos = url.end();
        }
        appendEscaped(out, text.substring(pos));
        return LINE_START_MARKDOWN.matcher(out).replaceAll("$1\\\\$2");
    }

    private static void appendEscaped(StringBuilder out, String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\' || c == '*' || c == '_' || c == '~' || c == '|' || c == '`') {
                out.append('\\');
            }
            out.append(c);
        }
    }

    /**
     * Breaks up "[text](url)" with a zero-width space so log content (e.g.
     * player chat) can't render as a disguised link. Invisible otherwise.
     */
    private static String neutralizeMaskedLinks(String text) {
        return text.replace("](", "]\u200B(");
    }

    /**
     * Parses a log line into a LogEntry.
     */
    private LogEntry parseLogLine(String line) {
        Matcher matcher = LOG_LEVEL_PATTERN.matcher(line);

        if (matcher.find()) {
            return new LogEntry(parseLevel(matcher.group(2)), line);
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
            return line.substring(0, matcher.end()) + " " + emoji + " " + line.substring(matcher.end()).stripLeading();
        }

        return line;
    }

    /**
     * Breaks up backtick runs so log content (e.g. player chat) cannot close
     * the surrounding code block and inject formatting or mentions.
     *
     * @param text Text that will be placed inside a code block
     * @return The text with a zero-width space after every backtick
     */
    public static String escapeCodeBlock(String text) {
        return text.replace("`", "`\u200B");
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
        /** The full console line as written by the appender (incl. time and thread). */
        final String line;

        LogEntry(Level level, String line) {
            this.level = level;
            this.line = line;
        }
    }
}
