# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

---

## [2.0.0] - 2026-05-03 — Minecraft 26 Edition

Port of v1.4.1 to Minecraft 26.x with Java 25. Functionally identical to v1.4.1.

- Java 25 target (was Java 21)
- Paper API 26.1.2 (was 1.21.4)
- api-version: 26.1 (was 1.21)

**Build:** Java 25 | Paper 26.1.2 | JDA 6.1.0 | Folia supported

---

## [1.4.1] - 2026-05-03 — Bugfix & Quality Release

### Bugfixes
- Fixed `NullPointerException` when audit logging is disabled
- Fixed rate limiter not updating on config reload
- Fixed `allowed-user-ids` not working with unquoted numeric IDs
- Fixed default config re-adding placeholder user IDs
- Fixed token placeholder check (now recognizes EN + DE placeholders)
- Fixed resource leak in `ModrinthUpdateChecker` (try-with-resources)
- Fixed `LogFilter` regex using `matches()` instead of `find()` — patterns no longer need `.*` wrapping
- Fixed `RateLimiter` memory leak on public servers (auto-cleanup of empty entries)
- Fixed update checker notifying about incompatible Java/MC versions
- Fixed language files not receiving new message keys on update (auto-merge added)

### Improvements
- Log queue: O(1) size tracking via `AtomicInteger`
- Async audit log file writing (dedicated daemon thread)
- Bulk delete for message cleanup (Discord API, < 14 days)
- Command aliases pointing to blocked commands are rejected on load
- Performance alerts (TPS/Memory) now fully localized (EN/DE)
- Update checker filters by Minecraft version via Modrinth API
- Language files auto-merge new keys (like `config.yml` already did)
- Deprecated API calls replaced (`URL` constructor, Reflection for TPS)

**Build:** Java 21 | Paper 1.21 - 1.21.11 | JDA 6.1.0 | Folia supported
**Migration:** Drop-in replacement — just swap the JAR.

---

## [1.4.0] - 2026-04-10 🚀 **Folia Support & Stability Release**

### ✨ New Features

**Folia Compatibility** ⭐
- **Full Folia support** - Plugin now works on both Paper and Folia servers
- **Automatic detection** - Detects server type and uses appropriate scheduler API
- **Zero configuration** - Works out of the box on both platforms
- **Region-aware scheduling** - Uses global region scheduler for console commands on Folia
- **Async scheduler migration** - All async tasks use Folia's new scheduler when available

**Modrinth Update Checker** ⭐
- Automatically checks for new versions on startup via Modrinth API
- Notification in server console and optionally in Discord channel
- Configurable: `update-checker.enabled`

**Config Auto-Merge** ⭐
- New config options from updates are automatically added to existing config
- User values are never overwritten - only missing keys are added
- No more manual config migration needed after updates

### 🐛 Bugfixes

- **Fixed: `MAX_EMBED_SIZE_EXCEEDED` error** - Embeds are now split into groups that respect Discord's 6000 character total limit per message. Previously, multiple embeds could exceed this limit and cause `ErrorResponseException: 50035`
- **Fixed: `Thread.sleep(1000)` blocking server shutdown** - Removed unnecessary sleep in `onDisable()`. The shutdown notification already uses blocking `complete()` call
- **Fixed: `CommandSecurity` race condition** - Static fields are now thread-safe using `AtomicBoolean` and `AtomicReference`. Config reloads during command checks no longer cause inconsistent state
- **Fixed: `SchedulerAdapter.isCancelled()` always returning `false`** - Folia tasks now properly track cancel state via `AtomicBoolean`, Paper tasks delegate to `BukkitTask.isCancelled()`
- **Fixed: Potential `NullPointerException` in `MessageCleanup`** - Added null-safety check for `jda.getSelfUser()` during message cleanup
- **Fixed: Fragile JSON parsing in `ModrinthUpdateChecker`** - Replaced manual `indexOf()`/`substring()` parsing with Gson for robust Modrinth API response handling

