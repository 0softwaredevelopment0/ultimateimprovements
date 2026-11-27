package com.ultimateimprovements.module.meteor;

import com.ultimateimprovements.util.ConsoleLogger;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Weighted pool of meteor shell blocks ({@code [meteor.blocks]} in
 * {@code configs/UI-World.toml}).
 *
 * <p>Each cluster of the falling sphere and of the landed meteorite is picked
 * independently from this pool, so a weight of {@code 2.0} makes a block twice
 * as common as a weight of {@code 1.0}.</p>
 *
 * <p>The obsolete {@code meteor.display_blocks} list is still honoured when
 * {@code [meteor.blocks]} is missing or empty, with equal weights.</p>
 */
public final class WeightedBlockPool {

    /** Bundled defaults — all equally likely. */
    public static final List<String> DEFAULT_BLOCKS = List.of(
            "DEEPSLATE", "COBBLED_DEEPSLATE", "BASALT", "MAGMA_BLOCK");

    private final List<BlockData> blocks;
    private final double[] cumulative;
    private final double totalWeight;

    private WeightedBlockPool(List<BlockData> blocks, double[] cumulative, double totalWeight) {
        this.blocks = blocks;
        this.cumulative = cumulative;
        this.totalWeight = totalWeight;
    }

    /** Reads {@code [meteor.blocks]} (weighted), then legacy {@code display_blocks}. */
    public static WeightedBlockPool fromConfig(FileConfiguration cfg) {
        Map<String, Double> weights = new LinkedHashMap<>();
        if (cfg.isConfigurationSection("meteor.blocks")) {
            ConfigurationSection section = cfg.getConfigurationSection("meteor.blocks");
            if (section != null) {
                for (String key : section.getKeys(false)) {
                    weights.put(key, section.getDouble(key, 1.0));
                }
            }
        }
        if (!weights.isEmpty()) {
            return of(weights);
        }

        // Legacy list (equal chances) — kept for configs that predate [meteor.blocks].
        List<String> legacy = cfg.getStringList("meteor.display_blocks");
        if (!legacy.isEmpty()) {
            Map<String, Double> equal = new LinkedHashMap<>();
            for (String name : legacy) equal.put(name, 1.0);
            return of(equal);
        }

        return defaults();
    }

    /** The bundled defaults, all equally likely. */
    public static WeightedBlockPool defaults() {
        Map<String, Double> weights = new LinkedHashMap<>();
        for (String name : DEFAULT_BLOCKS) weights.put(name, 1.0);
        return of(weights);
    }

    /**
     * Builds a pool from material names and positive weights. Invalid names and
     * non-block materials are skipped with a warning.
     */
    public static WeightedBlockPool of(Map<String, Double> raw) {
        List<BlockData> blocks = new ArrayList<>();
        List<Double> weights = new ArrayList<>();
        if (raw != null) {
            for (Map.Entry<String, Double> entry : raw.entrySet()) {
                Material material = materialOf(entry.getKey());
                if (material == null) continue;
                Double weight = entry.getValue();
                if (weight == null || !Double.isFinite(weight) || weight <= 0) {
                    ConsoleLogger.warn("[Meteor] Invalid weight for meteor block '" + entry.getKey()
                            + "' — expected a positive number; ignoring it.");
                    continue;
                }
                blocks.add(material.createBlockData());
                weights.add(weight);
            }
        }
        if (blocks.isEmpty()) return defaults();
        return build(blocks, weights);
    }

    private static WeightedBlockPool build(List<BlockData> blocks, List<Double> weights) {
        double[] cumulative = new double[weights.size()];
        double total = 0;
        for (int i = 0; i < weights.size(); i++) {
            total += weights.get(i);
            cumulative[i] = total;
        }
        if (total <= 0) return defaults();
        return new WeightedBlockPool(blocks, cumulative, total);
    }

    private static Material materialOf(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            Material material = Material.valueOf(raw.trim().toUpperCase(Locale.ROOT));
            if (!material.isBlock()) {
                ConsoleLogger.warn("[Meteor] Meteor block is not a block material: " + raw);
                return null;
            }
            return material;
        } catch (IllegalArgumentException e) {
            ConsoleLogger.warn("[Meteor] Invalid meteor block: " + raw);
            return null;
        }
    }

    /** Picks a block by weighted random. */
    public BlockData pick(Random random) {
        double roll = random.nextDouble() * totalWeight;
        for (int i = 0; i < cumulative.length; i++) {
            if (roll < cumulative[i]) return blocks.get(i);
        }
        return blocks.get(blocks.size() - 1);
    }

    public int size() {
        return blocks.size();
    }
}
