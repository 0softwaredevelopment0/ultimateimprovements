package com.ultimateimprovements.mechanics.security.auth;

import com.ultimateimprovements.config.MessagesManager;
import com.ultimateimprovements.database.StateStore;
import com.ultimateimprovements.util.AlertBroadcast;
import com.ultimateimprovements.util.ConsoleLogger;
import com.ultimateimprovements.util.MessageUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * "Player asks staff for a force login" flow behind the extra button on the
 * login dialog.
 * <p>
 * This is <b>not</b> a timed self-confirmation: clicking the button never
 * authenticates anybody. It only announces the request to the console and to
 * online holders of the alert permission ({@code ui.alerts} / OP) — with a
 * click-to-run {@code /ui auth forcelogin <nick>} action and a hover hint —
 * and rate-limits the button so a frozen player cannot spam staff.
 * <p>
 * Recipients can switch the chat notifications off with
 * {@code /ui auth forceloginrequest <on|off>}; the preference is persisted in
 * the {@code ui_state} table (namespace {@code auth}) and the console log is
 * not affected.
 */
public final class AuthForceLoginRequests {

    private static final String STATE_NAMESPACE = "auth";
    private static final String STATE_KEY_PREFIX = "forcelogin_request_alerts:";

    /** Last accepted click per player (ms) — the dialog button cooldown. */
    private static final Map<UUID, Long> lastRequestAt = new ConcurrentHashMap<>();
    /** Cached per-recipient preference: true = receive the notifications. */
    private static final Map<UUID, Boolean> alertsEnabled = new ConcurrentHashMap<>();

    private AuthForceLoginRequests() {}

    // =========================================================================
    // REQUEST
    // =========================================================================

    /**
     * Handles a click on the "request a force login" button: enforces the
     * cooldown, broadcasts the request and gives the requester a short receipt.
     * Never changes the authentication state.
     *
     * @param player the not-yet-authenticated player asking for help
     */
    public static void request(Player player) {
        if (player == null || !player.isOnline()) return;
        if (!AuthConfig.isForceloginRequestEnabled()) return;

        long now = System.currentTimeMillis();
        long cooldownMs = AuthConfig.getForceloginRequestCooldownSeconds() * 1000L;
        Long previous = lastRequestAt.get(player.getUniqueId());
        if (previous != null && cooldownMs > 0) {
            long remainingMs = cooldownMs - (now - previous);
            if (remainingMs > 0) {
                long seconds = Math.max(1L, (remainingMs + 999L) / 1000L);
                player.sendMessage(MessageUtil.parse(MessagesManager.getString(
                        "auth.forcelogin_request.cooldown",
                        "<red>❌ Please wait </red><yellow>%seconds%</yellow> <red>seconds before the next request!</red>")
                        .replace("%seconds%", String.valueOf(seconds))));
                return;
            }
        }
        lastRequestAt.put(player.getUniqueId(), now);

        String command = "/ui auth forcelogin " + player.getName();
        String notification = MessagesManager.getString("auth.forcelogin_request.notify",
                "<gold>✦</gold> <white>Player</white> <yellow>%player%</yellow> "
                        + "<white>requests a force login.</white> <gray>Confirm with:</gray> <yellow>%command%</yellow>")
                .replace("%player%", player.getName())
                .replace("%command%", command);
        String hoverText = MessagesManager.getString("auth.forcelogin_request.hover",
                "<green>Click to run</green>\n<gray>%command%</gray>")
                .replace("%player%", player.getName())
                .replace("%command%", command);

        // Console first: consoles cannot click and never see hover events, so the
        // log gets the same text with all formatting stripped.
        ConsoleLogger.info("[Auth] " + MessageUtil.toPlainText(notification));

        // Chat: one component, whole line clickable, hover hint in the same style
        // as the other clickable /ui buttons.
        Component chatMessage = MessageUtil.parse(notification)
                .clickEvent(ClickEvent.runCommand(command))
                .hoverEvent(HoverEvent.showText(MessageUtil.parse(hoverText)));
        for (Player recipient : Bukkit.getOnlinePlayers()) {
            if (recipient.getUniqueId().equals(player.getUniqueId())) continue;
            if (!AlertBroadcast.hasAlertPermission(recipient)) continue;
            if (!isAlertsEnabled(recipient.getUniqueId())) continue;
            recipient.sendMessage(chatMessage);
        }

        player.sendMessage(MessageUtil.parse(MessagesManager.getString(
                "auth.forcelogin_request.sent",
                "<green>✔</green> <white>Your force-login request was sent to the staff.</white>")));
    }

    // =========================================================================
    // RECIPIENT PREFERENCE
    // =========================================================================

    /** Whether the player (an alerts holder) still receives the chat notifications. */
    public static boolean isAlertsEnabled(UUID uuid) {
        if (uuid == null) return true;
        return alertsEnabled.computeIfAbsent(uuid, id ->
                !"0".equals(StateStore.get(STATE_NAMESPACE, STATE_KEY_PREFIX + id)));
    }

    /** Persists the recipient preference; {@code true} = receive notifications. */
    public static void setAlertsEnabled(UUID uuid, boolean enabled) {
        if (uuid == null) return;
        alertsEnabled.put(uuid, enabled);
        StateStore.put(STATE_NAMESPACE, STATE_KEY_PREFIX + uuid, enabled ? "1" : "0");
    }

    /** Drops the in-memory cooldown/preference bookkeeping for a player. */
    public static void removePlayer(UUID uuid) {
        if (uuid == null) return;
        lastRequestAt.remove(uuid);
    }
}
