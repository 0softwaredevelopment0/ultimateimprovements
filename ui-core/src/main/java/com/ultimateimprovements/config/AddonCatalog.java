package com.ultimateimprovements.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AddonCatalog — static routing table of the UltimateImprovements config system.
 * <p>
 * Maps every root config key to the addon that owns it (and therefore to the
 * {@code configs/UI-<Addon>.toml} file it is stored in), and lists every addon in
 * load order. Shared/global keys stay with {@link #CORE}.
 */
public final class AddonCatalog {

    /** The core plugin name — also the owner of all shared/global keys. */
    public static final String CORE = "UI-Core";

    private static final Map<String, String> ROOT_TO_ADDON = new LinkedHashMap<>();

    /** Prefix → addon overrides for sub-paths inside a root key (see {@link #addonOfPath(String)}). */
    private static final Map<String, String> PATH_TO_ADDON = new LinkedHashMap<>();

    private static void route(String addon, String... roots) {
        for (String r : roots) ROOT_TO_ADDON.put(r, addon);
    }

    static {
        // ── UI-MBS ──
        route("UI-MBS", "reactor", "nuclear_reactor");
        // ── UI-Energy ──
        route("UI-Energy", "energy", "energy_crafting");
        // ── UI-Chat ──
        route("UI-Chat", "chat", "chat_ping", "chat_filter", "ojm", "auto_broadcast");
        // ── UI-Clans ──
        route("UI-Clans", "clan");
        // ── UI-Combat ──
        route("UI-Combat", "turret");
        // ── UI-Punish ──
        route("UI-Punish", "punish", "blacklist", "opwhitelist", "op_lists", "access_control");
        // ── UI-Essentials ──
        route("UI-Essentials", "home", "spawn", "heal_feed", "report", "rtp", "near",
                "endersee", "troll");
        // ── UI-Anticheat ──
        route("UI-Anticheat", "anticheat");
        // ── UI-Datapack ──
        route("UI-Datapack", "datapack");
        // ── UI-Enchantments ──
        // The custom enchantments are registry entries (RegistryEvents.ENCHANTMENT),
        // so they get their own file with a master switch, one toggle per
        // enchantment and the values that used to be constants in the code.
        route("UI-Enchantments", "enchantments");
        // ── UI-Advancements ──
        // Custom advancements have their own file: they are datapack content
        // (JSON, no registry API), but they are toggled individually and are
        // therefore configured separately from the pack itself.
        route("UI-Advancements", "advancements");
        // ── UI-Shared ──
        route("UI-Shared", "space", "radiation", "hazmat");
        // ── UI-Enchant ──
        route("UI-Enchant", "enchant");
        // ── UI-Auth ──
        route("UI-Auth", "auth");
        // ── UI-Protection ──
        route("UI-Protection", "protection", "void_protection");
        // ── UI-Display ──
        route("UI-Display", "tab", "scoreboard", "bossbar");
        // ── UI-Admin ──
        route("UI-Admin", "economy", "op_self");
        // ── UI-Player ──
        route("UI-Player", "vanish", "armor_effects", "armor_trim_effects", "elytra_wear");
        // ── UI-Guard ──
        route("UI-Guard", "packet_guard", "proxy_server", "redstone_guard",
                "emergency_entity_kill", "server_overload_warning", "bot_protection",
                "check", "codepanel", "sudo", "gsit_spin_guard", "maintenance",
                "console_lockdown", "server_lockdown", "command_policy", "stresstest");
        // ── UI-World ──
        route("UI-World", "wireless_redstone", "meteor", "block_friction", "sculk", "nether_portal");
        // ── UI-Items ──
        route("UI-Items", "concrete_bucket", "netherite_upgrade", "particle_accelerator",
                "remote_detonator", "fireball", "portable_workbench");
        // ── UI-Other (features umbrella + mechanics) ──
        route("UI-Other", "features", "death_logger", "changedimmension", "kits");

        // ── Sub-path routing ──
        // Some `features.*` blocks and a few standalone roots are owned by the
        // addon whose context they belong to (the `features` umbrella root itself
        // stays in UI-Other for everything not listed below). Longest prefix wins.
        routePath("UI-Items",
                "features.antimatter", "features.entitylocator", "features.exp_bottle_upgrade");
        routePath("UI-Player",
                "features.attributes", "features.beacon", "features.blockdmg",
                "features.boostedcobweb", "features.deathbell", "features.glassbreak",
                "features.shieldslowness", "features.terracotaspeed",
                "sunburn", "suicide");
        routePath("UI-World",
                "features.block_collapse", "features.dragonegg", "features.enderchest",
                "features.magnet", "features.unbreakable_breaker",
                "structure_integrity");
        routePath("UI-Essentials",
                "features.modeprotect", "brand_spoof", "power", "scheduled_restart");
        routePath("UI-Display", "motd");

        // Message groups that follow their settings block.
        routePath("UI-Essentials",
                "messages.power", "messages_en.power",
                "messages.features.modeprotect", "messages_en.features.modeprotect");
        routePath("UI-Player",
                "messages.suicide", "messages_en.suicide");
        routePath("UI-Display",
                "messages.motd", "messages_en.motd");
        routePath("UI-World",
                "messages.structure_integrity", "messages_en.structure_integrity");
    }

    /** Prefix → addon overrides (see {@link #addonOfPath(String)}). */
    private static void routePath(String addon, String... prefixes) {
        for (String p : prefixes) PATH_TO_ADDON.put(p, addon);
    }

    // Message groups (first segment under messages/messages_en) routed to addons.
    private static final Map<String, String> MSG_GROUP_TO_ADDON = new LinkedHashMap<>();

    private static void routeMsg(String addon, String... groups) {
        for (String g : groups) MSG_GROUP_TO_ADDON.put(g, addon);
    }

    static {
        routeMsg("UI-Chat", "chat", "chat_ping", "chat_filter", "ojm", "broadcast");
        routeMsg("UI-Clans", "clan");
        routeMsg("UI-Combat", "turret", "combat");
        routeMsg("UI-Essentials", "home", "report", "spawn", "rtp", "near", "endersee",
                "troll", "misc", "invsee");
        routeMsg("UI-Anticheat", "anticheat", "ac");
        routeMsg("UI-Punish", "punish", "blacklist", "opwhitelist", "op_lists", "access_control");
        routeMsg("UI-Enchant", "enchant");
        routeMsg("UI-Auth", "auth");
        routeMsg("UI-Protection", "protection");
        routeMsg("UI-Display", "tab", "scoreboard", "bossbar");
        routeMsg("UI-Admin", "economy", "op_self", "op");
        routeMsg("UI-Player", "vanish", "elytra_wear");
        routeMsg("UI-Items", "crafting", "remote_detonator", "portable_workbench");
        routeMsg("UI-Guard", "packet_guard", "bot_protection",
                "check", "codepanel", "sudo", "maintenance", "console_lockdown",
                "server_lockdown", "stresstest");
        routeMsg("UI-World", "meteor", "nether_portal", "structure_integrity");
        routeMsg("UI-Other", "changedimmension",
                "notes", "death_logger",
                "structures", "space", "wireless_redstone",
                "kits", "expsplit");
        routeMsg("UI-Display", "motd");
        routeMsg("UI-Player", "suicide");
        routeMsg("UI-Essentials", "power");
    }

    private AddonCatalog() {}

    /** @return the addon owning the given root key (never null — defaults to CORE). */
    public static String addonOfRootKey(String rootKey) {
        return ROOT_TO_ADDON.getOrDefault(rootKey, CORE);
    }

    /**
     * Resolves the addon owning a FULL config path, honouring the sub-path
     * overrides (for example {@code features.antimatter} → UI-Items, while the
     * {@code features} umbrella itself stays in UI-Other).
     * <p>
     * The longest matching prefix wins, so {@code features.block_collapse.scan.area}
     * resolves through {@code features.block_collapse}.
     *
     * @param path dotted config path (settings or {@code messages.*})
     * @return the owning addon (never null — defaults to CORE)
     */
    public static String addonOfPath(String path) {
        if (path == null || path.isEmpty()) return CORE;
        String best = null;
        for (String prefix : PATH_TO_ADDON.keySet()) {
            if (path.equals(prefix) || path.startsWith(prefix + ".")) {
                if (best == null || prefix.length() > best.length()) best = prefix;
            }
        }
        if (best != null) return PATH_TO_ADDON.get(best);
        return addonOfRootKey(rootOf(path));
    }

    /** First path segment of a dotted key. */
    private static String rootOf(String path) {
        int dot = path.indexOf('.');
        return dot > 0 ? path.substring(0, dot) : path;
    }

    /**
     * Resolves an addon by its name, case-insensitively.
     *
     * @return the canonical addon name, or {@code null} when unknown
     */
    public static String lookup(String addonName) {
        if (addonName == null) return null;
        for (String addon : catalog()) {
            if (addon.equalsIgnoreCase(addonName.trim())) return addon;
        }
        return null;
    }

    /**
     * @return the addon owning the given message group (first segment under
     *         {@code messages.<group>} / {@code messages_en.<group>});
     *         global groups (general, reputation, help, ...) stay with CORE.
     */
    public static String addonOfMsgGroup(String group) {
        return MSG_GROUP_TO_ADDON.getOrDefault(group, CORE);
    }

    /** @return true when the key is a shared/global core key. */
    public static boolean isCoreRoot(String rootKey) {
        return addonOfPath(rootKey).equals(CORE);
    }

    /** All addons with per-addon config files, in family load order (CORE excluded). */
    public static List<String> catalog() {
        return List.of(
                CORE,
                "UI-Shared", "UI-MBS", "UI-Datapack", "UI-Advancements", "UI-Enchantments",
                "UI-Chat", "UI-Clans",
                "UI-Combat", "UI-Punish", "UI-Essentials", "UI-Energy",
                "UI-Anticheat", "UI-Enchant", "UI-Auth", "UI-Protection", "UI-Display", "UI-Admin", "UI-Player", "UI-Guard", "UI-World", "UI-Items", "UI-Other");
    }
}
