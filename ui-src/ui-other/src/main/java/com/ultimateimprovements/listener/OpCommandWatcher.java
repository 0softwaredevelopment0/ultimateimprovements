package com.ultimateimprovements.listener;

import com.ultimateimprovements.config.MessagesManager;
import com.ultimateimprovements.core.Main;
import com.ultimateimprovements.op.OpManager;
import com.ultimateimprovements.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.ServerCommandEvent;

import java.util.Locale;

/**
 * Watches vanilla {@code /op} and {@code /deop} without replacing them.
 * <p>
 * The commands keep their vanilla behaviour (permissions, argument handling,
 * {@code ops.json} update, target notification). This listener only reacts to
 * the dispatch: it prints the deprecation notice (with clickable
 * {@code /ui op} / {@code /ui deop} suggestions) and, one tick later — after
 * the vanilla command has actually run — reconciles the plugin's OP list with
 * the fresh {@code ops.json} set, so the database list and the real OP flag
 * stay in sync.
 * <p>
 * Covers the bare labels, the {@code minecraft:} / {@code bukkit:} namespaced
 * forms, and both players and the console. The event is never cancelled.
 */
public class OpCommandWatcher implements Listener {

    private static final String K_DEPRECATED = "op.vanilla_deprecated";
    private static final String K_SYNC_DONE = "op.vanilla_sync_done";
    private static final String K_SYNC_FAILED = "op.vanilla_sync_failed";

    private static final String D_DEPRECATED =
            "<yellow>⚠</yellow> <gray>The <white>%command%</white> command is deprecated.</gray>\n"
            + "<gray>Use "
            + "<hover:show_text:'<gray>Click to insert /ui op into chat</gray>'><click:suggest_command:'/ui op'><white>/ui op</white></click></hover>"
            + " <dark_gray>|</dark_gray> "
            + "<hover:show_text:'<gray>Click to insert /ui deop into chat</gray>'><click:suggest_command:'/ui deop'><white>/ui deop</white></click></hover>"
            + " instead.</gray>\n"
            + "<gray>The plugin's operator list will be synchronized after the command runs.</gray>";
    private static final String D_SYNC_DONE =
            "<gray>Operator list synchronized: added <white>%added%</white>, removed <white>%removed%</white>.</gray>";
    private static final String D_SYNC_FAILED =
            "<red>⚠</red> <gray>Could not read the operator list (database error) — synchronization skipped.</gray>";

    // =========================
    // PLAYER
    // =========================
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        String command = parse(event.getMessage());
        if (command == null) return;

        // The event runs before dispatch — vanilla executes the command right
        // after this handler, so the reconcile is deferred by one tick.
        announce(event.getPlayer(), command);
        scheduleReconcile(event.getPlayer());
    }

    // =========================
    // CONSOLE
    // =========================
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onConsoleCommand(ServerCommandEvent event) {
        String command = parse("/" + event.getCommand());
        if (command == null) return;

        announce(event.getSender(), command);
        scheduleReconcile(event.getSender());
    }

    // =========================
    // PARSING
    // =========================
    /**
     * @return the display form ({@code /op}, {@code /minecraft:deop}, …) when
     *         the message is a vanilla op/deop command, {@code null} otherwise
     */
    private static String parse(String raw) {
        String text = raw == null ? "" : raw.trim();
        if (text.startsWith("/")) text = text.substring(1);
        String[] parts = text.split("\\s+");
        if (parts.length == 0) return null;

        String head = parts[0].toLowerCase(Locale.ROOT);
        int colon = head.indexOf(':');
        if (colon >= 0) {
            String namespace = head.substring(0, colon);
            if (!namespace.equals("minecraft") && !namespace.equals("bukkit")) return null;
            head = head.substring(colon + 1);
        }
        if (!head.equals("op") && !head.equals("deop")) return null;

        return "/" + parts[0].toLowerCase(Locale.ROOT);
    }

    // =========================
    // SYNC
    // =========================
    private static void announce(CommandSender sender, String command) {
        sender.sendMessage(prefixed(K_DEPRECATED, D_DEPRECATED, "%command%", command));
    }

    private static void scheduleReconcile(CommandSender sender) {
        Bukkit.getScheduler().runTask(Main.getInstance(), () -> {
            OpManager.ReconcileResult result = OpManager.reconcileAll();
            if (result.failed()) {
                sender.sendMessage(prefixed(K_SYNC_FAILED, D_SYNC_FAILED));
            } else if (result.added() > 0 || result.removed() > 0) {
                sender.sendMessage(prefixed(K_SYNC_DONE, D_SYNC_DONE,
                        "%added%", String.valueOf(result.added()),
                        "%removed%", String.valueOf(result.removed())));
            }
        });
    }

    private static net.kyori.adventure.text.Component prefixed(String key, String def, String... pairs) {
        String text = MessagesManager.getString(key, def);
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            text = text.replace(pairs[i], pairs[i + 1]);
        }
        return MessageUtil.parse(MessageUtil.PREFIX + text);
    }
}
