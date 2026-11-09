package dev.boondocksulfur.consolediscord.listener;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AliasResolutionTest {

    private static final Map<String, String> ALIASES = Map.of("tps", "spark tps", "save", "save-all");

    @Test
    void replacesAliasWithoutArguments() {
        assertEquals("spark tps", DiscordListener.resolveAlias(ALIASES, "tps"));
    }

    @Test
    void keepsArguments() {
        assertEquals("spark tps --verbose", DiscordListener.resolveAlias(ALIASES, "tps --verbose"));
    }

    @Test
    void isCaseInsensitive() {
        assertEquals("save-all flush", DiscordListener.resolveAlias(ALIASES, "SAVE flush"));
    }

    @Test
    void onlyMatchesWholeFirstWord() {
        assertEquals("tpsx", DiscordListener.resolveAlias(ALIASES, "tpsx"));
        assertEquals("say tps", DiscordListener.resolveAlias(ALIASES, "say tps"));
    }
}
