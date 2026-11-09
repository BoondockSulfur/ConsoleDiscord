package dev.boondocksulfur.consolediscord.security;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * Handles security checks for commands executed from Discord.
 * Supports a blocklist (default) and an allowlist mode.
 * Thread-safe: uses atomic references so config reloads don't cause inconsistent reads.
 */
public class CommandSecurity {

    /**
     * Filter mode: block listed commands, or allow only listed commands.
     */
    public enum Mode {
        BLOCKLIST,
        ALLOWLIST
    }

    /**
     * Default commands that are recommended to be blocked for security reasons.
     * These can be overridden via config.yml.
     */
    private static final Set<String> DEFAULT_BLOCKED_COMMANDS = Set.of(
            "op", "deop", "stop", "restart", "reload", "ban-ip"
    );

    /**
     * Maximum nesting of execute/return/server aliases that is followed;
     * anything deeper is rejected.
     */
    private static final int MAX_DEPTH = 8;

    private static final AtomicReference<Set<String>> blockedCommands =
            new AtomicReference<>(DEFAULT_BLOCKED_COMMANDS);

    private static final AtomicReference<Set<String>> allowedCommands =
            new AtomicReference<>(Set.of());

    private static final AtomicReference<Mode> mode = new AtomicReference<>(Mode.BLOCKLIST);

    /**
     * Whether command security is enabled at all.
     */
    private static final AtomicBoolean securityEnabled = new AtomicBoolean(true);

    /**
     * Resolves a command label to the command's primary name, so aliases
     * registered on the server ("rl" for "reload") hit the same list entry.
     * Returns null for unknown labels. Default: no resolution.
     */
    private static final AtomicReference<Function<String, String>> labelResolver =
            new AtomicReference<>(label -> null);

    /**
     * Returns the command lines a server-side alias from commands.yml expands
     * to (with Bukkit's $1 / $1- placeholders), or null if the label is no
     * such alias. Default: no aliases.
     */
    private static final AtomicReference<Function<String, List<String>>> aliasExpander =
            new AtomicReference<>(label -> null);

    /**
     * Loads command security settings from configuration (blocklist mode).
     *
     * @param enabled Whether command security is enabled
     * @param configBlockedCommands List of commands to block from config
     */
    public static void configure(boolean enabled, List<String> configBlockedCommands) {
        configure(enabled, Mode.BLOCKLIST, configBlockedCommands, List.of());
    }

    /**
     * Loads command security settings from configuration.
     *
     * @param enabled Whether command security is enabled
     * @param newMode Blocklist or allowlist mode
     * @param configBlockedCommands Commands to block (blocklist mode)
     * @param configAllowedCommands Commands to allow (allowlist mode)
     */
    public static void configure(boolean enabled, Mode newMode,
                                 List<String> configBlockedCommands, List<String> configAllowedCommands) {
        securityEnabled.set(enabled);
        mode.set(newMode != null ? newMode : Mode.BLOCKLIST);

        if (configBlockedCommands == null || configBlockedCommands.isEmpty()) {
            blockedCommands.set(DEFAULT_BLOCKED_COMMANDS);
        } else {
            blockedCommands.set(toLowerSet(configBlockedCommands));
        }
        allowedCommands.set(configAllowedCommands == null ? Set.of() : toLowerSet(configAllowedCommands));
    }

    private static Set<String> toLowerSet(List<String> commands) {
        Set<String> set = new HashSet<>();
        for (String cmd : commands) {
            if (cmd != null && !cmd.isBlank()) {
                set.add(cmd.toLowerCase(Locale.ROOT).trim());
            }
        }
        return Collections.unmodifiableSet(set);
    }

    /**
     * Sets the resolver used to map command aliases to their primary name.
     * Must only be consulted on the server thread (see {@link #isSafeCommand}).
     *
     * @param resolver Maps a lower-case label to the primary command name, or null if unknown
     */
    public static void setLabelResolver(Function<String, String> resolver) {
        labelResolver.set(resolver != null ? resolver : label -> null);
    }

    /**
     * Sets the expander for server-side aliases defined in commands.yml.
     *
     * @param expander Maps a lower-case label to its command lines, or null if it is no alias
     */
    public static void setAliasExpander(Function<String, List<String>> expander) {
        aliasExpander.set(expander != null ? expander : label -> null);
    }

    /**
     * Full check whether a command is safe to execute from Discord, including
     * server aliases. Reads the server's command map through the resolvers,
     * so it must run on the server thread.
     *
     * @param command The command to check (without leading slash)
     * @return true if the command is safe, false if it's blocked
     */
    public static boolean isSafeCommand(String command) {
        return check(command, true, 0, null);
    }