### 🔄 Changes

**Scheduler Refactoring**
- New `SchedulerAdapter` class provides unified API for Paper and Folia
- Replaced all `Bukkit.getScheduler()` calls with adapter methods
- Console commands now use `GlobalRegionScheduler` on Folia
- Removed busy-wait loops when cancelling tasks (cleaner shutdown)

**Code Cleanup**
- Removed legacy `org.example` package (outdated duplicate code from pre-1.3.0)
- Added Gson as provided dependency (shipped by Paper at runtime)

**Technical Details**
- Auto-detection via `io.papermc.paper.threadedregions.RegionizedServer` class check
- `folia-supported: true` flag in plugin.yml
- Backwards compatible with Paper/Spigot servers

### 📦 Build Information

**JAR File:** `consolediscord-1.4.0.jar`
**Size:** ~16 MB (with JDA shaded)
**Java Version:** 21
**Paper Version:** 1.21.4-R0.1-SNAPSHOT (also supports Folia)
**JDA Version:** 6.1.0

---

## Migration Guide from 1.3.0 → 1.4.0

### ✅ Automatic Migration

All changes are **100% backwards compatible**. Simply replace the JAR file!

New config options (like `update-checker`) are **automatically merged** into your existing `config.yml` on first startup - no manual editing needed.

---

## [1.3.0] - 2026-03-10 🎉 **The Big Feature Update**

### ✨ New Features

#### 📋 Intelligent Log Forwarding

**1. Discord Embeds instead of Code Blocks**
- Color-coded embeds for better readability (Green=INFO, Orange=WARN, Red=ERROR)
- Structured presentation with timestamps
- Batch processing: Up to 10 log lines per embed
- Configurable: `log-formatting.use-embeds`

**2. Emoji Encoding for Log Levels**
- 💀 FATAL - Critical errors
- ❌ ERROR - Errors
- ⚠️ WARN - Warnings
- ℹ️ INFO - Information
- 🔍 DEBUG - Debug output
- 🔬 TRACE - Trace logs
- Configurable: `log-formatting.use-emojis`

**3. Log-Level Filtering**
- Send only specific log levels to Discord
- Default: INFO, WARN, ERROR, FATAL
- Configurable: `log-levels: [INFO, WARN, ERROR]`

**4. Regex Filters for Logs**
- Filter out annoying spam logs ("Can't keep up", UUID warnings)
- Unlimited regex patterns
- Configurable: `log-filters.ignore-patterns`

**5. Log Categories with Separate Channels**
- Security logs in separate channel (op, ban, kick, etc.)
- Performance logs in separate channel (TPS, Memory)
- Unlimited categories definable
- Pattern-based routing
- Configurable: `log-categories`

#### 🎮 Remote Commands

**6. Command Autocomplete**
- Intelligent suggestions while typing
- Pre-configured common commands (gamemode, weather, time, difficulty, etc.)
- Also shows command aliases
- Automatic Discord slash command integration

**7. Command Aliases**
- Define shortcuts: `tps` → `spark tps`
- Unlimited aliases
- Autocomplete shows aliases with arrow: `tps → spark tps`
- Configurable: `command-aliases.aliases`

#### 🔒 Security & Monitoring

**8. Command Audit Logging**
- Logs every command with user, timestamp, and status
- File-based: `plugins/ConsoleDiscord/audit.log`
- Optionally also in Discord channel
- Format: `[2026-03-10 19:30:45] User#123 (Name) executed: cmd [SUCCESS]`
- Configurable: `command-audit`

**9. Performance Alerts**
- TPS warning at < 15.0 TPS (adjustable)
- Memory warning at > 90% RAM (adjustable)
- Cooldown system prevents spam (default: 5min)
- Color-coded embeds: 🟡 Warning / 🔴 Critical
- Configurable: `performance-alerts`

#### 🛠️ Management

**10. Startup/Shutdown Notifications**
- 🟢 Server Start: Shows version & plugin count
- 🔴 Server Stop: Shows uptime
- Beautiful embeds with timestamp
- Configurable: `notifications.startup` & `notifications.shutdown`

