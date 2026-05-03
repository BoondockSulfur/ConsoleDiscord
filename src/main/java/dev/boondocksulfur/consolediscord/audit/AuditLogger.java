package dev.boondocksulfur.consolediscord.audit;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import org.bukkit.plugin.Plugin;

import java.awt.Color;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Logs command executions for audit and security purposes.
 * Supports both file-based logging and Discord channel logging.
 */
public class AuditLogger {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    private final Plugin plugin;
    private final File logFile;
    private final boolean logToDiscord;
    private final String auditChannelId;
    private final ExecutorService writeExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ConsoleDiscord-AuditWriter");
        t.setDaemon(true);
        return t;
    });
    private JDA jda;

    /**
     * Creates a new audit logger.
     *
     * @param plugin The plugin instance
     * @param logFileName Name of the log file
     * @param logToDiscord Whether to also log to Discord
     * @param auditChannelId Discord channel ID for audit logs
     */
    public AuditLogger(Plugin plugin, String logFileName, boolean logToDiscord, String auditChannelId) {
        this.plugin = plugin;
        this.logFile = new File(plugin.getDataFolder(), logFileName);
        this.logToDiscord = logToDiscord;
        this.auditChannelId = auditChannelId;

        // Create log file if it doesn't exist
        if (!logFile.exists()) {
            try {
                logFile.getParentFile().mkdirs();
                logFile.createNewFile();
            } catch (IOException e) {
                plugin.getLogger().log(Level.WARNING, "Could not create audit log file", e);
            }
        }
    }

    /**
     * Sets the JDA instance for Discord logging.
     *
     * @param jda The JDA instance
     */
    public void setJda(JDA jda) {
        this.jda = jda;
    }

    /**
     * Logs a command execution.
     *
     * @param userId The Discord user ID
     * @param username The Discord username
     * @param command The executed command
     * @param success Whether the command was successful
     */
    public void logCommand(String userId, String username, String command, boolean success) {
        String timestamp = FORMATTER.format(Instant.now());
        String status = success ? "SUCCESS" : "FAILED";
        String logLine = String.format("[%s] User#%s (%s) executed: %s [%s]",
                timestamp, userId, username, command, status);

        // Log to file
        logToFile(logLine);

        // Log to Discord if enabled
        if (logToDiscord && jda != null && auditChannelId != null && !auditChannelId.isBlank()) {
            logToDiscord(userId, username, command, success, timestamp);
        }
    }

    /**
     * Writes a log line to the audit file asynchronously.
     */
    private void logToFile(String line) {
        writeExecutor.submit(() -> {
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(logFile, true))) {
                writer.write(line);
                writer.newLine();
            } catch (IOException e) {
                plugin.getLogger().log(Level.WARNING, "Could not write to audit log file", e);
            }
        });
    }

    /**
     * Shuts down the audit writer thread gracefully.
     */
    public void shutdown() {
        writeExecutor.shutdown();
        try {
            if (!writeExecutor.awaitTermination(3, TimeUnit.SECONDS)) {
                writeExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            writeExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Sends an audit log message to Discord.
     */
    private void logToDiscord(String userId, String username, String command, boolean success, String timestamp) {
        if (jda.getStatus() != JDA.Status.CONNECTED) {
            return;
        }

        TextChannel channel = jda.getTextChannelById(auditChannelId);
        if (channel == null) {
            return;
        }

        MessageEmbed embed = createAuditEmbed(userId, username, command, success, timestamp);

        channel.sendMessageEmbeds(embed).queue(
                v -> {},
                error -> plugin.getLogger().warning("Could not send audit log to Discord: " + error.getMessage())
        );
    }

    /**
     * Creates a Discord embed for an audit log entry.
     */
    private MessageEmbed createAuditEmbed(String userId, String username, String command, boolean success, String timestamp) {
        Color color = success ? Color.GREEN : Color.RED;
        String emoji = success ? "✅" : "❌";
        String status = success ? "Success" : "Failed";

        return new EmbedBuilder()
                .setTitle(emoji + " Command Executed")
                .setColor(color)
                .addField("User", username + " (`" + userId + "`)", false)
                .addField("Command", "`" + command + "`", false)
                .addField("Status", status, true)
                .addField("Timestamp", timestamp, true)
                .setTimestamp(Instant.now())
                .build();
    }

    /**
     * Gets the audit log file.
     *
     * @return The audit log file
     */
    public File getLogFile() {
        return logFile;
    }
}
