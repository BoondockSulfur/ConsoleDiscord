package dev.boondocksulfur.consolediscord.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigMergerTest {

    private static YamlConfiguration yaml(String text) throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(text);
        return config;
    }

    @Test
    void addsMissingOptionsAndKeepsValues() throws Exception {
        YamlConfiguration user = yaml("language: de\n");
        YamlConfiguration defaults = yaml("language: en\nmax-commands-per-minute: 5\n");
        assertTrue(ConfigMerger.mergeMissing(user, defaults));
        assertEquals("de", user.getString("language"));
        assertEquals(5, user.getInt("max-commands-per-minute"));
    }

    @Test
    void deletedAdminEntriesDoNotComeBack() throws Exception {
        YamlConfiguration user = yaml("command-aliases:\n  aliases:\n    mine: list\n");
        YamlConfiguration defaults = yaml("command-aliases:\n  aliases:\n    tps: spark tps\n");
        assertFalse(ConfigMerger.mergeMissing(user, defaults));
        assertFalse(user.contains("command-aliases.aliases.tps"));
    }

    @Test
    void missingAdminSectionIsAddedWhole() throws Exception {
        YamlConfiguration user = yaml("language: en\n");
        YamlConfiguration defaults = yaml("command-permissions:\n  enabled: false\n  roles: {}\n  default: ['*']\n");
        assertTrue(ConfigMerger.mergeMissing(user, defaults));
        assertTrue(user.contains("command-permissions.roles"));
        assertEquals("*", user.getStringList("command-permissions.default").get(0));
    }
}
