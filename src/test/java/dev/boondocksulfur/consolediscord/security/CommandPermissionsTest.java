package dev.boondocksulfur.consolediscord.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandPermissionsTest {

    private static final CommandPermissions PERMISSIONS = new CommandPermissions(
            Map.of("100", List.of("*")),
            Map.of("mod", List.of("list", "/Kick"), "helper", List.of("tps")),
            List.of("list"));

    @AfterEach
    void reset() {
        CommandSecurity.configure(true, List.of());
        CommandSecurity.setLabelResolver(null);
        CommandSecurity.setAliasExpander(null);
    }

    @Test
    void userEntryWins() {
        assertEquals(Set.of("*"), PERMISSIONS.resolve("100", List.of()));
    }

    @Test
    void rolesAreCombinedAndNormalized() {
        assertEquals(Set.of("list", "kick", "tps"), PERMISSIONS.resolve("200", List.of("mod", "helper")));
    }

    @Test
    void defaultsApplyWithoutMatch() {
        assertEquals(Set.of("list"), PERMISSIONS.resolve("300", List.of("other")));
    }

    @Test
    void restrictionAppliesToBaseCommand() {
        Set<String> mod = Set.of("list", "kick");
        assertTrue(CommandSecurity.isSafeCommand("kick Notch", mod));
        assertFalse(CommandSecurity.isSafeCommand("ban Notch", mod));
        assertTrue(CommandSecurity.isSafeCommand("ban Notch", Set.of("*")));
    }

    @Test
    void restrictionFollowsServerAliases() {
        CommandSecurity.setLabelResolver(label -> label.equals("tp") ? "teleport" : null);
        assertTrue(CommandSecurity.isSafeCommand("tp Notch", Set.of("teleport")));
        assertFalse(CommandSecurity.isSafeCommand("tp Notch", Set.of("list")));
    }

    @Test
    void restrictionAppliesToNestedAndAliasCommands() {
        assertFalse(CommandSecurity.isSafeCommand("execute as @a run ban Notch", Set.of("execute", "say")));
        assertTrue(CommandSecurity.isSafeCommand("execute as @a run say hi", Set.of("execute", "say")));
        CommandSecurity.setAliasExpander(label -> label.equals("b") ? List.of("ban $1") : null);
        assertFalse(CommandSecurity.isSafeCommand("b Notch", Set.of("b")));
    }

    @Test
    void restrictionAppliesEvenWithSecurityDisabled() {
        CommandSecurity.configure(false, List.of());
        assertFalse(CommandSecurity.isSafeCommand("ban Notch", Set.of("list")));
        assertTrue(CommandSecurity.isSafeCommand("stop", null));
    }
}
