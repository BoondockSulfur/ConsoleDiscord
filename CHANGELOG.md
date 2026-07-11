# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

---

## [1.5.0] - 2026-07-11 — Security, Stability & Feature Release

Backport of the v2.1.0 changes from the Minecraft 26.x line: a full security and code review (fixes three attack vectors, five functional bugs and a packaging issue) plus a set of new quality-of-life features.

### ⚠️ Breaking Change
- **Empty whitelists now mean deny-all.** Previously an empty `allowed-user-ids` list allowed *every* Discord user in *every* server the bot was in to run console commands — with only `op`/`deop` blocked, anyone could run `stop`, `ban`, `whitelist add`, etc. Commands are now disabled until at least one Discord user ID (or role ID, see below) is whitelisted; a clear warning is logged on startup. **Migration:** add your Discord user ID to `allowed-user-ids` in `config.yml`.

### New Features
- **Command output replies:** `/mc` and `!mc` now reply with the actual command output (captured via Paper's feedback sender) instead of a generic "executing" note — e.g. `/mc list` shows the real player list in Discord. Configurable via `command-feedback` (enabled + collect window).
- **Role-based whitelist:** `allowed-role-ids` allows everyone holding one of the configured Discord roles to run commands — easier to manage for teams than individual user IDs.
- **`/cdr status`:** shows plugin version, server type, Discord connection state, configured channels (validated live), pending log queue size, whitelist counts and available updates.
- **`/cdr cleanup`:** manually triggers the message cleanup (the existing `cleanupNow()` logic was previously unreachable).
- **Tab completion** for all `/cdr` subcommands, filtered by permission.
- **Token via environment:** the bot token can be provided through the `CONSOLEDISCORD_BOT_TOKEN` environment variable, which takes precedence over `config.yml` — keeps the token out of config backups and support pastes.
- **Periodic update check:** the Modrinth check now repeats every `update-checker.interval-hours` (default 24, 0 = startup only) so long-running servers get notified too.
- **Audit log rotation:** `command-audit.max-file-size-mb` (default 10) rotates `audit.log` to `audit.log.old` so it no longer grows unbounded.
- **Startup notification** is now sent when the Discord connection is actually established instead of after a fixed 5-second delay (which silently skipped it on slow logins).
- **LICENSE file added** (MIT) — the README referenced it, but it didn't exist.

### Security
- Blocked-command checks can no longer be bypassed via plugin namespaces: **all** namespaced commands (`minecraft:op`, `essentials:op`, `anyplugin:cmd`, ...) are rejected. Previously only `minecraft:`, `bukkit:`, `spigot:` and `paper:` were caught, so `essentials:op` slipped past the `op` block.
- Leading slashes are stripped before the security check, so `/op` cannot bypass the `op` blocklist entry.
- `!mc` commands are ignored in direct messages — console access is only possible in guild channels where the channel restriction can apply.
- Default blocklist extended to `op`, `deop`, `stop`, `restart`, `reload`, `ban-ip` (existing configs keep their own list).

### Bugfixes
- Config validation no longer runs before JDA is connected — the channel cache was still empty right after startup, producing false "invalid channel-id" warnings on every start/reload. Validation now runs once the connection is established.
- Fixed a thread leak on `/cdr reload`: the previous `AuditLogger` writer thread is now shut down before a new one is created, and disabling the audit log via reload actually deactivates it.
- Fixed command aliases with a missing/non-string target being loaded as the literal command `"null"`; invalid aliases are now skipped with a warning. Alias keys are also normalized to lowercase so aliases with capital letters match.
- Fixed the shutdown notification potentially hanging `onDisable()` forever — `complete()` has no timeout and retries rate limits indefinitely; replaced with a hard 5-second timeout.
- Fixed oversized log lines producing an empty code block in Discord; overlong lines are now truncated instead of silently dropped.

### Improvements
- Watchdog reconnects no longer run on the main server thread — `stopDiscordStuff()` waits up to ~7s on JDA shutdown, which previously caused lag spikes on every reconnect attempt.
- A blocked command no longer consumes rate-limit quota (security check now runs before the rate limiter).
- `lastConnected`/`restartBackoffSec` are now `volatile` (written by the JDA event thread, read by the watchdog thread).
- Bulk message cleanup uses a 13-day boundary so messages close to Discord's 14-day bulk-delete limit don't fail while requests are queued.
- Test suite extended from 21 to 41 tests: new coverage for `LogFilter` (level/pattern/category filtering) and `LogFormatter` (Discord message/embed limits), plus new `CommandSecurity` bypass tests.

### Build
- All shaded JDA transitive dependencies (okhttp, okio, kotlin-stdlib, jackson, nv-websocket, commons-collections4, trove, tink, protobuf) are now relocated into the plugin's own namespace — previously they were shaded unrelocated and could clash with other plugins bundling the same libraries. `slf4j` and annotation-only artifacts are excluded from the JAR entirely.

**Build:** Java 21 | Paper 1.21.4 | JDA 6.1.0 | Folia supported
**Migration:** Swap the JAR **and add your Discord user ID to `allowed-user-ids`** (see Breaking Change above).

---

## [1.4.2] - 2026-06-05 — Bugfix, Tests & Setup Guide

### Bugfixes
- Fixed Discord showing "command failed" for vanilla commands that actually ran — `Bukkit.dispatchCommand()`'s unreliable return value is no longer treated as success/failure; only a thrown exception counts as a failure. The audit log now records the real execution status.
- Fixed the `RateLimiter` memory leak for good — removed the self-defeating empty-entry churn (entries were removed and immediately re-added) and added a periodic `purgeExpired()` that runs automatically every 100 commands, keeping the tracking map bounded on busy public servers.
- Fixed `HttpURLConnection` not being released in `ModrinthUpdateChecker` — `disconnect()` is now called in a `finally` block.

### Improvements
- The plugin no longer forwards its own log messages to Discord, preventing echo/feedback noise (e.g. repeated send-error warnings looping back into the log channel).
- Added a JUnit 5 test suite (21 tests) covering `CommandSecurity`, `RateLimiter` and the update-checker version comparison; tests run on every build via Surefire.
- Added a bundled `SETUP.md` guide (installation + step-by-step Discord bot creation), automatically exported next to `config.yml` on first start.
- bStats metrics integration for anonymous usage statistics (moved here — the commit landed after the 1.4.1 release).
- Aligned `@version` JavaDoc tags across classes and corrected placeholder GitHub links in the README.

**Build:** Java 21 | Paper 1.21 - 1.21.11 | JDA 6.1.0 | Folia supported
**Migration:** Drop-in replacement — just swap the JAR.

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
| **1.4.2** | 2026-06-05 | dispatchCommand/RateLimiter fixes, test suite, bundled setup guide | ✅ Current |
| 1.4.1 | 2026-05-03 | 10 Bugfixes, i18n, Version-aware updates | ✅ Stable |
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
