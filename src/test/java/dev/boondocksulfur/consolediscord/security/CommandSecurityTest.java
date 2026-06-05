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
        // Empty list -> falls back to the default blocked set (op/deop).
        CommandSecurity.configure(true, List.of());
    }

    @Test
    void defaultBlockedCommandsAreRejected() {
        assertFalse(CommandSecurity.isSafeCommand("op Notch"));
        assertFalse(CommandSecurity.isSafeCommand("deop Notch"));
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
    void namespacePrefixesBlockedEvenWhenSecurityDisabled() {
        CommandSecurity.configure(false, List.of());
        assertFalse(CommandSecurity.isSafeCommand("minecraft:kill @a"));
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
        assertEquals("", CommandSecurity.getBaseCommand(null));
    }
}
