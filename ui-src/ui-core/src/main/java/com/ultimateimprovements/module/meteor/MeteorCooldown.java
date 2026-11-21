package com.ultimateimprovements.module.meteor;

import com.ultimateimprovements.util.ConsoleLogger;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Spawn cooldown of the meteor scheduler ({@code [meteor.cooldown]} in
 * {@code configs/UI-World.toml}).
 *
 * <p>Three modes are supported:</p>
 * <ul>
 *   <li>{@code static} — always the same cooldown ({@code static});</li>
 *   <li>{@code list} — a random entry of {@code list} every time;</li>
 *   <li>{@code range} — a random number of seconds between the two bounds of
 *       {@code range} (e.g. {@code "40m-50m"}), picked to the second.</li>
 * </ul>
 *
 * <p>Every time value is written as a number plus one unit suffix:
 * {@code s} (seconds), {@code m} (minutes), {@code h} (hours) or {@code d}
 * (days), e.g. {@code "30m"}, {@code "2h"}, {@code "1d"}. A range is two such
 * values separated by a dash: {@code "40m-50m"}.</p>
 *
 * <p>This class is deliberately free of plugin state, so the parser can be unit
 * tested without a running server.</p>
 */
public final class MeteorCooldown {

    public enum Mode { STATIC, LIST, RANGE }

    /** Default cooldown of {@code mode = "static"}. */
    public static final long DEFAULT_STATIC_SECONDS = 30L * 60L;

    /** Default pool of {@code mode = "list"}. */
    public static final List<String> DEFAULT_LIST = List.of("15m", "10m", "5m", "20m");

    /** Default bounds of {@code mode = "range"}. */
    public static final long DEFAULT_RANGE_MIN_SECONDS = 40L * 60L;
    public static final long DEFAULT_RANGE_MAX_SECONDS = 50L * 60L;

    /** Sanity cap: nothing longer than ten years. */
    static final long MAX_SECONDS = 10L * 365L * 24L * 3600L;

    private final Mode mode;
    private final long staticSeconds;
    private final long[] listSeconds;
    private final long rangeMinSeconds;
    private final long rangeMaxSeconds;

    private MeteorCooldown(Mode mode, long staticSeconds, long[] listSeconds,
                           long rangeMinSeconds, long rangeMaxSeconds) {
        this.mode = mode;
        this.staticSeconds = staticSeconds;
        this.listSeconds = listSeconds;
        this.rangeMinSeconds = rangeMinSeconds;
        this.rangeMaxSeconds = rangeMaxSeconds;
    }

    /** The bundled defaults ({@code range} 40m-50m). */
    public static MeteorCooldown defaults() {
        return new MeteorCooldown(Mode.RANGE, DEFAULT_STATIC_SECONDS,
                parseList(DEFAULT_LIST).stream().mapToLong(Long::longValue).toArray(),
                DEFAULT_RANGE_MIN_SECONDS, DEFAULT_RANGE_MAX_SECONDS);
    }

    // ========================================================================
    // CONFIG
    // ========================================================================

    /**
     * Reads the cooldown section, falling back to the bundled defaults for every
     * invalid value (with a console warning instead of crashing the scheduler).
     */
    public static MeteorCooldown fromConfig(FileConfiguration cfg) {
        return parse(
                cfg.getString("meteor.cooldown.mode", "range"),
                cfg.getString("meteor.cooldown.static"),
                cfg.getStringList("meteor.cooldown.list"),
                cfg.getString("meteor.cooldown.range"));
    }

    /**
     * Builds a cooldown from raw config values. Every invalid entry falls back to
     * its bundled default, so a typo never kills the scheduler. Public for unit
     * tests — the Bukkit-free part of {@link #fromConfig}.
     */
    public static MeteorCooldown parse(String rawMode, String rawStatic,
                                       List<String> rawList, String rawRange) {
        Mode mode = parseMode(rawMode);

        long staticSeconds = DEFAULT_STATIC_SECONDS;
        if (rawStatic != null && !rawStatic.isBlank()) {
            Long parsed = tryParseSeconds(rawStatic);
            if (parsed != null) staticSeconds = parsed;
            else warnInvalid("meteor.cooldown.static", rawStatic, formatSeconds(DEFAULT_STATIC_SECONDS));
        }

        List<Long> listSeconds = parseList(rawList);
        if (listSeconds.isEmpty()) {
            if (rawList != null && !rawList.isEmpty() && rawList.stream().anyMatch(v -> v != null && !v.isBlank())) {
                warnInvalid("meteor.cooldown.list", String.join(", ", rawList),
                        String.join(", ", DEFAULT_LIST));
            }
            listSeconds = parseList(DEFAULT_LIST);
        }

        long rangeMin = DEFAULT_RANGE_MIN_SECONDS;
        long rangeMax = DEFAULT_RANGE_MAX_SECONDS;
        if (rawRange != null && !rawRange.isBlank()) {
            long[] range = tryParseRange(rawRange);
            if (range != null) {
                rangeMin = range[0];
                rangeMax = range[1];
            } else {
                warnInvalid("meteor.cooldown.range", rawRange,
                        formatSeconds(DEFAULT_RANGE_MIN_SECONDS) + "-" + formatSeconds(DEFAULT_RANGE_MAX_SECONDS));
            }
        }

        return new MeteorCooldown(mode, staticSeconds,
                listSeconds.stream().mapToLong(Long::longValue).toArray(), rangeMin, rangeMax);
    }

