package com.ultimateimprovements.mechanics.features.netherportal;

import com.ultimateimprovements.command.CommandErrors;
import com.ultimateimprovements.command.SubCommand;
import com.ultimateimprovements.config.MessagesManager;
import com.ultimateimprovements.core.Permissions;
import com.ultimateimprovements.mechanics.features.netherportal.NetherPortalManager.Target;
import com.ultimateimprovements.util.MessageUtil;
import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code /ui netherportal on|off|mode|status} — in-game control of the nether
 * portal breaking mechanic.
 * <p>
 * Permissions (registered in {@code Permissions}, not plugin.yml):
 * <ul>
 *   <li>{@code ui.command.netherportal.toggle} — on/off (OP by default);</li>
 *   <li>{@code ui.command.netherportal.mode} — player/entity target (OP);</li>
 *   <li>{@code ui.command.netherportal.status} — read-only status (everyone).</li>
 * </ul>
 * Errors use the shared {@link CommandErrors} codes: 001 for bad syntax, 002
 * for a missing permission, 006 for an invalid mode and 018 when the module is
 * not initialised.
 */
public final class NetherPortalSubcommand implements SubCommand {

    /** 006 — command-specific: unsupported target mode. */
    private static final int ERR_INVALID_MODE = 6;

    @Override
    public String getName() {
        return "netherportal";
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (args.length < 2) {
            CommandErrors.syntaxError(sender);
            send(sender, usage());
            return true;
        }

        return switch (args[1].toLowerCase(Locale.ROOT)) {
            case "on" -> setEnabled(sender, true);
            case "off" -> setEnabled(sender, false);
            case "mode" -> setMode(sender, args);
            case "status" -> status(sender);
            default -> {
                CommandErrors.unknownCommand(sender, args[1]);
                send(sender, usage());
                yield true;
            }
        };
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 2) {
            List<String> out = new ArrayList<>(3);
            if (sender.hasPermission(Permissions.CMD_NETHERPORTAL_TOGGLE)) {
                out.add("on");
                out.add("off");
            }
            if (sender.hasPermission(Permissions.CMD_NETHERPORTAL_MODE)) out.add("mode");
            if (sender.hasPermission(Permissions.CMD_NETHERPORTAL_STATUS)) out.add("status");
            return out;
        }
        if (args.length == 3 && args[1].equalsIgnoreCase("mode")
                && sender.hasPermission(Permissions.CMD_NETHERPORTAL_MODE)) {
            return List.of("player", "entity");
        }
        return List.of();
    }

    // =========================
    // SUBCOMMANDS
    // =========================

    private boolean setEnabled(CommandSender sender, boolean enabled) {
        if (!require(sender, Permissions.CMD_NETHERPORTAL_TOGGLE)) return true;

        NetherPortalManager manager = NetherPortalManager.getInstance();
        if (manager == null) {
            CommandErrors.moduleDisabled(sender, "nether_portal");
            return true;
        }

        if (!manager.setEnabled(enabled)) {
            CommandErrors.moduleDisabled(sender, "nether_portal");
            return true;
        }

        String key = enabled ? "nether_portal.enabled" : "nether_portal.disabled";
        String def = enabled
                ? "<green>✔ <white>Nether portal breaking is now <green>enabled</green>.</white>"
                : "<green>✔ <white>Nether portal breaking is now <red>disabled</red>.</white>";
        send(sender, MessagesManager.getString(key, def));
        return true;
    }

    private boolean setMode(CommandSender sender, String[] args) {
        if (!require(sender, Permissions.CMD_NETHERPORTAL_MODE)) return true;

        if (args.length < 3) {
            CommandErrors.syntaxError(sender);
            send(sender, usage());
            return true;
        }

        Target target = parseTarget(args[2]);
        if (target == null) {
            CommandErrors.custom(sender, ERR_INVALID_MODE,
                    MessagesManager.getString("nether_portal.invalid_mode",
                                    "<red>Invalid mode: <white>%mode%</white>. Use <white>player</white> or <white>entity</white>.</red>")
                            .replace("%mode%", args[2]));
            return true;
        }

        NetherPortalManager manager = NetherPortalManager.getInstance();
        if (manager == null) {
            CommandErrors.moduleDisabled(sender, "nether_portal");
            return true;
        }

        if (!manager.setTarget(target)) {
            CommandErrors.moduleDisabled(sender, "nether_portal");
            return true;
        }

        send(sender, MessagesManager.getString("nether_portal.mode_changed",
                        "<green>✔ <white>Target changed to <yellow>%mode%</yellow>.</white>")
                .replace("%mode%", target == Target.PLAYERS ? "player" : "entity"));
        return true;
    }

    private boolean status(CommandSender sender) {
        if (!require(sender, Permissions.CMD_NETHERPORTAL_STATUS)) return true;

        NetherPortalManager manager = NetherPortalManager.getInstance();
        if (manager == null) {
            CommandErrors.moduleDisabled(sender, "nether_portal");
            return true;
        }

        String state = manager.isEnabled()
                ? "<green>ON</green>"
                : "<red>OFF</red> <dark_gray>[nether_portal] enabled = false</dark_gray>";
        String target = manager.getTarget() == Target.PLAYERS ? "players" : "entities";
        String gamerules = manager.describeGamerules();

        send(sender, MessagesManager.getString("nether_portal.status_header",
                "<dark_gray>────────── <white>Nether portal</white> <dark_gray>──────────</dark_gray>"));
        send(sender, MessagesManager.getString("nether_portal.status_state",
                        "<gray>State: </gray>%state%")
                .replace("%state%", state));
        send(sender, MessagesManager.getString("nether_portal.status_target",
                        "<gray>Target: </gray><white>%target%</white><gray> · check every </gray><white>%interval%t</white>")
                .replace("%target%", target)
                .replace("%interval%", String.valueOf(manager.getCheckIntervalTicks())));
        send(sender, MessagesManager.getString("nether_portal.status_worlds",
                        "<gray>Worlds: </gray>%worlds%")
                .replace("%worlds%", manager.describeWorlds()));
        send(sender, MessagesManager.getString("nether_portal.status_gamerules",
                        "<gray>Gamerules: </gray>%gamerules%<gray> · every </gray><white>%interval%t</white>")
                .replace("%gamerules%", gamerules)
                .replace("%interval%", String.valueOf(manager.getGameruleIntervalTicks())));
        send(sender, MessagesManager.getString("nether_portal.status_broken",
                        "<gray>Portals broken since reload: </gray><yellow>%count%</yellow>")
                .replace("%count%", String.valueOf(manager.getBrokenPortals())));
        return true;
    }

    // =========================
    // HELPERS
    // =========================

    private Target parseTarget(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "player", "players" -> Target.PLAYERS;
            case "entity", "entities" -> Target.ENTITIES;
            default -> null;
        };
    }

    private boolean require(CommandSender sender, String permission) {
        if (sender.hasPermission(permission)) return true;
        CommandErrors.noPermission(sender, permission);
        return false;
    }

    private String usage() {
        return MessagesManager.getString("nether_portal.usage",
                "<gray>Usage:</gray> <white>/ui netherportal on|off|mode player|entity|status</white>");
    }

    /** Replies always carry the plugin prefix. */
    private void send(CommandSender sender, String miniMessage) {
        sender.sendMessage(MessageUtil.parse(MessageUtil.PREFIX + miniMessage));
    }
}
