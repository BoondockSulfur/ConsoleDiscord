package dev.boondocksulfur.consolediscord;

import dev.boondocksulfur.consolediscord.audit.AuditLogger;
import org.bstats.bukkit.Metrics;
import dev.boondocksulfur.consolediscord.cleanup.MessageCleanup;
import dev.boondocksulfur.consolediscord.i18n.Messages;
import dev.boondocksulfur.consolediscord.listener.DiscordListener;
import dev.boondocksulfur.consolediscord.logging.DiscordLogAppender;
import dev.boondocksulfur.consolediscord.logging.LogFilter;
import dev.boondocksulfur.consolediscord.logging.LogFormatter;
import dev.boondocksulfur.consolediscord.performance.PerformanceMonitor;
import dev.boondocksulfur.consolediscord.scheduler.SchedulerAdapter;
import dev.boondocksulfur.consolediscord.security.CommandSecurity;
import dev.boondocksulfur.consolediscord.security.RateLimiter;
import dev.boondocksulfur.consolediscord.updater.ModrinthUpdateChecker;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.OnlineStatus;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.StatusChangeEvent;
import net.dv8tion.jda.api.exceptions.InsufficientPermissionException;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.MemberCachePolicy;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;


import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import org.jetbrains.annotations.NotNull;

import java.awt.Color;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Main plugin class for ConsoleDiscord.
 * Integrates Discord with a Minecraft server, allowing log forwarding and remote command execution.
 *
 * @author BoondockSulfur
 * @version 1.5.0
 */
public class ConsoleDiscordPlugin extends JavaPlugin {

    private JDA jda;
    private Messages messages;
    private RateLimiter rateLimiter;
    private AuditLogger auditLogger;
    private PerformanceMonitor performanceMonitor;
    private MessageCleanup messageCleanup;
    private LogFormatter logFormatter;
    private LogFilter logFilter;

    private String logChannelId;
    private String commandChannelId;
    private Set<String> allowedUserIds = new HashSet<>();
    private Set<String> allowedRoleIds = new HashSet<>();
    private Map<String, String> commandAliases = new HashMap<>();

    private DiscordLogAppender appender;
    private SchedulerAdapter.CancellableTask logTask;
    private SchedulerAdapter.CancellableTask watchdogTask;

    private volatile Instant lastConnected = Instant.EPOCH;
    private volatile long restartBackoffSec = 10;

    private boolean debugStatusLogging;
    private int logFlushTicks;
    private int maxCommandsPerMinute;
    private String language;

    // Command feedback (capture command output and reply on Discord)
    private boolean commandFeedbackEnabled;
    private long feedbackCollectTicks;

    // Startup/Shutdown notifications
    private boolean notifyStartup;
    private boolean notifyShutdown;
    private long serverStartTime;

    /**
     * Set on enable; the startup notification is sent the first time JDA
     * reaches CONNECTED instead of after a fixed delay, so it can't be
     * skipped by a slow Discord login.
     */
    private volatile boolean startupNotificationPending = false;

    // Update checker
    private boolean updateCheckEnabled;
    private ModrinthUpdateChecker updateChecker;
    private SchedulerAdapter.CancellableTask updateTask;

    /**
     * Flag to prevent watchdog from working during shutdown/reload.
     */
    private volatile boolean isShuttingDown = false;

    /**
     * Lock for synchronizing Discord connection restarts.
     */
    private final Object restartLock = new Object();

    /**
     * Set on reload; the config validation runs once the next time JDA
     * reaches CONNECTED, because channel lookups need a populated cache.
     */
    private volatile boolean configValidationPending = false;

    /**
     * Called when the plugin is enabled.
     * Initializes configuration, messages, and starts Discord integration.
     */
    @Override
    public void onEnable() {
        isShuttingDown = false;
        serverStartTime = System.currentTimeMillis();
        saveDefaultConfig();
        mergeDefaultConfig();
        saveSetupGuide();
        getLogger().info("ConsoleDiscordPlugin starting...");

        // bStats Metrics
        new Metrics(this, 31074);

        reloadPlugin();

        if (messages != null) {
            getLogger().info(messages.getRaw("plugin.enabled"));
        }

        // Startup notification is sent once JDA reaches CONNECTED
        // (see JdaStatusListener); a fixed delay would silently skip it
        // whenever the Discord login takes longer.
        startupNotificationPending = notifyStartup && jda != null;
    }

