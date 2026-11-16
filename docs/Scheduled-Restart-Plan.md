# Scheduled Restart Plan

> Status: **implemented** (2026-11-17). Feature owner: `UI-Other`
> (the `Power` module), config file: `configs/UI-Other.toml`.

## 1. Goal

Run a server power action automatically — either at fixed daily times
("restart at 04:00") or on a repeating interval ("every 48 hours"). The action
is a restart by default, but a shutdown can be selected. Warnings, countdown and
player-facing messages are shared between both modes.

Non-goals:

- No cron expressions, weekdays or month rules — a list of daily `HH:mm` times
  (clock mode) and one interval (timer mode) keep the config readable.
- No in-game schedule editor; the TOML file is the source of truth and
  `/ui reload` applies changes.
- No per-world schedules.

## 2. Modes

### Clock mode — `mode = "times"`

`times = ["04:00", "16:00"]` in the configured IANA `timezone` (empty = server
default). The next slot is computed from the current time; a slot that already
passed today rolls over to tomorrow.

### Timer mode — `mode = "timer"`

`timer = "48h"` (units: `s`, `m`, `h`, `d`). The next absolute deadline is
stored in the `ui_state` table (`scheduled_restart.next_run`, epoch millis) and
survives server restarts:

- restart **before** the deadline → the same deadline is restored, nothing resets;
- restart **after** the deadline (downtime) → the action fires on the first tick
  after boot, then the next deadline is `now + interval`;
- changing the configured interval (or switching back from clock mode) starts a
  fresh interval from now.

## 3. Behaviour at the scheduled moment

1. Warnings (`warning_minutes = [30, 15, 10, 5, 1]`) are broadcast in both
   modes. Only the most urgent not-yet-sent threshold is broadcast per tick.
2. At the deadline the player-count gate is evaluated (see §4). If it fails, the
   action is skipped and the schedule resets:
   - timer mode → `next_run = now + interval` (persisted);
   - clock mode → the next daily slot.
3. If another power request/countdown is already running, the run is skipped and
   consumed in the same way (no double restart).
4. Otherwise the normal `PowerManager` countdown starts
   (`countdown_seconds`, default 60 s): BossBar + ActionBar + chat + beeps.
5. At zero the configured `action` runs: `Bukkit.getServer().restart()` or
   `Bukkit.getServer().shutdown()`. Shutdown rewards/hooks are granted first.
6. The consumed run is recorded **before** the restart/shutdown:
   - timer mode → next deadline persisted in `ui_state`;
   - clock mode → slot key `yyyy-MM-dd HH:mm` in `ui_state` (`last_slot`), so a
     restart that completes inside the same minute is never replayed.

## 4. Player-count gate

```toml
player_limit_enabled = false
player_limit_comparison = "above"   # above | below | equal
player_limit_value = 1
```

- `above` — allowed while `online > value` (e.g. value 5: needs more than 5);
- `below` — allowed while `online < value`;
- `equal` — allowed while `online == value`.

Disabled by default. Checked only at the scheduled moment (not at warnings).
`skip_if_empty` remains as a simpler "nobody online" skip and is evaluated
first.

## 5. Config

```toml
[scheduled_restart]
enabled = false
mode = "times"                 # times | timer
times = ["04:00"]
timezone = ""                  # IANA id, empty = server default
timer = "48h"                  # only for mode = "timer"
action = "restart"             # restart | shutdown
warning_minutes = [30, 15, 10, 5, 1]
countdown_seconds = 60
skip_if_empty = false
player_limit_enabled = false
player_limit_comparison = "above"
player_limit_value = 1
```

Validation:

- `ConfigRules` validates types/ranges; `notBlank` for `mode`, `timer`, `action`
  and `player_limit_comparison`.
- `RestartSchedule.parseTimes` / `parseInterval` drop malformed values and log
  one warning; an invalid timer disables the ticker (safe default), an invalid
  mode/comparison/action falls back with a warning.
- New root key `scheduled_restart` is routed to `UI-Other` in `AddonCatalog`,
  so existing installs receive the section through the normal repair pass.

## 6. Messages

Bilingual (`messages.power.*` = RU, `messages_en.power.*` = EN); the language is
chosen by `messages.lang` (EN default). `Broadcast.send` prepends the plugin
prefix (`[UI] `), so every warning/skip message carries it.

Keys: `scheduled_warning` (`%action%`, `%minutes%`), `scheduled_next`
(`%time%`, `%remaining%`), `scheduled_running` (`%action%`, `%remaining%`),
`scheduled_none`, `scheduled_cancelled` (`%action%`, `%by%`),
`scheduled_skipped_limit` (`%online%`, `%comparison%`, `%value%`).

## 7. Architecture

| Piece | Responsibility |
|-------|----------------|
| `restart/RestartSchedule` | Pure helpers: `HH:mm` parsing, next occurrence, interval parsing (`48h`), duration formatting, slot key. Unit tested. |
| `restart/ScheduledRestartManager` | Config load, one-second ticker, mode/deadline handling, warnings, DB persistence, player gate, starts/cancels the countdown. |
| `command/PowerManager` | Shared countdown UI. `startScheduledPowerAction(type, seconds)`, `cancelScheduledCountdown()`, `isScheduledCountdownActive()`, remaining-seconds and action-title getters. |
| `command/subcommands/PowerSubcommand` | `schedule`/`next` output; `undo` also cancels a scheduled countdown. |
| `module/SimpleModules` (`Power`) | Init / reload / shutdown hooks. |
| `Permissions` | `ui.command.power.schedule` (read-only, default `FALSE`). |

## 8. Edge cases

| Case | Result |
|------|--------|
| Restart before the timer deadline | Deadline restored from DB; countdown continues. |
| Server was down through the timer deadline | Action fires right after boot, then `now + interval`. |
| Restart completes inside the same minute (clock mode) | `last_slot` prevents a replay. |
| `/ui reload` during countdown | Ticker restarts, countdown keeps running. |
| Manual power request pending at the deadline | Run consumed + console warning. |
| Player gate fails | Skip message, schedule resets (timer: now + interval). |
| Invalid time/interval | Entry dropped with a warning; timer mode is disabled on an invalid interval. |
| Empty `times` / `enabled = false` | No ticker; `/ui power schedule` reports "not configured". |

## 9. Tests

- `ui-other/src/test/.../restart/RestartScheduleTest` — time parsing, interval
  parsing (`30s`, `48h`, `7D`, invalid), next-occurrence rollover, duration
  formatting.
- Manual in-game check: `mode = "timer"`, `timer = "2m"`, `/ui reload`, verify
  warning → countdown → action, restart the server before the deadline and
  confirm the deadline did not reset; set the player gate to a failing
  comparison and confirm the skip + reset.

## 10. Files

```
ui-other/.../restart/RestartSchedule.java
ui-other/.../restart/ScheduledRestartManager.java
ui-other/.../command/PowerManager.java
ui-other/.../command/subcommands/PowerSubcommand.java
ui-other/.../module/SimpleModules.java
ui-other/src/test/.../restart/RestartScheduleTest.java
ui-core/.../config/AddonCatalog.java
ui-core/.../config/ConfigRules.java
ui-core/.../core/Permissions.java
ui-core/src/main/resources/config/UI-Other.toml
GUIDE.md / README.md / CHANGELOG.md
```
