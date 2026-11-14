package com.ultimateimprovments.datapack;

import com.ultimateimprovments.module.ModuleManager;
import com.ultimateimprovments.util.ConsoleLogger;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * DatapackModules — modular loading of the bundled UI-Datapack.
 * <p>
 * The datapack is a SINGLE pack, but its parts can be enabled/disabled via
 * {@code datapack.modules.*} in config.yml (all parts are enabled by default).
 * The part list and the file paths behind it live in {@link DatapackParts}, so
 * this class and the bootstrapper can never disagree about what a part covers.
 * <p>
 * Disabled parts are NOT extracted into the pack folder, and the plugin code
 * modules bound to those parts are not registered either. Individual custom
 * enchantments can additionally be switched off with
 * {@code datapack.disabled_enchantments = ["flight", ...]}, which skips both the
 * registry entry and the code module behind it.
 */
public final class DatapackModules {

    private DatapackModules() {}

    // =========================
    // PARTS (re-exported for callers)
    // =========================

    public static final String ENCHANTMENTS = DatapackParts.ENCHANTMENTS;
    public static final String ADVANCEMENTS = DatapackParts.ADVANCEMENTS;
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
     */
    private static final Map<String, List<String>> CODE_MODULES;

    /**
     * Custom enchantment id ({@code ui:<id>}) -> the code module implementing it.
     * Used by {@code datapack.disabled_enchantments} to drop the effect engine
     * together with the registry entry; an id with no module here (the curses
     * {@code blunting}, {@code disappearance}, {@code vulnerability}) is pure data.
     */
    private static final Map<String, String> ENCHANTMENT_MODULES;

    static {
        Map<String, List<String>> modules = new LinkedHashMap<>();
        modules.put(ENCHANTMENTS, List.of(
                "AOEEnchantment", "AutoSmeltEnchantment", "VeinMinerEnchantment",
                "TreeCapitatorEnchantment", "FlightEnchantment", "MagnetEnchantment",
                "IgnitingEnchantment", "LevitationEnchantment", "SelfDestructEnchantment",
                "DegradationEnchantment", "AttackAoeEnchantment", "ItemStealingEnchantment",
                "RepairingEnchantment", "ContainerStealingEnchantment", "LavaWalkerEnchantment"));
        modules.put(ADVANCEMENTS, List.of(
                "BeyondSpace", "BedrockBreak", "Kaboom", "EarthCore", "ServerOverload",
                "WoodcutterChallenge", "EnderPearlChallenge", "NetheriteKing",
                "OutOfMemory", "ServerFreeze"));
        CODE_MODULES = java.util.Collections.unmodifiableMap(modules);

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
    private static final Set<String> DISABLED_ENCHANTMENTS = new LinkedHashSet<>();
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

        DISABLED_ENCHANTMENTS.clear();
        List<String> disabled = cfg.getStringList(DatapackParts.DISABLED_ENCHANTMENTS_KEY);
        for (String id : disabled) {
            String normalized = normalizeEnchantmentId(id);
            if (normalized == null) continue;
            if (!ENCHANTMENT_MODULES.containsKey(normalized) && !isDataOnlyCurse(normalized)) {
                ConsoleLogger.warn("[Datapack] datapack.disabled_enchantments: unknown enchantment '"
                        + id + "' — ignored. Known ids: " + String.join(", ", knownEnchantmentIds()) + ".");
                continue;
            }
            DISABLED_ENCHANTMENTS.add(normalized);
        }

        ConsoleLogger.info("[Datapack] " + (isMasterEnabled()
                ? "Master: ON | Language: " + language + " | Modules: " + describe()
                  + (DISABLED_ENCHANTMENTS.isEmpty() ? ""
                     : " | Disabled enchantments: " + String.join(", ", DISABLED_ENCHANTMENTS))
                : "Master: OFF (datapack.enabled: false) — datapack disabled entirely."));
    }

    /** @return the id without a {@code ui:} prefix, lower-cased, or null for blank input */
    private static String normalizeEnchantmentId(String id) {
        if (id == null) return null;
        String value = id.trim().toLowerCase(Locale.ROOT);
        if (value.startsWith("ui:")) value = value.substring(3);
        return value.isEmpty() ? null : value;
    }

    /** The three curses that are pure datapack data and have no code module. */
    private static boolean isDataOnlyCurse(String id) {
        return "blunting".equals(id) || "disappearance".equals(id) || "vulnerability".equals(id);
    }

    private static Set<String> knownEnchantmentIds() {
        Set<String> ids = new LinkedHashSet<>(ENCHANTMENT_MODULES.keySet());
        ids.add("blunting");
        ids.add("disappearance");
        ids.add("vulnerability");
        return ids;
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

    /** Custom enchantments the admin switched off ({@code datapack.disabled_enchantments}). */
    public static Set<String> getDisabledEnchantments() {
        return java.util.Collections.unmodifiableSet(DISABLED_ENCHANTMENTS);
    }

    /** Whether one custom enchantment ({@code ui:<id>}) may be registered and implemented. */
    public static boolean isEnchantmentEnabled(String id) {
        String normalized = normalizeEnchantmentId(id);
        if (normalized == null) return true;
        if (!masterEnabled) return false;
        return !DISABLED_ENCHANTMENTS.contains(normalized);
    }

    /** Human-readable state, e.g. "enchantments ✔, advancements ✘, ...". */
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
     * Whether a module implements a custom enchantment the admin disabled via
     * {@code datapack.disabled_enchantments}. Such a module must not register even
     * when the {@code enchantments} part as a whole is on.
     */
    public static boolean isModuleDisabled(String moduleName) {
        if (moduleName == null || DISABLED_ENCHANTMENTS.isEmpty()) return false;
        for (Map.Entry<String, String> e : ENCHANTMENT_MODULES.entrySet()) {
            if (!e.getValue().equals(moduleName)) continue;
            return DISABLED_ENCHANTMENTS.contains(e.getKey());
        }
        return false;
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
