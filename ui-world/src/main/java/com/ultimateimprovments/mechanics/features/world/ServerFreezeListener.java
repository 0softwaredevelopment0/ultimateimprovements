package com.ultimateimprovments.mechanics.features.world;

import com.ultimateimprovments.advancement.Advancements;
import com.ultimateimprovments.core.Main;
import com.ultimateimprovments.util.ConsoleLogger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * The server has not responding! — grants the {@code ui:datapack/server_not_responding}
 * achievement to every online player when the server's main thread freezes
 * for at least the configured time
 * ({@code advancements.server_not_responding.freeze_seconds}, 10s by default).
 * <p>
 * A per-tick task records the actual wall-clock gap between consecutive
 * scheduled executions: while the main thread is frozen the task runs late,
 * so the gap is exactly the freeze duration. When a gap of {@code >= freeze_seconds}
 * is detected (the server was unresponsive that whole time), all online
 * players are awarded (idempotently).
 */
public final class ServerFreezeListener {

    /** Achievement id (without the {@code datapack/} folder). */
    private static final String ACHIEVEMENT = "server_not_responding";

    /** Default required main-thread freeze, in seconds. */
    private static final int DEFAULT_FREEZE_SECONDS = 10;

    private static long lastTickNanos = 0L;

    private ServerFreezeListener() {}

    /** Awards the achievement to the player. Idempotent. */
    private static void grant(Player player) {
        if (player == null) return;
        try {
            Advancements.grant(player, ACHIEVEMENT);
        } catch (Exception e) {
            ConsoleLogger.warn("[ServerFreeze] Award error for " + player.getName() + ": " + e.getMessage());
        }
    }

    /** One tick: measure the gap since the last execution — a huge gap means a freeze. */
    private static void tick() {
        long now = System.nanoTime();

        if (lastTickNanos != 0L) {
            long gapMs = (now - lastTickNanos) / 1_000_000L;
            int requiredSeconds = Advancements.getInt(ACHIEVEMENT, "freeze_seconds",
                    DEFAULT_FREEZE_SECONDS);

            if (gapMs >= requiredSeconds * 1000L) {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    grant(player);
                }
            }
        }

        lastTickNanos = now;
    }

    /**
     * Starts the per-tick freeze detection.
     */
    public static void register(Main plugin) {
        Bukkit.getScheduler().runTaskTimer(plugin, ServerFreezeListener::tick, 1L, 1L);
        int seconds = Advancements.getInt(ACHIEVEMENT, "freeze_seconds", DEFAULT_FREEZE_SECONDS);
        ConsoleLogger.info("[ServerFreeze] Listener registered (main thread freeze ≥ "
                + seconds + "s → The server has not responding!).");
    }
}
