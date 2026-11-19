package com.ultimateimprovements.restart;

import com.ultimateimprovements.command.PowerManager;
import com.ultimateimprovements.command.PowerManager.RequestType;
import com.ultimateimprovements.config.MessagesManager;
import com.ultimateimprovements.core.Main;
import com.ultimateimprovements.database.StateStore;
import com.ultimateimprovements.util.Broadcast;
import com.ultimateimprovements.util.ConsoleLogger;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Automatic scheduled server power action (restart or shutdown).
 * <p>
 * Reads {@code [scheduled_restart]} from {@code configs/UI-Essentials.toml} and ticks
 * once per second. Two schedule modes are supported:
 * <ul>
 *   <li>{@code mode = "times"} — daily clock times ({@code times = ["04:00"]})
 *       in the configured IANA timezone;</li>
 *   <li>{@code mode = "timer"} — a repeating interval with an explicit unit
 *       ({@code timer = "48h"}), anchored to an absolute timestamp persisted in
 *       the {@code ui_state} table. A restart before the due time does not reset
 *       it: the manager resumes the same deadline after boot.</li>
 * </ul>
 * Warnings ({@code warning_minutes}) are identical in both modes. At the
 * scheduled moment the optional player-limit condition is evaluated; if it is
 * not met, the action is skipped and the schedule starts over (timer: now +
 * interval, times: the next daily slot). The countdown and the actual
 * restart/shutdown are performed by {@link PowerManager}.
 */
public final class ScheduledRestartManager {

    private static final String STATE_NAMESPACE = "scheduled_restart";
    private static final String STATE_LAST_SLOT = "last_slot";
    private static final String STATE_NEXT_RUN = "next_run";
    private static final String STATE_INTERVAL_MS = "interval_ms";
    private static final String STATE_SKIP_PENDING = "skip_pending";

    /** Tick period in ticks (1 second). */
    private static final long TICK_PERIOD = 20L;

    /** A clock-mode slot later than this is considered missed (freeze/GC) and skipped. */
    private static final long MAX_LATENESS_MILLIS = 120_000L;

    private static final DateTimeFormatter DISPLAY_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** Online-player comparison for {@code player_limit_comparison}. */
    public enum PlayerLimitComparison { ABOVE, BELOW, EQUAL }

    private enum Mode { TIMES, TIMER }

    private static ScheduledRestartManager instance;

    private Main plugin;
    private BukkitTask task;

    private boolean enabled;
    private Mode mode = Mode.TIMES;
    private RequestType action = RequestType.RESTART;
    private final List<LocalTime> times = new ArrayList<>();
    /** Announcement thresholds in seconds, ascending. */
    private final List<Long> warningSeconds = new ArrayList<>();
    /** Per-second report start in seconds (0 = disabled). */
    private long secondReportFrom = 10L;
    /** Delay between the scheduled moment and the actual action (0 = immediate). */
    private Duration delay = Duration.ZERO;
    private boolean skipIfEmpty;
    private boolean playerLimitEnabled;
    private PlayerLimitComparison playerLimitComparison = PlayerLimitComparison.ABOVE;
    private int playerLimitValue = 1;
    private Duration interval;
    private ZoneId zone = ZoneId.systemDefault();

    /** Absolute deadline for both modes (epoch millis); -1 = nothing scheduled. */
    private long nextFireMillis = -1L;
    /** Local representation of the clock-mode slot (null in timer mode). */
    private LocalDateTime nextRestart;
    private String lastFiredSlot;
    private boolean skipPending;
    private final Set<Long> sentWarnings = new LinkedHashSet<>();
    /** Last second already covered by the per-second report (-1 = none). */
    private long lastReportedSecond = -1L;

    private ScheduledRestartManager() {}

    // =========================
    // LIFECYCLE
    // =========================

    public static void init(Main plugin) {
        if (instance == null) instance = new ScheduledRestartManager();
        instance.start(plugin);
    }

    /** Restarts the ticker with fresh config (called by {@code /ui reload}). */
    public static void reload() {
        if (instance != null) instance.start(instance.plugin);
    }

    public static void shutdown() {
        if (instance != null) instance.stop();
    }

    public static ScheduledRestartManager getInstance() {
        return instance;
    }

