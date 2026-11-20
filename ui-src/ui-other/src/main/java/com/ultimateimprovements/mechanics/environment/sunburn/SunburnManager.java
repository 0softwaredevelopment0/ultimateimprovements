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
 * A block from {@code sunburn.block_blacklist} as the first non-air block
 * above the player's head counts as shade: the player is not sun-exposed at
 * all, so neither fire nor helmet wear happens. The default list covers every
 * glass block and pane.
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
     * Blocks that shield the player from the sun. When the first non-air block
     * above the player's head is one of these, the player is considered shaded
     * and never sunburned. Configured as {@code sunburn.block_blacklist}.
     */
    private Set<Material> sunShieldingBlocks;

    /** Vanilla glass colours used to build the default shielding list. */
    private static final String[] GLASS_COLORS = {
            "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink",
            "gray", "light_gray", "cyan", "purple", "blue", "brown", "green",
            "red", "black"
    };

    /**
     * Default {@code sunburn.block_blacklist}: every vanilla glass block and
     * pane — plain and tinted glass, the 16 stained glass variants and the 16
     * stained glass panes.
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

        sunShieldingBlocks = EnumSet.noneOf(Material.class);
        List<String> shieldingNames = cfg.isList("sunburn.block_blacklist")
                ? cfg.getStringList("sunburn.block_blacklist")
                : defaultBlockBlacklist();
        for (String name : shieldingNames) {
            if (name == null || name.isBlank()) continue;
            Material material = Material.matchMaterial(name.trim().toLowerCase(Locale.ROOT));
            if (material == null || !material.isBlock()) {
                ConsoleLogger.warn("[SunburnModule] Unknown block '" + name.trim()
                        + "' in sunburn.block_blacklist — ignored.");
                continue;
            }
            sunShieldingBlocks.add(material);
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
                + ", shieldingBlocks=" + sunShieldingBlocks.size()
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

            boolean exposed = isExposedToSun(player) && !isShadedByBlock(player);

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
        World world = player.getWorld();

        // Daytime only (time 0-12000)
        long time = world.getTime();
        if (time < 0 || time >= 12000) return false;

        // Open sky above (light from sky >= 15)
        if (player.getLocation().getBlock().getLightFromSky() < 15) return false;

        return true;
    }

    /**
     * Whether a configured shielding block (glass and panes by default) is the
     * first non-air block above the player's head. Such a block still lets sky
     * light through, so the vanilla light check alone keeps calling the player
     * exposed; the blacklist lets transparent roofs act as real shade.
     */
    private boolean isShadedByBlock(Player player) {
        if (sunShieldingBlocks.isEmpty()) return false;

        World world = player.getWorld();
        Location location = player.getLocation();
        int x = location.getBlockX();
        int z = location.getBlockZ();
        // The head block is feet + 1 for a standing player; start one above it.
        int startY = Math.max(world.getMinHeight(), location.getBlockY() + 2);
        for (int y = startY; y < world.getMaxHeight(); y++) {
            Material type = world.getBlockAt(x, y, z).getType();
            if (type.isAir()) continue;
            return sunShieldingBlocks.contains(type);
        }
        return false;
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
