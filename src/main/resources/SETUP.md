# ConsoleDiscord — Setup Guide

This guide walks you through everything from creating a Discord bot to getting
your first server logs into Discord. Follow the steps in order.

If you get stuck, check the **Troubleshooting** section at the bottom.

---

## 1. Requirements

- **Java 21** or higher
- **Paper / Spigot / Folia 1.21 – 1.21.11**
- A **Discord account** and a server (guild) where you have the *Manage Server*
  permission
- The plugin JAR placed in your server's `plugins/` folder

---

## 2. Create a Discord Bot

You need your own bot application. It takes about 3 minutes.

### 2.1 Create the application
1. Open the **Discord Developer Portal**: https://discord.com/developers/applications
2. Click **New Application** (top right), give it a name (e.g. `ConsoleDiscord`),
   accept the terms, and click **Create**.

### 2.2 Create the bot user
1. In the left sidebar, open the **Bot** tab.
2. Click **Add Bot** / **Reset Token** if needed.
3. Click **Reset Token** → **Copy**. This is your **bot token**.
   - Keep it secret — anyone with this token controls your bot.
   - You will paste it into `config.yml` in step 4.

### 2.3 Enable the required intent (IMPORTANT)
Still on the **Bot** tab, scroll down to **Privileged Gateway Intents** and
enable:

- ✅ **Message Content Intent**

Without this, the prefix command `!mc` and message reading will **not** work.
Save your changes.

---

## 3. Invite the Bot to Your Server

1. In the left sidebar, open **OAuth2 → URL Generator**.
2. Under **Scopes**, tick:
   - ✅ `bot`
   - ✅ `applications.commands`  *(needed for the `/mc` slash command)*
3. A **Bot Permissions** box appears below. Tick:
   - ✅ **Send Messages**
   - ✅ **Embed Links**  *(for the color-coded log embeds)*
   - ✅ **Read Message History**  *(needed for the optional auto-cleanup feature)*
   - ✅ **Use Slash Commands**
4. Copy the **generated URL** at the bottom, open it in your browser, choose your
   server, and click **Authorize**.

The bot now appears in your server's member list (offline until you start it).

---

## 4. Get the Channel IDs

The plugin needs the **numeric ID** of the channel where logs should be posted.

1. In Discord, open **User Settings → Advanced** and enable **Developer Mode**.
2. Right-click the target text channel → **Copy Channel ID**.
3. Repeat for any other channel you want to use (command channel, category
   channels, audit channel, …).

Your own **User ID** (for the whitelist) is obtained the same way: right-click
your name → **Copy User ID**.

---

## 5. Configure the Plugin

1. Start your server **once** so the plugin generates its files, then stop it.
   You will now find `plugins/ConsoleDiscord/config.yml`.
2. Open `config.yml` and set at least these values:

```yaml
# Paste the bot token from step 2.2
bot-token: "YOUR_DISCORD_BOT_TOKEN"

# Channel ID from step 4 where server logs are posted
log-channel-id: "123456789012345678"

# Optional: restrict /mc and !mc to one channel (empty = any channel)
command-channel-id: ""

# Who may run commands from Discord. Empty list = everyone allowed.
# Use your own User ID from step 4.
allowed-user-ids:
  - "987654321098765432"

# Language: en = English, de = German
language: "en"
```

3. Save the file and **start your server**.

That's it — the bot should come **online** and start forwarding logs.

> **Tip:** After changing the config on a running server you do not need a full
> restart. Run `/cdr reload` in-game (or from console) to apply changes.

---

## 6. Verify It Works

- The bot shows as **online** in your Discord member list.
- A **🟢 Server Started** notification appears in your log channel (if startup
  notifications are enabled).
- Server console output starts appearing as embeds in the log channel.

Try a command from Discord:

- **Slash command:** type `/mc` and pick or type a command, e.g. `time set day`.
- **Prefix command:** type `!mc say Hello from Discord`.

---

## 7. Commands

### From Discord
| Command | Description |
|---------|-------------|
| `/mc <command>` | Run a Minecraft command (slash command, with autocomplete) |
| `!mc <command>` | Run a Minecraft command (prefix command) |

### In-game / console
| Command | Permission | Description |
|---------|-----------|-------------|
| `/cdr reload` | `consolediscord.reload` | Reload the plugin config |
| `/cdr debug [on\|off\|status]` | `consolediscord.debug` | Toggle debug status logging |

---

## 8. Recommended Next Steps

These are optional but worth configuring (all in `config.yml`):

- **`command-security`** — block dangerous commands. By default only `op`/`deop`
  are blocked. Add `stop`, `restart`, `whitelist`, etc. as you see fit.
- **`max-commands-per-minute`** — per-user rate limit (default 5).
- **`log-levels`** — choose which levels are forwarded (e.g. only `WARN`/`ERROR`).
- **`log-filters.ignore-patterns`** — regex list to hide spammy log lines.
- **`command-aliases`** — shortcuts like `tps → spark tps`.
- **`performance-alerts`** — automatic TPS / memory warnings.
- **`command-audit`** — log every Discord command to a file and/or channel.

See the comments inside `config.yml` for the full list of options.

---

## 9. Troubleshooting

**The bot stays offline / no logs appear**
- Double-check the `bot-token` value. It must be the full token from step 2.2,
  with no extra spaces or quotes mismatched.
- Watch the server console on startup for messages from `ConsoleDiscord`. A bad
  token format is reported there.

**`!mc` (prefix command) does nothing**
- The **Message Content Intent** (step 2.3) is not enabled. Enable it and restart.

**`/mc` (slash command) does not show up**
- The bot was invited without the `applications.commands` scope. Re-invite it
  using the URL from step 3.
- Slash commands can take a minute to register after the bot first connects.

**"You are not allowed to use this command"**
- Your Discord User ID is not in `allowed-user-ids`, or the list contains the
  placeholder ID. Add your real ID (step 4) and run `/cdr reload`.

**Logs go to the wrong / no channel**
- Verify `log-channel-id` is the correct numeric ID and that the bot can see and
  send messages in that channel (check channel permissions).

**"Insufficient permissions" in the console**
- The bot lacks **Send Messages** or **Embed Links** in the target channel. Fix
  the channel/role permissions in Discord.

---

## Support

- Bug reports: https://github.com/BoondockSulfur/ConsoleDiscord/issues
- Feature ideas: https://github.com/BoondockSulfur/ConsoleDiscord/discussions
