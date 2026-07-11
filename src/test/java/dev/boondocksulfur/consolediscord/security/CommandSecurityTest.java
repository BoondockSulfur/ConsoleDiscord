package dev.boondocksulfur.consolediscord.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link CommandSecurity}.
 * Each test reconfigures the static state so order does not matter.
 */
class CommandSecurityTest {

    @BeforeEach
    void resetToDefaults() {
        // Empty list -> falls back to the default blocked set.
        CommandSecurity.configure(true, List.of());
    }

    @Test
    void defaultBlockedCommandsAreRejected() {
        assertFalse(CommandSecurity.isSafeCommand("op Notch"));
        assertFalse(CommandSecurity.isSafeCommand("deop Notch"));
        assertFalse(CommandSecurity.isSafeCommand("stop"));
        assertFalse(CommandSecurity.isSafeCommand("restart"));
        assertFalse(CommandSecurity.isSafeCommand("reload confirm"));
        assertFalse(CommandSecurity.isSafeCommand("ban-ip 1.2.3.4"));
    }

    @Test
    void normalCommandsAreAllowed() {
        assertTrue(CommandSecurity.isSafeCommand("time set day"));
        assertTrue(CommandSecurity.isSafeCommand("say hello"));
    }

    @Test
    void blockedCheckIsCaseInsensitive() {
        assertFalse(CommandSecurity.isSafeCommand("OP Notch"));
        assertFalse(CommandSecurity.isSafeCommand("  DeOp Notch  "));
    }

    @Test
    void namespacePrefixesAreAlwaysBlocked() {
        assertFalse(CommandSecurity.isSafeCommand("minecraft:kill @a"));
        assertFalse(CommandSecurity.isSafeCommand("bukkit:reload"));
        assertFalse(CommandSecurity.isSafeCommand("spigot:tps"));
        assertFalse(CommandSecurity.isSafeCommand("paper:version"));
    }

    @Test
    void arbitraryPluginNamespacesAreBlocked() {
        // Any namespaced form would otherwise bypass the blocklist entry.
        assertFalse(CommandSecurity.isSafeCommand("essentials:op Notch"));
        assertFalse(CommandSecurity.isSafeCommand("someplugin:stop"));
        assertFalse(CommandSecurity.isSafeCommand("x:say hello"));
    }

    @Test
    void leadingSlashesCannotBypassBlocklist() {
        assertFalse(CommandSecurity.isSafeCommand("/op Notch"));
        assertFalse(CommandSecurity.isSafeCommand("//op Notch"));
        assertFalse(CommandSecurity.isSafeCommand(" /minecraft:op Notch"));
        // Harmless commands stay allowed with a leading slash.
        assertTrue(CommandSecurity.isSafeCommand("/say hello"));
    }

    @Test
    void namespacePrefixesBlockedEvenWhenSecurityDisabled() {
        CommandSecurity.configure(false, List.of());
        assertFalse(CommandSecurity.isSafeCommand("minecraft:kill @a"));
        assertFalse(CommandSecurity.isSafeCommand("essentials:op Notch"));
        // ...but op is no longer blocked when security is off.
        assertTrue(CommandSecurity.isSafeCommand("op Notch"));
    }

    @Test
    void customBlockListReplacesDefaults() {
        CommandSecurity.configure(true, List.of("stop", "restart"));
        assertFalse(CommandSecurity.isSafeCommand("stop"));
        assertFalse(CommandSecurity.isSafeCommand("restart now"));
        // op is not in the custom list, so it is now allowed.
        assertTrue(CommandSecurity.isSafeCommand("op Notch"));
    }

    @Test
    void nullAndEmptyAreNotSafe() {
        assertFalse(CommandSecurity.isSafeCommand(null));
        assertFalse(CommandSecurity.isSafeCommand(""));
        assertFalse(CommandSecurity.isSafeCommand("   "));
    }

    @Test
    void getBaseCommandExtractsFirstToken() {
        assertEquals("gamemode", CommandSecurity.getBaseCommand("gamemode creative Notch"));
        assertEquals("stop", CommandSecurity.getBaseCommand("  STOP  "));
        assertEquals("op", CommandSecurity.getBaseCommand("/op Notch"));
        assertEquals("", CommandSecurity.getBaseCommand("///"));
        assertEquals("", CommandSecurity.getBaseCommand(null));
    }
}
