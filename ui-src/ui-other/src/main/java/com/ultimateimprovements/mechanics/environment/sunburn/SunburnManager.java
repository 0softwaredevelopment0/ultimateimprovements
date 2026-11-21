package com.ultimateimprovements.mechanics.environment.sunburn;

import com.ultimateimprovements.config.MessagesManager;
import com.ultimateimprovements.core.Main;
import com.ultimateimprovements.util.ConsoleLogger;
import com.ultimateimprovements.util.MessageUtil;

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
import java.util.concurrent.ThreadLocalRandom;

/**
 * SunburnManager — players catch fire from sunlight in the Overworld.
 *
 * Every check_interval ticks (default 80 = 4s), all online players in
 * Overworld are checked. If daytime + open sky above:
 *   - No helmet => set on fire (fire_ticks duration)
 *   - Helmet => spend helmet durability instead (no fire while protected)
 *   - Mob/player head => heads have no durability, so instead of wearing down
 *     each degrade step rolls {@code sunburn.head_break_chance_percent}
 *     (default 0.1%): on a hit the head is removed from the armour slot with
 *     an item-break sound and the player is told in chat.
 *
 * The plugin does its own sky check: it scans the block column upward from
 * the player (no vanilla sky-light test). Air is skipped; passable blocks are
 * skipped too while {@code sunburn.skip_non_solid_blocks} is on (default),
 * and solid blocks are skipped while {@code sunburn.skip_solid_blocks} is on
 * (off by default); blocks from {@code sunburn.block_blacklist} are skipped
 * because sunlight passes through them. The first remaining block shelters
 * the player from the sun. With no such block up to the world height the
 * player is sun-exposed: fire without a helmet, helmet wear with one.
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
    /**
     * Chance (in percent, per degrade step) that a mob/player head worn in the
     * helmet slot is destroyed by the sun. Heads have no durability, so this
     * roll replaces the helmet wear. Configured as
     * {@code sunburn.head_break_chance_percent} (default 0.1%).
     */
    private double headBreakChancePercent;
    private boolean extinguishWhenSafe;
    private Set<String> includedWorlds;
    /**
     * Transparent blocks that do NOT shelter from the sun; the upward scan
     * skips them and keeps looking for a real roof. Configured as
     * {@code sunburn.block_blacklist}.
     */
    private Set<Material> blacklistedBlocks;
    /**
     * When true (default), the upward scan only counts solid blocks as cover
     * and skips torches, plants and other passable blocks. When false, any
     * non-air block above the head shelters the player. Configured as
     * {@code sunburn.skip_non_solid_blocks}.
     */
    private boolean skipNonSolidBlocks;
    /**
     * When true, the upward scan skips solid blocks, so only passable blocks
     * (and anything not covered by the other skip flag) can shelter. Off by
     * default. Configured as {@code sunburn.skip_solid_blocks}.
     */
    private boolean skipSolidBlocks;

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
        headBreakChancePercent = Math.max(0.0,
                Math.min(100.0, cfg.getDouble("sunburn.head_break_chance_percent", 0.1)));
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
        skipNonSolidBlocks = cfg.getBoolean("sunburn.skip_non_solid_blocks", true);
        skipSolidBlocks = cfg.getBoolean("sunburn.skip_solid_blocks", false);
        if (skipNonSolidBlocks && skipSolidBlocks) {
            ConsoleLogger.warn("[SunburnModule] skip_non_solid_blocks and skip_solid_blocks are both on — "
                    + "no block can shelter from the sun; every exposed player will burn.");
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
                + ", headBreakChance=" + headBreakChancePercent + "%"
                + ", extinguishWhenSafe=" + extinguishWhenSafe
                + ", blacklistedBlocks=" + blacklistedBlocks.size()
                + ", skipNonSolidBlocks=" + skipNonSolidBlocks
                + ", skipSolidBlocks=" + skipSolidBlocks
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
                    if (isHeadItem(helmet)) {
                        // Heads have no durability: instead of wearing down, each
                        // degrade step rolls the chance that the head is destroyed.
                        for (int i = 0; i < helmetDegradeSteps; i++) {
                            if (rollHeadBreak()) {
                                breakWornHead(player);
                                break;
                            }
                        }
                    } else {
                        for (int i = 0; i < helmetDegradeSteps; i++) {
                            player.damageItemStack(EquipmentSlot.HEAD, helmetDurabilityLoss);
                        }
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
    // HEAD BREAK (worn mob/player heads)
    // =========================

    /** Rolls the configured per-step chance that a worn head is destroyed. */
    private boolean rollHeadBreak() {
        if (headBreakChancePercent <= 0.0) return false;
        return ThreadLocalRandom.current().nextDouble(100.0) < headBreakChancePercent;
    }

    /**
     * Destroys the head currently worn in the helmet slot: removes it from the
     * armour slot (no drop), plays the burning/item-break sounds, sets the
     * player on fire (they are unprotected now) and sends the chat message in
     * the active language.
     */
    private void breakWornHead(Player player) {
        PlayerInventory inv = player.getInventory();
        ItemStack worn = inv.getHelmet();
        if (!isHeadItem(worn)) return;

        String headName = displayName(worn.getType());
        inv.setHelmet(null);

        Location loc = player.getLocation();
        player.playSound(loc, Sound.ENTITY_ITEM_BREAK, 1.0f, 0.8f);
        player.playSound(loc, Sound.BLOCK_FIRE_EXTINGUISH, 0.7f, 1.2f);

        // Without the head the player is unprotected and burns like a bare head.
        player.setFireTicks(fireTicks);

        String raw = MessagesManager.getString("sunburn.head_broken",
                "<red>☀</red> <white>От солнца ваша голова</white> <yellow>%head%</yellow> <white>сгорела!</white>");
        if (raw == null || raw.isBlank()) return; // blank message = silent mode
        player.sendMessage(MessageUtil.parse(raw.replace("%head%", headName), player));
    }

    /** Whether the stack is any mob/player head (skull), wearable as a helmet. */
    static boolean isHeadItem(ItemStack item) {
        return item != null && item.getType() != Material.AIR
                && isHeadName(item.getType().name());
    }

    /**
     * Name-based head check: every vanilla skull/head item ends with
     * {@code _HEAD} or {@code _SKULL}. The wall-mounted block variants contain
     * {@code _WALL_} and are not wearable items, so they are excluded.
     */
    static boolean isHeadName(String materialName) {
        if (materialName == null || materialName.isBlank()) return false;
        String name = materialName.toUpperCase(Locale.ROOT);
        if (name.contains("_WALL_")) return false;
        return name.endsWith("_HEAD") || name.endsWith("_SKULL");
    }

    /** {@code PLAYER_HEAD} → {@code Player Head}. */
    private static String displayName(Material type) {
        String[] parts = type.name().toLowerCase(Locale.ROOT).split("_");
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) continue;
            if (!out.isEmpty()) out.append(' ');
            out.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return out.toString();
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
     * The block column is scanned upward from the head. Air is always skipped.
     * {@code sunburn.skip_non_solid_blocks} (default on) skips passable blocks
     * such as torches and plants, so only a solid block counts as a roof;
     * {@code sunburn.skip_solid_blocks} (default off) does the opposite and
     * skips solid blocks. Blocks listed in {@code sunburn.block_blacklist} are
     * skipped as well (sunlight passes through them). The first other cover
     * block shelters the player; reaching the world top without one means
     * direct sunlight.
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
            if (!isCover(type)) continue;                    // air / passable block
            if (blacklistedBlocks.contains(type)) continue;  // sun shines through
            return true;
        }
        return false;
    }

    /** Whether the material counts as a roof under the current config. */
    private boolean isCover(Material type) {
        if (type.isAir()) return false;
        boolean solid = type.isSolid();
        if (skipSolidBlocks && solid) return false;
        return !skipNonSolidBlocks || solid;
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
