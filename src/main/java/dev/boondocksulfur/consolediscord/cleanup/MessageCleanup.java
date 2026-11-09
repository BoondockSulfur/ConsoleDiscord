package dev.boondocksulfur.consolediscord.cleanup;

import dev.boondocksulfur.consolediscord.scheduler.SchedulerAdapter;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.utils.TimeUtil;
import org.bukkit.plugin.Plugin;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Automatically deletes the bot's own old messages from the log channels
 * (main log channel and category channels). The audit channel is never touched.
 */
public class MessageCleanup {

    /**
     * Messages read per channel and run; the next run continues where this one stopped.
     */
    private static final int MAX_SCANNED = 5000;

    /**
     * Messages deleted per channel and run.
     */
    private static final int MAX_DELETED = 1000;

    /**
     * Delay between single deletes of messages older than 14 days.
     */
    private static final long SINGLE_DELETE_DELAY_MS = 1100L;

    private final Plugin plugin;
    private final int cleanupAfterDays;
    private final long checkIntervalHours;

    private volatile JDA jda;
    private volatile List<String> channelIds = List.of();
    private SchedulerAdapter.CancellableTask cleanupTask;

    /**
     * Per channel: the oldest message ID read in a run that stopped at a
     * limit; the next run starts there instead of reading the same
     * messages again.
     */
    private final Map<String, Long> resumeBefore = new ConcurrentHashMap<>();

    /**
     * Set while a run reads the history; a second run (e.g. /cdr cleanup)
     * is skipped meanwhile.
     */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * Time until which delayed single deletes of the last run are still queued.
     */
    private volatile long busyUntil = 0;

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
     * Sets the JDA instance and the channels to clean.
     *
     * @param jda The JDA instance
     * @param channelIds The Discord channel IDs to clean
     */
    public void setJda(JDA jda, List<String> channelIds) {
        this.jda = jda;
        this.channelIds = channelIds == null ? List.of() : List.copyOf(channelIds);
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
        JDA current = jda;
        if (current == null || current.getStatus() != JDA.Status.CONNECTED) {
            return;
        }
        if (System.currentTimeMillis() < busyUntil) {
            plugin.getLogger().info("Message cleanup skipped: deletions of the previous run are still in progress");
            return;
        }
        if (!running.compareAndSet(false, true)) {
            plugin.getLogger().info("Message cleanup skipped: a cleanup is already running");
            return;
        }

        OffsetDateTime cutoff = OffsetDateTime.now().minusDays(cleanupAfterDays);
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (String channelId : channelIds) {
            if (channelId == null || channelId.isBlank()) {
                continue;
            }
            TextChannel channel = current.getTextChannelById(channelId);
            if (channel == null) {
                plugin.getLogger().warning("Cannot clean up channel " + channelId + ": not found");
                continue;
            }
            // One channel after another, so the history requests don't pile up;
            // an error in one channel doesn't stop the others
            chain = chain.thenCompose(v -> cleanupChannel(current, channel, cutoff).exceptionally(error -> {
                plugin.getLogger().warning("Error during message cleanup in " + channel.getName() + ": "
                        + error.getMessage());
                return null;
            }));
        }
        chain.whenComplete((v, error) -> {
            running.set(false);
            if (error != null) {
                plugin.getLogger().warning("Error during message cleanup: " + error.getMessage());
            }
        });
    }

    /**
     * Deletes the bot's messages older than the cutoff in one channel.
     */
    private CompletableFuture<Void> cleanupChannel(JDA current, TextChannel channel, OffsetDateTime cutoff) {
        String channelId = channel.getId();
        long selfId = current.getSelfUser().getIdLong();

        // Start the history at the cutoff (newer messages are never old
        // enough) or further back where the previous run stopped.
        long cutoffId = TimeUtil.getDiscordTimestamp(cutoff.toInstant().toEpochMilli());
        long start = Math.min(cutoffId, resumeBefore.getOrDefault(channelId, Long.MAX_VALUE));

        List<Message> own = new ArrayList<>();
        int[] scanned = {0};
        long[] oldestId = {0L};

        plugin.getLogger().info("Starting message cleanup in channel " + channel.getName());
        try {
            return channel.getIterableHistory()
                    .skipTo(start)
                    .forEachAsync(message -> {
                        scanned[0]++;
                        oldestId[0] = message.getIdLong();
                        if (message.getAuthor().getIdLong() == selfId
                                && message.getTimeCreated().isBefore(cutoff)) {
                            own.add(message);
                        }
                        return scanned[0] < MAX_SCANNED && own.size() < MAX_DELETED;
                    })
                    .thenAccept(v -> {
                        boolean stoppedAtLimit = scanned[0] >= MAX_SCANNED || own.size() >= MAX_DELETED;
                        if (stoppedAtLimit) {
                            resumeBefore.put(channelId, oldestId[0]);
                        } else {
                            // Reached the beginning of the channel: start at the cutoff again
                            resumeBefore.remove(channelId);
                        }
                        int deleted = delete(channel, own);
                        if (deleted > 0) {
                            plugin.getLogger().info("Cleaned up " + deleted + " old messages in " + channel.getName());
                        }
                    });
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private int delete(TextChannel channel, List<Message> messages) {
        // Discord bulk delete only accepts messages younger than 14 days;
        // use 13 as the boundary so messages close to the limit don't
        // cross it while the requests are queued.
        OffsetDateTime bulkLimit = OffsetDateTime.now().minusDays(13);

        List<Message> bulkDeletable = new ArrayList<>();
        List<Message> oldMessages = new ArrayList<>();
        for (Message message : messages) {
            if (message.getTimeCreated().isAfter(bulkLimit)) {
                bulkDeletable.add(message);
            } else {
                oldMessages.add(message);
            }
        }

        // Bulk delete messages younger than 14 days (max 100 per call)
        for (int i = 0; i < bulkDeletable.size(); i += 100) {
            List<Message> batch = bulkDeletable.subList(i, Math.min(i + 100, bulkDeletable.size()));
            if (batch.size() >= 2) {
                channel.deleteMessages(batch).queue(
                        v -> {},
                        error -> plugin.getLogger().warning("Bulk delete failed: " + error.getMessage())
                );
            } else {
                batch.get(0).delete().queue(v -> {}, error -> {});
            }
        }

        // Delete messages older than 14 days individually with delay
        for (int i = 0; i < oldMessages.size(); i++) {
            oldMessages.get(i).delete().queueAfter(i * SINGLE_DELETE_DELAY_MS, TimeUnit.MILLISECONDS,
                    v -> {},
                    error -> {}
            );
        }
        if (!oldMessages.isEmpty()) {
            long until = System.currentTimeMillis() + oldMessages.size() * SINGLE_DELETE_DELAY_MS;
            busyUntil = Math.max(busyUntil, until);
        }

        return messages.size();
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
