package dev.boondocksulfur.consolediscord.config;

import org.bukkit.configuration.ConfigurationSection;

import java.util.List;

/**
 * Adds options introduced by an update to an existing config without
 * touching values or entries the admin has set or removed.
 */
public final class ConfigMerger {

    /**
     * Sections whose entries belong to the admin (aliases, permissions,
     * categories, patterns). Their default entries are only added when the
     * whole section is missing, otherwise a deleted entry would come back
     * on every start.
     */
    private static final List<String> USER_MAP_SECTIONS = List.of(
            "command-aliases.aliases",
            "command-permissions.users",
            "command-permissions.roles",
            "log-categories.categories",
            "log-categories.patterns"
    );

    private ConfigMerger() {
    }

    /**
     * Copies every key missing in {@code user} from {@code defaults}.
     *
     * @param user The admin's config (modified in place)
     * @param defaults The default config bundled in the jar
     * @return true if anything was added
     */
    public static boolean mergeMissing(ConfigurationSection user, ConfigurationSection defaults) {
        boolean changed = false;
        for (String key : defaults.getKeys(true)) {
            if (isInsideUserMap(user, key)) {
                continue;
            }
            if (!user.contains(key, true)) {
                user.set(key, defaults.get(key));
                changed = true;
            }
        }
        return changed;
    }

    private static boolean isInsideUserMap(ConfigurationSection user, String key) {
        for (String section : USER_MAP_SECTIONS) {
            if (key.startsWith(section + ".") && user.contains(section, true)) {
                return true;
            }
        }
        return false;
    }
}