    /** Reads the config and (re)schedules the ticker. Idempotent. */
    public synchronized void start(Main plugin) {
        this.plugin = plugin;
        stopTask();

        times.clear();
        warningSeconds.clear();
        resetAnnouncements();
        nextRestart = null;
        nextFireMillis = -1L;
        interval = null;
        lastFiredSlot = null;

        if (plugin == null) return;

        FileConfiguration cfg = plugin.getConfig();
        enabled = cfg.getBoolean("scheduled_restart.enabled", false);
        if (!enabled) {
            ConsoleLogger.info("[ScheduledRestart] Disabled in config (scheduled_restart.enabled: false)");
            return;
        }

        skipPending = "true".equalsIgnoreCase(StateStore.get(STATE_NAMESPACE, STATE_SKIP_PENDING));

        zone = resolveZone(cfg.getString("scheduled_restart.timezone", ""));
        mode = parseMode(cfg.getString("scheduled_restart.mode", "times"));
        action = parseAction(cfg.getString("scheduled_restart.action", "restart"));
        delay = parseDelay(cfg);
        skipIfEmpty = cfg.getBoolean("scheduled_restart.skip_if_empty", false);
        playerLimitEnabled = cfg.getBoolean("scheduled_restart.player_limit_enabled", false);
        playerLimitComparison = parseComparison(
                cfg.getString("scheduled_restart.player_limit_comparison", "above"));
        playerLimitValue = Math.max(0, cfg.getInt("scheduled_restart.player_limit_value", 1));

        warningSeconds.addAll(RestartSchedule.parseWarningTimes(
                cfg.getStringList("scheduled_restart.warning_times"),
                raw -> ConsoleLogger.warn("[ScheduledRestart] Ignoring invalid warning time '" + raw
                        + "' (expected a value with a unit: 45s, 5m, 1h, 1d)")));
        if (warningSeconds.isEmpty()) {
            // Backward compatibility with the removed integer-minutes list.
            for (Integer minutes : cfg.getIntegerList("scheduled_restart.warning_minutes")) {
                if (minutes != null && minutes > 0 && !warningSeconds.contains(minutes * 60L)) {
                    warningSeconds.add(minutes * 60L);
                }
            }
            warningSeconds.sort(Long::compareTo);
            if (!warningSeconds.isEmpty()) {
                ConsoleLogger.info("[ScheduledRestart] warning_times is empty — using legacy warning_minutes.");
            }
        }
        secondReportFrom = parseSecondReportFrom(
                cfg.getString("scheduled_restart.second_report_from", "10s"));

        if (mode == Mode.TIMER) {
            interval = RestartSchedule.parseInterval(cfg.getString("scheduled_restart.timer", "48h"));
            if (interval == null) {
                ConsoleLogger.warn("[ScheduledRestart] Invalid timer '" + cfg.getString("scheduled_restart.timer")
                        + "' — expected a positive value with a unit (30s, 15m, 48h, 7d). Disabled.");
                return;
            }
            restoreTimer();
        } else {
            times.addAll(RestartSchedule.parseTimes(
                    cfg.getStringList("scheduled_restart.times"),
                    raw -> ConsoleLogger.warn("[ScheduledRestart] Ignoring invalid time '" + raw + "' (expected HH:mm)")));
            // Timer state is meaningless in clock mode — drop it so switching
            // back to the timer starts a fresh interval instead of a stale one.
            StateStore.remove(STATE_NAMESPACE, STATE_NEXT_RUN);
            StateStore.remove(STATE_NAMESPACE, STATE_INTERVAL_MS);

            lastFiredSlot = StateStore.get(STATE_NAMESPACE, STATE_LAST_SLOT);

            if (times.isEmpty()) {
                ConsoleLogger.info("[ScheduledRestart] No valid times configured — nothing to do");
                return;
            }
            computeNextClockSlot();
        }

        if (nextFireMillis < 0) return;

        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, TICK_PERIOD, TICK_PERIOD);

