package com.ultimateimprovements.command.subcommands;

import com.ultimateimprovements.command.CommandErrors;
import com.ultimateimprovements.command.SubCommand;
import com.ultimateimprovements.config.MessagesManager;
import com.ultimateimprovements.core.Permissions;
import com.ultimateimprovements.util.MessageUtil;
import org.bukkit.command.CommandSender;

import java.util.List;

/**
 * /ui lang [ru|en] — view or switch the plugin UI language.
 * <p>
 * The setting is global ({@code messages.lang} in the core config) and applies to
 * every addon immediately; messages themselves live per addon in
 * {@code configs/UI-<Addon>.toml} under {@code messages} (RU) and {@code messages_en} (EN).
 *
 * @see com.ultimateimprovements.core.Permissions#CMD_LANG
 */
public final class LangSubcommand implements SubCommand {

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (sender instanceof org.bukkit.entity.Player player
                && !player.hasPermission(Permissions.CMD_LANG)) {
            CommandErrors.noPermission(player, Permissions.CMD_LANG);
            return true;
        }

        if (args.length < 2) {
            String current = MessagesManager.currentLanguage();
            sender.sendMessage(MessageUtil.parse("<gray>Current language: <yellow>" + current
                    + "</yellow> <dark_gray>(/ui lang <ru|en>)"));
            return true;
        }

        String previous = MessagesManager.setLanguage(args[1]);
        if (previous.equalsIgnoreCase(args[1])) {
            sender.sendMessage(MessageUtil.parse("<yellow>Language is already <white>" + previous));
            return true;
        }
        sender.sendMessage(MessageUtil.parse("<green>✔</green> <white>Language switched: </white>"
                + "<gray>" + previous + " → </gray><yellow>" + MessagesManager.currentLanguage()));
        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 2) {
            return List.of("ru", "en");
        }
        return List.of();
    }
}
