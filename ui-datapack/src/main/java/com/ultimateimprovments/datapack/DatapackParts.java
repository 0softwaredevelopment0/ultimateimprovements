package com.ultimateimprovments.datapack;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DatapackParts — the single source of truth for the bundled datapack's
 * toggleable parts and for the file paths that belong to each of them.
 * <p>
 * Two very different pieces need the same mapping:
 * {@link DatapackPackBootstrap} (runs before UI-Core, copies the enabled subset
 * out of the JAR) and {@link DatapackModules} (runs after UI-Core, gates the
 * plugin code modules bound to those parts). Keeping the table here means a new
 * part is added in exactly one place and both sides can never disagree.
 * <p>
 * A path that matches no prefix is always copied: unknown content belongs to no
 * toggle, so silently dropping it would be worse than shipping it.
 */
public final class DatapackParts {

    private DatapackParts() {}

    // =========================
    // CONFIG KEYS
    // =========================

    /** Master toggle: {@code datapack.enabled}. */
    public static final String MASTER_KEY = "datapack.enabled";

    /** Language of the bundled pack: {@code datapack.lang} ("en", "ru" or "auto"). */
    public static final String LANG_KEY = "datapack.lang";

    /** Placement of the pack relative to other datapacks: {@code datapack.position}. */
    public static final String POSITION_KEY = "datapack.position";

    /** Config root section: {@code datapack.modules}. */
    public static final String CONFIG_ROOT = "datapack.modules";

    /** Individual enchantments to leave unregistered: {@code datapack.disabled_enchantments}. */
    public static final String DISABLED_ENCHANTMENTS_KEY = "datapack.disabled_enchantments";

    // =========================
    // PARTS
    // =========================

    public static final String ENCHANTMENTS = "enchantments";
    public static final String CUSTOM_RECIPES = "custom_recipes";
    public static final String VANILLA_RECIPES = "vanilla_recipes";
    public static final String LOOT_TABLES = "loot_tables";
    public static final String STRUCTURES = "structures";
    public static final String WORLDGEN = "worldgen";
    public static final String DIMENSION_LIMITS = "dimension_limits";
    public static final String VANILLA_TAGS = "vanilla_tags";

    // Achievement JSON lives under ui/advancement/ but is NOT a datapack part: it is
    // toggled per achievement in config/UI-Advancements.toml (AdvancementFilter), so
    // the pack always copies that folder and the achievement filter decides what stays.

    /** Order used for logging and for the generated config template. */
    private static final List<String> ORDER = List.of(
            ENCHANTMENTS, CUSTOM_RECIPES, VANILLA_RECIPES,
            LOOT_TABLES, STRUCTURES, WORLDGEN, DIMENSION_LIMITS, VANILLA_TAGS);

    /**
     * Datapack path prefixes per part (relative to the pack's {@code data/} folder).
     * Everything under a prefix belongs to that part.
     * <p>
     * {@code ui/structure/} and {@code ui/loot_table/} used to match no prefix at all,
     * so they were copied even with {@code worldgen}/{@code loot_tables} disabled;
     * they are now part of {@link #STRUCTURES} (the airdrop loot table belongs to the
     * airdrop structure). Vanilla tag overrides ({@code minecraft/tags/item/},
     * {@code minecraft/tags/mineable/}) were equally ungated and now have
     * {@link #VANILLA_TAGS}.
     */
    private static final Map<String, List<String>> PREFIXES;

    static {
        Map<String, List<String>> prefixes = new LinkedHashMap<>();
        prefixes.put(ENCHANTMENTS, List.of("ui/tags/item/", "minecraft/tags/enchantment/"));
        prefixes.put(CUSTOM_RECIPES, List.of("ui/recipe/"));
        prefixes.put(VANILLA_RECIPES, List.of("minecraft/recipe/"));
        prefixes.put(LOOT_TABLES, List.of("minecraft/loot_table/"));
        prefixes.put(STRUCTURES, List.of("ui/structure/", "ui/loot_table/", "minecraft/structure/"));
        prefixes.put(WORLDGEN, List.of("minecraft/worldgen/"));
        prefixes.put(DIMENSION_LIMITS, List.of("minecraft/dimension_type/"));
        prefixes.put(VANILLA_TAGS, List.of("minecraft/tags/item/", "minecraft/tags/mineable/"));
        PREFIXES = java.util.Collections.unmodifiableMap(prefixes);
    }

    /** Every part name, in logging order. */
    public static List<String> all() {
        return ORDER;
    }

    /** @return the datapack part a file belongs to, or {@code null} when it belongs to none */
    public static String partForPath(String relPath) {
        if (relPath == null || !relPath.startsWith("data/")) return null;
        for (Map.Entry<String, List<String>> entry : PREFIXES.entrySet()) {
            for (String prefix : entry.getValue()) {
                if (relPath.startsWith("data/" + prefix)) return entry.getKey();
            }
        }
        return null;
    }

    /**
     * Whether a datapack file (path relative to the pack root, e.g.
     * {@code data/minecraft/tags/enchantment/in_enchanting_table.json}) should be
     * copied. Files that belong to no part are always kept.
     *
     * @param parts enabled state per part (missing entries default to enabled)
     */
    public static boolean isPathEnabled(Map<String, Boolean> parts, String relPath) {
        String part = partForPath(relPath);
        if (part == null) return true;
        return parts.getOrDefault(part, Boolean.TRUE);
    }
}
