# Commands & Permissions

This section outlines all command syntax, descriptions, and permission nodes for MF-AutoRestart.

---

## Command Base Syntax

All subcommands run under the base command:
* `/autorestart <subcommand>` (Aliases: `/ar`, `/mfar`)

---

## Commands Table

| Command | Usage | Description |
| :--- | :--- | :--- |
| `/ar status` | `/ar status` | Check if a countdown is active, view timezone, and identify the next scheduled restart. |
| `/ar start` | `/ar start [duration]` | Manually start a countdown. Supports time suffixes (e.g. `80s`, `1m20s`, `5m`). |
| `/ar cancel` | `/ar cancel` | Stop an active restart countdown immediately and notify players. |
| `/ar schedule list` | `/ar schedule list` | View all active schedules (config + dynamic schedules). |
| `/ar schedule add daily` | `/ar schedule add daily <HH:mm>` | Add a daily scheduled restart time (e.g. `07:00`). |
| `/ar schedule add weekly` | `/ar schedule add weekly <day> <HH:mm>` | Add a weekly scheduled restart time (e.g. `SUNDAY 12:00`). |
| `/ar reload` | `/ar reload` | Reload configuration and locales from files on disk. |

---

## Permissions Reference

| Permission Node | Default | Description |
| :--- | :--- | :--- |
| `mfautorestart.admin` | `op` | Full access to execute all plugin commands. |
| `mfautorestart.bypass` | `false` | Exempts the player from BungeeCord redirections and falls back kick when server stops. |
