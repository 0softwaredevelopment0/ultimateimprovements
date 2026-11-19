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

import java.util.HashSet;
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

            boolean exposed = isExposedToSun(player);

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
