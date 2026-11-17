package com.ultimateimprovements.restart;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure helpers for the scheduled-restart feature: parsing configured
 * {@code HH:mm} times, finding the next occurrence and formatting durations.
 * <p>
 * Deliberately free of Bukkit/Paper dependencies so it can be unit tested.
 */
public final class RestartSchedule {

    /** Key format of a consumed slot (persisted in {@code ui_state}). */
    public static final DateTimeFormatter SLOT_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** Interval with an explicit unit: {@code 30s}, {@code 15m}, {@code 48h}, {@code 7d}. */
    private static final Pattern INTERVAL = Pattern.compile("^\\s*(\\d{1,7})\\s*([smhd])\\s*$",
            Pattern.CASE_INSENSITIVE);

    private RestartSchedule() {}

    /**
     * Parses configured times ({@code "4:00"}, {@code "04:00"}).
     * <p>
     * Malformed entries are dropped and reported through {@code onInvalid}
     * (the manager logs them); duplicates are collapsed. The result is sorted
     * ascending, so the first entry is always the earliest time of day.
     *
     * @param raw       configured strings, may be {@code null}
     * @param onInvalid called once per rejected entry with the raw value
     * @return immutable, sorted list of valid times (possibly empty)
     */
    public static List<LocalTime> parseTimes(Collection<String> raw, Consumer<String> onInvalid) {
        if (raw == null || raw.isEmpty()) return List.of();

        List<LocalTime> parsed = new ArrayList<>();
        for (String entry : raw) {
            LocalTime time = parseTime(entry);
            if (time == null) {
                if (onInvalid != null) onInvalid.accept(entry);
                continue;
            }
            if (!parsed.contains(time)) parsed.add(time);
        }
        Collections.sort(parsed);
        return List.copyOf(parsed);
    }

    /** @return the parsed time, or {@code null} when the entry is not {@code H:mm}/{@code HH:mm}. */
    public static LocalTime parseTime(String entry) {
        if (entry == null) return null;
        String value = entry.trim();
        if (!value.matches("^([01]?\\d|2[0-3]):[0-5]\\d$")) return null;
        try {
            return LocalTime.parse(value, DateTimeFormatter.ofPattern("H:mm"));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Parses a timer interval with an explicit unit:
     * {@code s} = seconds, {@code m} = minutes, {@code h} = hours,
     * {@code d} = days (e.g. {@code "48h"}).
     *
     * @return the duration, or {@code null} when the value is malformed or not positive
     */
    public static Duration parseInterval(String raw) {
        if (raw == null) return null;
        Matcher matcher = INTERVAL.matcher(raw);
        if (!matcher.matches()) return null;

        long amount;
        try {
            amount = Long.parseLong(matcher.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
        if (amount <= 0) return null;

        long seconds = switch (matcher.group(2).toLowerCase(Locale.ROOT)) {
            case "s" -> amount;
            case "m" -> amount * 60L;
            case "h" -> amount * 3_600L;
            case "d" -> amount * 86_400L;
            default -> 0L;
        };
        if (seconds <= 0) return null;
        return Duration.ofSeconds(seconds);
    }

    /**
     * Finds the earliest configured time strictly after {@code now}.
     * <p>
     * A time that already passed today rolls over to tomorrow. Strictly-after
     * semantics are intentional: a server that boots after today's slot must not
     * restart retroactively (restart-loop protection).
     *
     * @return the next occurrence, or {@code null} when {@code times} is empty
     */
    public static LocalDateTime nextAfter(List<LocalTime> times, LocalDateTime now) {
        if (times == null || times.isEmpty()) return null;

        LocalDateTime best = null;
        for (LocalTime time : times) {
            LocalDateTime candidate = now.toLocalDate().atTime(time);
            if (!candidate.isAfter(now)) candidate = candidate.plusDays(1);
            if (best == null || candidate.isBefore(best)) best = candidate;
        }
        return best;
    }

    /** Persisted identity of one slot, e.g. {@code "2026-11-18 04:00"}. */
    public static String slotKey(LocalDateTime slot) {
        return slot == null ? null : slot.format(SLOT_FORMAT);
    }

    /**
     * Human-readable duration for command output, e.g. {@code "2h 5m 3s"}.
     * Units are language-neutral (d/h/m/s) because the value is dropped into
     * both the Russian and English message templates.
     */
    public static String formatDuration(Duration duration) {
        long total = duration == null ? 0 : Math.max(0, duration.getSeconds());
        long days = total / 86_400;
        long hours = (total % 86_400) / 3_600;
        long minutes = (total % 3_600) / 60;
        long seconds = total % 60;

        StringBuilder out = new StringBuilder();
        if (days > 0) out.append(days).append("d ");
        if (hours > 0 || days > 0) out.append(hours).append("h ");
        if (minutes > 0 || hours > 0 || days > 0) out.append(minutes).append("m ");
        out.append(seconds).append('s');
        return out.toString();
    }
}
