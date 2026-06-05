# ConsoleDiscord

A modern, feature-rich Paper/Spigot plugin that integrates Discord with your Minecraft server. Focused on **intelligent console forwarding** with professional monitoring and security features.

[![Version](https://img.shields.io/badge/version-2.0.1-blue.svg)](https://github.com/BoondockSulfur/ConsoleDiscord)
[![Java](https://img.shields.io/badge/Java-25-orange.svg)](https://openjdk.org/projects/jdk/25/)
[![Paper](https://img.shields.io/badge/Paper-26.1.2-green.svg)](https://papermc.io/)

> **Branch Info:** This is the `mc-26` branch for Minecraft 26.x (Java 25).
> For Minecraft 1.21 - 1.21.11 (Java 21), see the [`main` branch](https://github.com/BoondockSulfur/ConsoleDiscord/tree/main).

---

## 🌟 Features

### 📋 Intelligent Log Forwarding

- **Discord Embeds** - Color-coded, structured logs instead of simple code blocks
- **Log-Level Filtering** - Send only WARN/ERROR or all levels (TRACE to FATAL)
- **Emoji Encoding** - Visual hierarchy: 💀 FATAL, ❌ ERROR, ⚠️ WARN, ℹ️ INFO, 🔍 DEBUG
- **Regex Filters** - Filter out annoying spam logs ("Can't keep up", UUID warnings, etc.)
- **Batch Embeds** - Up to 10 log lines per embed for better overview
- **Log Categories** - Separate Discord channels for Security, Performance, and General logs

### 🎮 Remote Command Execution

- **Dual Command Support** - `/mc` (Slash Command) + `!mc` (Prefix Command)
- **Autocomplete** - Intelligent suggestions for common commands (gamemode, weather, time, etc.)
- **Command Aliases** - Define shortcuts: `tps` → `spark tps`, `save` → `save-all`
- **User Whitelist** - Only authorized Discord users can execute commands
- **Channel Restriction** - Restrict commands to a specific channel

### 🔒 Security

- **Configurable Command Blacklist** - You decide which commands to block (default: only `op`/`deop`)
- **Rate Limiting** - Max 5 commands/minute per user (configurable)
- **Audit Logging** - Every command is logged with user, timestamp, and status
- **Permission System** - Bukkit permissions for all admin commands
- **Namespace Protection** - Commands with `minecraft:`, `bukkit:`, `spigot:`, `paper:` are always blocked

### 📊 Performance Monitoring

- **TPS Alerts** - Automatic warning when TPS < 15.0
- **Memory Alerts** - Warning when RAM usage > 90%
- **Cooldown System** - Prevents alert spam (default: 5min)
- **Color-coded Embeds** - 🟡 Warning / 🔴 Critical Status

### 🛠️ Management

- **Startup/Shutdown Notifications** - 🟢 Server started / 🔴 Server stopped with uptime
- **Auto-Cleanup** - Automatically deletes old log messages (e.g., after 7 days)
- **Watchdog System** - Automatic reconnect on Discord connection loss
- **Config Validation** - Validates channel IDs on startup

### 🌍 Internationalization

- **Multi-language** - German & English included
- **Easily Extensible** - Add your own languages via YAML files

---

## 📦 Installation

1. **Download** the latest JAR from [Releases](../../releases)
2. **Copy** the JAR to your server's `plugins/` folder
3. **Restart** your server
4. **Configure** `plugins/ConsoleDiscord/config.yml` (see below)
5. **Reload** the plugin: `/cdr reload`

---

## ⚙️ Configuration

<details>
<summary><b>Basic Configuration (click to expand)</b></summary>

```yaml
# Discord Bot Token from https://discord.com/developers/applications
bot-token: "YOUR_DISCORD_BOT_TOKEN"

# Text channel ID for server logs
log-channel-id: "123456789012345678"

# Optional: Restrict commands to a specific channel
command-channel-id: ""

# User whitelist (Discord user IDs). Empty = everyone allowed
allowed-user-ids:
  - "123456789012345678"

# Log flush interval in ticks (20 ticks = 1 second)
log-flush-ticks: 40

# Debug logging for JDA status changes
debug-status-logging: false

# Language (en = English, de = German)
language: "en"

# Rate limit: Max commands per minute per user
max-commands-per-minute: 5
```
</details>

<details>
<summary><b>Advanced Logging Options</b></summary>

```yaml
# Which log levels should be sent to Discord?
log-levels:
  - INFO
  - WARN
  - ERROR
  - FATAL

# Log formatting
log-formatting:
  use-embeds: true        # Use Discord embeds instead of code blocks
  use-emojis: true        # Show emojis for log levels
  embed-batch-size: 10    # Number of lines per embed

# Regex filters - These logs will NOT be sent
log-filters:
  ignore-patterns:
    - "Can't keep up! Is the server overloaded\\?"
    - ".*UUID of player.*already taken.*"

# Log categories - Different channels for different logs
log-categories:
  enabled: false
  categories:
    security: "987654321098765432"      # Channel for security logs
    performance: "876543210987654321"   # Channel for performance logs
  patterns:
    security:
      - ".*Banned player.*"
      - ".*UUID mismatch.*"
      - ".*issued server command.*op.*"
    performance:
      - ".*Can't keep up.*"
      - ".*Server tick.*"
      - ".*Memory.*"
```
</details>

<details>
<summary><b>Command Features</b></summary>

```yaml
# Command security - Block dangerous commands
command-security:
  enabled: true
  # Default: only op/deop blocked
  # You can add more: stop, restart, whitelist, ban-ip, reload, etc.
  blocked-commands:
    - "op"
    - "deop"

# Command aliases - Shortcuts for frequent commands
command-aliases:
  enabled: true
  aliases:
    tps: "spark tps"
    lag: "spark profiler start"
    save: "save-all"

# Command audit log - Logs all Discord commands
command-audit:
  enabled: true
  log-file: "audit.log"           # File in plugin folder
  log-to-discord: false           # Also log to Discord?
  audit-channel-id: ""            # Discord channel for audit logs
```
</details>

<details>
<summary><b>Performance Alerts</b></summary>

```yaml
performance-alerts:
  enabled: true
  low-tps-threshold: 15.0         # Warning when TPS below 15
  high-memory-threshold: 90       # Warning when >90% RAM usage
  alert-cooldown: 300             # Cooldown in seconds (5 min)
```
</details>

<details>
<summary><b>Notifications & Cleanup</b></summary>

```yaml
# Startup/shutdown notifications
notifications:
  startup: true    # 🟢 Server start notification
  shutdown: true   # 🔴 Server stop notification

# Auto-cleanup - Deletes old messages
auto-cleanup:
  enabled: false
  cleanup-after-days: 7           # Delete messages older than 7 days
  check-interval-hours: 24        # Check every 24 hours
```
</details>

---

## 🎯 Commands

### In-Game

| Command | Permission | Description |
|---------|-----------|-------------|
| `/cdr reload` | `consolediscord.reload` | Reloads the plugin |
| `/cdr debug [on\|off\|status]` | `consolediscord.debug` | Toggle debug mode |

### Discord

| Command | Description |
|---------|-------------|
| `/mc <command>` | Executes Minecraft command (Slash command with autocomplete) |
| `!mc <command>` | Executes Minecraft command (Prefix command) |

**Autocomplete Examples:**
- `/mc gamemode` → Suggestions: creative, survival, adventure, spectator
- `/mc weather` → Suggestions: clear, rain, thunder
- `/mc time set` → Suggestions: day, night, noon, midnight
- `/mc tps` → Shows `spark tps` (if alias configured)

---

## 🔐 Security

### Configurable Command Blacklist

You decide which commands to block! **Default recommendation** (already configured):

```yaml
command-security:
  enabled: true
  blocked-commands:
    - "op"      # Prevents OP assignment via Discord
    - "deop"    # Prevents OP removal via Discord
```

**Want to block more?** Simply add:
```yaml
blocked-commands:
  - "op"
  - "deop"
  - "stop"        # Stop server
  - "restart"     # Restart server
  - "whitelist"   # Manipulate whitelist
  - "reload"      # Reload plugins
```

**Want to allow everything?** Set `enabled: false` or empty the list.

**Always blocked** (not configurable): Namespace prefixes `minecraft:`, `bukkit:`, `spigot:`, `paper:`

### Rate Limiting

Each Discord user is limited to **5 commands per minute** (adjustable). When exceeded:

```
⏱️ Rate limit exceeded! Please wait 47s before trying again. (5/5 commands used)
```

### Audit Log Format

```
[2026-03-10 19:30:45] User#123456789 (MaxMustermann) executed: gamemode creative [SUCCESS]
[2026-03-10 19:31:12] User#123456789 (MaxMustermann) executed: op Player123 [FAILED]
```

---

## 🤖 Bot Setup

### 1. Create Discord Application

1. Go to [Discord Developer Portal](https://discord.com/developers/applications)
2. Create a new application
3. Go to **Bot** section and create a bot
4. Copy the **Bot Token**

### 2. Enable Privileged Gateway Intents

Under **Bot → Privileged Gateway Intents**:
- ✅ **Message Content Intent** (IMPORTANT!)

### 3. Invite Bot

OAuth2 URL Generator:
- **Scopes:** `bot`, `applications.commands`
- **Bot Permissions:**
  - ✅ Send Messages
  - ✅ Use Slash Commands
  - ✅ Read Message History (for auto-cleanup)

### 4. Find Channel IDs

1. Discord → User Settings → Advanced → Enable **Developer Mode**
2. Right-click on channel → **Copy ID**

---

## 📊 Feature Matrix

| Feature | Standard | Premium Plugins | ConsoleDiscord |
|---------|----------|-----------------|----------------|
| Log Forwarding | ✅ | ✅ | ✅ |
| Discord Embeds | ❌ | ✅ | ✅ |
| Log-Level Filter | ❌ | ⚠️ | ✅ |
| Regex Filters | ❌ | ❌ | ✅ |
| Log Categories | ❌ | ❌ | ✅ |
| Remote Commands | ✅ | ✅ | ✅ |
| Autocomplete | ❌ | ❌ | ✅ |
| Command Aliases | ❌ | ❌ | ✅ |
| Security Filter | ⚠️ | ✅ | ✅ |
| Rate Limiting | ❌ | ⚠️ | ✅ |
| Audit Logging | ❌ | ✅ | ✅ |
| Performance Alerts | ❌ | ❌ | ✅ |
| Auto-Cleanup | ❌ | ❌ | ✅ |
| i18n (DE+EN) | ❌ | ⚠️ | ✅ |
| **Price** | Free | $10-30 | **Free** |

---

## 🎨 Screenshots

### Discord Embeds with Emoji Encoding
```
🟢 Server Started
The Minecraft server has started successfully!
Version: 1.3.0 | Plugins: 25

ℹ️ INFO Server started in 3.2s
⚠️ WARN Can't keep up! Did the system time change?
❌ ERROR Could not load plugin 'Example'
```

### Performance Alert
```
⚠️ Low TPS Alert
Server TPS has dropped below threshold!

Current TPS: 12.34
Threshold: 15.00
Status: 🟡 Warning
```

---

## 🚀 Requirements

- **Java 25** or higher
- **Paper/Spigot/Folia 26.1.2** or higher
- **Discord Bot** with Message Content Intent

### ✨ Folia Support

This plugin is **fully compatible with Folia**! It automatically detects whether it's running on Paper or Folia and uses the appropriate scheduler API. No configuration needed - just install and it works!

---

## 📝 Changelog

See [CHANGELOG.md](CHANGELOG.md) for detailed version history.

### Version 2.0.1 (Current) - Bugfix, Tests & Setup Guide

**New in 2.0.1** (backport of the v1.4.2 fixes to the MC 26.x / Java 25 line):
- 🐛 Fixed false "command failed" replies in Discord for vanilla commands that actually executed
- 🐛 Fixed the RateLimiter memory leak properly (periodic auto-purge of idle users)
- 🐛 Fixed an unreleased HTTP connection in the update checker
- 🔇 Plugin no longer forwards its own log messages to Discord (no echo/feedback noise)
- ✅ Added a JUnit 5 test suite (21 tests) run on every build
- 📖 New bundled `SETUP.md` guide (install + Discord bot creation), auto-exported on first start

### Version 2.0.0 - Minecraft 26 Edition

**New in 2.0.0:**
- ✨ Port to Minecraft 26.x / Java 25 (Paper API 26.1.2, api-version 26.1)
- 📊 bStats metrics integration
- Functionally identical to v1.4.1

### Version 1.4.1 - Bugfix & Quality Release

**New in 1.4.1:**
- 🐛 Fixed NullPointerException when audit logging is disabled
- 🐛 Fixed rate limiter not updating on config reload
- 🐛 Fixed allowed-user-ids not working with unquoted IDs
- 🐛 Fixed default config re-adding placeholder user IDs
- 🐛 Fixed resource leak in update checker
- 🐛 Fixed LogFilter regex inconsistency (matches→find)
- 🐛 Fixed RateLimiter memory leak on public servers
- 🐛 Fixed update checker notifying about incompatible Java versions
- ⚡ Improved log queue performance (O(1) instead of O(n) size check)
- ⚡ Async audit log file writing
- ⚡ Bulk delete for message cleanup (< 14 days)
- 🌍 All notification + performance alert messages now use i18n system (EN + DE)
- 🔒 Command aliases pointing to blocked commands are rejected on load
- 🔧 Replaced deprecated API calls (URL constructor, Reflection for TPS)
- 🔧 Version-aware update checker (filters by Minecraft version via Modrinth API)

### Version 1.4.0 - Folia Support Release

**New in 1.4.0:**
- ✨ **Full Folia support** - Works on both Paper and Folia
- 🔄 Scheduler refactoring with automatic platform detection
- 🛡️ Region-aware scheduling for console commands

### Version 1.3.0 - The Big Feature Update

**13 new features added:**
- ✨ Log-Level Filtering & Regex Filters
- ✨ Discord Embeds with color coding & emojis
- ✨ Command Audit Logging (file + Discord)
- ✨ Performance Alerts (TPS & Memory)
- ✨ Command Autocomplete & Aliases
- ✨ Log Categories with separate channels
- ✨ Startup/Shutdown Notifications
- ✨ Auto-Cleanup for old messages
- ✨ **Configurable Command Blacklist** - You decide!
- 🔄 Package refactoring to `dev.boondocksulfur.consolediscord`
- 🔒 Bot token format validation
- 🛡️ Thread-safe reconnect logic
- 📚 Complete JavaDoc documentation

---

## 🛠️ Build

```bash
# Clone repository
git clone https://github.com/yourusername/ConsoleDiscord.git
cd ConsoleDiscord

# Build with Maven
mvn clean package

# JAR is located in: target/consolediscord-1.4.1.jar
```

**Or with IntelliJ IDEA:**
1. Open project
2. Maven → Lifecycle → clean → package
3. JAR in `target/` folder

---

## 🤝 Support

- 🐛 **Found a bug?** → [Issues](../../issues)
- 💡 **Feature idea?** → [Discussions](../../discussions)
- 📖 **Documentation** → This README

---

## 📄 License

MIT License - see [LICENSE](LICENSE) file

---

## 👨‍💻 Author

**BoondockSulfur**

---

## ⭐ Credits

- [JDA](https://github.com/discord-jda/JDA) - Discord API Wrapper
- [Paper](https://papermc.io/) - High-Performance Minecraft Server

---

<p align="center">
  <b>Made with ❤️ for the Minecraft Community</b><br>
  <sub>Focused on console forwarding, not bloated with unnecessary features</sub>
</p>
