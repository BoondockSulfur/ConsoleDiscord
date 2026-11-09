package dev.boondocksulfur.consolediscord.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for allowlist mode, commands.yml aliases and the thread-free pre-check.
 */
class CommandSecurityAdvancedTest {

    @BeforeEach
    void reset() {
        CommandSecurity.configure(true, List.of());
        CommandSecurity.setLabelResolver(null);
        CommandSecurity.setAliasExpander(null);
    }

    @AfterEach
    void cleanUp() {
        reset();
    }

    @Test
    void commandsYmlAliasToBlockedCommandIsRejected() {
        Map<String, List<String>> aliases = Map.of("rl", List.of("reload confirm"));
        CommandSecurity.setAliasExpander(aliases::get);
        assertFalse(CommandSecurity.isSafeCommand("rl"));
        assertTrue(CommandSecurity.isSafeCommand("time set day"));
    }

    @Test
    void commandsYmlPassThroughAliasChecksArguments() {
        Map<String, List<String>> aliases = Map.of("x", List.of("$1-"));
        CommandSecurity.setAliasExpander(aliases::get);
        assertFalse(CommandSecurity.isSafeCommand("x op Notch"));
        assertTrue(CommandSecurity.isSafeCommand("x say hi"));
    }

    @Test
    void commandsYmlAliasWithSeveralLinesNeedsAllSafe() {
        Map<String, List<String>> aliases = Map.of("combo", List.of("say bye", "stop"));
        CommandSecurity.setAliasExpander(aliases::get);
        assertFalse(CommandSecurity.isSafeCommand("combo"));
    }

    @Test
    void recursiveAliasIsRejected() {
        Map<String, List<String>> aliases = Map.of("loop", List.of("loop"));
        CommandSecurity.setAliasExpander(aliases::get);
        assertFalse(CommandSecurity.isSafeCommand("loop"));
    }

    @Test
    void failingResolverFailsClosed() {
        CommandSecurity.setAliasExpander(label -> {
            throw new IllegalStateException("command map not readable");
        });
        assertFalse(CommandSecurity.isSafeCommand("say hi"));
    }

    @Test
    void allowlistOnlyPermitsListedCommands() {
        CommandSecurity.configure(true, CommandSecurity.Mode.ALLOWLIST, List.of(), List.of("list", "say"));
        assertTrue(CommandSecurity.isSafeCommand("list"));
        assertTrue(CommandSecurity.isSafeCommand("say hello"));
        assertFalse(CommandSecurity.isSafeCommand("time set day"));
        assertFalse(CommandSecurity.isSafeCommand("op Notch"));
    }

    @Test
    void allowlistAcceptsServerAliasOfAllowedCommand() {
        CommandSecurity.configure(true, CommandSecurity.Mode.ALLOWLIST, List.of(), List.of("teleport"));
        CommandSecurity.setLabelResolver(label -> label.equals("tp") ? "teleport" : null);
        assertTrue(CommandSecurity.isSafeCommand("tp Notch Steve"));
    }

    @Test
    void allowlistChecksNestedExecute() {
        CommandSecurity.configure(true, CommandSecurity.Mode.ALLOWLIST, List.of(), List.of("execute", "say"));
        assertTrue(CommandSecurity.isSafeCommand("execute as @a run say hi"));
        assertFalse(CommandSecurity.isSafeCommand("execute as @a run op @s"));
    }

    @Test
    void preCheckRejectsObviousButDefersAliases() {
        CommandSecurity.setAliasExpander(label -> {
            throw new AssertionError("pre-check must not read server state");
        });
        assertFalse(CommandSecurity.passesPreCheck("op Notch"));
        assertFalse(CommandSecurity.passesPreCheck("minecraft:op Notch"));
        assertFalse(CommandSecurity.passesPreCheck("execute run stop"));
        assertTrue(CommandSecurity.passesPreCheck("rl"));
    }

    @Test
    void preCheckDefersAllowlistDecision() {
        CommandSecurity.configure(true, CommandSecurity.Mode.ALLOWLIST, List.of(), List.of("teleport"));
        assertTrue(CommandSecurity.passesPreCheck("tp Notch"));
        assertFalse(CommandSecurity.passesPreCheck("bukkit:tp Notch"));
    }

    @Test
    void argumentSubstitution() {
        String[] args = {"a", "b", "c"};
        assertEquals("say b", CommandSecurity.substituteArguments("say $2", args));
        assertEquals("say b c", CommandSecurity.substituteArguments("say $2-", args));
        assertEquals("give a", CommandSecurity.substituteArguments("give $$1", args));
        assertEquals("say ", CommandSecurity.substituteArguments("say $5", args));
        assertNull(CommandSecurity.substituteArguments("say $x", args));
    }
}
