package com.ultimateimprovements.command.subcommands;

import com.ultimateimprovements.command.CommandErrors;

import com.ultimateimprovements.config.MessagesManager;
import com.ultimateimprovements.core.Main;
import com.ultimateimprovements.core.Permissions;
import com.ultimateimprovements.module.meteor.MeteorCooldown;
import com.ultimateimprovements.module.meteor.MeteorModule;
import com.ultimateimprovements.util.MessageUtil;
import org.bukkit.command.CommandSender;

import java.util.List;
import java.util.Locale;

/**
 * {@code /ui meteor spawn|status|skip|unskip|reset} — meteor shower control.
 *
 * <p>Permissions (registered in {@code Permissions}, not plugin.yml):</p>
 * <ul>
 *   <li>{@code ui.command.meteor.status} — time until the next meteor (everyone);</li>
 *   <li>{@code ui.command.meteor.spawn} — force-spawn meteors (OP);</li>
 *   <li>{@code ui.command.meteor.skip} — skip the next meteor (OP);</li>
 *   <li>{@code ui.command.meteor.unskip} — cancel a pending skip (OP);</li>
 *   <li>{@code ui.command.meteor.reset} — reset the spawn timer (OP).</li>
 * </ul>
 *
 * <p>The module must be enabled in the config ({@code [meteor] enabled = true}
 * in {@code configs/UI-World.toml}); texts live in {@code [messages.meteor]} of
 * the same file.</p>
 */
public final class MeteorSubcommand {

    private static final String DEFAULT_USAGE =
            "<red>❌ Usage: </red><white>/ui meteor spawn [count]|status|skip|unskip|reset</white>";

    private MeteorSubcommand() {}

    /**
     * /ui meteor <spawn [count]|status|skip|unskip|reset>
     */
    public static boolean execute(CommandSender sender, String[] args) {
        if (args.length < 2) {
            send(sender, "usage", DEFAULT_USAGE);
            return true;
        }

        String sub = args[1].toLowerCase(Locale.ROOT);
        return switch (sub) {
            case "spawn" -> handleSpawn(sender, args);
            case "status" -> handleStatus(sender);
            case "skip" -> handleSkip(sender);
            case "unskip" -> handleUnskip(sender);
            case "reset" -> handleReset(sender);
            default -> {
                send(sender, "unknown_sub",
                        "<red>❌ Unknown meteor subcommand: </red><white>%sub%</white>", "%sub%", sub);
                send(sender, "usage", DEFAULT_USAGE);
                yield true;
            }
        };
    }

    public static List<String> tabComplete(String[] args) {
        if (args.length == 2) {
            return List.of("spawn", "status", "skip", "unskip", "reset");
        }
        if (args.length == 3 && args[1].equalsIgnoreCase("spawn")) {
            return List.of("1", "2", "3", "5", "10");
        }
        return List.of();
    }

    // =========================
    // SUBCOMMANDS
    // =========================

    private static boolean handleSpawn(CommandSender sender, String[] args) {
        if (!sender.hasPermission(Permissions.CMD_METEOR_SPAWN)) {
            CommandErrors.noPermission(sender, Permissions.CMD_METEOR_SPAWN);
            return true;
        }

        int count = 1;
        if (args.length > 2) {
            try {
                count = Integer.parseInt(args[2]);
                if (count < 1) count = 1;
                if (count > 100) count = 100;
            } catch (NumberFormatException e) {
                send(sender, "invalid_count",
                        "<red>❌ Invalid count! Usage: </red><white>/ui meteor spawn [count]</white>");
                return true;
            }
        }

        MeteorModule module = MeteorModule.getInstance();
        if (module == null) {
            send(sender, "module_not_init", "<red>❌ Meteor module is not initialized!</red>");
            return true;
        }

        if (!moduleEnabled()) {
            send(sender, "module_disabled", "<red>❌ Meteor module is disabled in configs/UI-World.toml!</red>");
            return true;
        }

        int spawned = module.spawnMeteors(count);
        if (spawned <= 0) {
            send(sender, "spawn_failed",
                    "<red>❌ Could not spawn a meteor — see the server console for the reason.</red>");
        } else if (spawned < count) {
            send(sender, "spawn_partial",
                    "<yellow>⚠ Spawned </yellow><white>%spawned%</white><yellow> of </yellow><white>%count%</white>"
                            + "<yellow> meteor(s) — active limit reached.</yellow>",
                    "%spawned%", String.valueOf(spawned), "%count%", String.valueOf(count));
        } else {
            send(sender, "spawned",
                    "<green>✔</green> <white>Spawned </white><yellow>%count%</yellow><white> meteor(s)!</white>",
                    "%count%", String.valueOf(spawned));
        }

        return true;
    }

