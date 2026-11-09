package dev.boondocksulfur.consolediscord.discord;

import dev.boondocksulfur.consolediscord.ConsoleDiscordPlugin;
import dev.boondocksulfur.consolediscord.listener.DiscordListener;
import dev.boondocksulfur.consolediscord.scheduler.SchedulerAdapter;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.OnlineStatus;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.events.StatusChangeEvent;
import net.dv8tion.jda.api.events.session.ShutdownEvent;
import net.dv8tion.jda.api.exceptions.InvalidTokenException;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.CloseCode;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.MemberCachePolicy;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Owns the Discord connection: building it on an async thread, handing it
 * to the plugin, shutting it down, and the watchdog that restarts a
 * connection stuck for too long.
 */
public final class DiscordConnection {

    /**
     * Notifications from the connection to the plugin.
     */
    public interface Callbacks {
        /** A new connection became the current one (restartLock held). */
        void onInstalled(JDA jda);

        /** The current connection reached CONNECTED (JDA thread). */
        void onConnected(JDA jda);
    }

    /**
     * How long JDA may try to reconnect by itself before the watchdog restarts it.
     */
    private static final long RECONNECT_GRACE_SECONDS = 300;

    private final ConsoleDiscordPlugin plugin;
    private final Logger logger;
    private final Callbacks callbacks;

    /**
     * Coordinates connection changes with reload and disable.
     */
    private final Object lock = new Object();

    private volatile JDA jda;

    /**
     * Incremented (under the lock) whenever a new connection is requested;
     * a connection built for an older generation is discarded.
     */
    private long generation = 0;

    /**
     * Set during reload and disable; no connection is installed or restarted meanwhile.
     */
    private volatile boolean shuttingDown = false;

    private volatile Instant lastConnected = Instant.EPOCH;
    private volatile long restartBackoffSec = 10;

    /**
     * Set when building the JDA instance threw (no connection object exists),
     * so the watchdog retries the start instead of waiting for a status.
     */
    private volatile boolean startFailed = false;
    private volatile Instant lastStartAttempt = Instant.EPOCH;

    /**
     * Whether the privileged Message Content intent is requested (needed for
     * !mc only). Cleared when Discord rejects it (close code 4014), so logs
     * and /mc keep working; a reload tries again.
     */
    private volatile boolean messageContentEnabled = true;

    /**
     * Set when Discord closed the connection with a code that won't get
     * better by reconnecting; the watchdog then leaves it alone until reload.
     */
    private volatile boolean connectionRejected = false;

    private SchedulerAdapter.CancellableTask watchdogTask;

    /**
     * Creates new connections; replaced in tests.
     */
    private Supplier<JDA> factory = this::build;

