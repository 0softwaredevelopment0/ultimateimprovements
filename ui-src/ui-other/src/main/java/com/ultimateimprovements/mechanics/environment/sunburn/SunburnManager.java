package com.ultimateimprovements.mechanics.environment.sunburn;

import com.ultimateimprovements.core.Main;
import com.ultimateimprovements.util.ConsoleLogger;

import org.bukkit.*;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * SunburnManager — players catch fire from sunlight in the Overworld.
 *
 * Every check_interval ticks (default 80 = 4s), all online players in
 * Overworld are checked. If daytime + open sky above:
 *   - No helmet => set on fire (fire_ticks duration)
 *   - Helmet => spend helmet durability instead (no fire while protected)
 *
 * The plugin does its own sky check: it scans the block column upward from
 * the player (no vanilla sky-light test). Air, liquids and passable blocks are
 * not cover; blocks from {@code sunburn.block_blacklist} are skipped because
 * sunlight passes through them. The first remaining solid block shelters the
 * player from the sun. With no such block up to the world height the player
 * is sun-exposed: fire without a helmet, helmet wear with one.
 *
 * Minecraft naturally extinguishes fire in water, rain, etc.
 * When player leaves sun (night, underground) => extinguished on next check.
 */
public class SunburnManager implements Listener {

    private static SunburnManager instance;

    // =========================
    // CONFIG
    // =========================
    private boolean enabled;
    private int checkInterval;
    private int fireTicks;
    private int helmetDegradeInterval;
    private int helmetDurabilityLoss;
    private boolean extinguishWhenSafe;
    private Set<String> includedWorlds;
    /**
     * Transparent blocks that do NOT shelter from the sun; the upward scan
     * skips them and keeps looking for a real roof. Configured as
     * {@code sunburn.block_blacklist}.
     */
    private Set<Material> blacklistedBlocks;

    /** Vanilla glass colours used to build the default blacklist. */
    private static final String[] GLASS_COLORS = {
            "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink",
            "gray", "light_gray", "cyan", "purple", "blue", "brown", "green",
            "red", "black"
    };

    /**
     * Default {@code sunburn.block_blacklist}: every vanilla glass block and
     * pane (plain, tinted, 16 stained glass variants and 16 stained panes) plus
     * the other transparent solids that still let sunlight through — ice,
     * slime, honey, copper grates, iron bars and the beacon.
     */
    static List<String> defaultBlockBlacklist() {
        List<String> names = new ArrayList<>();
        names.add("glass");
        names.add("tinted_glass");
        names.add("glass_pane");
        for (String color : GLASS_COLORS) {
            names.add(color + "_stained_glass");
            names.add(color + "_stained_glass_pane");
        }
        names.addAll(List.of(
                // Ice family — translucent, sunlight passes.
                "ice", "packed_ice", "blue_ice", "frosted_ice",
                // Slime and honey — see-through blocks light passes through.
                "slime_block", "honey_block",
                // Copper grates (1.21) — all oxidation and waxed variants.
                "copper_grate", "exposed_copper_grate", "weathered_copper_grate",
                "oxidized_copper_grate", "waxed_copper_grate", "waxed_exposed_copper_grate",
                "waxed_weathered_copper_grate", "waxed_oxidized_copper_grate",
                // Bars and beacon have a glass-like, light-passing body.
                "iron_bars", "beacon"));
        return names;
    }

    // =========================
    // TICK COUNTERS
    // =========================
    private int mainTickCounter = 0;
    private int helmetTickCounter = 0;

    // =========================
    // TRACKING
    // =========================
    private final Set<UUID> exposedPlayers = new HashSet<>();

    public static SunburnManager getInstance() {
        return instance;
    }

    public static void init() {
        instance = new SunburnManager();
        instance.loadConfig();

        Main plugin = Main.getInstance();
        plugin.getServer().getPluginManager().registerEvents(instance, plugin);

        ConsoleLogger.info("[SunburnModule] Initialized — check every " + instance.checkInterval
                + " ticks, fire " + instance.fireTicks + " ticks, helmet degrade every "
                + instance.helmetDegradeInterval + " ticks.");
    }

    // =========================
    // CONFIG
    // =========================

