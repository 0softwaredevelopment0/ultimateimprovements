package com.ultimateimprovments.mechanics.features.world;

import com.ultimateimprovments.advancement.Advancements;
import com.ultimateimprovments.core.Main;
import com.ultimateimprovments.util.ConsoleLogger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * java.lang.OutOfMemoryError — grants the {@code ui:datapack/out_of_memory}
 * achievement to every online player when the Java process's heap usage
 * reaches the configured threshold ({@code advancements.out_of_memory.threshold_percent},
 * 95% by default — the JVM dies before a literal 100%).
 * <p>
 * A cheap 1-second sweep (20 ticks) reads the JVM heap
 * ({@link Runtime#totalMemory()} / {@link Runtime#maxMemory()}): when the
 * used heap crosses the threshold, all online players are awarded
 * (idempotently).
 */
public final class OutOfMemoryListener {

    /** Achievement id (without the {@code datapack/} folder). */
    private static final String ACHIEVEMENT = "out_of_memory";

    /** Sweep interval: 20 ticks (1s). */
    private static final long SWEEP_INTERVAL_TICKS = 20L;

    /** Default heap usage that counts as "RAM exhausted". */
    private static final double DEFAULT_THRESHOLD_PERCENT = 95.0;

    private OutOfMemoryListener() {}

    /** Current JVM heap usage in percent (0..100+). */
    private static double ramUsagePercent() {
        Runtime rt = Runtime.getRuntime();
        long max = rt.maxMemory();
        if (max <= 0) return 0.0;
        long used = rt.totalMemory() - rt.freeMemory();
        return used * 100.0 / max;
    }

    /** One sweep tick: grant the achievement to everyone online while heap is above the threshold. */
    private static void sweep() {
        double threshold = Advancements.getDouble(ACHIEVEMENT, "threshold_percent",
                DEFAULT_THRESHOLD_PERCENT);
        if (ramUsagePercent() < threshold) return;

        for (Player player : Bukkit.getOnlinePlayers()) {
            try {
                Advancements.grant(player, ACHIEVEMENT);
            } catch (Exception e) {
                ConsoleLogger.warn("[OutOfMemory] Award error for " + player.getName() + ": " + e.getMessage());
            }
        }
    }

    /** Starts the periodic RAM-usage sweep. */
    public static void register(Main plugin) {
        Bukkit.getScheduler().runTaskTimer(plugin, OutOfMemoryListener::sweep,
                SWEEP_INTERVAL_TICKS, SWEEP_INTERVAL_TICKS);
        double threshold = Advancements.getDouble(ACHIEVEMENT, "threshold_percent",
                DEFAULT_THRESHOLD_PERCENT);
        ConsoleLogger.info("[OutOfMemory] Listener registered (heap ≥ " + (long) threshold
                + "% check every 1s).");
    }
}