        ConsoleLogger.info("[ScheduledRestart] Enabled: mode=" + mode.name().toLowerCase(Locale.ROOT)
                + (mode == Mode.TIMER ? ", interval=" + interval : ", times=" + times + ", zone=" + zone)
                + ", action=" + action
                + ", next=" + describeNext()
                + ", delay=" + (delay.isZero() ? "0s (immediate)" : delay.getSeconds() + "s")
                + ", warnings=" + warningSeconds.stream()
                        .map(RestartSchedule::splitUnits)
                        .map(ScheduledRestartManager::describeTimeValue)
                        .toList()
                + (secondReportFrom > 0 ? ", per-second-from=" + secondReportFrom + "s" : ", per-second-report=off")
                + (playerLimitEnabled ? ", player-limit " + playerLimitComparison + " " + playerLimitValue : "")
                + (skipIfEmpty ? ", skip-if-empty" : ""));
        if (mode == Mode.TIMES && lastFiredSlot != null) {
            ConsoleLogger.info("[ScheduledRestart] Last executed slot: " + lastFiredSlot);
        }
    }

    /** Stops the ticker (module shutdown). */
    public synchronized void stop() {
        stopTask();
        enabled = false;
        times.clear();
        warningSeconds.clear();
        resetAnnouncements();
        nextRestart = null;
        nextFireMillis = -1L;
        interval = null;
        plugin = null;
    }

    private void stopTask() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    /**
     * Restores the persisted timer deadline. Before the due time, the same
     * absolute deadline is kept; after it, the ticker fires immediately. A
     * changed configured interval starts a fresh interval from now.
     */
    private void restoreTimer() {
        long now = System.currentTimeMillis();
        long configuredMillis = interval.toMillis();

        Long storedRun = parseLong(StateStore.get(STATE_NAMESPACE, STATE_NEXT_RUN));
        Long storedInterval = parseLong(StateStore.get(STATE_NAMESPACE, STATE_INTERVAL_MS));

        if (storedRun == null || storedRun <= 0
                || storedInterval == null || storedInterval != configuredMillis) {
            nextFireMillis = now + configuredMillis;
            ConsoleLogger.info("[ScheduledRestart] Timer started: next run in "
                    + RestartSchedule.formatDuration(Duration.ofMillis(configuredMillis)));
        } else {
            nextFireMillis = storedRun;
            long remaining = storedRun - now;
            ConsoleLogger.info("[ScheduledRestart] Timer restored from DB: "
                    + (remaining > 0
                        ? "next run in " + RestartSchedule.formatDuration(Duration.ofMillis(remaining))
                        : "deadline already passed " + RestartSchedule.formatDuration(Duration.ofMillis(-remaining)) + " ago"));
        }

        StateStore.put(STATE_NAMESPACE, STATE_INTERVAL_MS, String.valueOf(configuredMillis));
        StateStore.put(STATE_NAMESPACE, STATE_NEXT_RUN, String.valueOf(nextFireMillis));
    }

    private Long parseLong(String raw) {
        if (raw == null) return null;
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private ZoneId resolveZone(String configured) {
        String id = configured == null ? "" : configured.trim();
        if (id.isEmpty()) return ZoneId.systemDefault();
        try {
            return ZoneId.of(id);
        } catch (RuntimeException e) {
            ConsoleLogger.warn("[ScheduledRestart] Unknown timezone '" + id + "' — using "
                    + ZoneId.systemDefault());
            return ZoneId.systemDefault();
        }
    }

    private Mode parseMode(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "timer" -> Mode.TIMER;
            case "times", "time", "clock" -> Mode.TIMES;
            default -> {
                ConsoleLogger.warn("[ScheduledRestart] Unknown mode '" + raw + "' — using 'times'");
                yield Mode.TIMES;
            }
        };
    }

    private RequestType parseAction(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "restart", "reboot" -> RequestType.RESTART;
            case "shutdown", "stop", "off" -> RequestType.STOP;
            default -> {
                ConsoleLogger.warn("[ScheduledRestart] Unknown action '" + raw + "' — using 'restart'");
                yield RequestType.RESTART;
            }
        };
    }

    /**
     * Parses {@code scheduled_restart.delay}: a time value with an explicit unit
     * ({@code s/m/h/d}), default {@code 0s} — the action is performed at the
     * scheduled moment because {@code warning_times} already counts down to it.
     * The removed {@code countdown_seconds} integer is honoured as a fallback
     * when {@code delay} is absent.
     */
    private Duration parseDelay(FileConfiguration cfg) {
        String raw = cfg.getString("scheduled_restart.delay", null);
        if (raw == null) {
            int legacy = cfg.getInt("scheduled_restart.countdown_seconds", 0);
            if (legacy > 0) {
                ConsoleLogger.info("[ScheduledRestart] delay is absent — using legacy countdown_seconds="
                        + legacy + "s.");
                return Duration.ofSeconds(legacy);
            }
            return Duration.ZERO;
        }

        String value = raw.trim();
        if (value.isEmpty() || value.equalsIgnoreCase("off")
                || value.equals("0") || value.equalsIgnoreCase("0s")) {
            return Duration.ZERO;
        }
        Duration parsed = RestartSchedule.parseInterval(value);
        if (parsed == null) {
            ConsoleLogger.warn("[ScheduledRestart] Invalid delay '" + raw
                    + "' — using 0s (expected 0s, 30s, 5m)");
            return Duration.ZERO;
        }
        return parsed;
    }

    private PlayerLimitComparison parseComparison(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "above", "more", "greater", ">", "gt" -> PlayerLimitComparison.ABOVE;
            case "below", "less", "fewer", "<", "lt" -> PlayerLimitComparison.BELOW;
            case "equal", "equals", "==", "eq" -> PlayerLimitComparison.EQUAL;
            default -> {
                ConsoleLogger.warn("[ScheduledRestart] Unknown player_limit_comparison '" + raw
                        + "' — using 'above'");
                yield PlayerLimitComparison.ABOVE;
            }
        };
    }

    // =========================
    // QUERIES
    // =========================

    public boolean isEnabled() {
        return enabled;
    }

    public ZoneId getZone() {
        return zone;
    }

    public RequestType getAction() {
        return action;
    }

    /** @return true when the next run is flagged to be skipped (persisted in the DB). */
    public synchronized boolean isSkipPending() {
        return skipPending;
    }

    /** @return a short human-readable mode description ("times" / "timer (48h 0m 0s)"). */
    public String describeMode() {
        return mode == Mode.TIMER
                ? "timer (" + RestartSchedule.formatDuration(interval) + ")"
                : "times";
    }

    /**
     * Turns the feature on/off from an in-game command and writes the value back
     * to {@code UI-Essentials.toml}.
     *
     * @return true when the config write was performed
     */
    public synchronized boolean setEnabled(boolean value) {
        Main main = plugin;
        if (main == null) return false;

        if (!value) {
            PowerManager power = PowerManager.getInstance();
            if (power != null && power.isScheduledCountdownActive()) {
                power.cancelScheduledCountdown("Console");
            }
        }

        main.getConfig().set("scheduled_restart.enabled", value);
        main.saveConfig();
        start(main);
        return true;
    }

    /**
     * Marks (or clears) the next run to be skipped. The flag is persisted, so it
     * survives a server restart. At the due moment the action is cancelled and
     * the schedule advances normally.
     *
     * @return false when there is no schedule to skip (only for {@code skip})
     */
    public synchronized boolean setSkipPending(boolean value) {
        if (value && (!enabled || nextFireMillis <= 0)) return false;
        skipPending = value;
        StateStore.put(STATE_NAMESPACE, STATE_SKIP_PENDING, value ? "true" : null);
        return true;
    }

    /**
     * Resets the schedule from now: timer mode restarts the configured interval,
     * clock mode recomputes the next slot. A pending skip is cleared.
     *
     * @return false when the feature is disabled or has nothing scheduled
     */
    public synchronized boolean resetSchedule() {
        if (!enabled || nextFireMillis <= 0) return false;

        PowerManager power = PowerManager.getInstance();
        if (power != null && power.isScheduledCountdownActive()) {
            power.cancelScheduledCountdown("Console");
        }

        setSkipPending(false);
        resetAnnouncements();
        advance(System.currentTimeMillis());
        return true;
    }

    private String actionTitle() {
        return action == RequestType.STOP
                ? MessagesManager.getString("power.action_stop_title", "Shutdown")
                : MessagesManager.getString("power.action_restart_title", "Restart");
    }

    /** @return the next scheduled moment, or {@code null} when nothing is scheduled. */
    public LocalDateTime getNextRestart() {
        if (!enabled || nextFireMillis <= 0) return null;
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(nextFireMillis), zone);
    }

    // =========================
    // TICKER
    // =========================

    private synchronized void tick() {
        if (!enabled || nextFireMillis <= 0) return;

        long now = System.currentTimeMillis();

        // Clock mode: a slot missed by more than the allowed lateness (server
        // freeze / long GC) is skipped. Timer mode never skips a passed
        // deadline — it fires on the first tick after boot.
        if (mode == Mode.TIMES && now > nextFireMillis + MAX_LATENESS_MILLIS) {
            ConsoleLogger.warn("[ScheduledRestart] Slot " + RestartSchedule.slotKey(nextRestart)
                    + " was missed by more than " + (MAX_LATENESS_MILLIS / 1000) + "s — skipped.");
            computeNextClockSlot();
            resetAnnouncements();
        }
        if (nextFireMillis <= 0) return;

        long remainingMillis = nextFireMillis - now;
        if (remainingMillis <= 0) {
            fire(now);
            return;
        }
        sendDueWarnings((remainingMillis + 999) / 1000);
    }

    /** Executes (or deliberately skips) the due action and moves to the next run. */
    private void fire(long now) {
        String slotKey = mode == Mode.TIMES ? RestartSchedule.slotKey(nextRestart) : null;

        // A restart that completed inside the same minute must not be replayed.
        if (mode == Mode.TIMES && slotKey != null && slotKey.equals(lastFiredSlot)) {
            advance(now);
            resetAnnouncements();
            return;
        }

        // Operator requested a skip: the timer still reaches zero, then the
        // action is cancelled here and the schedule advances as usual.
        if (skipPending) {
            skipPending = false;
            StateStore.put(STATE_NAMESPACE, STATE_SKIP_PENDING, null);
            Broadcast.sendWithConsole(MessagesManager.getString("power.autorestart_skip_cancelled",
                    "<dark_gray>[<dark_red>⚠</dark_red>]</dark_gray> <red>Scheduled action cancelled:</red> <white>%action%</white> <gray>(skip)</gray><red>.</red>")
                    .replace("%action%", actionTitle()));
            ConsoleLogger.info("[ScheduledRestart] Due action skipped by /ui autorestart skip.");
            advance(now);
            resetAnnouncements();
            return;
        }

        int online = Bukkit.getOnlinePlayers().size();

        if (skipIfEmpty && online == 0) {
            ConsoleLogger.info("[ScheduledRestart] Due action skipped: no players online.");
            advance(now);
            resetAnnouncements();
            return;
        }

        if (playerLimitEnabled && !playerLimitSatisfied(online)) {
            ConsoleLogger.info("[ScheduledRestart] Due action skipped: online " + online
                    + " does not satisfy " + playerLimitComparison + " " + playerLimitValue + ".");
            Broadcast.sendWithConsole(MessagesManager.getString("power.scheduled_skipped_limit",
                    "<dark_gray>[<dark_red>⚠</dark_red>]</dark_gray> <red>Scheduled action cancelled: online player limit not met</red> <gray>(%online% online, required %comparison% %value%)</gray><red>.</red>")
                    .replace("%online%", String.valueOf(online))
                    .replace("%comparison%", comparisonSymbol())
                    .replace("%value%", String.valueOf(playerLimitValue)));
            advance(now);
            resetAnnouncements();
            return;
        }

        PowerManager power = PowerManager.getInstance();
        if (power == null || power.hasPendingRequest() || power.hasActiveCountdown()) {
            ConsoleLogger.warn("[ScheduledRestart] Due action skipped: another power action is pending.");
            advance(now);
            resetAnnouncements();
            return;
        }

        if (mode == Mode.TIMES) markFired(slotKey);

        long delaySeconds = delay.getSeconds();
        boolean started = delaySeconds > 0
                ? power.startScheduledPowerAction(action, (int) Math.min(Integer.MAX_VALUE, delaySeconds))
                : power.executeScheduledNow(action);
        if (started) {
            ConsoleLogger.info("[ScheduledRestart] " + action + " triggered: "
                    + (delaySeconds > 0
                        ? "delay " + delaySeconds + "s."
                        : "immediate action (delay = 0s)."));
        } else {
            ConsoleLogger.warn("[ScheduledRestart] Could not start the scheduled " + action + " action.");
        }

        // The next run is persisted BEFORE the server goes down in timer mode,
        // so the interval survives a restart that happens during the countdown.
        advance(now);
        resetAnnouncements();
    }

    private boolean playerLimitSatisfied(int online) {
        return switch (playerLimitComparison) {
            case ABOVE -> online > playerLimitValue;
            case BELOW -> online < playerLimitValue;
            case EQUAL -> online == playerLimitValue;
        };
    }

    private String comparisonSymbol() {
        return switch (playerLimitComparison) {
            case ABOVE -> ">";
            case BELOW -> "<";
            case EQUAL -> "=";
        };
    }

    /** Sends at most one announcement per tick (the most urgent crossed threshold). */
    private void sendDueWarnings(long remainingSeconds) {
        if (!sendThresholdWarning(remainingSeconds)) {
            sendSecondReport(remainingSeconds);
        }
    }

    /**
     * Announces the most urgent not-yet-sent threshold that the countdown has
     * crossed, using the threshold's own configured value and unit
     * ({@code "30m"} announces {@code 30 minutes}, not {@code 1799 seconds}).
     *
     * @return true when an announcement was sent this tick
     */
    private boolean sendThresholdWarning(long remainingSeconds) {
        if (warningSeconds.isEmpty()) return false;

        Long due = null;
        for (Long threshold : warningSeconds) {
            if (sentWarnings.contains(threshold)) continue;
            if (remainingSeconds <= threshold) {
                if (due == null) due = threshold;
                // Larger thresholds are already in the past — don't spam them later.
                sentWarnings.add(threshold);
            }
        }
        if (due == null) return false;

        announce(due);
        return true;
    }

    /**
     * Per-second report once only {@code second_report_from} is left
     * (default {@code 10s}); each remaining second is announced exactly once.
     */
    private void sendSecondReport(long remainingSeconds) {
        if (secondReportFrom <= 0 || remainingSeconds <= 0 || remainingSeconds > secondReportFrom) return;
        if (remainingSeconds == lastReportedSecond) return;
        lastReportedSecond = remainingSeconds;
        announce(remainingSeconds);
    }

    /** Broadcasts one "server restarts in N unit" line to players AND the console. */
    private void announce(long seconds) {
        RestartSchedule.TimeValue time = RestartSchedule.splitUnits(seconds);
        String actionLabel = action == RequestType.STOP
                ? MessagesManager.getString("power.action_stop", "shutting down")
                : MessagesManager.getString("power.action_restart", "restarting");
        String message = MessagesManager.getString("power.scheduled_announce",
                "<dark_gray>[<dark_red>⚠</dark_red>]</dark_gray> <red>Server %action% in</red> <yellow>%time%</yellow> <red>%unit%.</red>")
                .replace("%action%", actionLabel)
                .replace("%time%", String.valueOf(time.value()))
                .replace("%unit%", unitWord(time.unit()));
        Broadcast.sendWithConsole(message);
    }

    /** Localized unit word for the current language ("seconds", "минут", ...). */
    private static String unitWord(RestartSchedule.Unit unit) {
        return switch (unit) {
            case DAYS -> MessagesManager.getString("power.unit_days", "days");
            case HOURS -> MessagesManager.getString("power.unit_hours", "hours");
            case MINUTES -> MessagesManager.getString("power.unit_minutes", "minutes");
            case SECONDS -> MessagesManager.getString("power.unit_seconds", "seconds");
        };
    }

    /** "{@code 30m}" for the startup log line. */
    private static String describeTimeValue(RestartSchedule.TimeValue time) {
        return time.value() + switch (time.unit()) {
            case DAYS -> "d";
            case HOURS -> "h";
            case MINUTES -> "m";
            case SECONDS -> "s";
        };
    }

    /**
     * Parses {@code second_report_from}: a value with a unit (default {@code 10s});
     * {@code 0}, {@code 0s} or {@code off} disables the per-second report.
     */
    private long parseSecondReportFrom(String raw) {
        if (raw == null || raw.isBlank()) return 10L;
        String value = raw.trim();
        if (value.equalsIgnoreCase("off") || value.equals("0") || value.equalsIgnoreCase("0s")) {
            return 0L;
        }
        Duration parsed = RestartSchedule.parseInterval(value);
        if (parsed == null) {
            ConsoleLogger.warn("[ScheduledRestart] Invalid second_report_from '" + raw
                    + "' — using 10s (expected 10s, 5m or 0/off)");
            return 10L;
        }
        return parsed.getSeconds();
    }

    /** Clears the "already announced" guards (new slot / new countdown). */
    private void resetAnnouncements() {
        sentWarnings.clear();
        lastReportedSecond = -1L;
    }

    /** Moves to the next run: clock mode -> next configured time, timer -> now + interval. */
    private void advance(long now) {
        if (mode == Mode.TIMER) {
            nextFireMillis = now + interval.toMillis();
            nextRestart = null;
            StateStore.put(STATE_NAMESPACE, STATE_NEXT_RUN, String.valueOf(nextFireMillis));
            return;
        }
        computeNextClockSlot();
    }

    private void computeNextClockSlot() {
        nextRestart = RestartSchedule.nextAfter(times, LocalDateTime.now(zone));
        nextFireMillis = nextRestart == null
                ? -1L
                : nextRestart.atZone(zone).toInstant().toEpochMilli();
    }

    private void markFired(String key) {
        lastFiredSlot = key;
        StateStore.put(STATE_NAMESPACE, STATE_LAST_SLOT, key);
    }

    /** Formats the next slot for command output ({@code yyyy-MM-dd HH:mm}). */
    public String describeNext() {
        LocalDateTime next = getNextRestart();
        return next == null ? null : next.format(DISPLAY_FORMAT);
    }
}