    private static boolean handleStatus(CommandSender sender) {
        if (!sender.hasPermission(Permissions.CMD_METEOR_STATUS)) {
            CommandErrors.noPermission(sender, Permissions.CMD_METEOR_STATUS);
            return true;
        }

        MeteorModule module = MeteorModule.getInstance();
        if (module == null) {
            send(sender, "module_not_init", "<red>❌ Meteor module is not initialized!</red>");
            return true;
        }

        long seconds = module.getSecondsUntilNextMeteor();
        if (seconds < 0L) {
            send(sender, "status_off",
                    "<gray>The next meteor is <red>not scheduled</red>.</gray>");
            return true;
        }

        send(sender, "status",
                "<gray>Next meteor in </gray><yellow>%time%</yellow><gray>.</gray>",
                "%time%", MeteorCooldown.formatSeconds(seconds));
        return true;
    }

    private static boolean handleSkip(CommandSender sender) {
        if (!sender.hasPermission(Permissions.CMD_METEOR_SKIP)) {
            CommandErrors.noPermission(sender, Permissions.CMD_METEOR_SKIP);
            return true;
        }

        MeteorModule module = MeteorModule.getInstance();
        if (module == null) {
            send(sender, "module_not_init", "<red>❌ Meteor module is not initialized!</red>");
            return true;
        }

        if (module.isSkipPending()) {
            send(sender, "skip_already",
                    "<gray>The next meteor is already marked to be skipped.</gray>");
            return true;
        }
        if (!module.skipNextMeteor()) {
            send(sender, "module_disabled", "<red>❌ Meteor module is disabled in configs/UI-World.toml!</red>");
            return true;
        }

        send(sender, "skip_set",
                "<green>✔ <white>The next meteor will be skipped and the timer will start over.</white>");
        return true;
    }

    private static boolean handleUnskip(CommandSender sender) {
        if (!sender.hasPermission(Permissions.CMD_METEOR_UNSKIP)) {
            CommandErrors.noPermission(sender, Permissions.CMD_METEOR_UNSKIP);
            return true;
        }

        MeteorModule module = MeteorModule.getInstance();
        if (module == null) {
            send(sender, "module_not_init", "<red>❌ Meteor module is not initialized!</red>");
            return true;
        }

        if (!module.cancelSkipNextMeteor()) {
            send(sender, "unskip_none", "<gray>There is no pending meteor skip.</gray>");
            return true;
        }

        send(sender, "unskip_done",
                "<green>✔ <white>The pending meteor skip was cancelled.</white>");
        return true;
    }

    private static boolean handleReset(CommandSender sender) {
        if (!sender.hasPermission(Permissions.CMD_METEOR_RESET)) {
            CommandErrors.noPermission(sender, Permissions.CMD_METEOR_RESET);
            return true;
        }

        MeteorModule module = MeteorModule.getInstance();
        if (module == null) {
            send(sender, "module_not_init", "<red>❌ Meteor module is not initialized!</red>");
            return true;
        }

        if (!module.resetMeteorTimer()) {
            send(sender, "module_disabled", "<red>❌ Meteor module is disabled in configs/UI-World.toml!</red>");
            return true;
        }

        long seconds = module.getSecondsUntilNextMeteor();
        send(sender, "reset_done",
                "<green>✔ <white>Meteor timer reset. Next meteor in </white><yellow>%time%</yellow><gray>.</gray>",
                "%time%", seconds < 0L ? "-" : MeteorCooldown.formatSeconds(seconds));
        return true;
    }

    // =========================
    // HELPERS
    // =========================

    private static boolean moduleEnabled() {
        return Main.getInstance().getConfig().getBoolean("meteor.enabled", false);
    }

    /** Sends one configurable message, replacing {@code %name%} placeholders first. */
    private static void send(CommandSender sender, String key, String def, String... placeholders) {
        String text = MessagesManager.getString("meteor." + key, def);
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            text = text.replace(placeholders[i], placeholders[i + 1]);
        }
        sender.sendMessage(MessageUtil.parse(text));
    }
}
