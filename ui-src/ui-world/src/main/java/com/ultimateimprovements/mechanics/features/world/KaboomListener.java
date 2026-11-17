package com.ultimateimprovements.mechanics.features.world;

import com.ultimateimprovements.advancement.Advancements;
import com.ultimateimprovements.core.Main;
import com.ultimateimprovements.util.ConsoleLogger;
import org.bukkit.Material;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

/**
 * Kaboom! — grants the {@code ui:datapack/kaboom} achievement when a
 * player deals enough damage to a mob with a SINGLE mace hit
 * ({@code advancements.kaboom.required_damage}, 1,000 HP by default).
 * <p>
 * {@code awardCriteria} is idempotent — the achievement is granted only once,
 * so further hits do nothing.
 */
public final class KaboomListener implements Listener {

    /** Achievement id (without the {@code datapack/} folder). */
    private static final String ACHIEVEMENT = "kaboom";

    /** Default single-hit damage required by the achievement. */
    private static final double DEFAULT_REQUIRED_DAMAGE = 1000.0;

    private KaboomListener() {}

    /** Awards the achievement to the player. Idempotent. */
    public static void grant(Player player) {
        if (player == null) return;
        try {
            Advancements.grant(player, ACHIEVEMENT);
        } catch (Exception e) {
            ConsoleLogger.warn("[Kaboom] Award error for " + player.getName() + ": " + e.getMessage());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) return;
        if (!(event.getEntity() instanceof LivingEntity victim)) return;
        if (victim instanceof Player) return; // mobs only
        if (!victim.isValid() || victim.isDead()) return;

        if (player.getInventory().getItemInMainHand().getType() != Material.MACE) return;

        double required = Advancements.getDouble(ACHIEVEMENT, "required_damage", DEFAULT_REQUIRED_DAMAGE);
        if (event.getFinalDamage() >= required) {
            grant(player);
        }
    }

    /** Registers the damage listener. */
    public static void register(Main plugin) {
        plugin.getServer().getPluginManager().registerEvents(new KaboomListener(), plugin);
        double required = Advancements.getDouble(ACHIEVEMENT, "required_damage", DEFAULT_REQUIRED_DAMAGE);
        ConsoleLogger.info("[Kaboom] Listener registered (single mace hit ≥ "
                + (long) required + " damage for Kaboom!).");
    }
}