**11. Auto-Cleanup for Old Messages**
- Automatically deletes old log messages
- Default: After 7 days (adjustable)
- Check every 24 hours (adjustable)
- Only bot's own messages are deleted
- Configurable: `auto-cleanup`

**12. Batch Embeds**
- Groups up to 10 log lines per embed
- Discord limit: Max 10 embeds per message
- Reduces API calls and rate limits
- Better performance with many logs
- Configurable: `log-formatting.embed-batch-size`

**13. Configurable Command Blacklist** ⭐
- **Fully customizable:** You decide which commands to block
- **Default recommendation:** Only `op` and `deop` blocked (changeable)
- **Flexible:** Add commands like `stop`, `restart`, `whitelist` as needed
- **Disableable:** Security can be completely turned off
- **Namespace protection:** Prefixes like `minecraft:`, `bukkit:` are always blocked
- Configurable: `command-security.enabled` & `command-security.blocked-commands`

---

### 🔄 Changes

#### Package Refactoring
```
Before: org.example
Now:    dev.boondocksulfur.consolediscord
```

**New Package Structure:**
- `dev.boondocksulfur.consolediscord` - Main plugin
- `dev.boondocksulfur.consolediscord.audit` - Audit logging
- `dev.boondocksulfur.consolediscord.cleanup` - Message cleanup
- `dev.boondocksulfur.consolediscord.i18n` - Internationalization
- `dev.boondocksulfur.consolediscord.listener` - Discord events
- `dev.boondocksulfur.consolediscord.logging` - Log appender & formatter
- `dev.boondocksulfur.consolediscord.performance` - Performance monitoring
- `dev.boondocksulfur.consolediscord.security` - Security & rate limiting

#### Maven Configuration
- **groupId:** `dev.boondocksulfur` (previously: `org.example`)
- **artifactId:** `consolediscord` (previously: `console-discord`)
- JDA relocation adjusted

#### Config Extensions
The `config.yml` has been extended with **58 new options**:
- Advanced logging options (17 options)
- Command features (10 options)
- Performance alerts (4 options)
- Notifications & cleanup (5 options)

---

### 📚 Documentation

- **JavaDoc:** Complete documentation for all classes and methods
- **README.md:** Completely revised with feature matrix and examples
- **CHANGELOG.md:** Detailed changelog with migration guide

---

### 🐛 Improvements

- Better error messages with i18n support
- More informative Discord responses (emojis for feedback)
- Cleaner code structure with separation of concerns
- Improved type safety with proper generics
- More consistent logging practices
- Optimized performance through batch processing
- **NEW:** Thread-safe reconnect logic with synchronized lock - no more race conditions
- **NEW:** Discord bot token format validation on startup
- **NEW:** Configurable command blacklist - you decide which commands to block

---

### 🔒 Security

- Blocks execution of server-critical commands
- Prevents command spam via rate limiting
- Validates user permissions before execution
- Filters commands with namespace prefixes
- Audit trail for all commands
- **NEW:** Configurable command blacklist - blocks only dangerous operations you specify
- **NEW:** Discord bot token validation - checks format and length on startup
- **NEW:** Race condition protection for Discord reconnects through synchronized lock

---

### 📦 Build Information

**JAR File:** `consolediscord-1.3.0.jar`
**Size:** ~8 MB (with JDA shaded)
**Java Version:** 21
**Paper Version:** 1.21.4-R0.1-SNAPSHOT
**JDA Version:** 6.1.0

---

## Migration Guide from 1.2.0 → 1.3.0

### ✅ Automatic Migration

Most changes are **backwards compatible**. Your old `config.yml` will continue to work!

### 📝 New Config Options

On first startup with 1.3.0, these options will be **automatically added**:

