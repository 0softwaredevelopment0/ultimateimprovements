package com.ultimateimprovements.enchant;

import com.ultimateimprovements.core.Main;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.EquipmentSlotGroup;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Enchantments — the single access point to the custom {@code ui:*} enchantments
 * and to {@code configs/UI-Enchantments.toml}.
 * <p>
 * Unlike advancements, these <b>are</b> registry entries: UI-Datapack registers
 * them through Paper's {@code RegistryEvents.ENCHANTMENT.compose()} in its
 * bootstrap phase (the registry is the same one the client receives). That runs
 * once per server start, so every value in the config is restart-only and the
 * registry entry is the only place a server-wide tooltip/limit can live.
 * <p>
 * Config layout: {@code enchantments.enabled} is the master switch,
 * {@code [enchantments.<id>]} holds the per-enchantment values and
 * {@code [enchantments.defaults]} the values shared by all of them (weight and
 * the enchanting-table cost curve, which used to be constants in
 * {@code EnchantmentDefinition}).
 * <p>
 * Legacy migration: installs that still use {@code datapack.modules.enchantments}
 * or {@code datapack.disabled_enchantments} keep their intent until the new keys
 * exist on disk (config repair adds them on the first start).
 */
public final class Enchantments {

    private Enchantments() {}

    /** Config root. */
    public static final String ROOT = "enchantments";

    /** Shared values block. */
    public static final String DEFAULTS = ROOT + ".defaults";

    /** Every custom enchantment id ({@code ui:<id>}), in config order. */
    public static final List<String> IDS = List.of(
            "aoe", "attack_aoe", "autosmelt", "blunting", "container_stealing",
            "degradation", "disappearance", "flight", "igniting", "item_stealing",
            "lava_walker", "levitation", "magnet", "repairing", "self_destruct",
            "treecapitator", "veinminer", "vulnerability");

    /** @return the id without a {@code ui:} prefix, lower-cased, or null for blank input */
    public static String normalize(String id) {
        if (id == null) return null;
        String value = id.trim().toLowerCase(Locale.ROOT);
        if (value.startsWith("ui:")) value = value.substring(3);
        return value.isEmpty() ? null : value;
    }

    /** @return true when the id names a bundled custom enchantment */
    public static boolean isKnown(String id) {
        String value = normalize(id);
        return value != null && IDS.contains(value);
    }

    /**
     * Whether custom enchantments are enabled at all. Falls back to the legacy
     * {@code datapack.modules.enchantments} while the new key is not on disk.
     */
    public static boolean isMasterEnabled() {
        ConfigurationSection config = config();
        if (config.isSet(ROOT + ".enabled")) {
            return config.getBoolean(ROOT + ".enabled", true);
        }
        return config.getBoolean("datapack.modules.enchantments", true);
    }

    /**
     * Whether one custom enchantment may be registered. Unknown ids are not
     * blocked (nothing to register), and an unset per-id flag falls back to the
     * legacy {@code datapack.disabled_enchantments} list.
     */
    public static boolean isEnabled(String id) {
        String value = normalize(id);
        if (value == null) return true;
        if (!isMasterEnabled()) return false;
        ConfigurationSection config = config();
        String key = ROOT + "." + value + ".enabled";
        if (config.isSet(key)) return config.getBoolean(key, true);
        return !legacyDisabled().contains(value);
    }

    /** Reads a per-enchantment int ({@code enchantments.<id>.<key>}). */
    public static int getInt(String id, String key, int fallback) {
        return config().getInt(ROOT + "." + normalize(id) + "." + key, fallback);
    }

    /** Reads a per-enchantment string ({@code enchantments.<id>.<key>}). */
    public static String getString(String id, String key, String fallback) {
        String value = config().getString(ROOT + "." + normalize(id) + "." + key);
        return value == null || value.isBlank() ? fallback : value;
    }

    /** Reads a shared value ({@code enchantments.defaults.<key>}). */
    public static int getDefaultInt(String key, int fallback) {
        return config().getInt(DEFAULTS + "." + key, fallback);
    }

    /**
     * Parses {@code enchantments.<id>.active_slots} into an {@link EquipmentSlotGroup}.
     * Unknown names fall back to the code default (the caller passes it) instead of
     * breaking the registry entry.
     */
    public static EquipmentSlotGroup getActiveSlots(String id, EquipmentSlotGroup fallback) {
        String configured = config().getString(ROOT + "." + normalize(id) + ".active_slots");
        if (configured == null || configured.isBlank()) return fallback;
        EquipmentSlotGroup group = EquipmentSlotGroup.getByName(configured.trim());
        return group != null ? group : fallback;
    }

    private static Set<String> legacyDisabled() {
        List<String> list = config().getStringList("datapack.disabled_enchantments");
        if (list == null || list.isEmpty()) return Collections.emptySet();
        Set<String> ids = new LinkedHashSet<>();
        for (String raw : list) {
            String id = normalize(raw);
            if (id != null) ids.add(id);
        }
        return ids;
    }

    private static ConfigurationSection config() {
        Main main = Main.getInstance();
        return main == null
                ? new org.bukkit.configuration.MemoryConfiguration()
                : main.getConfig();
    }
}
