package com.ultimateimprovements.command.subcommands;

import com.ultimateimprovements.command.CommandErrors;
import com.ultimateimprovements.whitelist.TimedAccessLists;

import org.bukkit.command.CommandSender;

import java.util.List;

/**
 * 📋 WhitelistSubcommand — handler for /ui whitelist.
 * <p>
 * All logic (instant, delayed and temp operations, list view, tab completion)
 * is shared — see {@link AccessListCommands}.
 */
public final class WhitelistSubcommand {

    private WhitelistSubcommand() {}

    public static boolean execute(CommandSender sender, String[] args) {
        if (!sender.hasPermission("ui.command.whitelist")) {
            CommandErrors.noPermission(sender, "ui.command.whitelist");
            return true;
        }
        return AccessListCommands.execute(sender, TimedAccessLists.AccessList.WHITELIST, args, false);
    }

    public static List<String> tabComplete(String[] args) {
        return AccessListCommands.tabComplete(args);
    }
}
