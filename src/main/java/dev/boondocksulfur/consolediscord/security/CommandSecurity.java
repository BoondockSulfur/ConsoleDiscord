package dev.boondocksulfur.consolediscord.security;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

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
    private static final Set<String> DEFAULT_BLOCKED_COMMANDS = Set.of(
            "op", "deop", "stop", "restart", "reload", "ban-ip"
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
     * Resolves a command label to the command's primary name, so aliases
     * registered on the server ("rl" for "reload") hit the same blocklist
     * entry. Returns null for unknown labels. Default: no resolution.
     */
    private static final AtomicReference<Function<String, String>> labelResolver =
            new AtomicReference<>(label -> null);

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
     * Sets the resolver used to map command aliases to their primary name.
     *
     * @param resolver Maps a lower-case label to the primary command name, or null if unknown
     */
    public static void setLabelResolver(Function<String, String> resolver) {
        labelResolver.set(resolver != null ? resolver : label -> null);
    }

    /**
     * Checks if a command is safe to execute from Discord.
     *
     * @param command The command to check (without leading slash)
     * @return true if the command is safe, false if it's blocked
     */
    public static boolean isSafeCommand(String command) {
        String baseCommand = getBaseCommand(command);
        if (baseCommand.isEmpty()) {
            return false;
        }

        // Namespaced commands (minecraft:op, essentials:op, anyplugin:cmd) are
        // always blocked — otherwise every blocklist entry could be bypassed
        // via its namespaced form.
        if (baseCommand.contains(":")) {
            return false;
        }

        // "execute ... run <command>" and "return run <command>" execute the
        // nested command, so every nested command has to pass the same checks.
        if (baseCommand.equals("execute") || baseCommand.equals("return")) {
            String[] tokens = normalize(command).split("\\s+");
            for (int i = 1; i < tokens.length - 1; i++) {
                if (tokens[i].equals("run")
                        && !isSafeCommand(String.join(" ", Arrays.copyOfRange(tokens, i + 1, tokens.length)))) {
                    return false;
                }
            }
        }

        if (!securityEnabled.get()) {
            return true;
        }

        Set<String> blocked = blockedCommands.get();
        if (blocked.contains(baseCommand)) {
            return false;
        }

        String primaryName;
        try {
            primaryName = labelResolver.get().apply(baseCommand);
        } catch (RuntimeException e) {
            // Fail closed: if the alias can't be resolved, don't risk a bypass.
            return false;
        }
        return primaryName == null || !blocked.contains(primaryName.toLowerCase(Locale.ROOT));
    }

    private static String normalize(String command) {
        String normalized = command.trim().toLowerCase(Locale.ROOT);
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }

    /**
     * Gets the base command name from a full command string.
     * Leading slashes are stripped so "/op" and "op" resolve to the same name.
     *
     * @param command The full command string
     * @return The base command name
     */
    public static String getBaseCommand(String command) {
        if (command == null || command.trim().isEmpty()) {
            return "";
        }
        String normalized = normalize(command);
        if (normalized.isEmpty()) {
            return "";
        }
        return normalized.split("\\s+")[0];
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
