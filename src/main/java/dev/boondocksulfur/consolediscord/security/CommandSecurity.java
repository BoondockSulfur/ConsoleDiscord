package dev.boondocksulfur.consolediscord.security;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Handles security checks for commands executed from Discord.
 * Provides a configurable blacklist system to prevent execution of dangerous commands.
 * Thread-safe: uses atomic references so config reloads don't cause inconsistent reads.
 */
public class CommandSecurity {

    /**
     * Default commands that are recommended to be blocked for security reasons.
     * These can be overridden via config.yml.
     */
    private static final Set<String> DEFAULT_BLOCKED_COMMANDS = Set.of("op", "deop");

    /**
     * Command prefixes that are always blocked to prevent plugin manipulation.
     * These cannot be configured and are always enforced.
     */
    private static final Set<String> BLOCKED_PREFIXES = Set.of(
            "minecraft:",
            "bukkit:",
            "spigot:",
            "paper:"
    );

    /**
     * Configurable set of blocked commands loaded from config.yml.
     * Uses AtomicReference for thread-safe swapping during config reload.
     */
    private static final AtomicReference<Set<String>> blockedCommands =
            new AtomicReference<>(DEFAULT_BLOCKED_COMMANDS);

    /**
     * Whether command security is enabled at all.
     */
    private static final AtomicBoolean securityEnabled = new AtomicBoolean(true);

    /**
     * Loads command security settings from configuration.
     *
     * @param enabled Whether command security is enabled
     * @param configBlockedCommands List of commands to block from config
     */
    public static void configure(boolean enabled, List<String> configBlockedCommands) {
        securityEnabled.set(enabled);

        if (configBlockedCommands == null || configBlockedCommands.isEmpty()) {
            blockedCommands.set(DEFAULT_BLOCKED_COMMANDS);
        } else {
            Set<String> newSet = new HashSet<>();
            for (String cmd : configBlockedCommands) {
                newSet.add(cmd.toLowerCase(Locale.ROOT).trim());
            }
            blockedCommands.set(Collections.unmodifiableSet(newSet));
        }
    }

    /**
     * Checks if a command is safe to execute from Discord.
     *
     * @param command The command to check (without leading slash)
     * @return true if the command is safe, false if it's blocked
     */
    public static boolean isSafeCommand(String command) {
        if (command == null || command.trim().isEmpty()) {
            return false;
        }

        String normalized = command.trim().toLowerCase(Locale.ROOT);
        String baseCommand = normalized.split(" ")[0];

        // If security is disabled, only check prefixes
        if (!securityEnabled.get()) {
            return !isBlockedPrefix(baseCommand);
        }

        // Check blocked commands
        if (blockedCommands.get().contains(baseCommand)) {
            return false;
        }

        // Check blocked prefixes (always enforced)
        if (isBlockedPrefix(baseCommand)) {
            return false;
        }

        return true;
    }

    /**
     * Checks if a command has a blocked namespace prefix.
     *
     * @param baseCommand The base command to check
     * @return true if the command has a blocked prefix
     */
    private static boolean isBlockedPrefix(String baseCommand) {
        for (String prefix : BLOCKED_PREFIXES) {
            if (baseCommand.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Gets the base command name from a full command string.
     *
     * @param command The full command string
     * @return The base command name
     */
    public static String getBaseCommand(String command) {
        if (command == null || command.trim().isEmpty()) {
            return "";
        }
        return command.trim().split(" ")[0].toLowerCase(Locale.ROOT);
    }

    /**
     * Gets a set of all currently blocked commands for display purposes.
     *
     * @return An unmodifiable set of blocked commands
     */
    public static Set<String> getBlockedCommands() {
        return Set.copyOf(blockedCommands.get());
    }

    /**
     * Gets the default recommended blocked commands.
     *
     * @return An unmodifiable set of default blocked commands
     */
    public static Set<String> getDefaultBlockedCommands() {
        return Set.copyOf(DEFAULT_BLOCKED_COMMANDS);
    }

    /**
     * Checks if command security is currently enabled.
     *
     * @return true if security is enabled
     */
    public static boolean isSecurityEnabled() {
        return securityEnabled.get();
    }
}
