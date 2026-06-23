# Configuration System

MF-AutoRestart manages its configurations inside `plugins/MF-AutoRestart/config.yml`. This section details the available parameters.

---

## Configuration Reference

### General Settings
* `settings.language`: Set plugin locale. Options: `en_us` (English), `en_id` (Indonesian). Default is `en_id`.
* `settings.timezone`: Timezone database ID (e.g. `Asia/Jakarta`, `UTC`).
* `settings.lobby-server`: Name of the BungeeCord server to send players to on shutdown.
* `settings.redirection-grace-period`: Delay (seconds) before kicking players remaining after redirect packets are sent.

### Restart Schedules
The `schedules` section handles automatic restarts. Format:
* `DAILY:<HH:mm>` (e.g. `"DAILY:07:00"`)
* `WEEKLY:<DAY>:<HH:mm>` (e.g. `"WEEKLY:SUNDAY:12:00"`)

### Countdown Alerts
* `default-countdown`: Starting seconds for automatic schedules or manual starts without args.
* `warning-intervals`: Seconds remaining when a Title, ActionBar, and Chat alert are broadcast.

### Custom Console Commands
* `pre-restart-commands`: Executed on the main thread when reaching specific countdown ticks. Placeholder `%seconds%` represents the remaining seconds.

---

## Default File Example

```yaml
settings:
  language: en_id
  timezone: "Asia/Jakarta"
  lobby-server: "lobby"
  redirection-grace-period: 3

schedules:
  - "DAILY:07:00"

default-countdown: 300

warning-intervals:
  - 300
  - 120
  - 60
  - 10
  - 3
  - 2
  - 1

pre-restart-commands:
  300:
    - "save-all"
  0:
    - "broadcast Restarting server now..."
```
