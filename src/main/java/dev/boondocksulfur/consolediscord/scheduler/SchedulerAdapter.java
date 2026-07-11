package dev.boondocksulfur.consolediscord.scheduler;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Scheduler adapter that provides compatibility between Paper and Folia.
 * Automatically detects the server type and uses the appropriate scheduler API.
 *
 * @author BoondockSulfur
 * @version 1.5.0
 */
public class SchedulerAdapter {

    private static final boolean IS_FOLIA;

    static {
        boolean folia;
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            folia = true;
        } catch (ClassNotFoundException e) {
            folia = false;
        }
        IS_FOLIA = folia;
    }

    /**
     * Checks if the server is running Folia.
     *
     * @return true if running on Folia, false if running on Paper/Spigot
     */
    public static boolean isFolia() {
        return IS_FOLIA;
    }

    /**
     * Runs a task asynchronously.
     * Works on both Paper and Folia.
     *
     * @param plugin The plugin instance
     * @param task The task to run
     */
    public static void runAsync(Plugin plugin, Runnable task) {
        if (IS_FOLIA) {
            Bukkit.getAsyncScheduler().runNow(plugin, t -> task.run());
        } else {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
        }
    }

    /**
     * Runs a task asynchronously after a delay.
     * Works on both Paper and Folia.
     *
     * @param plugin The plugin instance
     * @param task The task to run
     * @param delayTicks Delay in ticks (20 ticks = 1 second)
     */
    public static void runAsyncLater(Plugin plugin, Runnable task, long delayTicks) {
        if (IS_FOLIA) {
            long delayMs = delayTicks * 50; // Convert ticks to milliseconds
            Bukkit.getAsyncScheduler().runDelayed(plugin, t -> task.run(), delayMs, TimeUnit.MILLISECONDS);
        } else {
            Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, task, delayTicks);
        }
    }

    /**
     * Runs a task asynchronously on a repeating timer.
     * Works on both Paper and Folia.
     *
     * @param plugin The plugin instance
     * @param task The task to run
     * @param delayTicks Initial delay in ticks
     * @param periodTicks Period between executions in ticks
     * @return A cancellable task
     */
    public static CancellableTask runAsyncTimer(Plugin plugin, Runnable task, long delayTicks, long periodTicks) {
        if (IS_FOLIA) {
            long delayMs = delayTicks * 50;
            long periodMs = periodTicks * 50;
            var scheduledTask = Bukkit.getAsyncScheduler().runAtFixedRate(
                    plugin,
                    t -> task.run(),
                    delayMs,
                    periodMs,
                    TimeUnit.MILLISECONDS
            );
            AtomicBoolean cancelled = new AtomicBoolean(false);
            return new CancellableTask() {
                @Override
                public boolean cancel() {
                    scheduledTask.cancel();
                    cancelled.set(true);
                    return true;
                }
                @Override
                public boolean isCancelled() {
                    return cancelled.get();
                }
            };
        } else {
            var bukkitTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, task, delayTicks, periodTicks);
            return new CancellableTask() {
                @Override
                public boolean cancel() {
                    bukkitTask.cancel();
                    return true;
                }
                @Override
                public boolean isCancelled() {
                    return bukkitTask.isCancelled();
                }
            };
        }
    }

    /**
     * Runs a task on the global region (for server-wide operations like console commands).
     * On Paper, this runs on the main thread.
     * On Folia, this uses the global region scheduler.
     *
     * @param plugin The plugin instance
     * @param task The task to run
     */
    public static void runGlobal(Plugin plugin, Runnable task) {
        if (IS_FOLIA) {
            Bukkit.getGlobalRegionScheduler().run(plugin, t -> task.run());
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    /**
     * Represents a cancellable task that works on both Paper and Folia.
     */
    @FunctionalInterface
    public interface CancellableTask {
        /**
         * Cancels the task.
         *
         * @return true if successfully cancelled
         */
        boolean cancel();

        /**
         * Checks if the task is cancelled.
         * Default implementation always returns false.
         *
         * @return true if cancelled
         */
        default boolean isCancelled() {
            return false;
        }
    }
}
