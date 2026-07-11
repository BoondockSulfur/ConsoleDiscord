package dev.boondocksulfur.consolediscord.logging;

import net.dv8tion.jda.api.entities.MessageEmbed;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link LogFormatter}.
 */
class LogFormatterTest {

    private static final String INFO_LINE = "12:00:00 [Server thread/INFO]: Server started\n";
    private static final String ERROR_LINE = "12:00:01 [Server thread/ERROR]: Something broke\n";

    private LogFormatter plainFormatter() {
        return new LogFormatter(false, false, 10);
    }

    // ---------------- Code blocks ----------------

    @Test
    void codeBlockWrapsLinesInBackticks() {
        String result = plainFormatter().formatAsCodeBlock(List.of(INFO_LINE, ERROR_LINE));
        assertTrue(result.startsWith("```"));
        assertTrue(result.endsWith("```"));
        assertTrue(result.contains("Server started"));
        assertTrue(result.contains("Something broke"));
    }

    @Test
    void emptyInputProducesEmptyString() {
        assertEquals("", plainFormatter().formatAsCodeBlock(List.of()));
    }

    @Test
    void codeBlockNeverExceedsDiscordMessageLimit() {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            lines.add("12:00:00 [Server thread/INFO]: " + "x".repeat(80) + "\n");
        }
        String result = plainFormatter().formatAsCodeBlock(lines);
        assertTrue(result.length() <= 2000, "was " + result.length());
    }

    @Test
    void oversizedSingleLineIsTruncatedInsteadOfProducingEmptyBlock() {
        String hugeLine = "12:00:00 [Server thread/INFO]: " + "y".repeat(3000) + "\n";
        String result = plainFormatter().formatAsCodeBlock(List.of(hugeLine));
        assertFalse(result.isEmpty());
        assertTrue(result.contains("y"));
        assertTrue(result.length() <= 2000, "was " + result.length());
    }

    // ---------------- Embeds ----------------

    @Test
    void embedGroupsRespectBatchSize() {
        LogFormatter formatter = new LogFormatter(true, false, 2);
        List<String> lines = List.of(INFO_LINE, INFO_LINE, INFO_LINE, INFO_LINE, INFO_LINE);

        List<List<MessageEmbed>> groups = formatter.formatAsEmbedGroups(lines);
        int totalEmbeds = groups.stream().mapToInt(List::size).sum();
        // 5 lines with batch size 2 -> 3 embeds
        assertEquals(3, totalEmbeds);
    }

    @Test
    void embedGroupsStayWithinDiscordLimits() {
        LogFormatter formatter = new LogFormatter(true, false, 1);
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            lines.add("12:00:00 [Server thread/INFO]: " + "z".repeat(500) + "\n");
        }

        for (List<MessageEmbed> group : formatter.formatAsEmbedGroups(lines)) {
            assertTrue(group.size() <= 10, "max 10 embeds per message");
            int totalChars = group.stream().mapToInt(MessageEmbed::getLength).sum();
            assertTrue(totalChars <= 6000, "max 6000 chars per message, was " + totalChars);
        }
    }

    @Test
    void embedsAreEmptyForEmptyInput() {
        LogFormatter formatter = new LogFormatter(true, true, 10);
        assertTrue(formatter.formatAsEmbedGroups(List.of()).isEmpty());
    }

    @Test
    void isUsingEmbedsReflectsConfiguration() {
        assertTrue(new LogFormatter(true, false, 10).isUsingEmbeds());
        assertFalse(plainFormatter().isUsingEmbeds());
    }

    @Test
    void emojiIsProvidedForEveryLevel() {
        assertFalse(LogFormatter.getEmojiForLevel(org.apache.logging.log4j.Level.INFO).isEmpty());
        assertFalse(LogFormatter.getEmojiForLevel(org.apache.logging.log4j.Level.ERROR).isEmpty());
        assertFalse(LogFormatter.getEmojiForLevel(org.apache.logging.log4j.Level.FATAL).isEmpty());
    }
}
