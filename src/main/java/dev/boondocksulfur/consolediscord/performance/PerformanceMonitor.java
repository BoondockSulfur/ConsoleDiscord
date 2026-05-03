package dev.boondocksulfur.consolediscord.performance;

import dev.boondocksulfur.consolediscord.i18n.Messages;
import dev.boondocksulfur.consolediscord.scheduler.SchedulerAdapter;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.awt.Color;
import java.time.Instant;

/**
 * Monitors server performance and sends alerts to Discord when thresholds are exceeded.
 * Tracks TPS (Ticks Per Second) and memory usage.
 */
public class PerformanceMonitor {

    private final Plugin plugin;
    private final Messages messages;
    private final double lowTpsThreshold;
    private final int highMemoryThreshold;
    private final long alertCooldownSeconds;
    private final String logChannelId;

    private JDA jda;
    private SchedulerAdapter.CancellableTask monitorTask;

    private long lastTpsAlert = 0;
    private long lastMemoryAlert = 0;

    /**
     * Creates a new performance monitor.
     *
     * @param plugin The plugin instance
     * @param messages The i18n messages instance
     * @param lowTpsThreshold TPS threshold for alerts
     * @param highMemoryThreshold Memory percentage threshold for alerts
     * @param alertCooldownSeconds Cooldown between alerts in seconds
     * @param logChannelId Discord channel ID for alerts
     */
    public PerformanceMonitor(Plugin plugin,
                              Messages messages,
                              double lowTpsThreshold,
                              int highMemoryThreshold,
                              long alertCooldownSeconds,
                              String logChannelId) {
        this.plugin = plugin;
        this.messages = messages;
        this.lowTpsThreshold = lowTpsThreshold;
        this.highMemoryThreshold = highMemoryThreshold;
        this.alertCooldownSeconds = alertCooldownSeconds;
        this.logChannelId = logChannelId;
    }

    /**
     * Sets the JDA instance for sending alerts.
     *
     * @param jda The JDA instance
     */
    public void setJda(JDA jda) {
        this.jda = jda;
    }

    /**
     * Starts the performance monitoring task.
     * Checks performance every 5 seconds.
     */
    public void start() {
        if (monitorTask != null) {
            monitorTask.cancel();
        }

        monitorTask = SchedulerAdapter.runAsyncTimer(
                plugin,
                this::checkPerformance,
                100L, // Start after 5 seconds
                100L  // Check every 5 seconds
        );
    }

    /**
     * Stops the performance monitoring task.
     */
    public void stop() {
        if (monitorTask != null) {
            monitorTask.cancel();
            monitorTask = null;
        }
    }

    /**
     * Checks server performance and sends alerts if needed.
     */
    private void checkPerformance() {
        if (jda == null || jda.getStatus() != JDA.Status.CONNECTED) {
            return;
        }

        if (logChannelId == null || logChannelId.isBlank()) {
            return;
        }

        // Check TPS
        double tps = getCurrentTPS();
        if (tps > 0 && tps < lowTpsThreshold) {
            long now = System.currentTimeMillis() / 1000;
            if (now - lastTpsAlert >= alertCooldownSeconds) {
                sendTpsAlert(tps);
                lastTpsAlert = now;
            }
        }

        // Check Memory
        int memoryPercent = getMemoryUsagePercent();
        if (memoryPercent >= highMemoryThreshold) {
            long now = System.currentTimeMillis() / 1000;
            if (now - lastMemoryAlert >= alertCooldownSeconds) {
                sendMemoryAlert(memoryPercent);
                lastMemoryAlert = now;
            }
        }
    }

    /**
     * Gets the current server TPS.
     * On Paper, uses Bukkit.getTPS() for the global 1-minute average.
     * On Folia, TPS is per-region; global TPS is not meaningful,
     * so this returns -1 to skip TPS alerts.
     *
     * @return Current TPS (1-minute average), or -1 if unavailable
     */
    private double getCurrentTPS() {
        if (SchedulerAdapter.isFolia()) {
            return -1; // Folia has per-region TPS, global value not meaningful
        }

        try {
            double[] tpsArray = Bukkit.getTPS();
            return tpsArray[0]; // 1-minute average
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * Gets the current memory usage as a percentage.
     *
     * @return Memory usage percentage (0-100)
     */
    private int getMemoryUsagePercent() {
        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory();
        long usedMemory = runtime.totalMemory() - runtime.freeMemory();

        if (maxMemory == 0) {
            return 0;
        }

        return (int) ((usedMemory * 100) / maxMemory);
    }

    /**
     * Sends a TPS alert to Discord.
     */
    private void sendTpsAlert(double tps) {
        TextChannel channel = jda.getTextChannelById(logChannelId);
        if (channel == null) {
            return;
        }

        String status = tps < 10
                ? messages.getRaw("performance.tps_critical")
                : messages.getRaw("performance.tps_warning");

        MessageEmbed embed = new EmbedBuilder()
                .setTitle(messages.getRaw("performance.tps_title"))
                .setColor(Color.ORANGE)
                .setDescription(messages.getRaw("performance.tps_description"))
                .addField(messages.getRaw("performance.tps_current"), String.format("%.2f", tps), true)
                .addField(messages.getRaw("performance.tps_threshold"), String.format("%.2f", lowTpsThreshold), true)
                .addField(messages.getRaw("performance.tps_status"), status, false)
                .setTimestamp(Instant.now())
                .setFooter(messages.getRaw("performance.footer"), null)
                .build();

        channel.sendMessageEmbeds(embed).queue(
                v -> {},
                error -> plugin.getLogger().warning("Could not send TPS alert: " + error.getMessage())
        );
    }

    /**
     * Sends a memory alert to Discord.
     */
    private void sendMemoryAlert(int memoryPercent) {
        TextChannel channel = jda.getTextChannelById(logChannelId);
        if (channel == null) {
            return;
        }

        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory() / (1024 * 1024); // MB
        long usedMemory = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024); // MB

        String status = memoryPercent >= 95
                ? messages.getRaw("performance.memory_critical")
                : messages.getRaw("performance.memory_warning");

        MessageEmbed embed = new EmbedBuilder()
                .setTitle(messages.getRaw("performance.memory_title"))
                .setColor(Color.RED)
                .setDescription(messages.getRaw("performance.memory_description"))
                .addField(messages.getRaw("performance.memory_usage"), String.format("%d%% (%d MB / %d MB)",
                        memoryPercent, usedMemory, maxMemory), false)
                .addField(messages.getRaw("performance.memory_threshold"), highMemoryThreshold + "%", true)
                .addField(messages.getRaw("performance.memory_status"), status, true)
                .setTimestamp(Instant.now())
                .setFooter(messages.getRaw("performance.footer"), null)
                .build();

        channel.sendMessageEmbeds(embed).queue(
                v -> {},
                error -> plugin.getLogger().warning("Could not send memory alert: " + error.getMessage())
        );
    }

    /**
     * Checks if the monitor is running.
     *
     * @return true if monitoring is active
     */
    public boolean isMonitoring() {
        return monitorTask != null && !monitorTask.isCancelled();
    }
}