    public DiscordConnection(ConsoleDiscordPlugin plugin, Callbacks callbacks) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.callbacks = callbacks;
    }

    // ----------------------------------------------------------------
    // State
    // ----------------------------------------------------------------

    public JDA get() {
        return jda;
    }

    /**
     * The lock reload and disable hold while they change components.
     */
    public Object lock() {
        return lock;
    }

    public boolean isShuttingDown() {
        return shuttingDown;
    }

    public void setShuttingDown(boolean shuttingDown) {
        this.shuttingDown = shuttingDown;
    }

    public boolean isMessageContentEnabled() {
        return messageContentEnabled;
    }

    boolean isConnectionRejected() {
        return connectionRejected;
    }

    /**
     * Forgets a rejected intent or connection, so the next connection tries again.
     */
    public void resetRejections() {
        messageContentEnabled = true;
        connectionRejected = false;
    }

    // ----------------------------------------------------------------
    // Connect / disconnect
    // ----------------------------------------------------------------

    /**
     * Requests a new connection; must be called with the lock held. It is
     * built on an async thread, because the login can take seconds, and
     * installed only if no newer request or shutdown came in meanwhile.
     */
    public void requestConnection() {
        long requested = ++generation;
        try {
            SchedulerAdapter.runAsync(plugin, () -> connect(requested));
        } catch (IllegalPluginAccessException e) {
            // Plugin is being disabled: no new connection
        }
    }

    /**
     * Replaces how connections are created (tests only).
     */
    void setFactory(Supplier<JDA> factory) {
        this.factory = factory;
    }

    private void connect(long requested) {
        JDA fresh = factory.get();
        if (fresh == null) {
            return;
        }
        synchronized (lock) {
            if (shuttingDown || requested != generation || jda != null) {
                // Outdated: detach the listeners first, so its shutdown isn't
                // mistaken for Discord rejecting the connection
                fresh.getRegisteredListeners().forEach(fresh::removeEventListener);
                fresh.shutdownNow();
                return;
            }
            jda = fresh;
            callbacks.onInstalled(fresh);
        }
    }

    /**
     * Builds a JDA instance from the configured token.
     *
     * @return The new instance, or null without a usable token or if the build failed
     */
    private JDA build() {
        startFailed = false;
        lastStartAttempt = Instant.now();
        // Environment variable takes precedence so the token can be kept
        // out of config.yml (and out of config backups/support pastes).
        String token = System.getenv("CONSOLEDISCORD_BOT_TOKEN");
        if (token != null && !token.isBlank()) {
            token = token.trim();
            logger.info(plugin.getMessages().getRaw("discord.token_from_env"));
        } else {
            token = plugin.getConfig().getString("bot-token", "").trim();
        }
        if (token.isEmpty() || "DEIN_DISCORD_BOT_TOKEN".equals(token) || "YOUR_DISCORD_BOT_TOKEN".equals(token)) {
            logger.warning(plugin.getMessages().getRaw("discord.no_token"));
            return null;
        }
        if (!isValidBotToken(token)) {
            logger.warning(plugin.getMessages().getRaw("discord.invalid_token_format"));
            return null;
        }

        try {
            JDABuilder builder = JDABuilder.createDefault(token)
                    .setStatus(OnlineStatus.ONLINE)
                    .setActivity(Activity.playing("Minecraft"))
                    .enableIntents(GatewayIntent.GUILD_MESSAGES);
            // Privileged intent, must be switched on in the Developer Portal
            if (messageContentEnabled) {
                builder.enableIntents(GatewayIntent.MESSAGE_CONTENT);
            } else {
                builder.disableIntents(GatewayIntent.MESSAGE_CONTENT);
            }
            return builder
                    .setMemberCachePolicy(MemberCachePolicy.NONE)
                    .addEventListeners(new DiscordListener(plugin), new StatusListener())
                    .build();
        } catch (InvalidTokenException ex) {
            // Discord rejected the token: retrying can't help, wait for a reload
            logger.severe(plugin.getMessages().getRaw("discord.token_rejected"));
            return null;
        } catch (Exception ex) {
            startFailed = true;
            logger.log(Level.SEVERE,
                    plugin.getMessages().get("discord.startup_error", "error", ex.getMessage()), ex);
            return null;
        }
    }

    /**
     * Validates the Discord bot token format: three base64url parts
     * separated by dots, at least 50 characters in total.
     *
     * @param token The token to validate
     * @return true if the token format is valid
     */
    static boolean isValidBotToken(String token) {
        if (token == null || token.isEmpty()) {
            return false;
        }
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            return false;
        }
        for (String part : parts) {
            if (part.isEmpty() || !part.matches("[A-Za-z0-9_-]+")) {
                return false;
            }
        }
        return token.length() >= 50;
    }

    /**
     * Shuts down the current connection.
     *
     * @param wait true to block until JDA has shut down (plugin disable),
     *             false to finish the shutdown on an async thread
     */
    public void stop(boolean wait) {
        JDA old = jda;
        if (old == null) {
            return;
        }
        jda = null;

        try {
            old.getRegisteredListeners().forEach(listener -> {
                try {
                    old.removeEventListener(listener);
                } catch (Exception e) {
                    // ignore
                }
            });
            old.shutdown();
        } catch (Exception ex) {
            logger.log(Level.WARNING, "[ConsoleDiscord] Error during JDA shutdown: ", ex);
            return;
        }

        if (wait) {
            awaitShutdown(old);
        } else {
            try {
                SchedulerAdapter.runAsync(plugin, () -> awaitShutdown(old));
            } catch (IllegalPluginAccessException e) {
                // Plugin is being disabled: JDA finishes its shutdown on its own threads
            }
        }
    }

    private void awaitShutdown(JDA old) {
        try {
            if (!old.awaitShutdown(Duration.ofSeconds(5))) {
                logger.warning(plugin.getMessages().getRaw("discord.shutdown_warning"));
                old.shutdownNow();
                old.awaitShutdown(Duration.ofSeconds(2));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warning(plugin.getMessages().getRaw("discord.shutdown_interrupted"));
        } catch (Exception ex) {
            logger.log(Level.WARNING, "[ConsoleDiscord] Error during JDA shutdown: ", ex);
        }
    }

    // ----------------------------------------------------------------
    // Watchdog
    // ----------------------------------------------------------------

    public void startWatchdog() {
        cancelWatchdog();
        watchdogTask = SchedulerAdapter.runAsyncTimer(plugin, this::watchdogTick, 200L, 200L);
    }

    public void cancelWatchdog() {
        if (watchdogTask != null) {
            watchdogTask.cancel();
            watchdogTask = null;
        }
    }

    void watchdogTick() {
        if (shuttingDown) {
            return;
        }

        JDA current = jda;
        if (current == null) {
            retryFailedStart();
            return;
        }

        try {
            JDA.Status status = current.getStatus();

            // lastConnected is the last time the connection was seen
            // healthy, so the outage is measured from there.
            if (status == JDA.Status.CONNECTED) {
                lastConnected = Instant.now();
                return;
            }

            // A wrong token or a rejected connection (e.g. missing
            // intents) won't get better by restarting.
            if (status == JDA.Status.FAILED_TO_LOGIN || connectionRejected) {
                return;
            }

            if (lastConnected.equals(Instant.EPOCH)) {
                lastConnected = Instant.now();
                return;
            }

            // JDA reconnects on its own; only step in when that has
            // been stuck for a while, so a restart doesn't race JDA's
            // own resume after a short network hiccup.
            long threshold = isReconnecting(status)
                    ? Math.max(restartBackoffSec, RECONNECT_GRACE_SECONDS)
                    : restartBackoffSec;

            Duration down = Duration.between(lastConnected, Instant.now());
            if (down.getSeconds() >= threshold) {
                logger.warning(plugin.getMessages().get("discord.reconnecting",
                        "seconds", String.valueOf(down.getSeconds()),
                        "status", status.toString()));

                lastConnected = Instant.now();
                restartBackoffSec = Math.min(restartBackoffSec * 2, 120);

                // The lock is only held briefly: the old connection is
                // awaited and the new one built on other async threads,
                // so reload and disable don't wait for them.
                synchronized (lock) {
                    if (!shuttingDown && jda == current) {
                        stop(false);
                        requestConnection();
                    }
                }
            }
        } catch (Exception ex) {
            logger.log(Level.WARNING, plugin.getMessages().getRaw("watchdog.error"), ex);
        }
    }

    /**
     * Retries a start whose JDA build threw, with the same doubling backoff
     * as connection restarts. Nothing happens without a usable token.
     */
    private void retryFailedStart() {
        if (!startFailed) {
            return;
        }
        Duration since = Duration.between(lastStartAttempt, Instant.now());
        if (since.getSeconds() < restartBackoffSec) {
            return;
        }
        restartBackoffSec = Math.min(restartBackoffSec * 2, 120);
        logger.warning(plugin.getMessages().getRaw("discord.retry_start"));
        synchronized (lock) {
            if (!shuttingDown && jda == null && startFailed) {
                startFailed = false;
                requestConnection();
            }
        }
    }

    /**
     * States in which JDA is still logging in or reconnecting by itself.
     */
    private static boolean isReconnecting(JDA.Status status) {
        return switch (status) {
            case INITIALIZING, INITIALIZED, LOGGING_IN, CONNECTING_TO_WEBSOCKET,
                 IDENTIFYING_SESSION, AWAITING_LOGIN_CONFIRMATION, LOADING_SUBSYSTEMS,
                 DISCONNECTED, RECONNECT_QUEUED, WAITING_TO_RECONNECT, ATTEMPTING_TO_RECONNECT -> true;
            default -> false;
        };
    }

    // ----------------------------------------------------------------
    // JDA status events
    // ----------------------------------------------------------------

    final class StatusListener extends ListenerAdapter {
        @Override
        public void onStatusChange(@NotNull StatusChangeEvent event) {
            JDA.Status newStatus = event.getNewStatus();

            if (plugin.isDebugStatusLogging()) {
                logger.info("[ConsoleDiscord] JDA Status: " + newStatus);
            }

            if (newStatus == JDA.Status.CONNECTED) {
                lastConnected = Instant.now();
                restartBackoffSec = 10;
                callbacks.onConnected(event.getJDA());
            }
        }

        /**
         * Discord closed the connection for good (JDA doesn't reconnect).
         * Shutdowns started by the plugin don't get here: the listeners are
         * removed before. A missing Message Content intent is handled by
         * reconnecting without it; other permanent errors stop the watchdog
         * from restarting the same failing connection every two minutes.
         */
        @Override
        public void onShutdown(@NotNull ShutdownEvent event) {
            CloseCode code = event.getCloseCode();
            if (code == null || code.isReconnect()) {
                return;
            }
            synchronized (lock) {
                // Only the current connection, or one not installed yet
                if (shuttingDown || (jda != null && jda != event.getJDA())) {
                    return;
                }
                if (code == CloseCode.DISALLOWED_INTENTS && messageContentEnabled) {
                    messageContentEnabled = false;
                    logger.warning(plugin.getMessages().getRaw("discord.intent_missing"));
                    stop(false);
                    requestConnection();
                    return;
                }
                connectionRejected = true;
                logger.severe(plugin.getMessages().get("discord.connection_rejected",
                        "code", String.valueOf(code.getCode()), "meaning", code.getMeaning()));
            }
        }
    }
}
