package com.ultimateimprovements.whitelist;

import com.ultimateimprovements.core.Main;
import com.ultimateimprovements.database.StateStore;
import com.ultimateimprovements.punish.PunishmentMessages;
import com.ultimateimprovements.util.ConsoleLogger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Countdown warnings for scheduled whitelist removals.
 * <p>
 * A single ticker (every second) scans the pending removals kept by
 * {@link TimedAccessLists} and announces the remaining time with the same
 * cadence as the scheduled restart: configured thresholds
 * ({@code 1h}, {@code 30m}, ...) and a per-second report near the end.
 * Only the affected player receives the messages.
 * <p>
 * Warning progress is keyed by player + execution time, so a replaced
 * schedule starts a fresh countdown and a finished/cancelled one is dropped
 * automatically. A player who goes offline is forgotten, and joining resets
 * the cadence, so the next tick sends the exact current remaining time again.
 * <p>
 * The same listener delivers the "added back" notice: when an add cancels a
 * pending removal, the online player is told immediately; an offline player
 * gets the notice on the next join (persisted in {@code ui_state} for a week).
 * <p>
 * Config lives in {@code UI-Punish.toml} under {@code [access_control.warnings]}.
 */
public final class TimedListWarnings extends BukkitRunnable implements Listener {

    private static final long TICK_PERIOD = 20L;
    private static final String CFG = "access_control.warnings.";

    /** Persisted "added back while a removal was pending" notices. */
    private static final String NOTICE_NAMESPACE = "whitelist_readded";
    private static final long NOTICE_TTL_MS = 7L * 86_400_000L;

    /** Announcement progress by {@code player:executeAt}. */
    private static final Map<String, WarningCadence.State> STATES = new HashMap<>();

    private static List<Long> thresholds = List.of();
    private static long secondReportFrom = 10L;
    private static Main plugin;
    private static BukkitTask task;
    private static TimedListWarnings listener;

    private TimedListWarnings() {}

    // =========================
    // LIFECYCLE
    // =========================

    /** Reads the config and (re)starts the ticker. Safe to call on reload. */
    public static void start(Main plugin) {
        stop();
        TimedListWarnings.plugin = plugin;

        // The join listener also delivers persisted "re-added" notices, so it
        // stays registered even when the countdown itself is disabled.
        listener = new TimedListWarnings();
        Bukkit.getPluginManager().registerEvents(listener, plugin);

        if (!plugin.getConfig().getBoolean(CFG + "enabled", true)) {
            ConsoleLogger.info("[WhitelistWarnings] Disabled in config (" + CFG + "enabled: false).");
            return;
        }

        thresholds = parseThresholds(plugin.getConfig().getStringList(CFG + "warning_times"));
        secondReportFrom = parseSecondReportFrom(
                plugin.getConfig().getString(CFG + "second_report_from", "10s"));

        if (thresholds.isEmpty() && secondReportFrom <= 0) {
            ConsoleLogger.info("[WhitelistWarnings] No warning times and per-second report off — nothing to announce.");
            return;
        }

        task = new TimedListWarnings().runTaskTimer(plugin, TICK_PERIOD, TICK_PERIOD);
        ConsoleLogger.info("[WhitelistWarnings] Started: thresholds=" + thresholds
                + "s, per-second-from=" + (secondReportFrom > 0 ? secondReportFrom + "s" : "off"));
    }

