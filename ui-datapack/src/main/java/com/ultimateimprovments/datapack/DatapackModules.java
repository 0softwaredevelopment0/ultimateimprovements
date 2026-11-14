package com.ultimateimprovments.datapack;

import com.ultimateimprovments.module.ModuleManager;
import com.ultimateimprovments.util.ConsoleLogger;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * DatapackModules — modular loading of the bundled UI-Datapack.
 * <p>
 * The datapack is a SINGLE pack, but its parts can be enabled/disabled via
 * {@code datapack.modules.*} in config.yml (all parts are enabled by default).
 * The part list and the file paths behind it live in {@link DatapackParts}, so
 * this class and the bootstrapper can never disagree about what a part covers.
 * <p>
 * Disabled parts are NOT written into the built archive (the zip in
 * {@code plugins/UltimateImprovments/datapack/}), and the plugin code modules bound
 * to those parts are not registered either.
 * <p>
 * Custom enchantments and achievements are <b>not</b> datapack parts any more:
 * each has its own config ({@code UI-Enchantments.toml} /
 * {@code UI-Advancements.toml}) and its own per-entry switch. Their effect
 * modules are pruned through {@link ModuleManager.DatapackGate#isModuleDisabled}.
 */
public final class DatapackModules {

    private DatapackModules() {}

    // =========================
    // PARTS (re-exported for callers)
    // =========================

    public static final String CUSTOM_RECIPES = DatapackParts.CUSTOM_RECIPES;
    public static final String VANILLA_RECIPES = DatapackParts.VANILLA_RECIPES;
    public static final String LOOT_TABLES = DatapackParts.LOOT_TABLES;
    public static final String STRUCTURES = DatapackParts.STRUCTURES;
    public static final String WORLDGEN = DatapackParts.WORLDGEN;
    public static final String DIMENSION_LIMITS = DatapackParts.DIMENSION_LIMITS;
    public static final String VANILLA_TAGS = DatapackParts.VANILLA_TAGS;

    /**
     * Plugin code modules (ModuleManager names) bound to a datapack part.
     * If the part is disabled, these modules are skipped at registration.
     * <p>
     * Enchantments and achievements are deliberately absent: they are switched
     * individually, see {@link #ENCHANTMENT_MODULES} and {@link #ADVANCEMENT_MODULES}.
     */
    private static final Map<String, List<String>> CODE_MODULES;

    /**
     * Achievement code module -> achievement id ({@code ui:datapack/<id>}). Such a
     * module implements the trigger of exactly one achievement, so disabling that
     * achievement (or the whole {@code advancements} system) drops the module.
     * The reactor/space/crafting achievements have no module of their own — their
     * trigger lives inside a larger feature and only grants when the JSON exists.
     */
    private static final Map<String, String> ADVANCEMENT_MODULES;

    /**
     * Custom enchantment id ({@code ui:<id>}) -> the code module implementing it.
     * Used by {@code enchantments.<id>.enabled} to drop the effect engine together
     * with the registry entry; an id with no module here (the curses
     * {@code blunting}, {@code disappearance}, {@code vulnerability}) is pure data.
     */
    private static final Map<String, String> ENCHANTMENT_MODULES;

    static {
        Map<String, List<String>> modules = new LinkedHashMap<>();
        CODE_MODULES = java.util.Collections.unmodifiableMap(modules);

        Map<String, String> advancementModules = new LinkedHashMap<>();
        advancementModules.put("BedrockBreak", "hit_hit_to_pieces");
        advancementModules.put("Kaboom", "kaboom");
        advancementModules.put("EarthCore", "earth_core");
        advancementModules.put("WoodcutterChallenge", "woodcutter_at_full_throttle");
        advancementModules.put("EnderPearlChallenge", "let_me_teleport");
        advancementModules.put("NetheriteKing", "a_netherite_king");
        advancementModules.put("OutOfMemory", "out_of_memory");
        advancementModules.put("ServerFreeze", "server_not_responding");
        ADVANCEMENT_MODULES = java.util.Collections.unmodifiableMap(advancementModules);

        Map<String, String> enchantmentModules = new LinkedHashMap<>();
        enchantmentModules.put("aoe", "AOEEnchantment");
        enchantmentModules.put("attack_aoe", "AttackAoeEnchantment");
        enchantmentModules.put("autosmelt", "AutoSmeltEnchantment");
        enchantmentModules.put("container_stealing", "ContainerStealingEnchantment");
        enchantmentModules.put("degradation", "DegradationEnchantment");
        enchantmentModules.put("flight", "FlightEnchantment");
        enchantmentModules.put("igniting", "IgnitingEnchantment");
        enchantmentModules.put("item_stealing", "ItemStealingEnchantment");
        enchantmentModules.put("lava_walker", "LavaWalkerEnchantment");
        enchantmentModules.put("levitation", "LevitationEnchantment");
        enchantmentModules.put("magnet", "MagnetEnchantment");
        enchantmentModules.put("repairing", "RepairingEnchantment");
        enchantmentModules.put("self_destruct", "SelfDestructEnchantment");
        enchantmentModules.put("treecapitator", "TreeCapitatorEnchantment");
        enchantmentModules.put("veinminer", "VeinMinerEnchantment");
        ENCHANTMENT_MODULES = java.util.Collections.unmodifiableMap(enchantmentModules);
    }

    private static final Map<String, Boolean> CACHE = new HashMap<>();
    private static boolean masterEnabled = true;
    private static String language = "en";

    // =========================
    // INIT
    // =========================

    /**
     * Reads the toggles from the UI-Datapack config. Called once at startup
     * (UIDatapack), before any module registration or datapack install.
     */
    public static void init(JavaPlugin plugin) {
        // Single config lives in UI-Core (Main.getInstance().getConfig()).
        FileConfiguration cfg = com.ultimateimprovments.core.Main.getInstance().getConfig();
        masterEnabled = cfg.getBoolean(DatapackParts.MASTER_KEY, true);
        language = resolveLanguage(cfg.getString(DatapackParts.LANG_KEY, "auto"),
                cfg.getString("messages.lang", "en"));

        CACHE.clear();
        for (String part : DatapackParts.all()) {
            CACHE.put(part, cfg.getBoolean(DatapackParts.CONFIG_ROOT + "." + part, true));
        }

        ConsoleLogger.info("[Datapack] " + (isMasterEnabled()
                ? "Master: ON | Language: " + language + " | Modules: " + describe()
                  + " | Enchantments: " + enabledEnchantmentCount() + "/"
                  + com.ultimateimprovments.enchant.Enchantments.IDS.size()
                : "Master: OFF (datapack.enabled: false) — datapack disabled entirely."));
    }

    /** How many custom enchantments the new config leaves enabled (diagnostics only). */
    private static int enabledEnchantmentCount() {
        int count = 0;
        for (String id : com.ultimateimprovments.enchant.Enchantments.IDS) {
            if (com.ultimateimprovments.enchant.Enchantments.isEnabled(id)) count++;
        }
        return count;
    }

    /**
     * @param configured {@code datapack.lang} ("en", "ru" or "auto")
     * @param global     {@code messages.lang} used when {@code configured} is "auto"
     * @return the effective pack language ("en" or "ru")
     */
    private static String resolveLanguage(String configured, String global) {
        String wanted = normalizeLang(configured);
        if (wanted != null) return wanted;
        String fallback = normalizeLang(global);
        return fallback != null ? fallback : "en";
    }

    /** @return "en"/"ru" for a valid language value, otherwise {@code null} */
    private static String normalizeLang(String lang) {
        if (lang == null) return null;
        String value = lang.trim().toLowerCase(Locale.ROOT);
        return "en".equals(value) || "ru".equals(value) ? value : null;
    }

    /**
     * The language variant actually registered at startup ({"en"} or {"ru"}).
     * Mirrors {@code DatapackPackBootstrap}; changing it requires a server restart.
     */
    public static String getLanguage() {
        return language;
    }

    /**
     * Whether the whole datapack is enabled ({@code datapack.enabled}).
     * When off, no part is loaded and no bound code module is registered.
     */
    public static boolean isMasterEnabled() {
        return masterEnabled;
    }

    /** Human-readable state, e.g. "custom_recipes ✔, worldgen ✘, ...". */
    public static String describe() {
        StringBuilder sb = new StringBuilder();
        for (String part : DatapackParts.all()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(part).append(isEnabled(part) ? " ✔" : " ✘");
        }
        return sb.toString();
    }

    /**
     * Whether a datapack part is enabled. The master toggle gates everything:
     * with {@code datapack.enabled: false} no part is enabled. Uninitialized /
     * unknown parts default to enabled, so nothing is skipped by accident.
     */
    public static boolean isEnabled(String part) {
        if (!masterEnabled) return false;
        return CACHE.getOrDefault(part, Boolean.TRUE);
    }

    // =========================
    // PATH / MODULE LOOKUP
    // =========================

    /**
     * Whether a datapack file (path relative to the pack root, e.g.
     * {@code data/minecraft/tags/enchantment/in_enchanting_table.json}) should be copied to the world.
     */
    public static boolean isPathEnabled(String relPath) {
        if (relPath == null || !relPath.startsWith("data/")) return true;
        if (!masterEnabled) return false;
        return DatapackParts.isPathEnabled(CACHE, relPath);
    }

    /**
     * @return the datapack part a plugin module is bound to, or null if none
     */
    public static String getPartForModule(String moduleName) {
        if (moduleName == null) return null;
        for (Map.Entry<String, List<String>> e : CODE_MODULES.entrySet()) {
            if (e.getValue().contains(moduleName)) return e.getKey();
        }
        return null;
    }

    /**
     * Whether a module implements a custom enchantment disabled in
     * {@code UI-Enchantments.toml}, or the trigger of a disabled custom
     * achievement ({@code UI-Advancements.toml}). Such a module must not register
     * even when its part as a whole is on.
     */
    public static boolean isModuleDisabled(String moduleName) {
        if (moduleName == null) return false;
        for (Map.Entry<String, String> e : ENCHANTMENT_MODULES.entrySet()) {
            if (e.getValue().equals(moduleName)) {
                return !com.ultimateimprovments.enchant.Enchantments.isEnabled(e.getKey());
            }
        }
        String achievement = ADVANCEMENT_MODULES.get(moduleName);
        if (achievement == null) return false;
        return !com.ultimateimprovments.advancement.Advancements.isEnabled(achievement);
    }

    /**
     * A {@link ModuleManager.DatapackGate} that gates plugin code modules
     * (enchantments, achievement listeners, ...) by the datapack part they are
     * bound to. Installed by UI-Datapack so UI-Other skips the modules of
     * disabled datapack parts.
     */
    public static ModuleManager.DatapackGate gate() {
        return new ModuleManager.DatapackGate() {
            @Override
            public String partForModule(String moduleName) {
                return getPartForModule(moduleName);
            }

            @Override
            public boolean isPartEnabled(String part) {
                return isEnabled(part);
            }

            @Override
            public boolean isModuleDisabled(String moduleName) {
                return DatapackModules.isModuleDisabled(moduleName);
            }
        };
    }
}