    /**
     * Full check including a per-user command restriction: besides the
     * global filter, the command — and every command it runs (execute,
     * aliases) — must be in {@code permitted}. Server thread only.
     *
     * @param command The command to check
     * @param permitted Allowed base commands ("*" = all), or null for no restriction
     * @return true if the command is safe and permitted
     */
    public static boolean isSafeCommand(String command, Set<String> permitted) {
        return check(command, true, 0, permitted);
    }

    /**
     * Quick check that needs no server state and may run on any thread.
     * Only rejects what is blocked for sure (namespaces, direct list hits);
     * a command passing it must still pass {@link #isSafeCommand} on the
     * server thread before it runs.
     *
     * @param command The command to check
     * @return false if the command is certainly blocked
     */
    public static boolean passesPreCheck(String command) {
        return check(command, false, 0, null);
    }

    private static boolean check(String command, boolean resolve, int depth, Set<String> permitted) {
        if (depth > MAX_DEPTH) {
            return false;
        }
        String baseCommand = getBaseCommand(command);
        if (baseCommand.isEmpty()) {
            return false;
        }

        // Namespaced commands (minecraft:op, essentials:op, anyplugin:cmd) are
        // always blocked — otherwise every list entry could be bypassed
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
                        && !check(String.join(" ", Arrays.copyOfRange(tokens, i + 1, tokens.length)),
                                  resolve, depth + 1, permitted)) {
                    return false;
                }
            }
        }

        boolean security = securityEnabled.get();
        boolean allowlist = security && mode.get() == Mode.ALLOWLIST;
        boolean restricted = permitted != null && !permitted.contains("*");
        Set<String> list = allowlist ? allowedCommands.get() : blockedCommands.get();

        if (security && !allowlist && list.contains(baseCommand)) {
            return false;
        }

        if (!resolve) {
            // Without server state an allowlist miss may still be an alias
            // of an allowed command; the server-thread check decides.
            return true;
        }
        if (!security && !restricted) {
            return true;
        }

        // A commands.yml alias runs other command lines: each must pass.
        List<String> expansion;
        String primaryName;
        try {
            expansion = aliasExpander.get().apply(baseCommand);
            primaryName = labelResolver.get().apply(baseCommand);
        } catch (RuntimeException e) {
            // Fail closed: if the alias can't be resolved, don't risk a bypass.
            return false;
        }
        if (expansion != null) {
            if (expansion.isEmpty()) {
                return false;
            }
            String[] args = argumentsOf(command);
            for (String line : expansion) {
                String expanded = substituteArguments(line, args);
                if (expanded == null || !check(expanded, true, depth + 1, permitted)) {
                    return false;
                }
            }
            return true;
        }

        String primary = primaryName != null ? primaryName.toLowerCase(Locale.ROOT) : null;
        if (security) {
            boolean listed = list.contains(baseCommand) || (primary != null && list.contains(primary));
            if (allowlist != listed) {
                return false;
            }
        }
        if (restricted) {
            return permitted.contains(baseCommand) || (primary != null && permitted.contains(primary));
        }
        return true;
    }

    private static String[] argumentsOf(String command) {
        String[] tokens = normalize(command).split("\\s+");
        return Arrays.copyOfRange(tokens, 1, tokens.length);
    }

    /**
     * Replaces Bukkit alias placeholders: $N is the N-th argument, $N- the
     * N-th argument and everything after it, $$N a required argument.
     * Returns null for a malformed placeholder (fail closed).
     */
    static String substituteArguments(String line, String[] args) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < line.length()) {
            char c = line.charAt(i);
            if (c != '$') {
                out.append(c);
                i++;
                continue;
            }
            int j = i + 1;
            if (j < line.length() && line.charAt(j) == '$') {
                j++;
            }
            int digitsStart = j;
            while (j < line.length() && Character.isDigit(line.charAt(j))) {
                j++;
            }
            if (j == digitsStart) {
                return null;
            }
            int index;
            try {
                index = Integer.parseInt(line.substring(digitsStart, j)) - 1;
            } catch (NumberFormatException e) {
                return null;
            }
            boolean rest = j < line.length() && line.charAt(j) == '-';
            if (rest) {
                j++;
            }
            if (index >= 0 && index < args.length) {
                out.append(rest
                        ? String.join(" ", Arrays.copyOfRange(args, index, args.length))
                        : args[index]);
            }
            i = j;
        }
        return out.toString();
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
     * Gets the commands allowed in allowlist mode.
     *
     * @return An unmodifiable set of allowed commands
     */
    public static Set<String> getAllowedCommands() {
        return Set.copyOf(allowedCommands.get());
    }

    /**
     * Gets the current filter mode.
     *
     * @return The mode
     */
    public static Mode getMode() {
        return mode.get();
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