    private static Mode parseMode(String raw) {
        if (raw == null) return Mode.RANGE;
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "static" -> Mode.STATIC;
            case "list" -> Mode.LIST;
            default -> Mode.RANGE;
        };
    }

    /** Parses every valid entry of a cooldown list, warning once about invalid ones. */
    private static List<Long> parseList(List<String> raw) {
        List<Long> parsed = new ArrayList<>();
        if (raw == null) return parsed;
        for (String entry : raw) {
            Long seconds = tryParseSeconds(entry);
            if (seconds != null) {
                parsed.add(seconds);
            } else if (entry != null && !entry.isBlank()) {
                ConsoleLogger.warn("[Meteor] Invalid cooldown in meteor.cooldown.list: '" + entry
                        + "' — expected a value like 15m (s/m/h/d suffixes).");
            }
        }
        return parsed;
    }

    private static void warnInvalid(String key, String value, String fallback) {
        ConsoleLogger.warn("[Meteor] Invalid " + key + " value '" + value
                + "' — expected a time with an s/m/h/d suffix (e.g. 30m); using " + fallback + ".");
    }

    private static Long tryParseSeconds(String raw) {
        try {
            return parseSeconds(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static long[] tryParseRange(String raw) {
        try {
            return parseRange(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ========================================================================
    // PARSING
    // ========================================================================

    /**
     * Parses a single time value such as {@code "15m"}, {@code "2h"} or
     * {@code "1d"} into seconds.
     *
     * @throws IllegalArgumentException for anything that is not a positive
     *                                  number followed by {@code s}, {@code m},
     *                                  {@code h} or {@code d}
     */
    public static long parseSeconds(String raw) {
        if (raw == null) throw new IllegalArgumentException("time value is null");
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) throw new IllegalArgumentException("time value is empty");

        char unit = value.charAt(value.length() - 1);
        long multiplier = switch (unit) {
            case 's' -> 1L;
            case 'm' -> 60L;
            case 'h' -> 3600L;
            case 'd' -> 86400L;
            default -> throw new IllegalArgumentException(
                    "missing time suffix (s/m/h/d) in '" + raw + "'");
        };

        String number = value.substring(0, value.length() - 1).trim();
        long amount;
        try {
            amount = Long.parseLong(number);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("not a number in '" + raw + "'");
        }
        if (amount <= 0) throw new IllegalArgumentException("time must be positive in '" + raw + "'");

        long seconds = safeMultiply(amount, multiplier);
        if (seconds <= 0 || seconds > MAX_SECONDS) {
            throw new IllegalArgumentException("time out of range in '" + raw + "'");
        }
        return seconds;
    }

    /**
     * Parses a range such as {@code "40m-50m"} into {@code [min, max]} seconds.
     * The bounds are normalised, so {@code "50m-40m"} is accepted.
     */
    public static long[] parseRange(String raw) {
        if (raw == null) throw new IllegalArgumentException("range is null");
        String[] parts = raw.trim().split("\\s*-\\s*");
        if (parts.length != 2) {
            throw new IllegalArgumentException("expected '<min>-<max>' in '" + raw + "'");
        }
        long first = parseSeconds(parts[0]);
        long second = parseSeconds(parts[1]);
        return new long[]{Math.min(first, second), Math.max(first, second)};
    }

    private static long safeMultiply(long amount, long multiplier) {
        if (amount > MAX_SECONDS / multiplier) return Long.MAX_VALUE;
        return amount * multiplier;
    }

    // ========================================================================
    // RUNTIME
    // ========================================================================

    /** Picks the next cooldown, in seconds, according to the configured mode. */
    public long nextSeconds(Random random) {
        return switch (mode) {
            case STATIC -> staticSeconds;
            case LIST -> listSeconds.length == 0
                    ? DEFAULT_STATIC_SECONDS
                    : listSeconds[random.nextInt(listSeconds.length)];
            case RANGE -> pickRangeSeconds(random);
        };
    }

    private long pickRangeSeconds(Random random) {
        if (rangeMaxSeconds <= rangeMinSeconds) return rangeMinSeconds;
        long span = rangeMaxSeconds - rangeMinSeconds + 1;
        // nextDouble keeps the pick exact even for very wide ranges.
        long offset = (long) (random.nextDouble() * span);
        return Math.min(rangeMaxSeconds, rangeMinSeconds + offset);
    }

    public Mode getMode() {
        return mode;
    }

    public long getStaticSeconds() {
        return staticSeconds;
    }

    public long getRangeMinSeconds() {
        return rangeMinSeconds;
    }

    public long getRangeMaxSeconds() {
        return rangeMaxSeconds;
    }

    public long[] getListSeconds() {
        return listSeconds.clone();
    }

    /** "1d 2h 3m 4s" (leading zero units are omitted). */
    public static String formatSeconds(long totalSeconds) {
        long remaining = Math.max(0, totalSeconds);
        long days = remaining / 86400;
        remaining %= 86400;
        long hours = remaining / 3600;
        remaining %= 3600;
        long minutes = remaining / 60;
        long seconds = remaining % 60;

        StringBuilder out = new StringBuilder();
        if (days > 0) out.append(days).append("d ");
        if (hours > 0) out.append(hours).append("h ");
        if (minutes > 0) out.append(minutes).append("m ");
        if (seconds > 0 || out.isEmpty()) out.append(seconds).append("s");
        return out.toString().trim();
    }
}