    /**
     * Merges missing keys from the default config.yml (inside the JAR) into the user's config.
     * Preserves all existing user values while adding new keys introduced in updates.
     */
    private void mergeDefaultConfig() {
        try (InputStream defaultStream = getResource("config.yml")) {
            if (defaultStream == null) {
                return;
            }

            YamlConfiguration defaultConfig = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(defaultStream));
            FileConfiguration userConfig = getConfig();

            boolean changed = false;
            for (String key : defaultConfig.getKeys(true)) {
                if (!userConfig.contains(key, true)) {
                    userConfig.set(key, defaultConfig.get(key));
                    changed = true;
                }
            }

            if (changed) {
                saveConfig();
                getLogger().info("Config updated: new options from default config have been added.");
            }
        } catch (Exception e) {
            getLogger().warning("Could not merge default config: " + e.getMessage());
        }
    }

    /**
     * Copies the bundled SETUP.md guide into the plugin data folder on first run,
     * so server admins find the setup instructions right next to the config.
     * Existing files are never overwritten (admins may have added their own notes).
     */
    private void saveSetupGuide() {
        File guide = new File(getDataFolder(), "SETUP.md");
        if (guide.exists()) {
            return;
        }
        try {
            saveResource("SETUP.md", false);
        } catch (Exception e) {
            getLogger().warning("Could not write SETUP.md: " + e.getMessage());
        }
    }

    /**
     * Called when the plugin is disabled.
     * Shuts down Discord connection and cleans up resources.
     */
    @Override
    public void onDisable() {
        isShuttingDown = true;
        getLogger().info("[ConsoleDiscord] Plugin shutting down...");

        // Send shutdown notification (blocks with a hard 5s timeout)
        if (notifyShutdown && jda != null && jda.getStatus() == JDA.Status.CONNECTED) {
            sendShutdownNotification();
        }

        cancelWatchdog();
        cancelUpdateTask();
        stopPerformanceMonitor();
        stopMessageCleanup();
        stopDiscordStuff();
        removeLogAppender();

        if (auditLogger != null) {
            auditLogger.shutdown();
        }

        if (messages != null) {
            getLogger().info(messages.getRaw("plugin.disabled"));
        }
    }

    // ----------------------------------------------------------------
    // Public Accessors
    // ----------------------------------------------------------------

    public JDA getJda() {
        return jda;
    }

    public Messages getMessages() {
        return messages;
    }

    public RateLimiter getRateLimiter() {
        return rateLimiter;
    }

    public AuditLogger getAuditLogger() {
        return auditLogger;
    }

    public String getCommandChannelId() {
        return commandChannelId;
    }

    public int getMaxCommandsPerMinute() {
        return maxCommandsPerMinute;
    }

    public Map<String, String> getCommandAliases() {
        return new HashMap<>(commandAliases);
    }

    /**
     * Checks whether a Discord user may execute commands, either directly
     * via the user whitelist or through one of their guild roles.
     * Empty whitelists mean nobody is allowed (deny by default) — the
     * plugin executes console commands, so access must be granted explicitly.
     *
     * @param userId The Discord user ID
     * @param member The guild member (for role checks), may be null
     */
    public boolean isUserAllowed(String userId, Member member) {
        if (allowedUserIds.contains(userId)) {
            return true;
        }
        if (member != null && !allowedRoleIds.isEmpty()) {
            return member.getRoles().stream()
                    .anyMatch(role -> allowedRoleIds.contains(role.getId()));
        }
        return false;
    }

    public boolean isCommandFeedbackEnabled() {
        return commandFeedbackEnabled;
    }

    public long getFeedbackCollectTicks() {
        return feedbackCollectTicks;
    }

    public boolean isDebugStatusLogging() {
        return debugStatusLogging;
    }

    public void setDebugStatusLogging(boolean debugStatusLogging) {
        this.debugStatusLogging = debugStatusLogging;
        getConfig().set("debug-status-logging", debugStatusLogging);
        saveConfig();
    }

    // ----------------------------------------------------------------
    // Reload & Commands
    // ----------------------------------------------------------------

    private void reloadPlugin() {
        synchronized (restartLock) {
            isShuttingDown = true;
            getLogger().info(messages != null ? messages.getRaw("plugin.reloading") : "[ConsoleDiscord] Reloading...");

            reloadConfig();
            FileConfiguration cfg = getConfig();

            // Basic config
            this.logChannelId = cfg.getString("log-channel-id", "");
            this.commandChannelId = cfg.getString("command-channel-id", "");
            this.allowedUserIds = loadIdList(cfg, "allowed-user-ids");
            this.allowedRoleIds = loadIdList(cfg, "allowed-role-ids");
            this.logFlushTicks = cfg.getInt("log-flush-ticks", 40);
            this.debugStatusLogging = cfg.getBoolean("debug-status-logging", false);
            this.maxCommandsPerMinute = cfg.getInt("max-commands-per-minute", 5);
            this.language = cfg.getString("language", "en");

            // Command feedback
            this.commandFeedbackEnabled = cfg.getBoolean("command-feedback.enabled", true);
            this.feedbackCollectTicks = cfg.getLong("command-feedback.collect-ticks", 20L);

            // Notifications
            this.notifyStartup = cfg.getBoolean("notifications.startup", true);
            this.notifyShutdown = cfg.getBoolean("notifications.shutdown", true);

            // Update checker
            this.updateCheckEnabled = cfg.getBoolean("update-checker.enabled", true);

            // Initialize messages
            if (messages == null) {
                messages = new Messages(this, language);
            } else {
                messages.reload(language);
            }

            if (allowedUserIds.isEmpty() && allowedRoleIds.isEmpty()) {
                getLogger().warning(messages.getRaw("security.no_allowed_users"));
            }

            // Initialize rate limiter (always recreate to pick up new maxCommands value)
            rateLimiter = new RateLimiter(maxCommandsPerMinute, 60);

            // Load command security settings
            loadCommandSecurity(cfg);

            // Load command aliases
            loadCommandAliases(cfg);

            // Initialize audit logger (shut down the previous instance so its
            // writer thread doesn't leak and a disabled audit really stops)
            if (auditLogger != null) {
                auditLogger.shutdown();
                auditLogger = null;
            }
            boolean auditEnabled = cfg.getBoolean("command-audit.enabled", true);
            if (auditEnabled) {
                String auditFile = cfg.getString("command-audit.log-file", "audit.log");
                boolean logToDiscord = cfg.getBoolean("command-audit.log-to-discord", false);
                String auditChannelId = cfg.getString("command-audit.audit-channel-id", "");
                long maxFileSizeMb = cfg.getLong("command-audit.max-file-size-mb", 10);
                auditLogger = new AuditLogger(this, auditFile, logToDiscord, auditChannelId, maxFileSizeMb);
            }

            // Initialize log formatter
            boolean useEmbeds = cfg.getBoolean("log-formatting.use-embeds", true);
            boolean useEmojis = cfg.getBoolean("log-formatting.use-emojis", true);
            int embedBatchSize = cfg.getInt("log-formatting.embed-batch-size", 10);
            logFormatter = new LogFormatter(useEmbeds, useEmojis, embedBatchSize);

            // Initialize log filter
            List<String> logLevels = cfg.getStringList("log-levels");
            List<String> ignorePatterns = cfg.getStringList("log-filters.ignore-patterns");
            boolean categoriesEnabled = cfg.getBoolean("log-categories.enabled", false);
            Map<String, LogFilter.CategoryFilter> categoryFilters = loadCategoryFilters(cfg);
            logFilter = new LogFilter(logLevels, ignorePatterns, categoryFilters, categoriesEnabled);

            cancelWatchdog();
            cancelUpdateTask();
            stopPerformanceMonitor();
            stopMessageCleanup();
            stopDiscordStuff();
            removeLogAppender();

            isShuttingDown = false;

            startDiscordStuff();
            // Channel validation must wait until JDA is CONNECTED (the cache
            // is empty right after build()); JdaStatusListener triggers it.
            configValidationPending = true;
            setupLogAppender();
            startWatchdog();
            startPerformanceMonitor(cfg);
            startMessageCleanup(cfg);
            startUpdateChecker(cfg);

            getLogger().info(messages.getRaw("plugin.reloaded"));
        }
    }

    private void loadCommandSecurity(FileConfiguration cfg) {
        boolean securityEnabled = cfg.getBoolean("command-security.enabled", true);
        List<String> blockedCommands = cfg.getStringList("command-security.blocked-commands");

        CommandSecurity.configure(securityEnabled, blockedCommands);

        if (securityEnabled) {
            Set<String> blocked = CommandSecurity.getBlockedCommands();
            if (!blocked.isEmpty()) {
                getLogger().info(messages.get("security.commands_blocked",
                    "count", String.valueOf(blocked.size()),
                    "commands", String.join(", ", blocked)));
            } else {
                getLogger().info(messages.getRaw("security.no_commands_blocked"));
            }
        } else {
            getLogger().warning(messages.getRaw("security.disabled"));
        }
    }

    private void loadCommandAliases(FileConfiguration cfg) {
        commandAliases.clear();
        if (cfg.getBoolean("command-aliases.enabled", true)) {
            ConfigurationSection section = cfg.getConfigurationSection("command-aliases.aliases");
            if (section != null) {
                for (String key : section.getKeys(false)) {
                    String target = section.getString(key);
                    if (target == null || target.isBlank()) {
                        getLogger().warning(messages.get("security.alias_invalid", "alias", key));
                    } else if (!CommandSecurity.isSafeCommand(target)) {
                        getLogger().warning(messages.get("security.alias_blocked",
                                "alias", key, "command", target));
                    } else {
                        commandAliases.put(key.toLowerCase(Locale.ROOT), target);
                    }
                }
            }
        }
    }

    /**
     * Loads a list of Discord IDs from config, handling both quoted strings and unquoted numbers.
     * Filters out placeholder values and empty strings.
     */
    private Set<String> loadIdList(FileConfiguration cfg, String path) {
        Set<String> ids = new HashSet<>();
        List<?> rawList = cfg.getList(path);
        if (rawList == null) {
            return ids;
        }
        for (Object item : rawList) {
            if (item == null) continue;
            String id = String.valueOf(item).trim();
            // Skip empty strings and placeholder values
            if (!id.isEmpty() && !id.equals("123456789012345678")) {
                ids.add(id);
            }
        }
        return ids;
    }

    private Map<String, LogFilter.CategoryFilter> loadCategoryFilters(FileConfiguration cfg) {
        Map<String, LogFilter.CategoryFilter> filters = new HashMap<>();

        ConfigurationSection categoriesSection = cfg.getConfigurationSection("log-categories.categories");
        ConfigurationSection patternsSection = cfg.getConfigurationSection("log-categories.patterns");

        if (categoriesSection != null && patternsSection != null) {
            for (String category : categoriesSection.getKeys(false)) {
                String channelId = categoriesSection.getString(category, "");
                List<String> patterns = patternsSection.getStringList(category);

                if (!channelId.isBlank() && !patterns.isEmpty()) {
                    filters.put(category, new LogFilter.CategoryFilter(category, channelId, patterns));
                }
            }
        }

        return filters;
    }

    private void validateConfiguration() {
        if (jda == null) {
            return;
        }

        boolean valid = true;

        if (!logChannelId.isBlank()) {
            TextChannel logChannel = jda.getTextChannelById(logChannelId);
            if (logChannel == null) {
                getLogger().warning(messages.get("config.invalid_log_channel", "id", logChannelId));
                valid = false;
            }
        }

        if (!commandChannelId.isBlank()) {
            TextChannel commandChannel = jda.getTextChannelById(commandChannelId);
            if (commandChannel == null) {
                getLogger().warning(messages.get("config.invalid_command_channel", "id", commandChannelId));
                valid = false;
            }
        }

        if (valid) {
            getLogger().info(messages.getRaw("config.validation_passed"));
        } else {
            getLogger().warning(messages.getRaw("config.validation_failed"));
        }
    }

    @Override
    public boolean onCommand(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            @NotNull String[] args
    ) {
        if (!command.getName().equalsIgnoreCase("cdr")) {
            return false;
        }

        if (args.length == 0) {
            sender.sendMessage(ChatColor.YELLOW + messages.get("command.usage",
                    "usage", ChatColor.GOLD + messages.getRaw("command.usage_cdr")));
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        if (sub.equals("reload")) {
            if (!sender.hasPermission("consolediscord.reload")) {
                sender.sendMessage(ChatColor.RED + messages.getRaw("command.no_permission"));
                return true;
            }

            reloadPlugin();
            sender.sendMessage(ChatColor.GREEN + messages.getRaw("command.reload_success"));
            return true;
        }

        if (sub.equals("debug")) {
            if (!sender.hasPermission("consolediscord.debug")) {
                sender.sendMessage(ChatColor.RED + messages.getRaw("command.no_permission"));
                return true;
            }

            if (args.length == 1) {
                boolean newVal = !isDebugStatusLogging();
                setDebugStatusLogging(newVal);
                String status = newVal ? ChatColor.GREEN + messages.getRaw("status.on")
                                       : ChatColor.RED + messages.getRaw("status.off");
                sender.sendMessage(ChatColor.YELLOW + messages.get("command.debug_status", "status", status));
                return true;
            }

            String mode = args[1].toLowerCase(Locale.ROOT);
            switch (mode) {
                case "on" -> {
                    setDebugStatusLogging(true);
                    sender.sendMessage(ChatColor.YELLOW + messages.get("command.debug_status",
                            "status", ChatColor.GREEN + messages.getRaw("status.on")));
                }
                case "off" -> {
                    setDebugStatusLogging(false);
                    sender.sendMessage(ChatColor.YELLOW + messages.get("command.debug_status",
                            "status", ChatColor.RED + messages.getRaw("status.off")));
                }
                case "status" -> {
                    String status = isDebugStatusLogging()
                            ? ChatColor.GREEN + messages.getRaw("status.on")
                            : ChatColor.RED + messages.getRaw("status.off");
                    sender.sendMessage(ChatColor.YELLOW + messages.get("command.debug_status", "status", status));
                }
                default -> sender.sendMessage(ChatColor.RED + messages.getRaw("command.usage_cdr"));
            }
            return true;
        }

        if (sub.equals("status")) {
            if (!sender.hasPermission("consolediscord.status")) {
                sender.sendMessage(ChatColor.RED + messages.getRaw("command.no_permission"));
                return true;
            }

            sendStatus(sender);
            return true;
        }

        if (sub.equals("cleanup")) {
            if (!sender.hasPermission("consolediscord.cleanup")) {
                sender.sendMessage(ChatColor.RED + messages.getRaw("command.no_permission"));
                return true;
            }

            if (messageCleanup == null) {
                sender.sendMessage(ChatColor.RED + messages.getRaw("command.cleanup_disabled"));
            } else {
                messageCleanup.cleanupNow();
                sender.sendMessage(ChatColor.GREEN + messages.getRaw("command.cleanup_started"));
            }
            return true;
        }

        sender.sendMessage(ChatColor.RED + messages.getRaw("command.unknown_subcommand"));
        return true;
    }

    /**
     * Sends a status overview (connection, channels, queue, whitelist) to the sender.
     */
    private void sendStatus(CommandSender sender) {
        JDA current = jda;

        sender.sendMessage(ChatColor.GOLD + messages.getRaw("status.header"));
        sender.sendMessage(ChatColor.YELLOW + messages.get("status.version",
                "version", getDescription().getVersion()));
        sender.sendMessage(ChatColor.YELLOW + messages.get("status.server_type",
                "type", SchedulerAdapter.isFolia() ? "Folia" : "Paper/Spigot"));

        String jdaStatus = current != null ? current.getStatus().toString() : messages.getRaw("status.not_started");
        sender.sendMessage(ChatColor.YELLOW + messages.get("status.discord", "status",
                (current != null && current.getStatus() == JDA.Status.CONNECTED
                        ? ChatColor.GREEN : ChatColor.RED) + jdaStatus + ChatColor.YELLOW));

        sender.sendMessage(ChatColor.YELLOW + messages.get("status.log_channel",
                "status", describeChannel(current, logChannelId)));
        sender.sendMessage(ChatColor.YELLOW + messages.get("status.command_channel",
                "status", describeChannel(current, commandChannelId)));

        int queued = appender != null ? appender.getQueueSize() : 0;
        sender.sendMessage(ChatColor.YELLOW + messages.get("status.log_queue",
                "count", String.valueOf(queued)));

        sender.sendMessage(ChatColor.YELLOW + messages.get("status.whitelist",
                "users", String.valueOf(allowedUserIds.size()),
                "roles", String.valueOf(allowedRoleIds.size())));

        if (updateChecker != null && updateChecker.isUpdateAvailable()) {
            sender.sendMessage(ChatColor.GOLD + messages.get("status.update_available",
                    "version", updateChecker.getLatestVersion()));
        }
    }

    /**
     * Describes a configured channel for the status output:
     * not set, valid (with name) or not found.
     */
    private String describeChannel(JDA current, String channelId) {
        if (channelId == null || channelId.isBlank()) {
            return ChatColor.GRAY + messages.getRaw("status.not_set") + ChatColor.YELLOW;
        }
        if (current == null || current.getStatus() != JDA.Status.CONNECTED) {
            return ChatColor.GRAY + channelId + ChatColor.YELLOW;
        }
        TextChannel channel = current.getTextChannelById(channelId);
        if (channel == null) {
            return ChatColor.RED + messages.get("status.channel_invalid", "id", channelId) + ChatColor.YELLOW;
        }
        return ChatColor.GREEN + "#" + channel.getName() + ChatColor.YELLOW;
    }

    @Override
    public List<String> onTabComplete(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String alias,
            @NotNull String[] args
    ) {
        if (!command.getName().equalsIgnoreCase("cdr")) {
            return Collections.emptyList();
        }

        if (args.length == 1) {
            List<String> subs = new ArrayList<>();
            if (sender.hasPermission("consolediscord.reload")) subs.add("reload");
            if (sender.hasPermission("consolediscord.debug")) subs.add("debug");
            if (sender.hasPermission("consolediscord.status")) subs.add("status");
            if (sender.hasPermission("consolediscord.cleanup")) subs.add("cleanup");
            return filterCompletions(subs, args[0]);
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("debug")
                && sender.hasPermission("consolediscord.debug")) {
            return filterCompletions(List.of("on", "off", "status"), args[1]);
        }

        return Collections.emptyList();
    }

    private List<String> filterCompletions(List<String> options, String input) {
        String lower = input.toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        for (String option : options) {
            if (option.startsWith(lower)) {
                result.add(option);
            }
        }
        return result;
    }

    // ----------------------------------------------------------------
    // JDA Start/Stop
    // ----------------------------------------------------------------

    private void startDiscordStuff() {
        // Environment variable takes precedence so the token can be kept
        // out of config.yml (and out of config backups/support pastes).
        String token = System.getenv("CONSOLEDISCORD_BOT_TOKEN");
        if (token != null && !token.isBlank()) {
            token = token.trim();
            getLogger().info(messages.getRaw("discord.token_from_env"));
        } else {
            token = getConfig().getString("bot-token", "").trim();
        }
        if (token.isEmpty() || "DEIN_DISCORD_BOT_TOKEN".equals(token) || "YOUR_DISCORD_BOT_TOKEN".equals(token)) {
            getLogger().warning(messages.getRaw("discord.no_token"));
            return;
        }

        // Validate token format (Discord bot tokens have a specific structure)
        if (!isValidBotToken(token)) {
            getLogger().warning(messages.getRaw("discord.invalid_token_format"));
            return;
        }

        try {
            JDABuilder builder = JDABuilder.createDefault(token)
                    .setStatus(OnlineStatus.ONLINE)
                    .setActivity(Activity.playing("Minecraft"))
                    .enableIntents(
                            GatewayIntent.GUILD_MESSAGES,
                            GatewayIntent.MESSAGE_CONTENT
                    )
                    .setMemberCachePolicy(MemberCachePolicy.NONE)
                    .addEventListeners(
                            new DiscordListener(this),
                            new JdaStatusListener()
                    );

            jda = builder.build();

            // Set JDA for audit logger
            if (auditLogger != null) {
                auditLogger.setJda(jda);
            }

        } catch (Exception ex) {
            getLogger().log(java.util.logging.Level.SEVERE,
                    messages.get("discord.startup_error", "error", ex.getMessage()), ex);
        }
    }

    /**
     * Validates if a string is a valid Discord bot token format.
     * Discord bot tokens consist of three base64-encoded parts separated by dots.
     *
     * @param token The token to validate
     * @return true if the token format is valid, false otherwise
     */
    private boolean isValidBotToken(String token) {
        if (token == null || token.isEmpty()) {
            return false;
        }

        // Discord bot tokens have the format: BASE64.BASE64.BASE64
        // The first part is the bot's user ID in base64
        // Basic validation: check for two dots and reasonable length
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            return false;
        }

        // Each part should be non-empty and contain valid base64-like characters
        for (String part : parts) {
            if (part.isEmpty() || !part.matches("[A-Za-z0-9_-]+")) {
                return false;
            }
        }

        // Token should be at least 50 characters (typical Discord tokens are 59-70 chars)
        return token.length() >= 50;
    }

    private void stopDiscordStuff() {
        if (jda != null) {
            try {
                jda.getRegisteredListeners().forEach(listener -> {
                    try {
                        jda.removeEventListener(listener);
                    } catch (Exception e) {
                        // ignore
                    }
                });

                jda.shutdown();

                if (!jda.awaitShutdown(Duration.ofSeconds(5))) {
                    getLogger().warning(messages.getRaw("discord.shutdown_warning"));
                    jda.shutdownNow();
                    jda.awaitShutdown(Duration.ofSeconds(2));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                getLogger().warning(messages.getRaw("discord.shutdown_interrupted"));
            } catch (Exception ex) {
                getLogger().log(java.util.logging.Level.WARNING,
                        "[ConsoleDiscord] Error during JDA shutdown: ", ex);
            } finally {
                jda = null;
            }
        }
    }

    // ----------------------------------------------------------------
    // Startup/Shutdown Notifications
    // ----------------------------------------------------------------

    private void sendStartupNotification() {
        if (jda == null || jda.getStatus() != JDA.Status.CONNECTED) {
            return;
        }

        if (logChannelId == null || logChannelId.isBlank()) {
            return;
        }

        TextChannel channel = jda.getTextChannelById(logChannelId);
        if (channel == null) {
            return;
        }

        String version = getDescription().getVersion();
        int pluginCount = Bukkit.getPluginManager().getPlugins().length;

        MessageEmbed embed = new EmbedBuilder()
                .setTitle(messages.getRaw("notification.startup_title"))
                .setColor(Color.GREEN)
                .setDescription(messages.getRaw("notification.startup_description"))
                .addField(messages.getRaw("notification.version"), version, true)
                .addField(messages.getRaw("notification.plugins"), String.valueOf(pluginCount), true)
                .setTimestamp(Instant.now())
                .build();

        channel.sendMessageEmbeds(embed).queue();
    }

    private void sendShutdownNotification() {
        if (logChannelId == null || logChannelId.isBlank()) {
            return;
        }

        TextChannel channel = jda.getTextChannelById(logChannelId);
        if (channel == null) {
            return;
        }

        long uptime = (System.currentTimeMillis() - serverStartTime) / 1000;
        long hours = uptime / 3600;
        long minutes = (uptime % 3600) / 60;

        MessageEmbed embed = new EmbedBuilder()
                .setTitle(messages.getRaw("notification.shutdown_title"))
                .setColor(Color.RED)
                .setDescription(messages.getRaw("notification.shutdown_description"))
                .addField(messages.getRaw("notification.uptime"), String.format("%dh %dm", hours, minutes), false)
                .setTimestamp(Instant.now())
                .build();

        try {
            // complete() has no timeout and retries rate limits forever, which
            // could hang onDisable(); submit().get() enforces a hard limit.
            channel.sendMessageEmbeds(embed).submit().get(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            // Ignore - server is shutting down anyway
        }
    }

    // ----------------------------------------------------------------
    // Update Checker
    // ----------------------------------------------------------------

    /**
     * Schedules the update check: once shortly after startup and then
     * periodically (default every 24h), so long-running servers still
     * learn about new versions. interval-hours 0 disables the re-check.
     */
    private void startUpdateChecker(FileConfiguration cfg) {
        if (!updateCheckEnabled) {
            return;
        }

        long intervalHours = cfg.getLong("update-checker.interval-hours", 24);
        if (intervalHours > 0) {
            long intervalTicks = intervalHours * 60 * 60 * 20;
            updateTask = SchedulerAdapter.runAsyncTimer(this, this::checkForUpdates, 40L, intervalTicks);
        } else {
            SchedulerAdapter.runAsyncLater(this, this::checkForUpdates, 40L);
        }
    }

    private void cancelUpdateTask() {
        if (updateTask != null) {
            updateTask.cancel();
            updateTask = null;
        }
    }

    private void checkForUpdates() {
        try {
            updateChecker = new ModrinthUpdateChecker(this);

            if (updateChecker.checkForUpdates()) {
                updateChecker.logResult();

                // Optionally send to Discord
                if (jda != null && !logChannelId.isBlank()) {
                    sendUpdateNotification();
                }
            } else {
                updateChecker.logResult();
            }
        } catch (Exception e) {
            getLogger().log(java.util.logging.Level.WARNING, "Failed to check for updates", e);
        }
    }

    private void sendUpdateNotification() {
        if (!updateCheckEnabled || updateChecker == null || !updateChecker.isUpdateAvailable()) {
            return;
        }

        TextChannel channel = jda.getTextChannelById(logChannelId);
        if (channel == null) {
            return;
        }

        MessageEmbed embed = new EmbedBuilder()
                .setTitle(messages.getRaw("notification.update_title"))
                .setColor(Color.ORANGE)
                .setDescription(messages.getRaw("notification.update_description"))
                .addField(messages.getRaw("notification.current_version"), updateChecker.getCurrentVersion(), true)
                .addField(messages.getRaw("notification.latest_version"), updateChecker.getLatestVersion(), true)
                .addField(messages.getRaw("notification.download"), "[" + messages.getRaw("notification.download_link") + "](" + updateChecker.getDownloadUrl() + ")", false)
                .setTimestamp(Instant.now())
                .setFooter(messages.getRaw("notification.update_footer"))
                .build();

        try {
            channel.sendMessageEmbeds(embed).queue();
        } catch (Exception e) {
            getLogger().log(java.util.logging.Level.WARNING, "Failed to send update notification to Discord", e);
        }
    }

    // ----------------------------------------------------------------
    // JDA Status Listener
    // ----------------------------------------------------------------

    private final class JdaStatusListener extends ListenerAdapter {
        @Override
        public void onStatusChange(@NotNull StatusChangeEvent event) {
            JDA.Status newStatus = event.getNewStatus();

            if (debugStatusLogging) {
                getLogger().info("[ConsoleDiscord] JDA Status: " + newStatus);
            }

            if (newStatus == JDA.Status.CONNECTED) {
                lastConnected = Instant.now();
                restartBackoffSec = 10;

                if (configValidationPending) {
                    configValidationPending = false;
                    validateConfiguration();
                }

                if (startupNotificationPending) {
                    startupNotificationPending = false;
                    sendStartupNotification();
                }
            }
        }
    }

    // ----------------------------------------------------------------
    // Watchdog
    // ----------------------------------------------------------------

    private void startWatchdog() {
        if (watchdogTask != null) {
            watchdogTask.cancel();
        }

        watchdogTask = SchedulerAdapter.runAsyncTimer(
                this,
                () -> {
                    if (isShuttingDown) {
                        return;
                    }

                    JDA current = jda;
                    if (current == null) {
                        return;
                    }

                    try {
                        JDA.Status status = current.getStatus();

                        if (status == JDA.Status.CONNECTED
                                || status == JDA.Status.LOADING_SUBSYSTEMS
                                || status == JDA.Status.AWAITING_LOGIN_CONFIRMATION
                                || status == JDA.Status.IDENTIFYING_SESSION
                                || status == JDA.Status.ATTEMPTING_TO_RECONNECT
                                || status == JDA.Status.RECONNECT_QUEUED
                                || status == JDA.Status.WAITING_TO_RECONNECT) {
                            return;
                        }

                        if (lastConnected.equals(Instant.EPOCH)) {
                            lastConnected = Instant.now();
                            return;
                        }

                        Duration d = Duration.between(lastConnected, Instant.now());
                        if (d.getSeconds() >= restartBackoffSec) {
                            getLogger().warning(messages.get("discord.reconnecting",
                                    "seconds", String.valueOf(d.getSeconds()),
                                    "status", status.toString()));

                            lastConnected = Instant.now();
                            restartBackoffSec = Math.min(restartBackoffSec * 2, 120);

                            // Restart asynchronously: stopDiscordStuff() waits up to
                            // ~7s on awaitShutdown, which must not block the main thread.
                            SchedulerAdapter.runAsync(this, () -> {
                                synchronized (restartLock) {
                                    if (!isShuttingDown) {
                                        stopDiscordStuff();
                                        startDiscordStuff();
                                    }
                                }
                            });
                        }
                    } catch (Exception ex) {
                        getLogger().log(java.util.logging.Level.WARNING,
                                messages.getRaw("watchdog.error"), ex);
                    }
                },
                200L,
                200L
        );
    }

    private void cancelWatchdog() {
        if (watchdogTask != null) {
            watchdogTask.cancel();
            watchdogTask = null;
        }
    }

    // ----------------------------------------------------------------
    // Performance Monitor
    // ----------------------------------------------------------------

    private void startPerformanceMonitor(FileConfiguration cfg) {
        boolean enabled = cfg.getBoolean("performance-alerts.enabled", true);
        if (!enabled) {
            return;
        }

        double lowTps = cfg.getDouble("performance-alerts.low-tps-threshold", 15.0);
        int highMemory = cfg.getInt("performance-alerts.high-memory-threshold", 90);
        long cooldown = cfg.getLong("performance-alerts.alert-cooldown", 300);

        performanceMonitor = new PerformanceMonitor(this, messages, lowTps, highMemory, cooldown, logChannelId);
        performanceMonitor.setJda(jda);
        performanceMonitor.start();
    }

    private void stopPerformanceMonitor() {
        if (performanceMonitor != null) {
            performanceMonitor.stop();
            performanceMonitor = null;
        }
    }

    // ----------------------------------------------------------------
    // Message Cleanup
    // ----------------------------------------------------------------

    private void startMessageCleanup(FileConfiguration cfg) {
        boolean enabled = cfg.getBoolean("auto-cleanup.enabled", false);
        if (!enabled) {
            return;
        }

        int days = cfg.getInt("auto-cleanup.cleanup-after-days", 7);
        long hours = cfg.getLong("auto-cleanup.check-interval-hours", 24);

        messageCleanup = new MessageCleanup(this, days, hours);
        messageCleanup.setJda(jda, logChannelId);
        messageCleanup.start();
    }

    private void stopMessageCleanup() {
        if (messageCleanup != null) {
            messageCleanup.stop();
            messageCleanup = null;
        }
    }

    // ----------------------------------------------------------------
    // Log4j → Discord Appender
    // ----------------------------------------------------------------

    private void setupLogAppender() {
        removeLogAppender();

        try {
            LoggerContext ctx = (LoggerContext) LogManager.getContext(
                    this.getClass().getClassLoader(),
                    false
            );
            Configuration config = ctx.getConfiguration();
            LoggerConfig rootLoggerConfig = config.getRootLogger();

            DiscordLogAppender discordAppender = DiscordLogAppender.create("ConsoleDiscordAppender", getName());
            discordAppender.start();

            rootLoggerConfig.addAppender(discordAppender, org.apache.logging.log4j.Level.INFO, null);
            ctx.updateLoggers();

            this.appender = discordAppender;

            if (logFlushTicks > 0) {
                logTask = SchedulerAdapter.runAsyncTimer(this, this::flushDiscordLogs, logFlushTicks, logFlushTicks);
            }

        } catch (Exception ex) {
            getLogger().log(java.util.logging.Level.WARNING,
                    messages.getRaw("log.appender_register_failed"), ex);
        }
    }

    private void removeLogAppender() {
        if (logTask != null) {
            logTask.cancel();
            logTask = null;
        }

        try {
            if (appender != null) {
                LoggerContext ctx = (LoggerContext) LogManager.getContext(
                        this.getClass().getClassLoader(),
                        false
                );
                Configuration config = ctx.getConfiguration();
                LoggerConfig rootLoggerConfig = config.getRootLogger();

                rootLoggerConfig.removeAppender(appender.getName());
                ctx.updateLoggers();
                appender.stop();
            }
        } catch (Exception ex) {
            getLogger().log(java.util.logging.Level.WARNING,
                    messages != null ? messages.getRaw("log.appender_remove_failed")
                                     : "[ConsoleDiscord] Error removing LogAppender: ", ex);
        } finally {
            appender = null;
        }
    }

    private void flushDiscordLogs() {
        if (isShuttingDown) {
            return;
        }
        if (jda == null || jda.getStatus() != JDA.Status.CONNECTED) {
            return;
        }
        if (logChannelId == null || logChannelId.isBlank()) {
            return;
        }
        if (appender == null) {
            return;
        }

        List<String> lines = appender.drain(50);
        if (lines.isEmpty()) {
            return;
        }

        // Filter logs
        List<String> filteredLines = new ArrayList<>();
        Map<String, List<String>> categorizedLogs = new HashMap<>();

        for (String line : lines) {
            if (!logFilter.shouldSendLog(line)) {
                continue;
            }

            String category = logFilter.getCategory(line);
            if (category != null) {
                categorizedLogs.computeIfAbsent(category, k -> new ArrayList<>()).add(line);
            } else {
                filteredLines.add(line);
            }
        }

        // Send regular logs to main channel
        if (!filteredLines.isEmpty()) {
            sendLogsToChannel(logChannelId, filteredLines);
        }

        // Send categorized logs to their respective channels
        for (Map.Entry<String, List<String>> entry : categorizedLogs.entrySet()) {
            String channelId = logFilter.getCategoryChannelId(entry.getKey());
            if (channelId != null && !channelId.isBlank()) {
                sendLogsToChannel(channelId, entry.getValue());
            }
        }
    }

    private void sendLogsToChannel(String channelId, List<String> lines) {
        TextChannel channel = jda.getTextChannelById(channelId);
        if (channel == null) {
            return;
        }

        if (logFormatter.isUsingEmbeds()) {
            List<List<MessageEmbed>> groups = logFormatter.formatAsEmbedGroups(lines);
            for (List<MessageEmbed> group : groups) {
                if (!group.isEmpty()) {
                    channel.sendMessageEmbeds(group).queue(
                            success -> {},
                            this::handleLogSendError
                    );
                }
            }
        } else {
            String content = logFormatter.formatAsCodeBlock(lines);
            if (!content.isBlank()) {
                channel.sendMessage(content).queue(
                        success -> {},
                        this::handleLogSendError
                );
            }
        }
    }

    private void handleLogSendError(Throwable error) {
        if (error instanceof InsufficientPermissionException) {
            getLogger().warning(messages.getRaw("log.no_permission"));
            if (logTask != null) {
                logTask.cancel();
                logTask = null;
            }
        } else {
            getLogger().log(java.util.logging.Level.WARNING,
                    messages.getRaw("log.send_failed"), error);
        }
    }
}
