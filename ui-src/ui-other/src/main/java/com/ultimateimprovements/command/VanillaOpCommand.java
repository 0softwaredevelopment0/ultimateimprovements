package com.ultimateimprovements.command;

import com.ultimateimprovements.config.MessagesManager;
import com.ultimateimprovements.core.Main;
import com.ultimateimprovements.core.Permissions;
import com.ultimateimprovements.op.OpManager;
import com.ultimateimprovements.util.MessageUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Vanilla {@code /op} and {@code /deop}, kept available but deprecated.
 * <p>
 * Registered through the server CommandMap ({@link CommandRegistrar}) so every
 * dispatch path reaches it — a normal chat command, the console, and the sudo
 * re-run ({@link Player#performCommand(String)}, which bypasses
 * {@code PlayerCommandPreprocessEvent}). Namespaced forms
 * ({@code /minecraft:op}, {@code /bukkit:op}, …) are routed here by
 * {@link com.ultimateimprovements.listener.OpCommandBlocker}.
 * <p>
 * Flow for players:
 * <ol>
 *   <li>the first execution prints the deprecation notice (with clickable
 *       {@code /ui op} / {@code /ui deop} suggestions) and asks to repeat the
 *       exact command within {@code op.vanilla_confirm_seconds} (default 15 s);</li>
 *   <li>the repeated command performs the change through {@link OpManager}, so
 *       the real OP flag and the plugin's OP list stay in sync;</li>
 *   <li>without a repeat the pending action expires and a cancellation notice
 *       is sent.</li>
 * </ol>
 * The console is trusted: it gets the deprecation notice and the action runs
 * immediately. After every execution the OP list is reconciled against the real
 * {@code ops.json} set.
 */
public final class VanillaOpCommand extends Command {

    /** 15 s fallback when {@code op.vanilla_confirm_seconds} is missing. */
    private static final int DEFAULT_CONFIRM_SECONDS = 15;
    private static final long MAX_CONFIRM_SECONDS = 3600L;

    private static final String K_DEPRECATED = "op.vanilla_deprecated";
    private static final String K_CONFIRM = "op.vanilla_confirm";
    private static final String K_CONFIRMED = "op.vanilla_confirmed";
    private static final String K_TIMEOUT = "op.vanilla_timeout";
    private static final String K_USAGE = "op.vanilla_usage";
    private static final String K_UNKNOWN = "op.vanilla_player_unknown";
    private static final String K_GRANTED = "op.vanilla_granted";
    private static final String K_REVOKED = "op.vanilla_revoked";
    private static final String K_ALREADY_OP = "op.vanilla_already_op";
    private static final String K_NOT_OP = "op.vanilla_not_op";
    private static final String K_TARGET_REVOKED = "op.vanilla_target_revoked";
    private static final String K_SYNC_DONE = "op.vanilla_sync_done";
    private static final String K_SYNC_FAILED = "op.vanilla_sync_failed";
    private static final String K_LIST_UNAVAILABLE = "op.list_unavailable";
    private static final String K_OFFLINE = "op.offline_warning";
    private static final String K_TARGET_GRANTED = "op.target_granted";

    private static final String D_DEPRECATED =
            "<yellow>⚠</yellow> <gray>The <white>%command%</white> command is deprecated and may conflict with the plugin's operator system.</gray>\n"
            + "<gray>Use "
            + "<hover:show_text:'<gray>Click to insert /ui op into chat</gray>'><click:suggest_command:'/ui op'><white>/ui op</white></click></hover>"
            + " <dark_gray>|</dark_gray> "
            + "<hover:show_text:'<gray>Click to insert /ui deop into chat</gray>'><click:suggest_command:'/ui deop'><white>/ui deop</white></click></hover>"
            + " instead.</gray>";
    private static final String D_CONFIRM =
            "<gray>Type <white>%command%</white> again within <white>%seconds% s</white> to confirm. Otherwise the action will be cancelled.</gray>";
    private static final String D_CONFIRMED =
            "<green>✔</green> <gray>Confirmed: <white>%command%</white>.</gray>";
    private static final String D_TIMEOUT =
            "<red>⌛</red> <gray>Confirmation for <white>%command%</white> expired — the action was cancelled.</gray>";
    private static final String D_USAGE =
            "<red>Usage: </red><white>%command% \\<player\\></white>";
    private static final String D_UNKNOWN =
            "<red>Player </red><yellow>%player%</yellow><red> is not online and is not known on this server.</red>";
    private static final String D_GRANTED =
            "<green>✔</green> <gold>Operator</gold> <white>status granted to</white> <yellow>%player%</yellow><white>.</white>";
    private static final String D_REVOKED =
            "<green>✔</green> <gold>Operator</gold> <white>status revoked from</white> <yellow>%player%</yellow><white>.</white>";
    private static final String D_ALREADY_OP =
            "<yellow>%player%</yellow><red> already has OP status and is in the OP list.</red>";
    private static final String D_NOT_OP =
            "<yellow>%player%</yellow><red> does not have OP status and is not in the OP list.</red>";
    private static final String D_TARGET_REVOKED =
            "<red>⛔</red> <white>Your</white> <gold>operator</gold> <white>status has been revoked.</white>";
    private static final String D_OFFLINE =
            "<yellow>⚠</yellow> <gray>Player </gray><white>%player%</white> <gray>is currently offline — the change was written to ops.json and the OP list.</gray>";
    private static final String D_TARGET_GRANTED = "<gold>⚡</gold> <white>You are now an</white> <gold>operator</gold><white>!</white>";
    private static final String D_SYNC_DONE =
            "<gray>Operator list synchronized: added <white>%added%</white>, removed <white>%removed%</white>.</gray>";
    private static final String D_SYNC_FAILED =
            "<red>⚠</red> <gray>Could not read the operator list (database error) — synchronization skipped.</gray>";

    /** Pending confirmations: player UUID → the exact normalized command. */
    private static final Map<UUID, Pending> pending = new ConcurrentHashMap<>();

    private record Pending(String command, long createdAt) {}

    private final boolean grant;

    public VanillaOpCommand(String name, boolean grant) {
        super(name);
        this.grant = grant;
        setDescription(grant
                ? "Grant operator status (deprecated, use /ui op)"
                : "Revoke operator status (deprecated, use /ui deop)");
        setUsage("/" + name + " <player>");
    }

    /** Drops every pending confirmation — called when the listener is re-created on reload. */
    public static void resetPending() {
        pending.clear();
    }

    /** Drops one player's pending confirmation (quit / reload). */
    public static void clearPending(UUID uuid) {
        pending.remove(uuid);
    }

    @Override
    public boolean execute(CommandSender sender, String label, String[] args) {
        return handle(sender, grant, args, "/" + label);
    }

    /**
     * Shared entry point for the CommandMap override, the namespaced listener
     * and the console.
     *
     * @param sender  command sender
     * @param grant   {@code true} for /op, {@code false} for /deop
     * @param args    command arguments (first one is the target player)
     * @param raw     display form of the command ({@code /op}, {@code /deop}, …)
     */
    public static boolean handle(CommandSender sender, boolean grant, String[] args, String raw) {
        String commandLabel = "/" + (grant ? "op" : "deop");
        String permission = grant ? Permissions.CMD_OP : Permissions.CMD_DEOP;

        boolean player = sender instanceof Player;
        if (player && !sender.hasPermission(permission)) {
            CommandErrors.noPermission(sender, permission);
            return true;
        }

        if (args.length < 1) {
            sender.sendMessage(prefixed(K_USAGE, D_USAGE, "%command%", commandLabel));
            return true;
        }

        String targetName = args[0];
        // Resolve before asking to repeat: an unknown name must not cost the
        // player a confirmation cycle.
        OfflinePlayer target = OpManager.resolve(targetName);
        if (target == null || target.getName() == null) {
            sender.sendMessage(prefixed(K_UNKNOWN, D_UNKNOWN, "%player%", targetName));
            return true;
        }
        String identity = (grant ? "op " : "deop ") + target.getName().toLowerCase(Locale.ROOT);
        String display = raw + " " + targetName;

        if (player) {
            Player actor = (Player) sender;
            long now = System.currentTimeMillis();
            long timeoutMs = confirmSeconds() * 1000L;
            Pending previous = pending.get(actor.getUniqueId());
            if (previous == null
                    || !previous.command().equals(identity)
                    || now - previous.createdAt() > timeoutMs) {
                // First attempt (or a different target/command) — warn and wait.
                pending.put(actor.getUniqueId(), new Pending(identity, now));
                actor.sendMessage(prefixed(K_DEPRECATED, D_DEPRECATED, "%command%", commandLabel));
                actor.sendMessage(prefixed(K_CONFIRM, D_CONFIRM,
                        "%command%", display,
                        "%seconds%", String.valueOf(confirmSeconds())));
                scheduleTimeout(actor, new Pending(identity, now), display, timeoutMs);
                return true;
            }
            pending.remove(actor.getUniqueId());
            actor.sendMessage(prefixed(K_CONFIRMED, D_CONFIRMED, "%command%", display));
        } else {
            // Console is trusted — no confirmation, just the deprecation notice.
            sender.sendMessage(prefixed(K_DEPRECATED, D_DEPRECATED, "%command%", commandLabel));
        }

        apply(sender, grant, targetName);
        scheduleReconcile(sender);
        return true;
    }

    // =========================
    // ACTION
    // =========================
    private static void apply(CommandSender sender, boolean grant, String targetName) {
        OfflinePlayer target = OpManager.resolve(targetName);
        if (target == null || target.getName() == null) {
            sender.sendMessage(prefixed(K_UNKNOWN, D_UNKNOWN, "%player%", targetName));
            return;
        }

        String name = target.getName();
        Boolean listed = OpManager.lookup(name);
        if (listed == null) {
            sender.sendMessage(prefixed(K_LIST_UNAVAILABLE,
                    "<red>Could not read the OP list (database error).</red> <gray>Nothing was changed — check the server console.</gray>"));
            return;
        }

        boolean realOp = OpManager.hasRealOp(target);
        if (grant) {
            if (realOp && listed) {
                sender.sendMessage(prefixed(K_ALREADY_OP, D_ALREADY_OP, "%player%", name));
                return;
            }
            boolean wasOp = realOp;
            OpManager.grant(target, sender.getName());
            sender.sendMessage(prefixed(K_GRANTED, D_GRANTED, "%player%", name));
            if (target.isOnline()) {
                Player online = target.getPlayer();
                if (online != null && !wasOp) {
                    online.sendMessage(MessageUtil.parse(MessageUtil.PREFIX
                            + MessagesManager.getString(K_TARGET_GRANTED, D_TARGET_GRANTED)));
                }
            } else {
                sender.sendMessage(prefixed(K_OFFLINE, D_OFFLINE, "%player%", name));
            }
            return;
        }

        if (!realOp && !listed) {
            sender.sendMessage(prefixed(K_NOT_OP, D_NOT_OP, "%player%", name));
            return;
        }
        OpManager.revoke(target);
        sender.sendMessage(prefixed(K_REVOKED, D_REVOKED, "%player%", name));
        if (target.isOnline()) {
            Player online = target.getPlayer();
            if (online != null) {
                online.sendMessage(MessageUtil.parse(MessageUtil.PREFIX
                        + MessagesManager.getString(K_TARGET_REVOKED, D_TARGET_REVOKED)));
            }
        } else {
            sender.sendMessage(prefixed(K_OFFLINE, D_OFFLINE, "%player%", name));
        }
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
    // OP LIST RECONCILIATION
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

    @Override
    public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
        if (args.length != 1) return List.of();
        String prefix = args[0].toLowerCase(Locale.ROOT);
        return Bukkit.getOnlinePlayers().stream()
                .map(Player::getName)
                .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                .sorted()
                .collect(Collectors.toList());
    }
}
