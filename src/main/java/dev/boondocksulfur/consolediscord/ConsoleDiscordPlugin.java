package dev.boondocksulfur.consolediscord;

import dev.boondocksulfur.consolediscord.audit.AuditLogger;
import dev.boondocksulfur.consolediscord.command.CdrCommand;
import dev.boondocksulfur.consolediscord.config.ConfigMerger;
import dev.boondocksulfur.consolediscord.discord.DiscordConnection;
import dev.boondocksulfur.consolediscord.security.CommandPermissions;
import org.bstats.bukkit.Metrics;
import dev.boondocksulfur.consolediscord.cleanup.MessageCleanup;
import dev.boondocksulfur.consolediscord.i18n.Messages;
import dev.boondocksulfur.consolediscord.listener.UpdateNotifyListener;
import dev.boondocksulfur.consolediscord.logging.DiscordLogAppender;
import dev.boondocksulfur.consolediscord.logging.LogFilter;
import dev.boondocksulfur.consolediscord.logging.LogFormatter;
import dev.boondocksulfur.consolediscord.logging.LogForwarder;
import dev.boondocksulfur.consolediscord.performance.PerformanceMonitor;
import dev.boondocksulfur.consolediscord.scheduler.SchedulerAdapter;
import dev.boondocksulfur.consolediscord.security.CommandSecurity;
import dev.boondocksulfur.consolediscord.security.RateLimiter;
import dev.boondocksulfur.consolediscord.updater.ModrinthUpdateChecker;
import dev.boondocksulfur.consolediscord.updater.UpdateNotifier;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.utils.messages.MessageRequest;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;


import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;

import java.awt.Color;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Main plugin class for ConsoleDiscord.
 * Integrates Discord with a Minecraft server, allowing log forwarding and remote command execution.
 *
 * @author BoondockSulfur
 * @version 2.2.0
 */
public class ConsoleDiscordPlugin extends JavaPlugin {

    private Messages messages;
    // Read by JDA threads, replaced on reload
    private volatile RateLimiter rateLimiter;
    private volatile AuditLogger auditLogger;
    private PerformanceMonitor performanceMonitor;
    private MessageCleanup messageCleanup;
    private volatile LogFormatter logFormatter;
    private volatile LogFilter logFilter;

    private volatile String logChannelId = "";
    private volatile String commandChannelId = "";
    private volatile Set<String> allowedUserIds = Set.of();
    private volatile Set<String> allowedRoleIds = Set.of();
    private volatile Map<String, String> commandAliases = Map.of();
    private volatile boolean slashCommandAdminOnly = true;
    private volatile boolean publicReplies = false;

    /**
     * Per-user and per-role command lists (null = feature disabled).
     */
    private volatile CommandPermissions commandPermissions;

    private DiscordConnection connection;

    private volatile DiscordLogAppender appender;
    private volatile LogForwarder logForwarder;
    private SchedulerAdapter.CancellableTask logTask;
    private volatile boolean debugStatusLogging;
    private int logFlushTicks;
    private volatile int maxCommandsPerMinute;
    private String language;

    // Command feedback (capture command output and reply on Discord)
    private volatile boolean commandFeedbackEnabled;
    private volatile long feedbackCollectTicks;

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

    private UpdateNotifier updateNotifier;

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
        connection = new DiscordConnection(this, new DiscordConnection.Callbacks() {
            @Override
            public void onInstalled(JDA jda) {
                onDiscordInstalled(jda);
            }

            @Override
            public void onConnected(JDA jda) {
                onDiscordConnected(jda);
            }
        });
        serverStartTime = System.currentTimeMillis();
        saveDefaultConfig();
        mergeDefaultConfig();
        saveSetupGuide();
        getLogger().info("ConsoleDiscordPlugin starting...");

        // bStats Metrics
        new Metrics(this, 31074);

        // Log lines and command output contain player-controlled text, so the
        // bot never pings anyone (@everyone, roles, users). The setting is
        // static, but JDA is relocated, so it only affects this plugin.
        MessageRequest.setDefaultMentions(EnumSet.noneOf(Message.MentionType.class));

