package com.ultimateimprovments.mechanics.features.world;

import com.ultimateimprovments.advancement.Advancements;
import com.ultimateimprovments.core.Main;
import com.ultimateimprovments.util.ConsoleLogger;
import com.ultimateimprovments.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Timed advancement challenge: <b>Let me teleport!</b> — teleport with an
 * ender pearl {@code goal} times within {@code duration_seconds} to earn the
 * {@code ui:datapack/let_me_teleport} achievement.
 * <p>
 * Both values come from {@code [advancements.let_me_teleport]} in
 * {@code UI-Advancements.toml} and are captured when a challenge starts, so a
 * config edit never changes a run that is already in progress.
 * <p>
 * Started via {@code /ui advancement start teleport}. Per-teleport work is a
 * single map increment; a cheap 1-second sweep only iterates players with an
 * active challenge (showing progress / expiring). The global
 * {@link TimedChallengeLock} guarantees only one challenge per player at a time.
 */
public final class EnderPearlChallenge implements Listener {

    /** Achievement id (without the {@code datapack/} folder). */
    private static final String ACHIEVEMENT = "let_me_teleport";

    /** Active challenges: player UUID → state. */
    private static final Map<UUID, ChallengeState> ACTIVE = new ConcurrentHashMap<>();

    private EnderPearlChallenge() {}

    private static final class ChallengeState {
        final long startTime = System.currentTimeMillis();
        final int goal = Math.max(1,
                Advancements.getInt(ACHIEVEMENT, "goal", 60));
        final long durationMs = Math.max(1,
                Advancements.getInt(ACHIEVEMENT, "duration_seconds", 60)) * 1000L;
        int count = 0;
    }

    // =========================
    // COMMAND ENTRY
    // =========================

    /** Starts the timed challenge ("teleport"). */
    public static void start(Player player, String name) {
        UUID uuid = player.getUniqueId();
        if (!TimedChallengeLock.tryAcquire(uuid)) {
            player.sendMessage(MessageUtil.parse(
                    "<red>❌ <white>You already have an active challenge! Finish the current one first.</white>"));
            return;
        }

        ChallengeState state = new ChallengeState();
        ACTIVE.put(uuid, state);
        player.sendMessage(MessageUtil.parse(
                "<gold>⏱ <white>Challenge <yellow>Let me teleport!</yellow> started!</white>\n"
                + "<gray>Teleport <white>" + state.goal + "</white> times with an ender pearl in <white>"
                + ChallengeText.duration((int) (state.durationMs / 1000)) + "</white>."));
    }

    /** Cancels the player's active challenge, if any. Returns true if one was stopped. */
    public static boolean stop(Player player) {
        ChallengeState state = ACTIVE.remove(player.getUniqueId());
        TimedChallengeLock.release(player.getUniqueId());
        if (state == null) return false;
        player.sendMessage(MessageUtil.parse(
                "<yellow>⏹ <white>The challenge has been canceled. Teleports: <yellow>" + state.count
                + "</yellow>/" + state.goal + ".</white>"));
        return true;
    }

    // =========================
    // COUNTING
    // =========================

    /** Counts a successful ender pearl teleport toward the player's active challenge. */
    @EventHandler(ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (event.getCause() != PlayerTeleportEvent.TeleportCause.ENDER_PEARL) return;

        Player player = event.getPlayer();
        ChallengeState state = ACTIVE.get(player.getUniqueId());
        if (state == null) return;

        state.count++;
        if (state.count >= state.goal) {
            ACTIVE.remove(player.getUniqueId());
            TimedChallengeLock.release(player.getUniqueId());
            grant(player, state.count);
        }
    }

    // =========================
    // SWEEP (20 ticks) — progress bar + expiry
    // =========================

    private static void sweep() {
        long now = System.currentTimeMillis();

        ACTIVE.entrySet().removeIf(entry -> {
            ChallengeState state = entry.getValue();
            Player player = Bukkit.getPlayer(entry.getKey());
            long elapsed = now - state.startTime;

            if (elapsed >= state.durationMs) {
                TimedChallengeLock.release(entry.getKey());
                if (player != null) {
                    player.sendMessage(MessageUtil.parse(
                            "<red>❌ <white>Time's up! You teleported: <yellow>" + state.count
                            + "</yellow>/" + state.goal + " times.</white>"));
                }
                return true;
            }

            if (player != null) {
                int percent = (int) (state.count * 100.0 / state.goal);
                long leftSec = (state.durationMs - elapsed) / 1000;
                player.sendActionBar(MessageUtil.parse(
                        "<gold>🌀 <white>Teleports: <yellow>" + state.count + "</yellow>/" + state.goal + "</white> "
                        + "<gray>(" + percent + "%) · remaining " + leftSec + "s</gray>"));
            }
            return false;
        });
    }

    // =========================
    // REWARD
    // =========================

    /** Awards the achievement and notifies the player. Idempotent. */
    private static void grant(Player player, int count) {
        if (!Advancements.isEnabled(ACHIEVEMENT)) return;
        try {
            Advancements.grant(player, ACHIEVEMENT);
        } catch (Exception e) {
            ConsoleLogger.warn("[EnderPearlChallenge] Award error for " + player.getName() + ": " + e.getMessage());
        }
        player.sendMessage(MessageUtil.parse(
                "<green>✔ <white>Challenge completed! <yellow>Let me teleport!</yellow> reached "
                + "(" + count + " ender pearl teleports).</white>"));
    }

    // =========================
    // REGISTRATION
    // =========================

    public static void register(Main plugin) {
        plugin.getServer().getPluginManager().registerEvents(new EnderPearlChallenge(), plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, EnderPearlChallenge::sweep, 20L, 20L);
        ConsoleLogger.info("[EnderPearlChallenge] Registered (timed challenge: Let me teleport!).");
    }
}
