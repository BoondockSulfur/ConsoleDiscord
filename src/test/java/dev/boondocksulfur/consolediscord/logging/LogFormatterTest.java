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

    // ---------------- Level detection & escaping ----------------

    @Test
    void embedDetectsLevelForThreadNamesWithSpaces() {
        LogFormatter formatter = new LogFormatter(true, false, 10);
        MessageEmbed embed = formatter.formatAsEmbedGroups(List.of(ERROR_LINE)).get(0).get(0);
        assertEquals(java.awt.Color.RED, embed.getColor());
        assertTrue(embed.getDescription().contains("`ERROR` 12:00:01 [Server thread/ERROR]: Something broke"),
                embed.getDescription());
    }

    @Test
    void embedDetectsWarnFromSchedulerThread() {
        LogFormatter formatter = new LogFormatter(true, false, 10);
        String line = "12:00:02 [Craft Scheduler Thread - 3/WARN]: Slow task\n";
        MessageEmbed embed = formatter.formatAsEmbedGroups(List.of(line)).get(0).get(0);
        assertTrue(embed.getDescription().contains("`WARN` 12:00:02 [Craft Scheduler Thread - 3/WARN]: Slow task"),
                embed.getDescription());
    }

    @Test
    void embedKeepsTheFullConsoleLineLikeVersion210() {
        LogFormatter formatter = new LogFormatter(true, true, 10);
        String description = formatter.formatAsEmbedGroups(List.of(INFO_LINE)).get(0).get(0).getDescription();
        assertEquals("ℹ️ `INFO` 12:00:00 [Server thread/INFO]: Server started\n\n", description);
    }

    @Test
    void embedNeutralizesMaskedLinksFromLogContent() {
        LogFormatter formatter = new LogFormatter(true, false, 10);
        String line = "12:00:03 [Async Chat Thread - #0/INFO]: <Steve> [free](https://x.y)\n";
        String description = formatter.formatAsEmbedGroups(List.of(line)).get(0).get(0).getDescription();
        assertFalse(description.contains("](https://x.y)"), description);
        assertTrue(description.contains("https://x.y"), description);
    }

    @Test
    void oversizedEmbedLineIsCutNotDropped() {
        LogFormatter formatter = new LogFormatter(true, false, 10);
        String hugeLine = "12:00:00 [Server thread/ERROR]: " + "q".repeat(6000) + "\n";
        MessageEmbed embed = formatter.formatAsEmbedGroups(List.of(hugeLine)).get(0).get(0);
        String description = embed.getDescription();
        assertTrue(description.contains("`ERROR` 12:00:00 [Server thread/ERROR]: qqq"), description.substring(0, 60));
        assertTrue(description.endsWith("*...truncated*"));
        assertTrue(description.length() <= 4096, "was " + description.length());
    }

    @Test
    void codeBlockCannotBeClosedByLogContent() {
        String line = "12:00:04 [Async Chat Thread - #0/INFO]: <Steve> ``` @everyone\n";
        String result = plainFormatter().formatAsCodeBlock(List.of(line));
        String inner = result.substring(3, result.length() - 3);
        assertFalse(inner.contains("```"), inner);
    }

    @Test
    void codeBlocksSplitInsteadOfDroppingLines() {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            lines.add("12:00:00 [Server thread/INFO]: line-" + i + " " + "x".repeat(80) + "\n");
        }
        List<String> messages = plainFormatter().formatAsCodeBlocks(lines);
        assertTrue(messages.size() > 1);
        String all = String.join("", messages);
        for (int i = 0; i < 50; i++) {
            assertTrue(all.contains("line-" + i + " "), "missing line-" + i);
        }
        for (String message : messages) {
            assertTrue(message.length() <= 2000, "was " + message.length());
            assertTrue(message.startsWith("```") && message.endsWith("```"));
        }
    }

    @Test
    void longEntryStartsNewEmbedInsteadOfDroppingFollowingLines() {
        LogFormatter formatter = new LogFormatter(true, false, 10);
        List<String> lines = new ArrayList<>();
        lines.add("12:00:00 [Server thread/INFO]: before\n");
        lines.add("12:00:01 [Server thread/ERROR]: trace " + "t".repeat(3500) + "\n");
        lines.add("12:00:02 [Server thread/INFO]: after-1\n");
        lines.add("12:00:03 [Server thread/INFO]: after-2\n");

        StringBuilder all = new StringBuilder();
        for (List<MessageEmbed> group : formatter.formatAsEmbedGroups(lines)) {
            for (MessageEmbed embed : group) {
                assertTrue(embed.getDescription().length() <= 4096);
                all.append(embed.getDescription());
            }
        }
        assertTrue(all.indexOf("before") >= 0);
        assertTrue(all.indexOf("trace ttt") >= 0);
        assertTrue(all.indexOf("after-1") >= 0, "line after the long entry was dropped");
        assertTrue(all.indexOf("after-2") >= 0, "line after the long entry was dropped");
        assertEquals(-1, all.indexOf("truncated"));
    }

    @Test
    void threadNamesWithSlashesKeepTimeAndLevel() {
        LogFormatter formatter = new LogFormatter(true, false, 10);
        String line = "23:46:20 [RCON Client /0:0:0:0:0:0:0:1 #2/WARN]: Thread shutting down\n";
        String description = formatter.formatAsEmbedGroups(List.of(line)).get(0).get(0).getDescription();
        assertTrue(description.startsWith(" `WARN` 23:46:20 [RCON Client /0:0:0:0:0:0:0:1 #2/WARN]: Thread shutting down"),
                description);
    }

    @Test
    void markdownInLogLinesIsEscaped() {
        assertEquals("dark\\_oak\\_button \\*x\\* \\~\\~y\\~\\~ \\|\\|z\\|\\| \\`c\\` C:\\\\tmp",
                LogFormatter.escapeMarkdown("dark_oak_button *x* ~~y~~ ||z|| `c` C:\\tmp"));
    }

    @Test
    void urlsAreNotEscaped() {
        String url = "https://repo.example/com/google/error_prone_annotations-2.27.0.jar";
        assertEquals("Downloading " + url + " to error\\_prone",
                LogFormatter.escapeMarkdown("Downloading " + url + " to error_prone"));
    }

    @Test
    void lineStartMarkdownIsEscaped() {
        assertEquals("first\n\\> quote\n  \\# heading\n\\-# sub",
                LogFormatter.escapeMarkdown("first\n> quote\n  # heading\n-# sub"));
    }

    @Test
    void colorCodesAreRemoved() {
        assertEquals("Mythic ---- Update: https://x.y",
                LogFormatter.stripColorCodes("§x§f§f§0§0§0§0Mythic §6---- §2Update: §bhttps://x.y\u001B[0m"));
    }

    @Test
    void embedShowsUnderscoresLiterally() {
        LogFormatter formatter = new LogFormatter(true, false, 10);
        String line = "07:23:03 [Server thread/INFO]: [ChestProtect] block: §6dark_oak_button\n";
        String description = formatter.formatAsEmbedGroups(List.of(line)).get(0).get(0).getDescription();
        assertTrue(description.contains("block: dark\\_oak\\_button"), description);
    }

    @Test
    void ansiSequencesFromStdoutAreRemoved() {
        LogFormatter formatter = new LogFormatter(true, false, 10);
        List<String> lines = List.of(
                "07:23:34 [Server thread/INFO]: [STDOUT] \u001B[31mＴＩＮＳＭＣ\u001B[0m\n",
                "07:23:34 [Server thread/INFO]: [STDOUT] \u001B[38;5;208m┌┐┌┐\u001B[0m\n");
        String description = formatter.formatAsEmbedGroups(lines).get(0).get(0).getDescription();
        assertFalse(description.contains("\u001B"), description);
        assertFalse(description.contains("[0m"), description);
        assertTrue(description.contains("[STDOUT] ＴＩＮＳＭＣ\n"), description);
        assertTrue(description.contains("[STDOUT] ┌┐┌┐\n"), description);
    }
}
