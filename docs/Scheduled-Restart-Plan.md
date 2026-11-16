# Scheduled Restart Plan

> Status: **implemented** (2026-11-17). Feature owner: `UI-Other`
> (the `Power` module), config file: `configs/UI-Other.toml`.

## 1. Goal

Restart the server automatically at fixed daily times (cron-style "restart at
04:00 every day"), with player-facing warnings and a countdown that reuses the
existing power-management UX (chat, ActionBar, BossBar, accelerating beeps).

Non-goals:

- No arbitrary cron expressions or per-weekday schedules in v1 — a list of
  daily `HH:mm` times is enough and keeps the config readable.
- No automatic shutdown (`/ui power off`) — only restart.
- No command to edit the schedule in game; the TOML file is the source of truth
  and `/ui reload` applies changes.

## 2. Behaviour

1. `[scheduled_restart]` in `UI-Other.toml` lists daily times (`times = ["04:00"]`).
2. One second before each time the manager broadcasts configured warnings
   (`warning_minutes = [30, 15, 10, 5, 1]`).
3. At the scheduled time the manager starts the normal power countdown
   (`countdown_seconds`, default 60 s): BossBar + ActionBar + chat + beeps.
   When the countdown reaches zero the existing `PowerManager` path runs
   `Bukkit.getServer().restart()`, so shutdown rewards/hooks keep working.
4. `/ui power schedule` (alias `/ui power next`) prints the next scheduled
   restart and the time left. `/ui power undo` cancels an active scheduled
   countdown (the cancelled slot is not retried until the next day).
5. Skipped slot is consumed, never replayed: `skip_if_empty` (nobody online) or
   a conflicting manual power request. A slot that is more than two minutes
   late (server freeze, long GC pause) is skipped with a console warning.

### Restart-loop protection

The executed slot is persisted in the existing `ui_state` table
(`StateStore`, namespace `scheduled_restart`, key `last_slot`, value
`yyyy-MM-dd HH:mm`). A server that comes back at `04:00:20` therefore sees the
slot as already consumed and does not restart again. A cold start **after** the
slot never fires it retroactively: the next occurrence is computed strictly
after "now".

## 3. Config

```toml
[scheduled_restart]
enabled = false
times = ["04:00"]
timezone = ""                  # IANA id, empty = server default
warning_minutes = [30, 15, 10, 5, 1]
countdown_seconds = 60
skip_if_empty = false
```

Validation:

- `ConfigRules` validates types/ranges (`enabled`, `times`, `timezone`,
  `warning_minutes`, `countdown_seconds`, `skip_if_empty`).
- `RestartSchedule.parseTimes` silently drops malformed entries and logs one
  warning; `ZoneId` falls back to the server default on an invalid id.
- New root key `scheduled_restart` is routed to `UI-Other` in `AddonCatalog`,
  so existing installs receive the section through the normal repair pass.

## 4. Architecture

| Piece | Responsibility |
|-------|----------------|
| `restart/RestartSchedule` | Pure helpers: parse `HH:mm`, next occurrence, duration formatting, slot key. No Bukkit — unit tested. |
| `restart/ScheduledRestartManager` | Config load, one-second ticker, warning state, persistence, starts/cancels the countdown. |
| `command/PowerManager` | Countdown UI (unchanged visuals). New `startScheduledRestart(seconds)`, `cancelScheduledCountdown()`, `isScheduledCountdownActive()`, remaining-seconds getter. |
| `command/subcommands/PowerSubcommand` | `schedule`/`next` output; `undo` also cancels a scheduled countdown. |
| `module/SimpleModules` (`Power`) | Init / reload / shutdown hooks for the manager. |
| `Permissions` | `ui.command.power.schedule` (read-only; default `FALSE` like the other power nodes). |

The ticker is a single `runTaskTimer` every 20 ticks (same pattern as
`AutoBroadcastManager`); it stops with the plugin/module.

## 5. Edge cases

| Case | Result |
|------|--------|
| Server boots after today's slot | Next = tomorrow; no retroactive restart. |
| Restart completes inside the same minute | `last_slot` persisted → no second restart. |
| `/ui reload` during countdown | Ticker restarts, countdown keeps running. |
| Manual `/ui power reboot` pending/confirmed at the slot | Slot consumed + console warning (no double restart). |
| Invalid time entry | Dropped, console warning, other entries keep working. |
| Empty `times` / `enabled = false` | No ticker; `/ui power schedule` reports "not configured". |

## 6. Tests

- `ui-other/src/test/.../restart/RestartScheduleTest` — parsing (`H:mm`,
  `HH:mm`, invalid), next-occurrence rollover, duration formatting.
- Manual in-game check: set `times` to one minute ahead, `/ui reload`, verify
  warning → countdown → restart, then verify no repeat after the restart and
  `/ui power schedule` output.

## 7. Files

```
ui-other/.../restart/RestartSchedule.java
ui-other/.../restart/ScheduledRestartManager.java
ui-other/.../command/PowerManager.java            (refactor + scheduled API)
ui-other/.../command/subcommands/PowerSubcommand.java
ui-other/.../module/SimpleModules.java
ui-other/src/test/.../restart/RestartScheduleTest.java
ui-core/.../config/AddonCatalog.java
ui-core/.../config/ConfigRules.java
ui-core/.../core/Permissions.java
ui-core/src/main/resources/config/UI-Other.toml
GUIDE.md / README.md / CHANGELOG.md
```
