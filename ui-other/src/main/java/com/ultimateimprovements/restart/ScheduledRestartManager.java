package com.ultimateimprovements.restart;

import com.ultimateimprovements.command.PowerManager;
import com.ultimateimprovements.config.MessagesManager;
import com.ultimateimprovements.core.Main;
import com.ultimateimprovements.database.StateStore;
import com.ultimateimprovements.util.Broadcast;
import com.ultimateimprovements.util.ConsoleLogger;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Automatic daily server restart (scheduled restart).
 * <p>
 * Reads {@code [scheduled_restart]} from {@code configs/UI-Other.toml} and ticks
 * once per second:
 * <ul>
 *   <li>broadcasts warnings at configured minutes before the slot;</li>
 *   <li>at the slot starts the existing {@link PowerManager} countdown
 *       (BossBar/ActionBar/chat/beeps) with a configurable duration;</li>
 *   <li>persists the consumed slot in {@link StateStore}, so a restart that
 *       completes inside the same minute is never replayed.</li>
 * </ul>
 * The ticker is idempotent: {@link #start(Main)} stops the previous task, so
 * {@code /ui reload} is safe. The countdown itself lives in {@link PowerManager}
 * and keeps running across a config reload.
 */
public final class ScheduledRestartManager {

    private static final String STATE_NAMESPACE = "scheduled_restart";
    private static final String STATE_LAST_SLOT = "last_slot";

    /** Tick period in ticks (1 second). */
    private static final long TICK_PERIOD = 20L;

    /** A slot later than this is considered missed (freeze/GC) and is skipped. */
    private static final long MAX_LATENESS_SECONDS = 120L;

    private static final DateTimeFormatter DISPLAY_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private static ScheduledRestartManager instance;

    private Main plugin;
    private BukkitTask task;

    private boolean enabled;
    private final List<LocalTime> times = new ArrayList<>();
    private final List<Integer> warningMinutes = new ArrayList<>();
    private int countdownSeconds = 60;
    private boolean skipIfEmpty;
    private ZoneId zone = ZoneId.systemDefault();

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
        lastFiredSlot = null;

        if (plugin == null) return;

        FileConfiguration cfg = plugin.getConfig();
        enabled = cfg.getBoolean("scheduled_restart.enabled", false);
        if (!enabled) {
            ConsoleLogger.info("[ScheduledRestart] Disabled in config (scheduled_restart.enabled: false)");
            return;
        }

        zone = resolveZone(cfg.getString("scheduled_restart.timezone", ""));
        times.addAll(RestartSchedule.parseTimes(
                cfg.getStringList("scheduled_restart.times"),
                raw -> ConsoleLogger.warn("[ScheduledRestart] Ignoring invalid time '" + raw + "' (expected HH:mm)")));

        for (Integer minutes : cfg.getIntegerList("scheduled_restart.warning_minutes")) {
            if (minutes != null && minutes > 0 && !warningMinutes.contains(minutes)) {
                warningMinutes.add(minutes);
            }
        }
        warningMinutes.sort(Integer::compareTo);

        countdownSeconds = Math.max(1, cfg.getInt("scheduled_restart.countdown_seconds", 60));
        skipIfEmpty = cfg.getBoolean("scheduled_restart.skip_if_empty", false);

        lastFiredSlot = StateStore.get(STATE_NAMESPACE, STATE_LAST_SLOT);

        if (times.isEmpty()) {
            ConsoleLogger.info("[ScheduledRestart] No valid times configured — nothing to do");
            return;
        }

        nextRestart = RestartSchedule.nextAfter(times, LocalDateTime.now(zone));
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, TICK_PERIOD, TICK_PERIOD);

        ConsoleLogger.info("[ScheduledRestart] Enabled: times=" + times
                + ", zone=" + zone + ", next=" + RestartSchedule.slotKey(nextRestart)
                + ", countdown=" + countdownSeconds + "s"
                + (skipIfEmpty ? ", skip-if-empty" : ""));
        if (lastFiredSlot != null) {
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
        plugin = null;
    }

    private void stopTask() {
        if (task != null) {
            task.cancel();
            task = null;
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

    // =========================
    // QUERIES
    // =========================

    public boolean isEnabled() {
        return enabled;
    }

    public ZoneId getZone() {
        return zone;
    }

    /** @return the next scheduled slot, or {@code null} when not configured. */
    public LocalDateTime getNextRestart() {
        return nextRestart;
    }

    // =========================
    // TICKER
    // =========================

    private synchronized void tick() {
        if (!enabled || times.isEmpty()) return;

        LocalDateTime now = LocalDateTime.now(zone);

        // No slot yet, or the previous one was missed by more than the allowed
        // lateness (server freeze / long GC): roll forward without firing.
        if (nextRestart == null
                || now.isAfter(nextRestart.plusSeconds(MAX_LATENESS_SECONDS))) {
            if (nextRestart != null) {
                ConsoleLogger.warn("[ScheduledRestart] Slot " + RestartSchedule.slotKey(nextRestart)
                        + " was missed by more than " + MAX_LATENESS_SECONDS + "s — skipped.");
            }
            advance(now);
            sentWarnings.clear();
        }
        if (nextRestart == null) return;

        long remainingMillis = Duration.between(now, nextRestart).toMillis();
        if (remainingMillis <= 0) {
            fireSlot(now);
            return;
        }
        sendDueWarnings((remainingMillis + 999) / 1000);
    }

    /** Executes (or deliberately skips) the current slot and moves to the next one. */
    private void fireSlot(LocalDateTime now) {
        LocalDateTime slot = nextRestart;
        String key = RestartSchedule.slotKey(slot);

        if (key.equals(lastFiredSlot)) {
            // Restart completed inside the same minute — never replay the slot.
            advance(now);
            sentWarnings.clear();
            return;
        }

        if (skipIfEmpty && Bukkit.getOnlinePlayers().isEmpty()) {
            markFired(key);
            ConsoleLogger.info("[ScheduledRestart] Slot " + key + " skipped: no players online.");
            advance(now);
            sentWarnings.clear();
            return;
        }

        PowerManager power = PowerManager.getInstance();
        if (power == null || power.hasPendingRequest() || power.hasActiveCountdown()) {
            markFired(key);
            ConsoleLogger.warn("[ScheduledRestart] Slot " + key
                    + " skipped: another power action is pending.");
            advance(now);
            sentWarnings.clear();
            return;
        }

        markFired(key);
        boolean started = power.startScheduledRestart(countdownSeconds);
        if (started) {
            ConsoleLogger.info("[ScheduledRestart] Slot " + key
                    + " triggered — countdown " + countdownSeconds + "s.");
        } else {
            ConsoleLogger.warn("[ScheduledRestart] Slot " + key + " could not start the countdown.");
        }
        advance(now);
        sentWarnings.clear();
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

        String message = MessagesManager.getString("power.scheduled_warning",
                "<dark_gray>[<dark_red>⚠</dark_red>]</dark_gray> <red>Scheduled server restart in</red> <yellow>%minutes%</yellow> <red>min.</red>")
                .replace("%minutes%", String.valueOf(due));
        Broadcast.send(message);
    }

    private void advance(LocalDateTime now) {
        nextRestart = RestartSchedule.nextAfter(times, now);
    }

    private void markFired(String key) {
        lastFiredSlot = key;
        StateStore.put(STATE_NAMESPACE, STATE_LAST_SLOT, key);
    }

    /** Formats the next slot for command output ({@code yyyy-MM-dd HH:mm}). */
    public String describeNext() {
        return nextRestart == null ? null : nextRestart.format(DISPLAY_FORMAT);
    }
}
