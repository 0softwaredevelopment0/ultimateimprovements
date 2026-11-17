package com.ultimateimprovements.command.subcommands;

import com.ultimateimprovements.command.CommandErrors;

import com.ultimateimprovements.command.ChgDimDialogScreen;
import com.ultimateimprovements.config.MessagesManager;
import com.ultimateimprovements.util.MessageUtil;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class ChgDimSubcommand {

    private ChgDimSubcommand() {}

    public static boolean execute(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(MessageUtil.parse(MessagesManager.getString("changedimmension.messages.player_only",
                            "<dark_red>❌</dark_red> <red>Только игрок может использовать эту команду!</red>")));
            return true;
        }
        if (!player.hasPermission("ui.command.chgdim")) {
            CommandErrors.noPermission(player, "ui.command.chgdim");
            return true;
        }
        // Open a Custom Screen (Dialog) to enter the world name
        ChgDimDialogScreen.open(player);
        return true;
    }
}
