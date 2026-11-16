package com.ultimateimprovements.command.subcommands;

import com.ultimateimprovements.command.CommandErrors;
import com.ultimateimprovements.command.PowerManager;
import com.ultimateimprovements.command.SubCommand;
import com.ultimateimprovements.config.MessagesManager;
import com.ultimateimprovements.core.Permissions;
import com.ultimateimprovements.core.SubCommandInfo;
import com.ultimateimprovements.restart.RestartSchedule;
import com.ultimateimprovements.restart.ScheduledRestartManager;
import com.ultimateimprovements.util.MessageUtil;
import org.bukkit.command.CommandSender;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

/**
 * {@code /ui autorestart on|off|status|skip|undoskip|reset} — in-game control of
 * the automatic restart schedule.
 * <p>
 * The command itself is exposed through the existing {@code /ui} root, which is
 * registered with the modern Paper/Brigadier command API
 * ({@code PaperCommands} + {@code LifecycleEvents.COMMANDS}); this class plugs
 * into {@link com.ultimateimprovements.command.SubCommandRegistry}, and its
 * {@link #tabComplete} feeds Brigadier's suggestion provider.
 * <p>
 * Permissions (registered in {@code Permissions}, not plugin.yml):
 * <ul>
 *   <li>{@code ui.command.autorestart.toggle} — on/off (OP by default);</li>
 *   <li>{@code ui.command.autorestart.status} — read-only status (everyone);</li>
 *   <li>{@code ui.command.autorestart.skip} — skip the next run (OP);</li>
 *   <li>{@code ui.command.autorestart.undoskip} — cancel the skip (OP);</li>
 *   <li>{@code ui.command.autorestart.reset} — restart the timer (OP).</li>
 * </ul>
 */
@SubCommandInfo(name = "autorestart")
public final class AutorestartSubcommand implements SubCommand {

    private static final String USAGE = "<gray>Usage:</gray> <white>/ui autorestart on|off|status|skip|undoskip|reset</white>";

