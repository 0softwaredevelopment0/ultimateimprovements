package com.ultimateimprovements.listener;

import com.ultimateimprovements.core.Main;
import com.ultimateimprovements.util.AlertBroadcast;
import com.ultimateimprovements.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LoadedAlertListener — after a server start, every player who receives
 * alerts ({@code ui.alerts} / OP, per {@link AlertBroadcast}) is told
 * <pre>prefix + "UltimateImprovements loaded successfully!"</pre>
 * exactly ONCE per server session: the first time they are present after
 * startup (online at enable time, or on their first join afterwards).
 * Later joins in the same session stay silent. The in-memory flag set
 * resets naturally on the next server restart (fresh classloader).
 * <p>
 * The same one-shot pass also warns alert holders when LuckPerms is not
 * installed — the plugin is built around its permission/permission metadata
 * model, so LuckPerms is highly recommended.
 * <p>
 * Feature toggles: {@code loaded_alert.enabled} and
 * {@code luckperms_warning.enabled} (both default true, read live —
 * /ui reload applies them without a restart).
 * <p>
 * Message texts are configurable per language in {@code UI-Core.toml}:
 * {@code [messages.loaded_alert]} / {@code [messages_en.loaded_alert]}
 * and {@code [messages.luckperms_warning]} / {@code [messages_en.luckperms_warning]}
 * ({@code message} + {@code download} keys).
 */
public final class LoadedAlertListener implements Listener {

    /** Players already notified in this server session (one message each). */
    private static final Set<UUID> NOTIFIED = ConcurrentHashMap.newKeySet();

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        notifyIfNeeded(event.getPlayer());
    }

    /** Notifies alert-holders that are already online when ui-core (re)enables. */
    public static void sweepOnline() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            notifyIfNeeded(player);
        }
    }

    private static void notifyIfNeeded(Player player) {
        if (!AlertBroadcast.hasAlertPermission(player)) return;

        boolean sendLoaded = isLoadedAlertEnabled();
        boolean sendLuckPermsWarning = isLuckPermsWarningEnabled() && !isLuckPermsInstalled();
        if (!sendLoaded && !sendLuckPermsWarning) return;

        if (!NOTIFIED.add(player.getUniqueId())) return; // already shown this session

        if (sendLoaded) {
            String text = MessageUtil.raw("loaded_alert.message",
                    "<white>UltimateImprovements loaded successfully!</white>");
            player.sendMessage(MessageUtil.parse(MessageUtil.PREFIX + text));
        }
        if (sendLuckPermsWarning) {
            String text = MessageUtil.raw("luckperms_warning.message",
                    "<yellow>LuckPerms was not found on this server. "
                            + "It is highly recommended for UltimateImprovements: "
                            + "permissions, groups and placeholders depend on it.</yellow>");
            String download = MessageUtil.raw("luckperms_warning.download",
                    "<yellow>Download: <click:open_url:'https://luckperms.net/download'>"
                            + "<white><u>https://luckperms.net/download</u></white></click></yellow>");
            player.sendMessage(MessageUtil.parse(MessageUtil.PREFIX + text));
            player.sendMessage(MessageUtil.parse(MessageUtil.PREFIX + download));
        }
    }

    /** Feature toggle: {@code loaded_alert.enabled} (default true). */
    private static boolean isLoadedAlertEnabled() {
        Main plugin = Main.getInstance();
        return plugin == null
                || plugin.getConfig().getBoolean("loaded_alert.enabled", true);
    }

    /** Feature toggle: {@code luckperms_warning.enabled} (default true). */
    private static boolean isLuckPermsWarningEnabled() {
        Main plugin = Main.getInstance();
        return plugin == null
                || plugin.getConfig().getBoolean("luckperms_warning.enabled", true);
    }

    /** True when the LuckPerms plugin is installed on the server. */
    private static boolean isLuckPermsInstalled() {
        return Bukkit.getPluginManager().getPlugin("LuckPerms") != null;
    }
}
