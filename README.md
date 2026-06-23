# MF-AutoRestart

A lightweight, robust, and highly configurable Paper 1.21.x Minecraft server plugin designed to perform safe and automated restarts. 

MF-AutoRestart handles timezone-aware scheduling, executes customized console command sequences prior to shutdown, redirects players to a proxy lobby server instead of kicking them, and features a public Developer API and PlaceholderAPI integration.

---

## Features

* **Timezone-Aware Schedules**: Define automated restart times based on your local timezone (e.g. `Asia/Jakarta`, `America/New_York`, or `UTC`). Supports daily and weekly schedules.
* **Smart Duration Formats**: Restarts accept verbal time suffixes (e.g. `/ar start 1m20s` or `/ar start 5m`). Screen displays and broadcasts are automatically translated into clean units (e.g., `1m 20s`).
* **BungeeCord Redirection**: Safely send players to your BungeeCord/Waterfall/Velocity lobby server at `0s` remaining, with a fallback kick mechanism after a short grace period.
* **Pre-Restart Commands**: Execute console commands (like `/save-all` or custom broadcasts) at exact intervals during the countdown sequence.
* **Dynamic Schedules Database**: Add, list, and manage schedules dynamically in-game. Dynamic schedules are persisted to a lightweight, thread-safe local SQLite database.
* **Developer API & Events**: Clean Java API (`MFAutoRestartAPI`) registered in the Bukkit Services Manager and custom cancellable Bukkit events (`AutoRestartStartEvent`, `AutoRestartCancelEvent`, `AutoRestartPreShutdownEvent`).
* **PlaceholderAPI (PAPI) Support**: Expose live countdown variables to display in scoreboards, tab lists, chat formats, or holograms.
* **Indonesian & English Translation**: Dual-language support right out-of-the-box. Both language configurations (`en_us.yml` and `en_id.yml`) are unpacked automatically.

---

## Commands & Permissions

All commands require the `mfautorestart.admin` permission node (defaults to server operators).

| Command | Description |
| :--- | :--- |
| `/autorestart status` | View active countdowns, configured timezone, and the next scheduled restart time. |
| `/autorestart start [duration]` | Manually start a restart countdown. Supports raw seconds or suffixes (e.g. `80s`, `1m20s`). |
| `/autorestart cancel` | Abort an active restart countdown and notify all online players. |
| `/autorestart schedule list` | Display all loaded schedules (both config-defined and database-defined). |
| `/autorestart schedule add daily <HH:mm>` | Register a daily scheduled restart time (e.g. `07:00`). Saved to database. |
| `/autorestart schedule add weekly <DAY> <HH:mm>` | Register a weekly scheduled restart time (e.g. `SUNDAY 12:00`). Saved to database. |
| `/autorestart reload` | Reload configuration files, locale messages, and dynamic schedules. |

*Aliases: `/ar`, `/mfar`*

### Bypass Permission
* `mfautorestart.bypass`: Players holding this permission node will **not** be redirected or kicked when the server shuts down.

---

## Configuration (`config.yml`)

The default configuration file generated in `plugins/MF-AutoRestart/config.yml`:

```yaml
settings:
  # Language for plugin messages. Available: en_us, en_id
  language: en_id

  # The timezone used for calculating scheduled restart times.
  timezone: "Asia/Jakarta"

  # The BungeeCord server name that players will be redirected to
  lobby-server: "lobby"

  # Grace period in seconds to wait after redirecting players before final shutdown
  redirection-grace-period: 3

# Define automatic restart times.
# Formats: "DAILY:HH:mm" or "WEEKLY:DAY:HH:mm"
schedules:
  - "DAILY:07:00"

# Default countdown duration in seconds when running /ar start without duration.
default-countdown: 300

# Chat, Title, and ActionBar warnings are broadcast at these seconds remaining.
warning-intervals:
  - 300
  - 180
  - 120
  - 60
  - 30
  - 10
  - 5
  - 4
  - 3
  - 2
  - 1

# Execute console commands at specific seconds remaining (without leading slash).
pre-restart-commands:
  300:
    - "save-all"
  0:
    - "broadcast &c[MF-AutoRestart] &fRestarting server now. Redirecting all players..."
```

---

## PlaceholderAPI (PAPI) Placeholders

Use these variables to display countdown details in other plugins:

* `%mfautorestart_active%` — Check if a restart sequence is running (`true`/`false`).
* `%mfautorestart_seconds_remaining%` — Raw seconds left in the active countdown.
* `%mfautorestart_time_remaining%` — Minutes/seconds left (`MM:SS` format, e.g., `02:30`).
* `%mfautorestart_time_remaining_formatted%` — User-friendly time countdown (e.g. `2m` or `1m 20s`).
* `%mfautorestart_next_restart%` — Day/Time of next schedule (e.g., `Sun 07:00`).
* `%mfautorestart_next_remaining%` — Duration left until next schedule (e.g., `3h 45m`).

---

## Developer API & Custom Events

A detailed reference manual for developers can be found in **[API.md](https://fnx-plugins.gitbook.io/mf-autorestart/api)**.

---

## Installation

1. Download or compile the plugin:
   ```bash
   mvn clean package
   ```
2. Copy `target/MF-AutoRestart-1.0.jar` into your Paper server's `plugins/` directory.
3. Start the server to generate configuration files.
4. Customize `plugins/MF-AutoRestart/config.yml` and run `/ar reload` to apply settings!
