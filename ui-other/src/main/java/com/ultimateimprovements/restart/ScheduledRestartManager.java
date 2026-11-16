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
 * Reads {@code [scheduled_restart]} from {@code configs/UI-Other.toml} and ticks
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
    private final List<Integer> warningMinutes = new ArrayList<>();
    private int countdownSeconds = 60;
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
    private final Set<Integer> sentWarnings = new LinkedHashSet<>();

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
        warningMinutes.clear();
        sentWarnings.clear();
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

        zone = resolveZone(cfg.getString("scheduled_restart.timezone", ""));
        mode = parseMode(cfg.getString("scheduled_restart.mode", "times"));
        action = parseAction(cfg.getString("scheduled_restart.action", "restart"));
        countdownSeconds = Math.max(1, cfg.getInt("scheduled_restart.countdown_seconds", 60));
        skipIfEmpty = cfg.getBoolean("scheduled_restart.skip_if_empty", false);
        playerLimitEnabled = cfg.getBoolean("scheduled_restart.player_limit_enabled", false);
        playerLimitComparison = parseComparison(
                cfg.getString("scheduled_restart.player_limit_comparison", "above"));
        playerLimitValue = Math.max(0, cfg.getInt("scheduled_restart.player_limit_value", 1));

        for (Integer minutes : cfg.getIntegerList("scheduled_restart.warning_minutes")) {
            if (minutes != null && minutes > 0 && !warningMinutes.contains(minutes)) {
                warningMinutes.add(minutes);
            }
        }
        warningMinutes.sort(Integer::compareTo);

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
                + ", countdown=" + countdownSeconds + "s"
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
        warningMinutes.clear();
        sentWarnings.clear();
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
            sentWarnings.clear();
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
            sentWarnings.clear();
            return;
        }

        int online = Bukkit.getOnlinePlayers().size();

        if (skipIfEmpty && online == 0) {
            ConsoleLogger.info("[ScheduledRestart] Due action skipped: no players online.");
            advance(now);
            sentWarnings.clear();
            return;
        }

        if (playerLimitEnabled && !playerLimitSatisfied(online)) {
            ConsoleLogger.info("[ScheduledRestart] Due action skipped: online " + online
                    + " does not satisfy " + playerLimitComparison + " " + playerLimitValue + ".");
            Broadcast.send(MessagesManager.getString("power.scheduled_skipped_limit",
                    "<dark_gray>[<dark_red>⚠</dark_red>]</dark_gray> <red>Scheduled action cancelled: online player limit not met</red> <gray>(%online% online, required %comparison% %value%)</gray><red>.</red>")
                    .replace("%online%", String.valueOf(online))
                    .replace("%comparison%", comparisonSymbol())
                    .replace("%value%", String.valueOf(playerLimitValue)));
            advance(now);
            sentWarnings.clear();
            return;
        }

        PowerManager power = PowerManager.getInstance();
        if (power == null || power.hasPendingRequest() || power.hasActiveCountdown()) {
            ConsoleLogger.warn("[ScheduledRestart] Due action skipped: another power action is pending.");
            advance(now);
            sentWarnings.clear();
            return;
        }

        if (mode == Mode.TIMES) markFired(slotKey);

        boolean started = power.startScheduledPowerAction(action, countdownSeconds);
        if (started) {
            ConsoleLogger.info("[ScheduledRestart] " + action + " triggered: countdown "
                    + countdownSeconds + "s.");
        } else {
            ConsoleLogger.warn("[ScheduledRestart] Could not start the scheduled " + action + " countdown.");
        }

        // The next run is persisted BEFORE the server goes down in timer mode,
        // so the interval survives a restart that happens during the countdown.
        advance(now);
        sentWarnings.clear();
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

    /** Sends at most one warning per tick (the most urgent crossed threshold). */
    private void sendDueWarnings(long remainingSeconds) {
        if (warningMinutes.isEmpty()) return;

        Integer due = null;
        for (Integer minutes : warningMinutes) {
            if (sentWarnings.contains(minutes)) continue;
            if (remainingSeconds <= minutes * 60L) {
                if (due == null) due = minutes;
                // Larger thresholds are already in the past — don't spam them later.
                sentWarnings.add(minutes);
            }
        }
        if (due == null) return;

        String actionLabel = action == RequestType.STOP
                ? MessagesManager.getString("power.action_stop", "shutting down")
                : MessagesManager.getString("power.action_restart", "restarting");
        String message = MessagesManager.getString("power.scheduled_warning",
                "<dark_gray>[<dark_red>⚠</dark_red>]</dark_gray> <red>Server %action% in</red> <yellow>%minutes%</yellow> <red>min.</red>")
                .replace("%action%", actionLabel)
                .replace("%minutes%", String.valueOf(due));
        Broadcast.send(message);
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
