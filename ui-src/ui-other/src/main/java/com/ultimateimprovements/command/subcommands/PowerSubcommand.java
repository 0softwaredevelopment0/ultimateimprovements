package com.ultimateimprovements.command.subcommands;

import com.ultimateimprovements.command.CommandErrors;

import com.ultimateimprovements.command.PowerManager;
import com.ultimateimprovements.config.MessagesManager;
import com.ultimateimprovements.core.Permissions;
import com.ultimateimprovements.restart.RestartSchedule;
import com.ultimateimprovements.restart.ScheduledRestartManager;
import com.ultimateimprovements.util.Broadcast;
import com.ultimateimprovements.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.time.LocalDateTime;

public final class PowerSubcommand {

    private PowerSubcommand() {}

    public static boolean execute(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(MessageUtil.parse("<dark_red>❌</dark_red> <red>Error: </red><gray>Usage: /ui power off|reboot|schedule|confirm|undo</gray>"));
            return true;
        }

        PowerManager pm = PowerManager.getInstance();

        return switch (args[1].toLowerCase()) {
            case "off" -> handleOff(sender, pm);
            case "reboot" -> handleReboot(sender, pm);
            case "schedule", "next" -> handleSchedule(sender, pm);
            case "confirm" -> handleConfirm(sender, pm);
            case "undo" -> handleUndo(sender, pm);
            default -> {
                sender.sendMessage(MessageUtil.parse("<dark_red>❌</dark_red> <red>Error: </red><gray>Usage: /ui power off|reboot|schedule|confirm|undo</gray>"));
                yield true;
            }
        };
    }

    private static boolean handleOff(CommandSender sender, PowerManager pm) {
        if (!(sender instanceof Player)) { pm.executeDirect(false); return true; }
        Player player = (Player) sender;
        if (!player.hasPermission("ui.command.power.off")) {
            CommandErrors.noPermission(player, "ui.command.power.off"); return true;
        }
        if (pm.hasPendingRequest()) { sender.sendMessage(MessageUtil.parse("<dark_gray>[<dark_red>⚠</dark_red>]</dark_gray> <red>There is already an active power management request.</red>")); return true; }
        pm.requestStop(player.getName(), player.getUniqueId());
        player.sendMessage(MessageUtil.parse("<dark_gray>[<dark_red>⚠</dark_red>]</dark_gray> <yellow>Server shutdown initiated, waiting for console confirmation.</yellow>"));
        Bukkit.getConsoleSender().sendMessage(MessageUtil.parse("<dark_gray>[<dark_red>⚠</dark_red>]</dark_gray> <yellow>Server shutdown requested by player </yellow><white>" + player.getName() + "</white><yellow>. Confirm with: </yellow><white>/ui power confirm</white>"));
        Bukkit.getConsoleSender().sendMessage(MessageUtil.parse("<dark_gray>[<dark_red>⚠</dark_red>]</dark_gray> <yellow>Cancel: </yellow><white>/ui power undo</white>"));
        Bukkit.getConsoleSender().sendMessage(MessageUtil.parse("<dark_gray>[<dark_red>⚠</dark_red>]</dark_gray> <yellow>Request will be automatically cancelled after </yellow><white>30</white><yellow> seconds.</yellow>"));
        return true;
    }

    private static boolean handleReboot(CommandSender sender, PowerManager pm) {
        if (!(sender instanceof Player)) { pm.executeDirect(true); return true; }
        Player player = (Player) sender;
        if (!player.hasPermission("ui.command.power.reboot")) {
            CommandErrors.noPermission(player, "ui.command.power.reboot"); return true;
        }
        if (pm.hasPendingRequest()) { sender.sendMessage(MessageUtil.parse("<dark_gray>[<dark_red>⚠</dark_red>]</dark_gray> <red>There is already an active power management request.</red>")); return true; }
        pm.requestRestart(player.getName(), player.getUniqueId());
        player.sendMessage(MessageUtil.parse("<dark_gray>[<dark_red>⚠</dark_red>]</dark_gray> <yellow>Server restart initiated, waiting for console confirmation.</yellow>"));
        Bukkit.getConsoleSender().sendMessage(MessageUtil.parse("<dark_gray>[<dark_red>⚠</dark_red>]</dark_gray> <yellow>Server restart requested by player </yellow><white>" + player.getName() + "</white><yellow>. Confirm with: </yellow><white>/ui power confirm</white>"));
        Bukkit.getConsoleSender().sendMessage(MessageUtil.parse("<dark_gray>[<dark_red>⚠</dark_red>]</dark_gray> <yellow>Cancel: </yellow><white>/ui power undo</white>"));
        Bukkit.getConsoleSender().sendMessage(MessageUtil.parse("<dark_gray>[<dark_red>⚠</dark_red>]</dark_gray> <yellow>Request will be automatically cancelled after </yellow><white>30</white><yellow> seconds.</yellow>"));
        return true;
    }

    private static boolean handleSchedule(CommandSender sender, PowerManager pm) {
        if (sender instanceof Player player && !player.hasPermission(Permissions.CMD_POWER_SCHEDULE)) {
            CommandErrors.noPermission(player, Permissions.CMD_POWER_SCHEDULE);
            return true;
        }

        if (pm != null && pm.isScheduledCountdownActive()) {
            String remaining = RestartSchedule.formatDuration(
                    Duration.ofSeconds(pm.getCountdownRemainingSeconds()));
            sender.sendMessage(MessageUtil.parse(MessagesManager.getString("power.scheduled_running",
                    "<dark_gray>[<dark_red>⚠</dark_red>]</dark_gray> <red>Scheduled %action% in</red> <yellow>%remaining%</yellow><red>.</red>")
                    .replace("%action%", pm.getScheduledActionTitle())
                    .replace("%remaining%", remaining)));
            return true;
        }

        ScheduledRestartManager schedule = ScheduledRestartManager.getInstance();
        if (schedule == null || !schedule.isEnabled() || schedule.getNextRestart() == null) {
            sender.sendMessage(MessageUtil.parse(MessagesManager.getString("power.scheduled_none",
                    "<dark_gray>[<dark_green>✔</dark_green>]</dark_gray> <gray>No scheduled restarts are configured.</gray>")));
            return true;
        }

        LocalDateTime next = schedule.getNextRestart();
        String remaining = RestartSchedule.formatDuration(
                Duration.between(LocalDateTime.now(schedule.getZone()), next));
        sender.sendMessage(MessageUtil.parse(MessagesManager.getString("power.scheduled_next",
                "<dark_gray>[<dark_red>⚠</dark_red>]</dark_gray> <gray>Next scheduled restart:</gray> <white>%time%</white> <gray>(in</gray> <yellow>%remaining%</yellow><gray>)</gray>")
                .replace("%time%", schedule.describeNext())
                .replace("%remaining%", remaining)));
        return true;
    }

    private static boolean handleConfirm(CommandSender sender, PowerManager pm) {
        if (sender instanceof Player) { sender.sendMessage(MessageUtil.parse("<dark_red>❌</dark_red> <red>Error: </red><gray>Only console can confirm a request.</gray>")); return true; }
        if (!pm.hasPendingRequest()) { sender.sendMessage(MessageUtil.parse("<dark_gray>[<dark_red>⚠</dark_red>]</dark_gray> <red>No active shutdown/restart requests.</red>")); return true; }
        String action = pm.getCurrentRequestType() == PowerManager.RequestType.STOP ? "Shutdown" : "Restart";
        String requester = pm.getRequesterName();
        if (pm.confirmRequest()) {
            sender.sendMessage(MessageUtil.parse("<dark_gray>[<green>✔</green>]</dark_gray> <green>" + action + " confirmed (request from " + requester + ").</green>"));
            Broadcast.send("<dark_gray>[<dark_red>⚠</dark_red>]</dark_gray> <red>" + action + " confirmed by console.</red>");
        } else sender.sendMessage(MessageUtil.parse("<dark_red>❌</dark_red> <red>Error during confirmation.</red>"));
        return true;
    }

    private static boolean handleUndo(CommandSender sender, PowerManager pm) {
        if (!pm.hasPendingRequest() && !pm.isScheduledCountdownActive()) { sender.sendMessage(MessageUtil.parse("<dark_gray>[<dark_red>⚠</dark_red>]</dark_gray> <red>No active shutdown/restart requests.</red>")); return true; }
        if (sender instanceof Player player && !player.hasPermission("ui.command.power.undo")) {
            CommandErrors.noPermission(player, "ui.command.power.undo"); return true;
        }
        String undoerName = sender instanceof Player ? ((Player) sender).getName() : "Console";
        String action = pm.undoRequest(undoerName);
        sender.sendMessage(action != null ? MessageUtil.parse("<dark_gray>[<green>✔</green>]</dark_gray> <green>" + action + " cancelled.</green>") : MessageUtil.parse("<dark_red>❌</dark_red> <red>Error during cancellation.</red>"));
        return true;
    }
}
