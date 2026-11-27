package com.ultimateimprovements.module.meteor;

import com.ultimateimprovements.util.ConsoleLogger;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

/**
 * Weighted table of {@link MeteorType}s ({@code [meteor.types]} in
 * {@code configs/UI-World.toml}).
 *
 * <p>Every type has a positive weight; the chance of a type is
 * {@code weight / totalWeight}. The bundled defaults are equal for
 * {@code wet}, {@code hot}, {@code basic} and {@code ore}, while
 * {@code ancient} is five times rarer ({@code 0.2}).</p>
 */
public final class MeteorTypes {

    /** Default weight of a regular type. */
    public static final double DEFAULT_WEIGHT = 1.0;
    /** Default weight of the ancient type — five times rarer than the others. */
    public static final double DEFAULT_ANCIENT_WEIGHT = 0.2;

    private final Map<MeteorType, Double> weights;
    private final double totalWeight;

    private MeteorTypes(Map<MeteorType, Double> weights, double totalWeight) {
        this.weights = weights;
        this.totalWeight = totalWeight;
    }

    /** The bundled defaults (equal, ancient 5× rarer). */
    public static MeteorTypes defaults() {
        Map<MeteorType, Double> weights = new EnumMap<>(MeteorType.class);
        for (MeteorType type : MeteorType.values()) {
            weights.put(type, type == MeteorType.ANCIENT ? DEFAULT_ANCIENT_WEIGHT : DEFAULT_WEIGHT);
        }
        return build(weights);
    }

    /** Reads {@code [meteor.types]}, falling back to the bundled defaults when empty. */
    public static MeteorTypes fromConfig(FileConfiguration cfg) {
        Map<String, Double> raw = new LinkedHashMap<>();
        if (cfg.isConfigurationSection("meteor.types")) {
            ConfigurationSection section = cfg.getConfigurationSection("meteor.types");
            if (section != null) {
                for (String key : section.getKeys(false)) {
                    raw.put(key, section.getDouble(key, DEFAULT_WEIGHT));
                }
            }
        }
        return parse(raw);
    }

    /**
     * Builds the table from raw config entries. Unknown keys and non-positive
     * weights are ignored with a warning; an empty result falls back to the
     * bundled defaults. Public for unit tests.
     */
    public static MeteorTypes parse(Map<String, Double> raw) {
        Map<MeteorType, Double> weights = new EnumMap<>(MeteorType.class);
        if (raw != null) {
            for (Map.Entry<String, Double> entry : raw.entrySet()) {
                MeteorType type = MeteorType.fromKey(entry.getKey());
                if (type == null) {
                    ConsoleLogger.warn("[Meteor] Unknown meteor type '" + entry.getKey()
                            + "' in meteor.types — expected wet, hot, basic, ore or ancient.");
                    continue;
                }
                Double weight = entry.getValue();
                if (weight == null || !Double.isFinite(weight) || weight <= 0) {
                    ConsoleLogger.warn("[Meteor] Invalid weight for meteor type '" + type.getKey()
                            + "' — expected a positive number; ignoring it.");
                    continue;
                }
                weights.put(type, weight);
            }
        }
        return weights.isEmpty() ? defaults() : build(weights);
    }

    private static MeteorTypes build(Map<MeteorType, Double> weights) {
        double total = 0;
        for (double weight : weights.values()) total += weight;
        if (total <= 0) return defaults();
        return new MeteorTypes(new EnumMap<>(weights), total);
    }

    /** Picks a type by weighted random. */
    public MeteorType next(Random random) {
        double roll = random.nextDouble() * totalWeight;
        double cumulative = 0;
        MeteorType last = null;
        for (Map.Entry<MeteorType, Double> entry : weights.entrySet()) {
            cumulative += entry.getValue();
            last = entry.getKey();
            if (roll < cumulative) return entry.getKey();
        }
        return last == null ? MeteorType.BASIC : last;
    }

    /** @return the configured weight, or {@code 0} when the type is not present. */
    public double weight(MeteorType type) {
        return weights.getOrDefault(type, 0.0);
    }

    public boolean has(MeteorType type) {
        return weights.containsKey(type);
    }
}
