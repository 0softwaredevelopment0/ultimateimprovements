package com.ultimateimprovements.module.meteor;

import java.util.Locale;

/**
 * The material identity of a meteorite's center block
 * ({@code [meteor.types]} in {@code configs/UI-World.toml}).
 *
 * <p>The shell of the sphere is always built from {@code [meteor.blocks]};
 * the type only decides what sits in the single center block:</p>
 *
 * <ul>
 *   <li>{@code wet} — water;</li>
 *   <li>{@code hot} — lava;</li>
 *   <li>{@code basic} — blackstone;</li>
 *   <li>{@code ore} — a random ore from {@code [meteor.core_ores]};</li>
 *   <li>{@code ancient} — ancient debris.</li>
 * </ul>
 *
 * <p>The enum itself carries no Bukkit types, so it can be resolved (and unit
 * tested) without a running server. {@code MeteorModule} maps each entry to
 * its block material.</p>
 */
public enum MeteorType {

    WET("wet"),
    HOT("hot"),
    BASIC("basic"),
    ORE("ore"),
    ANCIENT("ancient");

    private final String key;

    MeteorType(String key) {
        this.key = key;
    }

    /** The config key of this type in {@code [meteor.types]}. */
    public String getKey() {
        return key;
    }

    /**
     * @return the type matching a config key (case-insensitive), or {@code null}
     *         when the key is unknown
     */
    public static MeteorType fromKey(String raw) {
        if (raw == null) return null;
        String key = raw.trim().toLowerCase(Locale.ROOT);
        for (MeteorType type : values()) {
            if (type.key.equals(key)) return type;
        }
        return null;
    }
}
