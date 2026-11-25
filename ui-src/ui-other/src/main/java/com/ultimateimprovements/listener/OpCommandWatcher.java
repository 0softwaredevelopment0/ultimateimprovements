package com.ultimateimprovements.listener;

import com.ultimateimprovements.config.MessagesManager;
import com.ultimateimprovements.core.Main;
import com.ultimateimprovements.op.OpManager;
import com.ultimateimprovements.util.MessageUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.ServerCommandEvent;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Watches vanilla {@code /op} and {@code /deop} without replacing them.
 * <p>
 * The commands keep their vanilla behaviour (permissions, argument handling,
 * {@code ops.json} update, target notification). This listener only gates the
 * dispatch:
 * <ol>
 *   <li>the first player execution is cancelled, prints the deprecation notice
 *       (with clickable {@code /ui op} / {@code /ui deop} suggestions) and asks
 *       to repeat the same command within {@code op.vanilla_confirm_seconds};</li>
 *   <li>the repeated command is not cancelled, so vanilla executes it exactly
 *       as before, and one tick later the plugin's OP list is reconciled with
 *       the fresh {@code ops.json} set;</li>
 *   <li>without a repeat the pending action expires and a timeout notice is
 *       sent.</li>
 * </ol>
 * The console is trusted: it gets the deprecation notice, the command runs
 * immediately and the OP list is reconciled afterwards.
 * <p>
 * Covers the bare labels and the {@code minecraft:} / {@code bukkit:}
 * namespaced forms. Events cancelled by another plugin (the sudo dialog, for
 * example) are left untouched.
 */
public class OpCommandWatcher implements Listener {

    /** 15 s fallback when {@code op.vanilla_confirm_seconds} is missing. */
    private static final int DEFAULT_CONFIRM_SECONDS = 15;
    private static final long MAX_CONFIRM_SECONDS = 3600L;

    private static final String K_DEPRECATED = "op.vanilla_deprecated";
    private static final String K_CONFIRM = "op.vanilla_confirm";
    private static final String K_CONFIRMED = "op.vanilla_confirmed";
    private static final String K_TIMEOUT = "op.vanilla_timeout";
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
    private static final String D_CONFIRM =
            "<gray>Type <white>%command%</white> again within <white>%seconds% s</white> to confirm. Otherwise the action will be cancelled.</gray>";
    private static final String D_CONFIRMED =
            "<green>✔</green> <gray>Confirmed: <white>%command%</white>. The operator list will be synchronized.</gray>";
    private static final String D_TIMEOUT =
            "<red>⌛</red> <gray>Confirmation for <white>%command%</white> expired — the action was cancelled.</gray>";
    private static final String D_SYNC_DONE =
            "<gray>Operator list synchronized: added <white>%added%</white>, removed <white>%removed%</white>.</gray>";
    private static final String D_SYNC_FAILED =
            "<red>⚠</red> <gray>Could not read the operator list (database error) — synchronization skipped.</gray>";

    /** Pending confirmations: player UUID → the exact normalized command. */
    private static final Map<UUID, Pending> pending = new ConcurrentHashMap<>();

    private record Pending(String identity, long createdAt) {}

    /** A soft reload creates a new listener — pending confirmations must not survive it. */
    public OpCommandWatcher() {
        pending.clear();
    }

    // =========================
    // PLAYER
    // =========================
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        Parsed parsed = parse(event.getMessage());
        if (parsed == null) return;

        Player actor = event.getPlayer();
        // Let vanilla answer unauthorized attempts itself — no confirmation
        // cycle and no deprecation spam for players who cannot run the command.
        if (!actor.hasPermission(parsed.permission())) return;

