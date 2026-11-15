package com.ultimateimprovements.advancement;

import com.ultimateimprovements.core.Main;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.advancement.Advancement;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * Advancements — the single access point to the custom {@code ui:datapack/*}
 * achievements and to {@code configs/UI-Advancements.toml}.
 * <p>
 * Advancements are <b>not</b> a Minecraft registry: the server loads them from
 * datapack JSON, and Paper exposes no registry event for them (unlike
 * {@code RegistryEvents.ENCHANTMENT}). The bundled pack therefore ships
 * {@code data/ui/advancement/datapack/<id>.json}, and
 * {@code DatapackPackBootstrap} extracts only the enabled subset before the
 * worlds load. That makes every change here restart-only — but it also means a
 * disabled achievement is genuinely absent, not merely hidden.
 * <p>
 * The config layout is {@code [advancements] enabled} for the master switch and
 * {@code [advancements.<id>] enabled} plus optional tunables per achievement;
 * {@code <id>} is the JSON file name, so this class, the config and
 * {@code /advancement} all use the same identifier.
 */
public final class Advancements {

    private Advancements() {}

    /** Config root. */
    public static final String ROOT = "advancements";

    /** Namespace of every custom achievement. */
    public static final String NAMESPACE = "ui";

    /** Folder inside that namespace ({@code ui:datapack/<id>}). */
    public static final String FOLDER = "datapack";

    /**
     * Every bundled achievement id, in tree order (root first). Used for
     * validation and diagnostics; unknown ids in the config are ignored.
     */
    public static final List<String> IDS = List.of(
            "start",
            // crafting chain
            "craft_ender_chest", "blowed_by_echest", "craft_dosimeter", "craft_locator",
            "craft_healthmeter", "craft_nether_star", "craft_echo_shard",
            "craft_antimatter", "react_with_antimatter", "weaks_to_strongs",
            "craft_shoker", "craft_reinforced_deepslate",
            // world exploration
            "end_village_find", "high_temperatures", "the_space", "ah_where_oxyden",
            "earth_core", "hit_hit_to_pieces", "we_are_landing", "kaboom",
            "woodcutter_at_full_throttle", "impossible", "suicide",
            "let_me_teleport", "a_netherite_king",
            // server emergencies
            "server_overload", "halt_the_plugin", "out_of_memory", "server_not_responding",
            // DFC reactor
            "start_dfc", "dfc_self_destruct", "is_it_really_that_simple", "dfc_unstable",
            "critical_overload", "explode_dfc", "senior_reactor_ops", "inside_dfc",
            "burn_inside_dfc", "vaporised_in_plasma", "operator_incompetence",
            "saved_from_extinction", "power_of_fusion");

    /** @return true when the id names a bundled achievement */
    public static boolean isKnown(String id) {
        return id != null && IDS.contains(id.trim().toLowerCase(java.util.Locale.ROOT));
    }

    /** Whether the whole achievement system is enabled ({@code advancements.enabled}). */
    public static boolean isMasterEnabled() {
        return config().getBoolean(ROOT + ".enabled", true);
    }

    /**
     * Whether one achievement is enabled. The master toggle gates everything;
     * unknown ids and unreadable config default to enabled so nothing is lost
     * by accident.
     */
    public static boolean isEnabled(String id) {
        if (id == null) return true;
        String key = id.trim().toLowerCase(java.util.Locale.ROOT);
        if (!isMasterEnabled()) return false;
        return config().getBoolean(ROOT + "." + key + ".enabled", true);
    }

    /** Reads a per-achievement double ({@code advancements.<id>.<key>}). */
    public static double getDouble(String id, String key, double fallback) {
        return config().getDouble(ROOT + "." + id + "." + key, fallback);
    }

    /** Reads a per-achievement int ({@code advancements.<id>.<key>}). */
    public static int getInt(String id, String key, int fallback) {
        return config().getInt(ROOT + "." + id + "." + key, fallback);
    }

    /**
     * Awards an achievement, ignoring disabled ones and a pack that has not
     * loaded the node (null advancement). Idempotent — already-granted
     * achievements are left alone.
     *
     * @param id achievement id without the {@code datapack/} folder and namespace
     * @return true when the criteria was awarded by this call
     */
    public static boolean grant(Player player, String id) {
        if (player == null || id == null) return false;
        if (!isEnabled(id)) return false;
        try {
            Advancement advancement = Bukkit.getAdvancement(
                    new NamespacedKey(NAMESPACE, FOLDER + "/" + id));
            if (advancement == null) return false; // datapack not loaded / node extracted away
            var progress = player.getAdvancementProgress(advancement);
            if (progress.isDone()) return false;
            return progress.awardCriteria("1");
        } catch (Throwable t) {
            return false;
        }
    }

    private static org.bukkit.configuration.ConfigurationSection config() {
        Main main = Main.getInstance();
        return main == null
                ? new org.bukkit.configuration.MemoryConfiguration()
                : main.getConfig();
    }
}