        // Let the command filter see through server-side aliases: registered
        // aliases ("rl" -> "reload") and aliases from commands.yml, which
        // run their own command lines.
        CommandSecurity.setLabelResolver(label -> {
            org.bukkit.command.Command cmd = Bukkit.getCommandMap().getCommand(label);
            return cmd != null ? cmd.getName() : null;
        });
        CommandSecurity.setAliasExpander(label -> {
            for (Map.Entry<String, String[]> entry : Bukkit.getCommandAliases().entrySet()) {
                if (entry.getKey().equalsIgnoreCase(label)) {
                    return Arrays.asList(entry.getValue());
                }
            }
            return null;
        });

        updateNotifier = new UpdateNotifier(this);
        getServer().getPluginManager().registerEvents(new UpdateNotifyListener(updateNotifier), this);
        CdrCommand cdr = new CdrCommand(this);
        PluginCommand cdrCommand = getCommand("cdr");
        if (cdrCommand != null) {
            cdrCommand.setExecutor(cdr);
            cdrCommand.setTabCompleter(cdr);
        }

        reloadPlugin(true);

        if (messages != null) {
            getLogger().info(messages.getRaw("plugin.enabled"));
        }
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
            if (ConfigMerger.mergeMissing(getConfig(), defaultConfig)) {
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
        if (connection == null) {
            return;
        }
        connection.setShuttingDown(true);
        getLogger().info("[ConsoleDiscord] Plugin shutting down...");

        JDA current = connection.get();
        if (current != null && current.getStatus() == JDA.Status.CONNECTED) {
            // Last console lines first (max. 3s), then the shutdown notice (max. 5s)
            LogForwarder forwarder = logForwarder;
            if (forwarder != null) {
                forwarder.flushRemaining(current, Duration.ofSeconds(3));
            }
            if (notifyShutdown) {
                sendShutdownNotification(current);
            }
        }

        // Under the connection lock (only held briefly); a connection still
        // being built sees the shutdown and discards itself instead of leaking.
        synchronized (connection.lock()) {
            connection.cancelWatchdog();
            updateNotifier.stop();
            stopPerformanceMonitor();
            stopMessageCleanup();
            connection.stop(true);
            removeLogAppender();

            if (auditLogger != null) {
                auditLogger.shutdown();
            }
        }

        if (messages != null) {
            getLogger().info(messages.getRaw("plugin.disabled"));
        }
    }

    // ----------------------------------------------------------------
    // Public Accessors
    // ----------------------------------------------------------------