        long now = System.currentTimeMillis();
        long timeoutMs = confirmSeconds() * 1000L;
        Pending previous = pending.get(actor.getUniqueId());
        if (previous == null
                || !previous.identity().equals(parsed.identity())
                || now - previous.createdAt() > timeoutMs) {
            // First attempt (or a different target/command): cancel so nothing
            // changes yet, warn and wait for the repeat.
            event.setCancelled(true);
            Pending created = new Pending(parsed.identity(), now);
            pending.put(actor.getUniqueId(), created);
            actor.sendMessage(prefixed(K_DEPRECATED, D_DEPRECATED, "%command%", parsed.command()));
            actor.sendMessage(prefixed(K_CONFIRM, D_CONFIRM,
                    "%command%", parsed.display(),
                    "%seconds%", String.valueOf(confirmSeconds())));
            scheduleTimeout(actor, created, parsed.display(), timeoutMs);
            return;
        }

        // Confirmed: do NOT cancel — vanilla executes the command exactly as
        // it would without the plugin, and the OP list is synced afterwards.
        pending.remove(actor.getUniqueId());
        actor.sendMessage(prefixed(K_CONFIRMED, D_CONFIRMED, "%command%", parsed.display()));
        scheduleReconcile(actor);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        pending.remove(event.getPlayer().getUniqueId());
    }

    // =========================
    // CONSOLE
    // =========================
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onConsoleCommand(ServerCommandEvent event) {
        Parsed parsed = parse("/" + event.getCommand());
        if (parsed == null) return;

        // Console is trusted — no confirmation, just the deprecation notice.
        CommandSender sender = event.getSender();
        sender.sendMessage(prefixed(K_DEPRECATED, D_DEPRECATED, "%command%", parsed.command()));
        scheduleReconcile(sender);
    }

    // =========================
    // CONFIRMATION TIMEOUT
    // =========================
    private static void scheduleTimeout(Player player, Pending expected, String display, long timeoutMs) {
        Bukkit.getScheduler().runTaskLater(Main.getInstance(), () -> {
            UUID id = player.getUniqueId();
            Pending current = pending.get(id);
            if (current == null || !current.equals(expected)) return;
            pending.remove(id);
            if (player.isOnline()) {
                player.sendMessage(prefixed(K_TIMEOUT, D_TIMEOUT, "%command%", display));
            }
        }, Math.max(1L, timeoutMs / 50L));
    }

    // =========================
    // PARSING
    // =========================
    private record Parsed(boolean grant, String[] args, String command, String display) {
        /** Vanilla permission node for this command ({@code minecraft.command.op|deop}). */
        String permission() {
            return "minecraft.command." + (grant ? "op" : "deop");
        }

        /** Pending-confirmation identity: the grant flag plus the target argument. */
        String identity() {
            return (grant ? "op " : "deop ") + String.join(" ", args).toLowerCase(Locale.ROOT);
        }
    }

    /**
     * @return the parsed command when the message is a vanilla op/deop command,
     *         {@code null} otherwise
     */
    private static Parsed parse(String raw) {
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

        boolean grant;
        if (head.equals("op")) {
            grant = true;
        } else if (head.equals("deop")) {
            grant = false;
        } else {
            return null;
        }

        String[] args = new String[Math.max(0, parts.length - 1)];
        System.arraycopy(parts, 1, args, 0, args.length);

        String command = "/" + parts[0].toLowerCase(Locale.ROOT);
        String display = args.length == 0 ? command : command + " " + String.join(" ", args);
        return new Parsed(grant, args, command, display);
    }

    // =========================
    // SYNC
    // =========================
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

    // =========================
    // HELPERS
    // =========================
    private static int confirmSeconds() {
        int configured = Main.getInstance().getConfig()
                .getInt("op.vanilla_confirm_seconds", DEFAULT_CONFIRM_SECONDS);
        return (int) Math.min(Math.max(configured, 1L), MAX_CONFIRM_SECONDS);
    }

    private static Component prefixed(String key, String def, String... pairs) {
        String text = MessagesManager.getString(key, def);
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            text = text.replace(pairs[i], pairs[i + 1]);
        }
        return MessageUtil.parse(MessageUtil.PREFIX + text);
    }
}