```yaml
# New options with defaults
language: "en"
max-commands-per-minute: 5
command-security:
  enabled: true
  blocked-commands: ["op", "deop"]
log-levels: [INFO, WARN, ERROR, FATAL]
log-formatting:
  use-embeds: true
  use-emojis: true
  embed-batch-size: 10
log-filters:
  ignore-patterns: []
log-categories:
  enabled: false
command-aliases:
  enabled: true
  aliases: {}
command-audit:
  enabled: true
  log-file: "audit.log"
performance-alerts:
  enabled: true
  low-tps-threshold: 15.0
  high-memory-threshold: 90
notifications:
  startup: true
  shutdown: true
auto-cleanup:
  enabled: false
```

### ⚙️ Recommended Post-Migration Steps

1. **Enable Discord Embeds:**
   ```yaml
   log-formatting:
     use-embeds: true
     use-emojis: true
   ```

2. **Filter Spam Logs:**
   ```yaml
   log-filters:
     ignore-patterns:
       - "Can't keep up! Is the server overloaded\\?"
   ```

3. **Configure Command Aliases:**
   ```yaml
   command-aliases:
     enabled: true
     aliases:
       tps: "spark tps"
       save: "save-all"
   ```

4. **Reload Plugin:**
   ```
   /cdr reload
   ```

### ⚠️ Breaking Changes

**None!** All changes are backwards compatible.

---

## [1.2.0] - 2025-01-22

### 🐛 Bugfixes

- **Discord Slash Command Interaction Fix**
  - Fixed: `IllegalStateException: This interaction has already been acknowledged`
  - Slash commands now use `deferReply()` for immediate acknowledgment
  - Responses sent via `getHook().sendMessage()`
  - JDA status is checked before responses

- **Improved Error Handling**
  - All Discord API calls have error handlers
  - Errors are logged instead of throwing exceptions

### 🔧 Technical Details

- `DiscordListener.java` refactoring
- New `sendResponse()` helper method
- Clean separation of slash command and message responses

---

## [1.1.0] - Initial Version

### ✨ Features

- **Log Forwarding** - Server logs automatically to Discord
- **Remote Commands**
  - `/mc <command>` - Slash command
  - `!mc <command>` - Text prefix
- **User Permissions** - Configurable user IDs
- **Channel Restriction** - Optionally restrict commands to one channel
- **Watchdog** - Automatic JDA reconnects on connection issues
- **Debug Mode** - JDA status logging for troubleshooting

---

## 🔮 Planned Features (Roadmap)

Features planned for future versions:

### Version 1.5.0 (Q3 2026)
- [ ] Web dashboard for configuration
- [ ] Extended statistics (player count, TPS history)
- [ ] Custom webhook support
- [ ] Backup notifications
- [ ] Crash reports automatically to Discord
- [ ] Integration with Spark for performance profiling
- [ ] Discord → Minecraft chat bridge (optional)

**Note:** Roadmap is tentative and subject to change.

---

## 📊 Version Overview

| Version | Release Date | Main Features | Status |
|---------|--------------|---------------|--------|
| **1.4.1** | 2026-05-03 | 10 Bugfixes, i18n, Version-aware updates | ✅ Current |
| 1.4.0 | 2026-04-10 | Folia support, Embed fix, Config auto-merge, Update checker, 6 Bugfixes | ✅ Stable |
| 1.3.0 | 2026-03-10 | 13 new features (Embeds, Alerts, Security, etc.) | ✅ Stable |
| 1.2.0 | 2025-01-22 | Slash command fix | ✅ Stable |
| 1.1.0 | 2025-01-15 | Initial release | ⚠️ Legacy |

---

## 🤝 Contributing

Want to contribute to development?

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/AmazingFeature`)
3. Commit your changes (`git commit -m 'Add some AmazingFeature'`)
4. Push to the branch (`git push origin feature/AmazingFeature`)
5. Open a pull request

**Please note:**
- JavaDoc for new methods
- Test all changes thoroughly
- Keep the focus on console forwarding

---

<p align="center">
  <b>Made with ❤️ by BoondockSulfur</b>
</p>
