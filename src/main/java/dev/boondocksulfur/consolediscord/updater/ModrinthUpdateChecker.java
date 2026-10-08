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

/**
 * Checks for plugin updates on Modrinth.
 * Compares current version with the latest available version,
 * filtered by the server's Minecraft version to avoid cross-notifications
 * between incompatible builds (e.g. Java 21 vs Java 25).
 *
 * @author BoondockSulfur
 * @version 2.1.1
 */
public class ModrinthUpdateChecker {

    private static final String PROJECT_ID = "consolediscord";
    private static final String MODRINTH_API = "https://api.modrinth.com/v2/project/" + PROJECT_ID + "/version";

    /** Project page on Modrinth. */
    public static final String MODRINTH_URL = "https://modrinth.com/plugin/" + PROJECT_ID;

    /** Project page on CurseForge. */
    public static final String CURSEFORGE_URL = "https://www.curseforge.com/minecraft/bukkit-plugins/consolediscord";

    /**
     * Outcome of an update check.
     */
    public enum Result {
        UPDATE_AVAILABLE,
        UP_TO_DATE,
        NO_COMPATIBLE_VERSION,
        FAILED
    }

    private final String currentVersion;
    private final String minecraftVersion;

    // Written on the async check thread, read on the main thread (join/status)
    private volatile String latestVersion = null;
    private volatile boolean updateAvailable = false;
    private volatile String lastError = null;

    /**
     * Creates a new update checker.
     *
     * @param plugin The plugin instance
     */
    public ModrinthUpdateChecker(Plugin plugin) {
        this.currentVersion = plugin.getDescription().getVersion();
        this.minecraftVersion = Bukkit.getMinecraftVersion();
    }

    /**
     * Queries Modrinth for the newest version compatible with this server.
     * Blocking (HTTP with 5s timeouts), call from an async thread.
     *
     * @return The result of the check
     */
    public Result checkForUpdates() {
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
                lastError = "HTTP " + responseCode;
                return Result.FAILED;
            }

            StringBuilder response = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
            }

            // Parse JSON response with Gson (provided by Paper at runtime)
            JsonArray versions = JsonParser.parseString(response.toString()).getAsJsonArray();
            JsonObject latest = newestByPublishDate(versions);
            if (latest == null) {
                updateAvailable = false;
                return Result.NO_COMPATIBLE_VERSION;
            }

            JsonElement versionElement = latest.get("version_number");
            if (versionElement == null || versionElement.isJsonNull()) {
                lastError = "missing version_number";
                return Result.FAILED;
            }
            latestVersion = versionElement.getAsString();
            updateAvailable = isNewerVersion(latestVersion, currentVersion);

            return updateAvailable ? Result.UPDATE_AVAILABLE : Result.UP_TO_DATE;

        } catch (Exception e) {
            lastError = e.toString();
            return Result.FAILED;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * Picks the most recently published version. The API's ordering is not
     * contractual, and a backport for an older Minecraft version must not be
     * mistaken for the latest release.
     */
    private static JsonObject newestByPublishDate(JsonArray versions) {
        JsonObject newest = null;
        String newestDate = null;
        for (JsonElement element : versions) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject version = element.getAsJsonObject();
            JsonElement date = version.get("date_published");
            String published = date != null && !date.isJsonNull() ? date.getAsString() : "";
            // ISO-8601 timestamps in UTC compare correctly as strings
            if (newest == null || published.compareTo(newestDate) > 0) {
                newest = version;
                newestDate = published;
            }
        }
        return newest;
    }

    /**
     * Compares two version strings numerically (e.g. "1.4.1" vs "1.4.0").
     *
     * @param latest The latest version
     * @param current The current version
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
     * Gets the latest version found on Modrinth.
     *
     * @return The latest version, or null if not checked yet
     */
    public String getLatestVersion() {
        return latestVersion;
    }

    /**
     * Gets the currently installed version.
     *
     * @return The current version
     */
    public String getCurrentVersion() {
        return currentVersion;
    }

    /**
     * Gets the Minecraft version used to filter compatible releases.
     *
     * @return The server's Minecraft version
     */
    public String getMinecraftVersion() {
        return minecraftVersion;
    }

    /**
     * Gets the error of the last failed check.
     *
     * @return The error description, or null
     */
    public String getLastError() {
        return lastError;
    }

    /**
     * Checks if an update is available.
     *
     * @return true if a newer version exists
     */
    public boolean isUpdateAvailable() {
        return updateAvailable;
    }
}
