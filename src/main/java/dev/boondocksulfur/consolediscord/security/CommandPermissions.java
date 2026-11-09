package dev.boondocksulfur.consolediscord.security;

import java.util.*;

/**
 * Which commands a whitelisted Discord user may run, configured per user
 * ID and per role ID. Entries are base command names; "*" allows every
 * command that passes the global command filter.
 */
public final class CommandPermissions {

    private final Map<String, Set<String>> users;
    private final Map<String, Set<String>> roles;
    private final Set<String> defaults;

    /**
     * @param users Commands per Discord user ID
     * @param roles Commands per Discord role ID
     * @param defaults Commands for whitelisted users without a matching entry
     */
    public CommandPermissions(Map<String, ? extends Collection<String>> users,
                              Map<String, ? extends Collection<String>> roles,
                              Collection<String> defaults) {
        this.users = normalize(users);
        this.roles = normalize(roles);
        this.defaults = normalizeSet(defaults);
    }

    private static Map<String, Set<String>> normalize(Map<String, ? extends Collection<String>> source) {
        Map<String, Set<String>> result = new HashMap<>();
        if (source != null) {
            source.forEach((id, commands) -> result.put(id.trim(), normalizeSet(commands)));
        }
        return Map.copyOf(result);
    }

    private static Set<String> normalizeSet(Collection<String> commands) {
        Set<String> result = new HashSet<>();
        if (commands != null) {
            for (String command : commands) {
                if (command == null || command.isBlank()) {
                    continue;
                }
                String name = command.trim().toLowerCase(Locale.ROOT);
                while (name.startsWith("/")) {
                    name = name.substring(1);
                }
                result.add(name);
            }
        }
        return Set.copyOf(result);
    }

    /**
     * Combines the entries of the user and all of their roles; without any
     * matching entry the defaults apply.
     *
     * @param userId The Discord user ID
     * @param roleIds The user's role IDs in the guild
     * @return The permitted base commands ("*" = all)
     */
    public Set<String> resolve(String userId, Collection<String> roleIds) {
        Set<String> result = new HashSet<>();
        boolean matched = false;
        Set<String> own = users.get(userId);
        if (own != null) {
            result.addAll(own);
            matched = true;
        }
        if (roleIds != null) {
            for (String roleId : roleIds) {
                Set<String> commands = roles.get(roleId);
                if (commands != null) {
                    result.addAll(commands);
                    matched = true;
                }
            }
        }
        return matched ? Set.copyOf(result) : defaults;
    }
}
