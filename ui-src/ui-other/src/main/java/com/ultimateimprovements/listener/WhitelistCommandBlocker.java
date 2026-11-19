package com.ultimateimprovements.listener;

import com.ultimateimprovements.config.MessagesManager;
import com.ultimateimprovements.util.MessageUtil;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.ServerCommandEvent;

/**
 * ⚠ WhitelistCommandBlocker — warns that the vanilla /whitelist command is
 * deprecated in favour of the plugin's own /ui whitelist and /ui blacklist.
 * <p>
 * The command is NOT cancelled: it keeps working exactly like vanilla, but
 * every execution prints a deprecation notice with the UI prefix, because the
 * plugin maintains its own database-backed whitelist/blacklist and running
 * both systems side by side can produce confusing access conflicts.
 * <p>
 * Intercepts:
 * <ul>
 *   <li>{@code /whitelist}, {@code /minecraft:whitelist}, {@code /bukkit:whitelist} (any arguments)</li>
 *   <li>Both from players (PlayerCommandPreprocessEvent) and from the console (ServerCommandEvent)</li>
 * </ul>
 * The listener runs at {@link EventPriority#MONITOR} and never touches
 * {@code setCancelled}, so it cannot interfere with the actual command.
 */
public class WhitelistCommandBlocker implements Listener {

    /** Message key in the {@code plugin_hide} section of the composite config. */
    private static final String MESSAGE_KEY = "plugin_hide.whitelist_deprecated";

    private static final String DEFAULT_MESSAGE =
            "<yellow>⚠</yellow> <gray>The <white>/whitelist</white> command is deprecated and may conflict with the plugin's access systems.</gray>\n"
            + "<gray>Use <white>/ui whitelist</white> <dark_gray>|</dark_gray> <white>/ui blacklist</white> instead.</gray>";

    /**
     * Checks whether the command is a variant of /whitelist (considering namespaces and arguments).
     */
    private static boolean isWhitelistCommand(String msg) {
        return msg.equals("/whitelist")
                || msg.startsWith("/whitelist ")
                || msg.equals("/minecraft:whitelist")
                || msg.startsWith("/minecraft:whitelist ")
                || msg.equals("/bukkit:whitelist")
                || msg.startsWith("/bukkit:whitelist ");
    }

    private static String message() {
        return MessageUtil.PREFIX + MessagesManager.getString(MESSAGE_KEY, DEFAULT_MESSAGE);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        String msg = event.getMessage().toLowerCase().trim();

        if (isWhitelistCommand(msg)) {
            event.getPlayer().sendMessage(MessageUtil.parse(message()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onConsoleCommand(ServerCommandEvent event) {
        String command = event.getCommand().toLowerCase().trim();

        if (isWhitelistCommand("/" + command)) {
            event.getSender().sendMessage(MessageUtil.parse(message()));
        }
    }
}
