package dev.boondocksulfur.consolediscord.i18n;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;

/**
 * Handles internationalization (i18n) for the plugin.
 * Supports multiple languages with fallback to English.
 */
public class Messages {

    private final Plugin plugin;
    private final Map<String, String> messages = new HashMap<>();
    private String language;

    public Messages(Plugin plugin, String language) {
        this.plugin = plugin;
        this.language = language;
        loadMessages();
    }

    /**
     * Loads messages from the language file.
     * Falls back to English if the specified language is not found.
     * Merges new keys from the JAR resource into existing files so that
     * updates adding new message keys are picked up automatically.
     */
    private void loadMessages() {
        String fileName = "messages_" + language + ".yml";
        File langFile = new File(plugin.getDataFolder(), fileName);

        // Create language file from resources if it doesn't exist
        if (!langFile.exists()) {
            plugin.getDataFolder().mkdirs();
            try (InputStream in = plugin.getResource(fileName)) {
                if (in != null) {
                    Files.copy(in, langFile.toPath());
                } else if (!language.equals("en")) {
                    // Fallback to English
                    plugin.getLogger().warning("Language file " + fileName + " not found, falling back to English");
                    this.language = "en";
                    loadMessages();
                    return;
                }
            } catch (IOException e) {
                plugin.getLogger().log(Level.WARNING, "Could not create language file", e);
            }
        } else {
            // Merge new keys from JAR resource into existing file
            mergeDefaults(langFile, fileName);
        }

        // Load messages from file
        if (langFile.exists()) {
            YamlConfiguration config = YamlConfiguration.loadConfiguration(langFile);
            for (String key : config.getKeys(true)) {
                if (config.isString(key)) {
                    messages.put(key, config.getString(key));
                }
            }
        }
    }

    /**
     * Merges missing keys from the default language file (inside the JAR)
     * into the user's existing language file. Preserves all user-customized values.
     *
     * @param langFile The user's language file on disk
     * @param resourceName The resource name inside the JAR
     */
    private void mergeDefaults(File langFile, String resourceName) {
        try (InputStream in = plugin.getResource(resourceName)) {
            if (in == null) {
                return;
            }

            YamlConfiguration defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(in));
            YamlConfiguration userConfig = YamlConfiguration.loadConfiguration(langFile);

            boolean changed = false;
            for (String key : defaults.getKeys(true)) {
                if (!userConfig.contains(key, true)) {
                    userConfig.set(key, defaults.get(key));
                    changed = true;
                }
            }

            if (changed) {
                userConfig.save(langFile);
                plugin.getLogger().info("Language file " + resourceName + " updated: new message keys added.");
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Could not merge default messages for " + resourceName, e);
        }
    }

    /**
     * Gets a message by key with placeholder replacement.
     *
     * @param key The message key
     * @param replacements Placeholder replacements in pairs (placeholder, value)
     * @return The formatted message
     */
    public String get(String key, String... replacements) {
        String message = messages.getOrDefault(key, key);

        for (int i = 0; i < replacements.length - 1; i += 2) {
            message = message.replace("{" + replacements[i] + "}", replacements[i + 1]);
        }

        return message;
    }

    /**
     * Gets a raw message by key without placeholder replacement.
     *
     * @param key The message key
     * @return The message
     */
    public String getRaw(String key) {
        return messages.getOrDefault(key, key);
    }

    /**
     * Reloads all messages from the language file.
     */
    public void reload(String newLanguage) {
        messages.clear();
        this.language = newLanguage;
        loadMessages();
    }
}
