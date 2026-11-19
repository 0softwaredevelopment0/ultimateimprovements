package com.ultimateimprovements.command;

import com.ultimateimprovements.config.MessagesManager;
import com.ultimateimprovements.core.Main;
import com.ultimateimprovements.core.Permissions;
import com.ultimateimprovements.core.hooks.CoreHooks;
import com.ultimateimprovements.util.Broadcast;
import com.ultimateimprovements.util.MessageUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.List;
import java.util.UUID;

/**
 * Vanilla {@code /stop} and {@code /restart}, kept available but deprecated and
 * gated by a console confirmation.
 * <p>
 * Registered through the server CommandMap ({@link CommandRegistrar}) so all
 * dispatch paths reach it — player chat, the console and the sudo re-run
 * ({@code Player#performCommand}, which bypasses
 * {@code PlayerCommandPreprocessEvent}). Namespaced labels are routed here by
 * {@link com.ultimateimprovements.listener.PowerInterceptListener}.
 * <p>
 * Flow:
 * <ul>
 *   <li><b>Player</b> runs the command → both the player and the console get a
 *       deprecation notice; a request is registered for the console. The console
 *       must type the <b>same</b> command within {@code power.vanilla_confirm_seconds}
 *       (default 15 s). The player cannot self-confirm and is told the request is
 *       already submitted. On console confirmation the player is told the action
 *       will run; after {@code power.vanilla_execute_delay_ticks} (default 20)
 *       the server stops/restarts. No confirmation → timeout notice to both.</li>
 *   <li><b>Console</b> runs the command → it gets the deprecation notice and must
 *       type the same command again within the same window; then the action runs
 *       after the configured delay. No repeat → timeout notice.</li>
 * </ul>
 */
public final class VanillaPowerCommand extends Command {

    private static final int DEFAULT_CONFIRM_SECONDS = 15;
    private static final int DEFAULT_EXECUTE_DELAY_TICKS = 20;
    private static final long MAX_CONFIRM_SECONDS = 3600L;

    private static final String K_DEPRECATED = "power.vanilla_deprecated";
    private static final String K_CONSOLE_REQUEST = "power.vanilla_console_request";
    private static final String K_PLAYER_REQUEST_SENT = "power.vanilla_player_request_sent";
    private static final String K_CONSOLE_CONFIRM = "power.vanilla_console_confirm";
    private static final String K_ALREADY_SUBMITTED = "power.vanilla_already_submitted";
    private static final String K_ALREADY_PENDING = "power.vanilla_already_pending";
    private static final String K_CONFIRMED_CONSOLE = "power.vanilla_confirmed_console";
    private static final String K_CONFIRMED_PLAYER = "power.vanilla_confirmed_player";
    private static final String K_TIMEOUT = "power.vanilla_timeout";
    private static final String K_SCHEDULED = "power.vanilla_already_scheduled";

    private static final String D_DEPRECATED =
            "<yellow>⚠</yellow> <gray>The <white>%command%</white> command is deprecated. Use "
            + "<hover:show_text:'<gray>Click to insert /ui power off into chat</gray>'><click:suggest_command:'/ui power off'><white>/ui power off</white></click></hover>"
            + " <dark_gray>|</dark_gray> "
            + "<hover:show_text:'<gray>Click to insert /ui power reboot into chat</gray>'><click:suggest_command:'/ui power reboot'><white>/ui power reboot</white></click></hover>"
            + " instead.</gray>";
    private static final String D_CONSOLE_REQUEST =
            "<yellow>%player%</yellow> <gray>requested</gray> <white>%command%</white><gray>. Type the same command within</gray> <white>%seconds% s</white> <gray>to confirm.</gray>";
    private static final String D_PLAYER_REQUEST_SENT =
            "<gray>Request sent to the console. It has <white>%seconds% s</white> to confirm with <white>%command%</white>.</gray>";
    private static final String D_CONSOLE_CONFIRM =
            "<gray>Type <white>%command%</white> again within <white>%seconds% s</white> to confirm. Otherwise the action is cancelled.</gray>";
    private static final String D_ALREADY_SUBMITTED =
            "<yellow>⚠</yellow> <gray>The request is already submitted — only the console can confirm it.</gray>";
    private static final String D_ALREADY_PENDING =
            "<yellow>⚠</yellow> <gray>Another power request (<white>%command%</white>) is already pending — wait for it to finish or expire.</gray>";
    private static final String D_CONFIRMED_CONSOLE =
            "<green>✔</green> <gray>Confirmed. The server will be shut down/restarted in <white>%ticks% ticks</white>.</gray>";
    private static final String D_CONFIRMED_PLAYER =
            "<green>✔</green> <gray>The console confirmed the request — the server will be shut down/restarted in <white>%ticks% ticks</white>.</gray>";
    private static final String D_TIMEOUT =
            "<red>⌛</red> <gray>Confirmation for <white>%command%</white> expired — the action was cancelled.</gray>";
    private static final String D_SCHEDULED =
            "<yellow>⚠</yellow> <gray>A confirmed power action is already scheduled to run.</gray>";

