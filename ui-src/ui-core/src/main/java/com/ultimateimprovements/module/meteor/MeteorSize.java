package com.ultimateimprovements.module.meteor;

import com.ultimateimprovements.util.ConsoleLogger;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Size (sphere radius, in blocks) of a meteor ({@code [meteor.size]} in
 * {@code configs/UI-World.toml}).
 *
 * <p>Three modes are supported, mirroring the spawn cooldown:</p>
 * <ul>
 *   <li>{@code static} — always the same size ({@code static});</li>
 *   <li>{@code list} — a random entry of {@code list} every time;</li>
 *   <li>{@code range} — a random integer between the two bounds of
 *       {@code range} (e.g. {@code "1-8"}), both inclusive.</li>
 * </ul>
 *
 * <p>The size is the radius of the meteorite sphere: {@code 1} is a small
 * 3×3×3 blob, {@code 8} a 17-block sphere. The explosion power scales with it
 * (see {@code [meteor.explosion]}).</p>
 *
 * <p>This class is deliberately free of plugin state, so the parser can be unit
 * tested without a running server.</p>
 */
public final class MeteorSize {

    public enum Mode { STATIC, LIST, RANGE }

    /** Smallest allowed radius: the center block alone. */
    public static final int MIN_SIZE = 1;
    /** Largest allowed radius — bigger spheres are thousands of entities. */
    public static final int MAX_SIZE = 12;

    /** Default size of {@code mode = "static"}. */
    public static final int DEFAULT_STATIC = 2;

    /** Default pool of {@code mode = "list"}. */
    public static final List<Integer> DEFAULT_LIST = List.of(1, 2, 3, 4);

    /** Default bounds of {@code mode = "range"}. */
    public static final int DEFAULT_RANGE_MIN = 1;
    public static final int DEFAULT_RANGE_MAX = 8;

    private final Mode mode;
    private final int staticSize;
    private final int[] listSizes;
    private final int rangeMin;
    private final int rangeMax;

    private MeteorSize(Mode mode, int staticSize, int[] listSizes, int rangeMin, int rangeMax) {
        this.mode = mode;
        this.staticSize = staticSize;
        this.listSizes = listSizes;
        this.rangeMin = rangeMin;
        this.rangeMax = rangeMax;
    }

    /** The bundled defaults ({@code range} 1-8). */
    public static MeteorSize defaults() {
        return new MeteorSize(Mode.RANGE, DEFAULT_STATIC,
                DEFAULT_LIST.stream().mapToInt(Integer::intValue).toArray(),
                DEFAULT_RANGE_MIN, DEFAULT_RANGE_MAX);
    }

    // ========================================================================
    // CONFIG
    // ========================================================================

    /**
     * Reads the size section, falling back to the bundled defaults for every
     * invalid value (with a console warning instead of crashing the spawn).
     */
    public static MeteorSize fromConfig(FileConfiguration cfg) {
        return parse(
                cfg.getString("meteor.size.mode", "range"),
                cfg.getInt("meteor.size.static", DEFAULT_STATIC),
                cfg.getIntegerList("meteor.size.list"),
                cfg.getString("meteor.size.range"));
    }

    /**
     * Builds a size rule from raw config values. Every invalid entry falls back
     * to its bundled default. Public for unit tests — the Bukkit-free part of
     * {@link #fromConfig}.
     */
    public static MeteorSize parse(String rawMode, int rawStatic,
                                   List<Integer> rawList, String rawRange) {
        Mode mode = parseMode(rawMode);

        int staticSize = rawStatic >= MIN_SIZE && rawStatic <= MAX_SIZE
                ? rawStatic
                : DEFAULT_STATIC;

        List<Integer> listSizes = new ArrayList<>();
        if (rawList != null) {
            for (Integer entry : rawList) {
                if (entry != null) listSizes.add(clamp(entry));
            }
        }
        if (listSizes.isEmpty()) listSizes = new ArrayList<>(DEFAULT_LIST);

        int rangeMin = DEFAULT_RANGE_MIN;
        int rangeMax = DEFAULT_RANGE_MAX;
        if (rawRange != null && !rawRange.isBlank()) {
            int[] range = tryParseRange(rawRange);
            if (range != null) {
                rangeMin = range[0];
                rangeMax = range[1];
            } else {
                ConsoleLogger.warn("[Meteor] Invalid meteor.size.range value '" + rawRange
                        + "' — expected '<min>-<max>' (e.g. 1-8); using "
                        + DEFAULT_RANGE_MIN + "-" + DEFAULT_RANGE_MAX + ".");
            }
        }

        return new MeteorSize(mode, staticSize,
                listSizes.stream().mapToInt(Integer::intValue).toArray(), rangeMin, rangeMax);
    }

    private static Mode parseMode(String raw) {
        if (raw == null) return Mode.RANGE;
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "static" -> Mode.STATIC;
            case "list" -> Mode.LIST;
            default -> Mode.RANGE;
        };
    }

    private static int[] tryParseRange(String raw) {
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
     * Parses a range such as {@code "1-8"} into {@code [min, max]} sizes.
     * The bounds are normalised, so {@code "8-1"} is accepted, and clamped to
     * {@link #MIN_SIZE}..{@link #MAX_SIZE}.
     *
     * @throws IllegalArgumentException for anything that is not two integers
     *                                  separated by a dash
     */
    public static int[] parseRange(String raw) {
        if (raw == null) throw new IllegalArgumentException("range is null");
        String[] parts = raw.trim().split("\\s*-\\s*");
        if (parts.length != 2) {
            throw new IllegalArgumentException("expected '<min>-<max>' in '" + raw + "'");
        }
        int first;
        int second;
        try {
            first = Integer.parseInt(parts[0].trim());
            second = Integer.parseInt(parts[1].trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("not a number in '" + raw + "'");
        }
        return new int[]{Math.min(clamp(first), clamp(second)), Math.max(clamp(first), clamp(second))};
    }

    private static int clamp(int size) {
        return Math.max(MIN_SIZE, Math.min(MAX_SIZE, size));
    }

    // ========================================================================
    // RUNTIME
    // ========================================================================

    /** Picks the next sphere radius according to the configured mode. */
    public int nextSize(Random random) {
        return switch (mode) {
            case STATIC -> staticSize;
            case LIST -> listSizes.length == 0
                    ? DEFAULT_STATIC
                    : listSizes[random.nextInt(listSizes.length)];
            case RANGE -> pickRangeSize(random);
        };
    }

    private int pickRangeSize(Random random) {
        if (rangeMax <= rangeMin) return rangeMin;
        int span = rangeMax - rangeMin + 1;
        return rangeMin + random.nextInt(span);
    }

    public Mode getMode() {
        return mode;
    }

    public int getStaticSize() {
        return staticSize;
    }

    public int[] getListSizes() {
        return listSizes.clone();
    }

    public int getRangeMin() {
        return rangeMin;
    }

    public int getRangeMax() {
        return rangeMax;
    }
}
