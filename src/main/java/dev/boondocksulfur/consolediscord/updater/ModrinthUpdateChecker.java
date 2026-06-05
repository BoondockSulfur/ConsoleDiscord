package dev.boondocksulfur.consolediscord.updater;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;

/**
 * Checks for plugin updates on Modrinth.
 * Compares current version with the latest available version,
 * filtered by the server's Minecraft version to avoid cross-notifications
 * between incompatible builds (e.g. Java 21 vs Java 25).
 *
 * @author BoondockSulfur
 * @version 2.0.1
 */
public class ModrinthUpdateChecker {

    private static final String PROJECT_ID = "consolediscord";
    private static final String MODRINTH_API = "https://api.modrinth.com/v2/project/" + PROJECT_ID + "/version";
    private static final String DOWNLOAD_URL = "https://modrinth.com/plugin/" + PROJECT_ID;

    private final Plugin plugin;
    private final String currentVersion;
    private final String minecraftVersion;

    private String latestVersion = null;
    private String downloadUrl = null;
    private boolean updateAvailable = false;

    /**
     * Creates a new update checker.
     *
     * @param plugin The plugin instance
     */
    public ModrinthUpdateChecker(Plugin plugin) {
        this.plugin = plugin;
        this.currentVersion = plugin.getDescription().getVersion();
        this.minecraftVersion = Bukkit.getMinecraftVersion();
    }

    /**
     * Checks for updates asynchronously.
     * Filters results by the current server's Minecraft version so that
     * users on older Java/MC versions don't get notified about incompatible builds.
     *
     * @return true if an update is available, false otherwise
     */
    public boolean checkForUpdates() {
        HttpURLConnection connection = null;
        try {
            // Filter by game version to avoid cross-version notifications
            String gameVersionFilter = URLEncoder.encode(
                    "[\"" + minecraftVersion + "\"]", StandardCharsets.UTF_8);
            String apiUrl = MODRINTH_API + "?game_versions=" + gameVersionFilter;

            URL url = URI.create(apiUrl).toURL();
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setRequestProperty("User-Agent", "ConsoleDiscord/" + currentVersion);
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);

            int responseCode = connection.getResponseCode();
            if (responseCode != 200) {
                plugin.getLogger().warning("Failed to check for updates: HTTP " + responseCode);
                return false;
            }

            StringBuilder response = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
            }

            // Parse JSON response with Gson (provided by Paper at runtime)
            JsonArray versions = JsonParser.parseString(response.toString()).getAsJsonArray();
            if (versions.isEmpty()) {
                plugin.getLogger().info("No compatible versions found on Modrinth for MC " + minecraftVersion);
                return false;
            }

            JsonObject latest = versions.get(0).getAsJsonObject();
            latestVersion = latest.get("version_number").getAsString();

            // Extract download URL from files array
            JsonArray files = latest.getAsJsonArray("files");
            if (files != null && !files.isEmpty()) {
                JsonObject firstFile = files.get(0).getAsJsonObject();
                JsonElement urlElement = firstFile.get("url");
                if (urlElement != null) {
                    downloadUrl = urlElement.getAsString();
                }
            }

            // Compare versions
            updateAvailable = !currentVersion.equals(latestVersion) && isNewerVersion(latestVersion, currentVersion);

            return updateAvailable;

        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to check for updates", e);
            return false;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * Compares two version strings to determine if the latest is newer.
     * Supports semantic versioning (e.g., 1.2.3).
     *
     * @param latest The latest version string
     * @param current The current version string
     * @return true if latest is newer than current
     */
    static boolean isNewerVersion(String latest, String current) {
        try {
            String[] latestParts = latest.split("\\.");
            String[] currentParts = current.split("\\.");

            int maxLength = Math.max(latestParts.length, currentParts.length);

            for (int i = 0; i < maxLength; i++) {
                int latestPart = i < latestParts.length ? Integer.parseInt(latestParts[i]) : 0;
                int currentPart = i < currentParts.length ? Integer.parseInt(currentParts[i]) : 0;

                if (latestPart > currentPart) {
                    return true;
                } else if (latestPart < currentPart) {
                    return false;
                }
            }

            return false; // Versions are equal
        } catch (NumberFormatException e) {
            // If parsing fails, do string comparison
            return latest.compareTo(current) > 0;
        }
    }

    /**
     * Gets the latest version string.
     *
     * @return The latest version, or null if not checked yet
     */
    public String getLatestVersion() {
        return latestVersion;
    }

    /**
     * Gets the current version string.
     *
     * @return The current version
     */
    public String getCurrentVersion() {
        return currentVersion;
    }

    /**
     * Gets the download URL for the latest version.
     *
     * @return The download URL, or the Modrinth page if not available
     */
    public String getDownloadUrl() {
        return downloadUrl != null ? downloadUrl : DOWNLOAD_URL;
    }

    /**
     * Checks if an update is available.
     *
     * @return true if an update is available
     */
    public boolean isUpdateAvailable() {
        return updateAvailable;
    }

    /**
     * Logs the update check result to console.
     */
    public void logResult() {
        if (updateAvailable) {
            plugin.getLogger().warning("A new version of ConsoleDiscord is available: " + latestVersion + " (current: " + currentVersion + ")");
            plugin.getLogger().warning("Download: " + DOWNLOAD_URL);
        } else {
            plugin.getLogger().info("You are running the latest version (" + currentVersion + ")");
        }
    }
}