    /** The single pending/confirmed power request. */
    private static Pending pending;
    private static boolean executionScheduled;

    private final boolean restart;

    public VanillaPowerCommand(String name, boolean restart) {
        super(name);
        this.restart = restart;
        setDescription(restart
                ? "Restart the server (deprecated, use /ui power reboot)"
                : "Stop the server (deprecated, use /ui power off)");
        setUsage("/" + name);
    }

    /** Drops pending/scheduled state — called when the commands are re-registered on reload. */
    public static void reset() {
        synchronized (VanillaPowerCommand.class) {
            if (pending != null) {
                cancelTimeout(pending);
                pending = null;
            }
            executionScheduled = false;
        }
    }

    @Override
    public boolean execute(CommandSender sender, String label, String[] args) {
        return handle(sender, restart, "/" + label);
    }

    /**
     * Shared entry point for the CommandMap override and the namespaced listener.
     *
     * @param sender  command sender (player or console/RCON)
     * @param restart {@code true} for /restart, {@code false} for /stop
     * @param raw     display form of the command
     */
    public static boolean handle(CommandSender sender, boolean restart, String raw) {
        String permission = restart ? Permissions.CMD_POWER_REBOOT : Permissions.CMD_POWER_OFF;
        if (sender instanceof Player player && !player.hasPermission(permission)) {
            CommandErrors.noPermission(player, permission);
            return true;
        }

        String command = "/" + (restart ? "restart" : "stop");
        boolean nonPlayer = !(sender instanceof Player);

        synchronized (VanillaPowerCommand.class) {
            if (executionScheduled) {
                sender.sendMessage(prefixed(K_SCHEDULED, D_SCHEDULED));
                return true;
            }

            // Do not race the /ui power request/countdown that PowerManager owns.
            PowerManager uiPower = PowerManager.getInstance();
            if (uiPower != null && (uiPower.hasPendingRequest() || uiPower.hasActiveCountdown())) {
                sender.sendMessage(prefixed(K_ALREADY_PENDING, D_ALREADY_PENDING,
                        "%command%", command));
                return true;
            }

            if (pending != null) {
                if (pending.restart() != restart) {
                    sender.sendMessage(prefixed(K_ALREADY_PENDING, D_ALREADY_PENDING,
                            "%command%", "/" + (pending.restart() ? "restart" : "stop")));
                    return true;
                }

                if (nonPlayer) {
                    confirm(sender, command);
                    return true;
                }

                sender.sendMessage(prefixed(K_ALREADY_SUBMITTED, D_ALREADY_SUBMITTED));
                return true;
            }

            // Fresh request.
            pending = new Pending(restart, command,
                    sender instanceof Player p ? p.getUniqueId() : null,
                    sender instanceof Player p ? p.getName() : null);
            scheduleTimeout(pending);
            scheduleConfirm(pending);
        }
        return true;
    }

    // =========================
    // CONFIRM / TIMEOUT
    // =========================
    private static void confirm(CommandSender confirmer, String command) {
        int ticks = executeDelayTicks();
        Pending confirmed = pending;
        pending = null;
        if (confirmed == null) return;
        cancelTimeout(confirmed);
        executionScheduled = true;

        Component consoleMsg = prefixed(K_CONFIRMED_CONSOLE, D_CONFIRMED_CONSOLE,
                "%ticks%", String.valueOf(ticks));
        Bukkit.getConsoleSender().sendMessage(consoleMsg);
        if (confirmer != Bukkit.getConsoleSender()) {
            confirmer.sendMessage(consoleMsg);
        }

        if (confirmed.initiatorId() != null) {
            Player initiator = Bukkit.getPlayer(confirmed.initiatorId());
            if (initiator != null && initiator.isOnline()) {
                initiator.sendMessage(prefixed(K_CONFIRMED_PLAYER, D_CONFIRMED_PLAYER,
                        "%ticks%", String.valueOf(ticks)));
            }
        }

        Bukkit.getScheduler().runTaskLater(Main.getInstance(),
                () -> execute(confirmed.restart()), Math.max(1L, ticks));
    }