    public JDA getJda() {
        DiscordConnection current = connection;
        return current != null ? current.get() : null;
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
        return commandAliases;
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

    public boolean isSlashCommandAdminOnly() {
        return slashCommandAdminOnly;
    }

    /**
     * Whether /mc results are visible to the whole channel instead of only the caller.
     */
    public boolean isPublicReplies() {
        return publicReplies;
    }

    /**
     * Commands a whitelisted user may run according to command-permissions.
     *
     * @param userId The Discord user ID
     * @param member The guild member (for roles), may be null
     * @return The permitted base commands ("*" = all), or null if per-role limits are off
     */
    public Set<String> getPermittedCommands(String userId, Member member) {
        CommandPermissions permissions = commandPermissions;
        if (permissions == null) {
            return null;
        }
        List<String> roleIds = member == null ? List.of()
                : member.getRoles().stream().map(role -> role.getId()).toList();
        return permissions.resolve(userId, roleIds);
    }

    public boolean isMessageContentEnabled() {
        return connection == null || connection.isMessageContentEnabled();
    }

    public String getLogChannelId() {
        return logChannelId;
    }

    public int getLogQueueSize() {
        DiscordLogAppender current = appender;
        return current != null ? current.getQueueSize() : 0;
    }

    public int getAllowedUserCount() {
        return allowedUserIds.size();
    }

    public int getAllowedRoleCount() {
        return allowedRoleIds.size();
    }

    public ModrinthUpdateChecker getUpdateChecker() {
        return updateNotifier != null ? updateNotifier.getChecker() : null;
    }

    /**
     * Starts a manual message cleanup.
     *
     * @return false if auto-cleanup is disabled
     */
    public boolean triggerCleanup() {
        MessageCleanup cleanup = messageCleanup;
        if (cleanup == null) {
            return false;
        }
        cleanup.cleanupNow();
        return true;
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

    /**
     * Reloads config and restarts all components (/cdr reload).
     */
    public void reload() {
        reloadPlugin(false);
    }

    private void reloadPlugin(boolean initial) {
        synchronized (connection.lock()) {
            try {
                reloadPluginLocked(initial);
            } finally {
                // Never leave watchdog and log flushing switched off for good
                connection.setShuttingDown(false);
            }
        }
    }

    private void reloadPluginLocked(boolean initial) {
        connection.setShuttingDown(true);
        connection.resetRejections();
        getLogger().info(messages != null ? messages.getRaw("plugin.reloading") : "[ConsoleDiscord] Reloading...");

        reloadConfig();
        FileConfiguration cfg = getConfig();

        // Basic config
        this.logChannelId = configuredId(cfg, "log-channel-id");
        this.commandChannelId = configuredId(cfg, "command-channel-id");
        this.allowedUserIds = loadIdList(cfg, "allowed-user-ids");
        this.allowedRoleIds = loadIdList(cfg, "allowed-role-ids");
        this.logFlushTicks = cfg.getInt("log-flush-ticks", 40);
        this.debugStatusLogging = cfg.getBoolean("debug-status-logging", false);
        this.maxCommandsPerMinute = cfg.getInt("max-commands-per-minute", 5);
        this.language = cfg.getString("language", "en");

        // Command feedback
        this.commandFeedbackEnabled = cfg.getBoolean("command-feedback.enabled", true);
        this.feedbackCollectTicks = cfg.getLong("command-feedback.collect-ticks", 20L);
        this.publicReplies = cfg.getBoolean("command-feedback.public-replies", false);

        // Notifications
        this.notifyStartup = cfg.getBoolean("notifications.startup", true);
        this.notifyShutdown = cfg.getBoolean("notifications.shutdown", true);
        if (initial) {
            // Sent the first time JDA reaches CONNECTED (see onDiscordConnected),
            // whichever start attempt gets there; set before connecting.
            startupNotificationPending = notifyStartup;
        }

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

        // Per-user / per-role command lists
        loadCommandPermissions(cfg);

        // Initialize audit logger (shut down the previous instance so its
        // writer thread doesn't leak and a disabled audit really stops)
        if (auditLogger != null) {
            // Pending entries are still written, but /cdr reload runs on
            // the main thread and must not wait for the writer.
            auditLogger.shutdownAsync();
            auditLogger = null;
        }
        boolean auditEnabled = cfg.getBoolean("command-audit.enabled", true);
        if (auditEnabled) {
            String auditFile = cfg.getString("command-audit.log-file", "audit.log");
            boolean logToDiscord = cfg.getBoolean("command-audit.log-to-discord", false);
            String auditChannelId = configuredId(cfg, "command-audit.audit-channel-id");
            long maxFileSizeMb = cfg.getLong("command-audit.max-file-size-mb", 10);
            int maxBackups = cfg.getInt("command-audit.max-backups", 5);
            auditLogger = new AuditLogger(this, auditFile, logToDiscord, auditChannelId, maxFileSizeMb, maxBackups);
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

        connection.cancelWatchdog();
        updateNotifier.stop();
        stopPerformanceMonitor();
        stopMessageCleanup();
        // Don't wait for the old connection here: /cdr reload runs on the
        // main thread, and awaiting the JDA shutdown can take seconds.
        connection.stop(false);
        removeLogAppender();

        connection.setShuttingDown(false);

        // Channel validation must wait until JDA is CONNECTED (the cache
        // is empty right after build()); onDiscordConnected triggers it.
        configValidationPending = true;
        setupLogAppender();
        startPerformanceMonitor(cfg);
        startMessageCleanup(cfg);
        updateNotifier.start(cfg);
        // Built on an async thread (login can take seconds); it is handed to
        // the components above once it exists.
        connection.requestConnection();
        connection.startWatchdog();

        getLogger().info(messages.getRaw("plugin.reloaded"));
    }

    private void loadCommandSecurity(FileConfiguration cfg) {
        boolean securityEnabled = cfg.getBoolean("command-security.enabled", true);
        List<String> blockedCommands = cfg.getStringList("command-security.blocked-commands");
        List<String> allowedCommands = cfg.getStringList("command-security.allowed-commands");
        String modeName = cfg.getString("command-security.mode", "blocklist").trim().toLowerCase(Locale.ROOT);
        CommandSecurity.Mode mode = switch (modeName) {
            case "allowlist" -> CommandSecurity.Mode.ALLOWLIST;
            case "blocklist" -> CommandSecurity.Mode.BLOCKLIST;
            default -> {
                getLogger().warning(messages.get("security.invalid_mode", "mode", modeName));
                yield CommandSecurity.Mode.BLOCKLIST;
            }
        };
        slashCommandAdminOnly = cfg.getBoolean("command-security.slash-command-admin-only", true);

        CommandSecurity.configure(securityEnabled, mode, blockedCommands, allowedCommands);

        if (securityEnabled && mode == CommandSecurity.Mode.ALLOWLIST) {
            Set<String> allowed = CommandSecurity.getAllowedCommands();
            getLogger().info(messages.get("security.commands_allowed",
                    "count", String.valueOf(allowed.size()),
                    "commands", String.join(", ", allowed)));
        } else if (securityEnabled) {
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
        // Built aside and swapped in whole, JDA threads read it concurrently
        Map<String, String> aliases = new LinkedHashMap<>();
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
                        aliases.put(key.toLowerCase(Locale.ROOT), target);
                    }
                }
            }
        }
        commandAliases = Collections.unmodifiableMap(aliases);
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
            if (!id.isEmpty() && !isPlaceholderId(id)) {
                ids.add(id);
            }
        }
        return Set.copyOf(ids);
    }

    /**
     * Example IDs from the default config and README; they count as "not set".
     */
    private static final Set<String> PLACEHOLDER_IDS = Set.of(
            "123456789012345678", "987654321098765432", "876543210987654321");

    static boolean isPlaceholderId(String id) {
        return PLACEHOLDER_IDS.contains(id.trim());
    }

    /**
     * Reads a Discord ID from the config; empty if missing or a placeholder.
     */
    private static String configuredId(FileConfiguration cfg, String path) {
        String id = String.valueOf(cfg.get(path, "")).trim();
        return isPlaceholderId(id) ? "" : id;
    }

    private void loadCommandPermissions(FileConfiguration cfg) {
        if (!cfg.getBoolean("command-permissions.enabled", false)) {
            commandPermissions = null;
            return;
        }
        commandPermissions = new CommandPermissions(
                readCommandLists(cfg.getConfigurationSection("command-permissions.users")),
                readCommandLists(cfg.getConfigurationSection("command-permissions.roles")),
                cfg.getStringList("command-permissions.default"));
        getLogger().info(messages.getRaw("security.permissions_enabled"));
    }

    private static Map<String, List<String>> readCommandLists(ConfigurationSection section) {
        Map<String, List<String>> lists = new HashMap<>();
        if (section != null) {
            for (String id : section.getKeys(false)) {
                lists.put(id, section.getStringList(id));
            }
        }
        return lists;
    }

    private Map<String, LogFilter.CategoryFilter> loadCategoryFilters(FileConfiguration cfg) {
        Map<String, LogFilter.CategoryFilter> filters = new HashMap<>();

        ConfigurationSection categoriesSection = cfg.getConfigurationSection("log-categories.categories");
        ConfigurationSection patternsSection = cfg.getConfigurationSection("log-categories.patterns");

        if (categoriesSection != null && patternsSection != null) {
            for (String category : categoriesSection.getKeys(false)) {
                String channelId = categoriesSection.getString(category, "").trim();
                if (isPlaceholderId(channelId)) {
                    channelId = "";
                }
                List<String> patterns = patternsSection.getStringList(category);

                if (!channelId.isBlank() && !patterns.isEmpty()) {
                    filters.put(category, new LogFilter.CategoryFilter(category, channelId, patterns));
                }
            }
        }

        return filters;
    }

    private void validateConfiguration(JDA current) {
        boolean valid = true;

        if (!logChannelId.isBlank()) {
            TextChannel logChannel = current.getTextChannelById(logChannelId);
            if (logChannel == null) {
                getLogger().warning(messages.get("config.invalid_log_channel", "id", logChannelId));
                valid = false;
            }
        }

        if (!commandChannelId.isBlank()) {
            TextChannel commandChannel = current.getTextChannelById(commandChannelId);
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

    // ----------------------------------------------------------------
    // Startup/Shutdown Notifications
    // ----------------------------------------------------------------

    private void sendStartupNotification(JDA current) {
        if (current.getStatus() != JDA.Status.CONNECTED) {
            return;
        }

        if (logChannelId == null || logChannelId.isBlank()) {
            return;
        }

        TextChannel channel = current.getTextChannelById(logChannelId);
        if (channel == null) {
            return;
        }

        String version = getPluginMeta().getVersion();
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

    private void sendShutdownNotification(JDA current) {
        if (logChannelId == null || logChannelId.isBlank()) {
            return;
        }

        TextChannel channel = current.getTextChannelById(logChannelId);
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
    // Connection callbacks
    // ----------------------------------------------------------------

    /**
     * A new Discord connection became the current one (connection lock held):
     * hand it to every component that sends messages, otherwise they would
     * keep a shut-down instance after a restart and silently stop working.
     */
    private void onDiscordInstalled(JDA fresh) {
        AuditLogger audit = auditLogger;
        if (audit != null) {
            audit.setJda(fresh);
        }
        if (performanceMonitor != null) {
            performanceMonitor.setJda(fresh);
        }
        if (messageCleanup != null) {
            messageCleanup.setJda(fresh, cleanupChannelIds());
        }
    }

    /**
     * The connection reached CONNECTED: run what needs a populated cache.
     */
    private void onDiscordConnected(JDA current) {
        if (configValidationPending) {
            configValidationPending = false;
            validateConfiguration(current);
        }

        if (startupNotificationPending) {
            startupNotificationPending = false;
            sendStartupNotification(current);
        }

        // Deliver an update notice found while Discord was not connected
        updateNotifier.notifyDiscord();
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
        performanceMonitor.setJda(getJda());
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

        // Lower bounds: 0 days would delete every bot message, 0 hours would
        // run the cleanup on every tick.
        int days = Math.max(1, cfg.getInt("auto-cleanup.cleanup-after-days", 7));
        long hours = Math.max(1L, cfg.getLong("auto-cleanup.check-interval-hours", 24));

        messageCleanup = new MessageCleanup(this, days, hours);
        messageCleanup.setJda(getJda(), cleanupChannelIds());
        messageCleanup.start();
    }

    /**
     * Channels the auto-cleanup works on: the log channel and the category
     * channels. The audit channel is left alone on purpose.
     */
    private List<String> cleanupChannelIds() {
        Set<String> ids = new LinkedHashSet<>();
        if (logChannelId != null && !logChannelId.isBlank()) {
            ids.add(logChannelId);
        }
        LogFilter filter = logFilter;
        if (filter != null) {
            ids.addAll(filter.getCategoryChannelIds());
        }
        return List.copyOf(ids);
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

            // Filtered before buffering, so ignored lines don't push out real ones
            DiscordLogAppender discordAppender = DiscordLogAppender.create("ConsoleDiscordAppender", getName(), logFilter);
            discordAppender.start();

            // Register at the least severe configured level, so DEBUG/TRACE
            // reach the appender when they are enabled in log-levels.
            rootLoggerConfig.addAppender(discordAppender, logFilter.getLeastSpecificLevel(), null);
            ctx.updateLoggers();

            this.appender = discordAppender;
            this.logForwarder = new LogForwarder(getLogger(), messages, discordAppender,
                    logFilter, logFormatter, logChannelId);

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
            logForwarder = null;
        }
    }

    private void flushDiscordLogs() {
        if (connection.isShuttingDown()) {
            return;
        }
        JDA current = getJda();
        if (current == null || current.getStatus() != JDA.Status.CONNECTED) {
            return;
        }
        LogForwarder forwarder = logForwarder;
        if (forwarder != null) {
            forwarder.flush(current);
        }
    }
}