    @Override
    public String getName() {
        return "autorestart";
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (args.length < 2) {
            send(sender, USAGE);
            return true;
        }

        return switch (args[1].toLowerCase(Locale.ROOT)) {
            case "on" -> setEnabled(sender, true);
            case "off" -> setEnabled(sender, false);
            case "status" -> status(sender);
            case "skip" -> skip(sender, true);
            case "undoskip", "unskip" -> skip(sender, false);
            case "reset" -> reset(sender);
            default -> {
                send(sender, USAGE);
                yield true;
            }
        };
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 2) {
            return List.of("on", "off", "status", "skip", "undoskip", "reset");
        }
        return List.of();
    }

    // =========================
    // SUBCOMMANDS
    // =========================

    private boolean setEnabled(CommandSender sender, boolean enabled) {
        if (!sender.hasPermission(Permissions.CMD_AUTORESTART_TOGGLE)) {
            CommandErrors.noPermission(sender, Permissions.CMD_AUTORESTART_TOGGLE);
            return true;
        }

        ScheduledRestartManager schedule = ScheduledRestartManager.getInstance();
        if (schedule == null) {
            send(sender, MessagesManager.getString("power.autorestart_unavailable",
                    "<red>Automatic restart is unavailable.</red>"));
            return true;
        }

        if (!schedule.setEnabled(enabled)) {
            send(sender, MessagesManager.getString("power.autorestart_unavailable",
                    "<red>Automatic restart is unavailable.</red>"));
            return true;
        }

        String key = enabled ? "power.autorestart_enabled" : "power.autorestart_disabled";
        String def = enabled
                ? "<green>✔ <white>Automatic restart is now <green>enabled</green>.</white>"
                : "<green>✔ <white>Automatic restart is now <red>disabled</red>.</white>";
        send(sender, MessagesManager.getString(key, def));
        return true;
    }

    private boolean status(CommandSender sender) {
        if (!sender.hasPermission(Permissions.CMD_AUTORESTART_STATUS)) {
            CommandErrors.noPermission(sender, Permissions.CMD_AUTORESTART_STATUS);
            return true;
        }

        ScheduledRestartManager schedule = ScheduledRestartManager.getInstance();
        if (schedule == null || !schedule.isEnabled() || schedule.getNextRestart() == null) {
            send(sender, MessagesManager.getString("power.autorestart_status_off",
                    "<gray>Automatic restart is <red>off</red>.</gray>"));
            return true;
        }

        PowerManager power = PowerManager.getInstance();
        if (power != null && power.isScheduledCountdownActive()) {
            String remaining = RestartSchedule.formatDuration(
                    Duration.ofSeconds(power.getCountdownRemainingSeconds()));
            send(sender, MessagesManager.getString("power.scheduled_running",
                    "<red>%action% in</red> <yellow>%remaining%</yellow><red>.</red>")
                    .replace("%action%", power.getScheduledActionTitle())
                    .replace("%remaining%", remaining));
            return true;
        }

        LocalDateTime next = schedule.getNextRestart();
        String remaining = RestartSchedule.formatDuration(
                Duration.between(LocalDateTime.now(schedule.getZone()), next));
        send(sender, MessagesManager.getString("power.autorestart_status",
                "<gray>Next automatic restart:</gray> <white>%time%</white> <gray>(in</gray> <yellow>%remaining%</yellow><gray>)</gray>")
                .replace("%time%", schedule.describeNext())
                .replace("%remaining%", remaining));

        if (schedule.isSkipPending()) {
            send(sender, MessagesManager.getString("power.autorestart_skip_pending",
                    "<gray>ℹ The next run will be skipped — use</gray> <white>/ui autorestart undoskip</white><gray>.</gray>"));
        }
        return true;
    }

    private boolean skip(CommandSender sender, boolean skip) {
        String permission = skip ? Permissions.CMD_AUTORESTART_SKIP : Permissions.CMD_AUTORESTART_UNDOSKIP;
        if (!sender.hasPermission(permission)) {
            CommandErrors.noPermission(sender, permission);
            return true;
        }

        ScheduledRestartManager schedule = ScheduledRestartManager.getInstance();
        if (schedule == null) {
            send(sender, MessagesManager.getString("power.autorestart_unavailable",
                    "<red>Automatic restart is unavailable.</red>"));
            return true;
        }

        if (skip) {
            if (!schedule.setSkipPending(true)) {
                send(sender, MessagesManager.getString("power.autorestart_status_off",
                        "<gray>Automatic restart is <red>off</red>.</gray>"));
                return true;
            }
            send(sender, MessagesManager.getString("power.autorestart_skip_set",
                    "<green>✔ <white>The next automatic restart will be skipped.</white>"));
            return true;
        }

        if (!schedule.isSkipPending()) {
            send(sender, MessagesManager.getString("power.autorestart_undoskip_none",
                    "<gray>There is no pending skip.</gray>"));
            return true;
        }
        schedule.setSkipPending(false);
        send(sender, MessagesManager.getString("power.autorestart_undoskip_done",
                "<green>✔ <white>The pending skip was cancelled.</white>"));
        return true;
    }

    private boolean reset(CommandSender sender) {
        if (!sender.hasPermission(Permissions.CMD_AUTORESTART_RESET)) {
            CommandErrors.noPermission(sender, Permissions.CMD_AUTORESTART_RESET);
            return true;
        }

        ScheduledRestartManager schedule = ScheduledRestartManager.getInstance();
        if (schedule == null || !schedule.resetSchedule()) {
            send(sender, MessagesManager.getString("power.autorestart_status_off",
                    "<gray>Automatic restart is <red>off</red>.</gray>"));
            return true;
        }

        LocalDateTime next = schedule.getNextRestart();
        String remaining = next == null ? "" : RestartSchedule.formatDuration(
                Duration.between(LocalDateTime.now(schedule.getZone()), next));
        send(sender, MessagesManager.getString("power.autorestart_reset",
                "<green>✔ <white>Restart timer reset. Next run:</white> <yellow>%time%</yellow> <gray>(in %remaining%)</gray>")
                .replace("%time%", schedule.describeNext() == null ? "-" : schedule.describeNext())
                .replace("%remaining%", remaining));
        return true;
    }

    // =========================
    // HELPERS
    // =========================

    /** Replies always carry the plugin prefix. */
    private void send(CommandSender sender, String miniMessage) {
        sender.sendMessage(MessageUtil.parse(MessageUtil.PREFIX + miniMessage));
    }
}