    private static void scheduleTimeout(Pending request) {
        int seconds = confirmSeconds();
        request.timeoutTask = Bukkit.getScheduler().runTaskLater(Main.getInstance(), () -> {
            synchronized (VanillaPowerCommand.class) {
                if (pending != request) return;
                pending = null;
            }
            Component msg = prefixed(K_TIMEOUT, D_TIMEOUT, "%command%", request.command());
            Bukkit.getConsoleSender().sendMessage(msg);
            if (request.initiatorId() != null) {
                Player initiator = Bukkit.getPlayer(request.initiatorId());
                if (initiator != null && initiator.isOnline()) {
                    initiator.sendMessage(msg);
                }
            }
        }, Math.max(1L, seconds * 20L));
    }

    private static void cancelTimeout(Pending request) {
        if (request.timeoutTask != null) {
            request.timeoutTask.cancel();
            request.timeoutTask = null;
        }
    }

    private static void execute(boolean restart) {
        String action = restart
                ? MessagesManager.getString("power.action_restart", "restarting")
                : MessagesManager.getString("power.action_stop", "shutting down");
        Broadcast.sendWithConsole(MessagesManager.getString("power.executing",
                "<dark_gray>[<dark_red>⚠</dark_red>]</dark_gray> <red>Server %action%...</red>")
                .replace("%action%", action));

        Bukkit.getScheduler().runTaskLater(Main.getInstance(), () -> {
            CoreHooks.grantShutdownToAllOnline();
            if (restart) {
                Bukkit.getServer().restart();
            } else {
                Bukkit.getServer().shutdown();
            }
        }, 1L);
    }

    // =========================
    // NOTICES
    // =========================
    private static void scheduleConfirm(Pending request) {
        // Deprecation notice goes to the initiator and to the console.
        String command = request.command();
        if (request.initiatorId() != null) {
            Player initiator = Bukkit.getPlayer(request.initiatorId());
            if (initiator != null && initiator.isOnline()) {
                initiator.sendMessage(prefixed(K_DEPRECATED, D_DEPRECATED, "%command%", command));
                initiator.sendMessage(prefixed(K_PLAYER_REQUEST_SENT, D_PLAYER_REQUEST_SENT,
                        "%seconds%", String.valueOf(confirmSeconds()),
                        "%command%", command));
            }
            Bukkit.getConsoleSender().sendMessage(prefixed(K_DEPRECATED, D_DEPRECATED, "%command%", command));
            Bukkit.getConsoleSender().sendMessage(prefixed(K_CONSOLE_REQUEST, D_CONSOLE_REQUEST,
                    "%player%", request.initiatorName() == null ? "?" : request.initiatorName(),
                    "%command%", command,
                    "%seconds%", String.valueOf(confirmSeconds())));
        } else {
            Bukkit.getConsoleSender().sendMessage(prefixed(K_DEPRECATED, D_DEPRECATED, "%command%", command));
            Bukkit.getConsoleSender().sendMessage(prefixed(K_CONSOLE_CONFIRM, D_CONSOLE_CONFIRM,
                    "%command%", command,
                    "%seconds%", String.valueOf(confirmSeconds())));
        }
    }

    // =========================
    // CONFIG / HELPERS
    // =========================
    private static int confirmSeconds() {
        int configured = Main.getInstance().getConfig()
                .getInt("power.vanilla_confirm_seconds", DEFAULT_CONFIRM_SECONDS);
        return (int) Math.min(Math.max(configured, 1L), MAX_CONFIRM_SECONDS);
    }

    private static int executeDelayTicks() {
        return Math.max(1, Main.getInstance().getConfig()
                .getInt("power.vanilla_execute_delay_ticks", DEFAULT_EXECUTE_DELAY_TICKS));
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
        return List.of();
    }

    /** Pending request state. */
    private static final class Pending {
        private final boolean restart;
        private final String command;
        private final UUID initiatorId;
        private final String initiatorName;
        private BukkitTask timeoutTask;

        private Pending(boolean restart, String command, UUID initiatorId,
                        String initiatorName) {
            this.restart = restart;
            this.command = command;
            this.initiatorId = initiatorId;
            this.initiatorName = initiatorName;
        }

        private boolean restart() { return restart; }
        private String command() { return command; }
        private UUID initiatorId() { return initiatorId; }
        private String initiatorName() { return initiatorName; }
    }
}