    private void loadConfig() {
        FileConfiguration cfg = Main.getInstance().getConfig();

        enabled = cfg.getBoolean("sunburn.enabled", true);
        checkInterval = cfg.getInt("sunburn.check_interval", 80);
        fireTicks = cfg.getInt("sunburn.fire_ticks", 80);
        helmetDegradeInterval = cfg.getInt("sunburn.helmet_degrade_interval", 80);
        // Renamed from helmet_integrity_loss when the custom integrity system
        // was replaced by vanilla durability; the old key is still honoured so
        // existing server configs keep their tuned value.
        helmetDurabilityLoss = cfg.getInt("sunburn.helmet_durability_loss",
                cfg.getInt("sunburn.helmet_integrity_loss", 1));
        extinguishWhenSafe = cfg.getBoolean("sunburn.extinguish_when_safe", true);

        blacklistedBlocks = EnumSet.noneOf(Material.class);
        List<String> blacklistNames = cfg.isList("sunburn.block_blacklist")
                ? cfg.getStringList("sunburn.block_blacklist")
                : defaultBlockBlacklist();
        for (String name : blacklistNames) {
            if (name == null || name.isBlank()) continue;
            Material material = Material.matchMaterial(name.trim().toLowerCase(Locale.ROOT));
            if (material == null || !material.isBlock()) {
                ConsoleLogger.warn("[SunburnModule] Unknown block '" + name.trim()
                        + "' in sunburn.block_blacklist — ignored.");
                continue;
            }
            blacklistedBlocks.add(material);
        }

        includedWorlds = new HashSet<>();
        if (cfg.isList("sunburn.included_worlds")) {
            includedWorlds.addAll(cfg.getStringList("sunburn.included_worlds"));
        // Default: only overworld if list is empty
        if (includedWorlds.isEmpty()) {
            includedWorlds.add("world");
        }
        }

        ConsoleLogger.info("[SunburnModule] Config: enabled=" + enabled
                + ", checkInterval=" + checkInterval
                + ", fireTicks=" + fireTicks
                + ", helmetDegrade=" + helmetDegradeInterval
                + ", helmetLoss=" + helmetDurabilityLoss
                + ", extinguishWhenSafe=" + extinguishWhenSafe
                + ", blacklistedBlocks=" + blacklistedBlocks.size()
                + ", includedWorlds=" + includedWorlds);
    }

    public void reloadConfig() {
        loadConfig();
    }

    // =========================
    // PUBLIC API
    // =========================

    public static boolean isEnabled() {
        return instance != null && instance.enabled;
    }

    // =========================
    // TICK (called from SunburnTask every 1 tick)
    // =========================

    public void tick() {
        if (!enabled) return;

        mainTickCounter++;
        if (mainTickCounter < checkInterval) return;
        mainTickCounter = 0;

        // helmet_degrade_interval is configured in TICKS, but the loop below
        // only runs once per check_interval ticks. Accumulate the elapsed
        // ticks instead of counting checks: otherwise the effective interval
        // was check_interval × helmet_degrade_interval (80 × 80 ticks = 5.3
        // minutes per point with the defaults), which looked like the helmet
        // never wearing at all.
        helmetTickCounter += checkInterval;
        int helmetDegradeSteps = 0;
        if (helmetDegradeInterval > 0) {
            helmetDegradeSteps = helmetTickCounter / helmetDegradeInterval;
            helmetTickCounter %= helmetDegradeInterval;
        }

        Set<UUID> nowExposed = new HashSet<>();

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getWorld().getEnvironment() != World.Environment.NORMAL) continue;
            if (!includedWorlds.contains(player.getWorld().getName())) continue;
            if (player.getGameMode() != GameMode.SURVIVAL && player.getGameMode() != GameMode.ADVENTURE) continue;
            if (player.isDead() || player.getHealth() <= 0) continue;

            boolean exposed = isExposedToSun(player) && !isShelteredFromSun(player);

            if (exposed) {
                nowExposed.add(player.getUniqueId());

                PlayerInventory inv = player.getInventory();
                ItemStack helmet = inv.getHelmet();
                boolean hasHelmet = helmet != null && helmet.getType() != Material.AIR;

                if (hasHelmet) {
                    for (int i = 0; i < helmetDegradeSteps; i++) {
                        player.damageItemStack(EquipmentSlot.HEAD, helmetDurabilityLoss);
                    }
                } else {
                    // No helmet — set on fire
                    // Minecraft handles natural extinguishing (water, rain, etc.)
                    player.setFireTicks(fireTicks);
                }
            } else {
                // Not exposed — extinguish if previously exposed
                if (extinguishWhenSafe && exposedPlayers.contains(player.getUniqueId())) {
                    player.setFireTicks(0);
                }
            }
        }

        exposedPlayers.clear();
        exposedPlayers.addAll(nowExposed);
    }

    // =========================
    // EXPOSURE CHECK
    // =========================

    private boolean isExposedToSun(Player player) {
        // Daytime only (time 0-12000). No vanilla sky-light check: the plugin
        // walks the block column itself, so the blacklist is authoritative.
        long time = player.getWorld().getTime();
        return time >= 0 && time < 12000;
    }

    /**
     * Whether a solid roof above the player's head shelters from the sun.
     * <p>
     * The block column is scanned upward from the head. Air, liquids and other
     * passable blocks are skipped, and so are the blocks listed in
     * {@code sunburn.block_blacklist} (sunlight passes through them). The
     * first other solid block shelters the player; reaching the world top
     * without one means direct sunlight.
     */
    private boolean isShelteredFromSun(Player player) {
        World world = player.getWorld();
        Location location = player.getLocation();
        int x = location.getBlockX();
        int z = location.getBlockZ();
        // The head block is feet + 1 for a standing player; start one above it.
        int startY = Math.max(world.getMinHeight(), location.getBlockY() + 2);
        for (int y = startY; y < world.getMaxHeight(); y++) {
            Material type = world.getBlockAt(x, y, z).getType();
            if (!isCover(type)) continue;                    // air / liquid / passable
            if (blacklistedBlocks.contains(type)) continue;  // sun shines through
            return true;
        }
        return false;
    }

    /** Whether the material is a roof: a non-air solid block (liquids are not solid). */
    private static boolean isCover(Material type) {
        return !type.isAir() && type.isSolid();
    }

    // =========================
    // EVENTS
    // =========================

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {}

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        exposedPlayers.remove(event.getPlayer().getUniqueId());
    }
}