    /** Cancels the ticker and drops all announcement progress. */
    public static void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        if (listener != null) {
            HandlerList.unregisterAll(listener);
            listener = null;
        }
        STATES.clear();
        thresholds = List.of();
        secondReportFrom = 10L;
        plugin = null;
    }

    // =========================
    // TICK
    // =========================

    @Override
    public void run() {
        if (plugin == null) return;

        long now = System.currentTimeMillis();
        Set<String> seen = new HashSet<>();

        for (TimedAccessLists.PendingRemoval removal
                : TimedAccessLists.pendingRemovals(TimedAccessLists.AccessList.WHITELIST)) {
            String playerName = removal.player();
            String key = playerName + ":" + removal.executeAt();

            Player player = onlinePlayer(playerName);
            if (player == null || !WhitelistManager.isEnabled()
                    || !WhitelistManager.isWhitelisted(playerName)) {
                // Offline or no longer affected: a later join starts fresh.
                STATES.remove(key);
                continue;
            }

            long remainingMs = removal.executeAt() - now;
            if (remainingMs <= 0) {
                STATES.remove(key);
                continue;
            }

            seen.add(key);
            long remainingSeconds = (remainingMs + 999L) / 1000L;
            WarningCadence.State state = STATES.computeIfAbsent(key, k -> new WarningCadence.State());
            Long announce = WarningCadence.nextAnnouncement(
                    state, remainingSeconds, thresholds, secondReportFrom);
            if (announce != null) {
                announce(player, announce);
            }
        }

        STATES.keySet().retainAll(seen);
    }

    /** Delivers offline notices and makes the next tick re-announce the exact time. */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        resetCadence(player.getName());
        deliverReAddedNotice(player);
    }

    // =========================
    // RE-ADDED NOTICES
    // =========================

    /**
     * Records that the player was added back while a whitelist removal was
     * pending. Online players are told immediately; offline players get the
     * notice on their next join (persisted for {@link #NOTICE_TTL_MS}).
     */
    public static void recordReAddedNotice(String playerName) {
        if (playerName == null || playerName.isBlank()) return;

        Player online = onlinePlayer(playerName);
        if (online != null) {
            sendReAddedNotice(online);
            return;
        }

        String key = playerName.toLowerCase(Locale.ROOT).trim();
        StateStore.put(NOTICE_NAMESPACE, key,
                String.valueOf(System.currentTimeMillis() + NOTICE_TTL_MS));
    }

    /** Drops a stored re-added notice (a new removal supersedes it). */
    public static void clearReAddedNotice(String playerName) {
        if (playerName == null || playerName.isBlank()) return;
        StateStore.remove(NOTICE_NAMESPACE, playerName.toLowerCase(Locale.ROOT).trim());
    }

    private static void deliverReAddedNotice(Player player) {
        String key = player.getName().toLowerCase(Locale.ROOT);
        String raw = StateStore.get(NOTICE_NAMESPACE, key);
        if (raw == null) return;

        StateStore.remove(NOTICE_NAMESPACE, key);

        long expiresAt;
        try {
            expiresAt = Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return;
        }
        if (System.currentTimeMillis() > expiresAt) return;
        sendReAddedNotice(player);
    }

    private static void sendReAddedNotice(Player player) {
        if (!WhitelistManager.isWhitelisted(player.getName())) return;
        player.sendMessage(PunishmentMessages.buildReAddedNotice(
                TimedAccessLists.AccessList.WHITELIST.displayName()));
    }

    /** Forgets any in-memory cadence for this player so the next tick starts fresh. */
    private static void resetCadence(String playerName) {
        String prefix = playerName.toLowerCase(Locale.ROOT) + ":";
        STATES.keySet().removeIf(key -> key.startsWith(prefix));
    }

    private static void announce(Player player, long seconds) {
        TimeValue time = splitUnits(seconds);
        player.sendMessage(PunishmentMessages.buildAccessRemovalWarning(
                TimedAccessLists.AccessList.WHITELIST.displayName(),
                time.value(), time.unitKey()));
    }

    /** Resolves an online player by name, ignoring the stored name's case. */
    private static Player onlinePlayer(String name) {
        Player exact = Bukkit.getPlayerExact(name);
        if (exact != null) return exact;
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.getName().equalsIgnoreCase(name)) return online;
        }
        return null;
    }

    // =========================
    // CONFIG PARSING
    // =========================

    /** Parses {@code warning_times} into distinct seconds, ascending. */
    static List<Long> parseThresholds(List<String> raw) {
        if (raw == null || raw.isEmpty()) return List.of();

        List<Long> parsed = new ArrayList<>();
        for (String entry : raw) {
            long millis = TimedAccessLists.parseDelayMillis(entry);
            if (millis <= 0) {
                ConsoleLogger.warn("[WhitelistWarnings] Ignoring invalid warning time '" + entry
                        + "' (expected a value with a unit: 45s, 5m, 1h, 1d)");
                continue;
            }
            long seconds = millis / 1000L;
            if (!parsed.contains(seconds)) parsed.add(seconds);
        }
        Collections.sort(parsed);
        return List.copyOf(parsed);
    }

    /**
     * Parses {@code second_report_from} (default {@code 10s}); {@code 0},
     * {@code 0s} or {@code off} disables the per-second report.
     */
    private static long parseSecondReportFrom(String raw) {
        if (raw == null || raw.isBlank()) return 10L;

        String value = raw.trim();
        if (value.equalsIgnoreCase("off") || value.equals("0") || value.equalsIgnoreCase("0s")) {
            return 0L;
        }

        long millis = TimedAccessLists.parseDelayMillis(value);
        if (millis <= 0) {
            ConsoleLogger.warn("[WhitelistWarnings] Invalid second_report_from '" + raw
                    + "' — using 10s (expected 10s, 5m or 0/off)");
            return 10L;
        }
        return Math.max(1L, millis / 1000L);
    }

    // =========================
    // TIME SPLITTING
    // =========================

    /** A number plus the config key of its localized unit word. */
    record TimeValue(long value, String unitKey) {}

    /**
     * Splits seconds into the largest exact unit, mirroring the scheduled
     * restart announcements ({@code 3600 -> 1 hour}, {@code 90 -> 90 seconds}).
     */
    static TimeValue splitUnits(long totalSeconds) {
        long seconds = Math.max(0, totalSeconds);
        if (seconds >= 86_400 && seconds % 86_400 == 0) {
            return new TimeValue(seconds / 86_400, "unit_days");
        }
        if (seconds >= 3_600 && seconds % 3_600 == 0) {
            return new TimeValue(seconds / 3_600, "unit_hours");
        }
        if (seconds >= 60 && seconds % 60 == 0) {
            return new TimeValue(seconds / 60, "unit_minutes");
        }
        return new TimeValue(seconds, "unit_seconds");
    }
}
