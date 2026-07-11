package dev.boondocksulfur.consolediscord.cleanup;

import dev.boondocksulfur.consolediscord.scheduler.SchedulerAdapter;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Automatically cleans up old messages from Discord channels.
 * Helps keep log channels organized by removing messages older than a specified duration.
 */
public class MessageCleanup {

    private final Plugin plugin;
    private final int cleanupAfterDays;
    private final long checkIntervalHours;

    private JDA jda;
    private SchedulerAdapter.CancellableTask cleanupTask;
    private String logChannelId;

    /**
     * Creates a new message cleanup handler.
     *
     * @param plugin The plugin instance
     * @param cleanupAfterDays Number of days before messages are deleted
     * @param checkIntervalHours How often to run cleanup (in hours)
     */
    public MessageCleanup(Plugin plugin, int cleanupAfterDays, long checkIntervalHours) {
        this.plugin = plugin;
        this.cleanupAfterDays = cleanupAfterDays;
        this.checkIntervalHours = checkIntervalHours;
    }

    /**
     * Sets the JDA instance and log channel ID.
     *
     * @param jda The JDA instance
     * @param logChannelId The Discord channel ID to clean
     */
    public void setJda(JDA jda, String logChannelId) {
        this.jda = jda;
        this.logChannelId = logChannelId;
    }

    /**
     * Starts the automatic cleanup task.
     */
    public void start() {
        if (cleanupTask != null) {
            cleanupTask.cancel();
        }

        long intervalTicks = checkIntervalHours * 60 * 60 * 20; // Convert hours to ticks

        cleanupTask = SchedulerAdapter.runAsyncTimer(
                plugin,
                this::performCleanup,
                intervalTicks, // Delay first run
                intervalTicks  // Repeat interval
        );

        plugin.getLogger().info("Message cleanup scheduled every " + checkIntervalHours + " hours");
    }

    /**
     * Stops the cleanup task.
     */
    public void stop() {
        if (cleanupTask != null) {
            cleanupTask.cancel();
            cleanupTask = null;
        }
    }

    /**
     * Performs the cleanup of old messages.
     */
    private void performCleanup() {
        if (jda == null || jda.getStatus() != JDA.Status.CONNECTED) {
            return;
        }

        if (logChannelId == null || logChannelId.isBlank()) {
            return;
        }

        TextChannel channel = jda.getTextChannelById(logChannelId);
        if (channel == null) {
            plugin.getLogger().warning("Cannot cleanup: log channel not found");
            return;
        }

        OffsetDateTime cutoff = OffsetDateTime.now().minusDays(cleanupAfterDays);

        plugin.getLogger().info("Starting message cleanup in channel " + channel.getName());

        cleanupChannel(channel, cutoff);
    }

    /**
     * Cleans up messages in a channel older than the cutoff date.
     */
    private void cleanupChannel(TextChannel channel, OffsetDateTime cutoff) {
        try {
            // Retrieve messages in batches
            channel.getIterableHistory()
                    .takeAsync(1000) // Max 1000 messages per cleanup
                    .thenAccept(messages -> {
                        var selfUser = jda.getSelfUser();
                        // Discord bulk delete only accepts messages younger than 14 days;
                        // use 13 as the boundary so messages close to the limit don't
                        // cross it while the requests are queued.
                        OffsetDateTime twoWeeksAgo = OffsetDateTime.now().minusDays(13);

                        List<Message> bulkDeletable = new ArrayList<>();
                        List<Message> oldMessages = new ArrayList<>();

                        for (Message message : messages) {
                            // Only delete bot's own messages
                            if (selfUser == null || !message.getAuthor().equals(selfUser)) {
                                continue;
                            }

                            // Check if message is older than cutoff
                            if (message.getTimeCreated().isBefore(cutoff)) {
                                if (message.getTimeCreated().isAfter(twoWeeksAgo)) {
                                    bulkDeletable.add(message);
                                } else {
                                    oldMessages.add(message);
                                }
                            }
                        }

                        int deletedCount = 0;

                        // Bulk delete messages younger than 14 days (max 100 per call)
                        for (int i = 0; i < bulkDeletable.size(); i += 100) {
                            List<Message> batch = bulkDeletable.subList(i, Math.min(i + 100, bulkDeletable.size()));
                            if (batch.size() >= 2) {
                                channel.deleteMessages(batch).queue(
                                        v -> {},
                                        error -> plugin.getLogger().warning("Bulk delete failed: " + error.getMessage())
                                );
                            } else if (batch.size() == 1) {
                                batch.get(0).delete().queue(v -> {}, error -> {});
                            }
                            deletedCount += batch.size();
                        }

                        // Delete messages older than 14 days individually with delay
                        for (int i = 0; i < oldMessages.size(); i++) {
                            oldMessages.get(i).delete().queueAfter(i * 1100L, TimeUnit.MILLISECONDS,
                                    v -> {},
                                    error -> {}
                            );
                            deletedCount++;
                        }

                        final int finalCount = deletedCount;
                        if (finalCount > 0) {
                            plugin.getLogger().info("Cleaned up " + finalCount + " old messages");
                        }
                    })
                    .exceptionally(error -> {
                        plugin.getLogger().warning("Error during message cleanup: " + error.getMessage());
                        return null;
                    });

        } catch (Exception e) {
            plugin.getLogger().warning("Error during message cleanup: " + e.getMessage());
        }
    }

    /**
     * Performs an immediate cleanup (for manual triggering).
     */
    public void cleanupNow() {
        SchedulerAdapter.runAsync(plugin, this::performCleanup);
    }

    /**
     * Checks if cleanup is running.
     *
     * @return true if the cleanup task is active
     */
    public boolean isRunning() {
        return cleanupTask != null && !cleanupTask.isCancelled();
    }
}
