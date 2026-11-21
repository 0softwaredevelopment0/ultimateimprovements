package com.ultimateimprovements.command.subcommands;

import com.ultimateimprovements.command.CommandErrors;
import com.ultimateimprovements.command.SubCommand;
import com.ultimateimprovements.config.MessagesManager;
import com.ultimateimprovements.core.Permissions;
import com.ultimateimprovements.util.MessageUtil;
import org.bukkit.command.CommandSender;

import java.util.List;
import java.util.Locale;

/**
 * /ui language status | set &lt;en|ru&gt; — view or switch the global plugin language.
 * <p>
 * The setting is {@code messages.lang} in the core config ({@code UI-Core.toml})
 * and applies to every addon. {@code set} writes the new value immediately and
 * warns that a restart or {@code /ui reload} is needed for every running
 * component to pick it up.
 * <p>
 * Permission: {@code ui.command.language} (default TRUE — every player may view
 * and change the UI language).
 */
public final class LangSubcommand implements SubCommand {

    @Override
    public String getName() {
        return "language";
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (sender instanceof org.bukkit.entity.Player player
                && !player.hasPermission(Permissions.CMD_LANGUAGE)) {
            CommandErrors.noPermission(player, Permissions.CMD_LANGUAGE);
            return true;
        }

        String action = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "status";
        return switch (action) {
            case "status" -> status(sender);
            case "set" -> set(sender, args);
            default -> {
                sendUsage(sender);
                yield true;
            }
        };
    }

    private static boolean status(CommandSender sender) {
        sender.sendMessage(MessageUtil.parse(MessagesManager.getString("language.status",
                        "<gray>Current UI language: <yellow>%lang%</yellow> <dark_gray>(/ui language set <en|ru>)")
                .replace("%lang%", MessagesManager.currentLanguage())));
        return true;
    }

    private static boolean set(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(MessageUtil.parse(MessagesManager.getString("language.set_usage",
                    "<red>❌ Usage: </red><white>/ui language set <en|ru></white>")));
            return true;
        }

        String requested = args[2].toLowerCase(Locale.ROOT);
        if (!requested.equals("en") && !requested.equals("ru")) {
            sender.sendMessage(MessageUtil.parse(MessagesManager.getString("language.invalid",
                            "<red>❌ Unknown language: </red><yellow>%lang%</yellow>"
                                    + "<red>. Available: </red><white>en</white><red>, </red><white>ru</white>")
                    .replace("%lang%", args[2])));
            return true;
        }

        String previous = MessagesManager.currentLanguage();
        if (previous.equalsIgnoreCase(requested)) {
            sender.sendMessage(MessageUtil.parse(MessagesManager.getString("language.set_same",
                            "<yellow>⚠</yellow> <white>The language is already</white> <yellow>%lang%</yellow><white>.</white>")
                    .replace("%lang%", previous)));
            return true;
        }

        MessagesManager.setLanguage(requested);
        sender.sendMessage(MessageUtil.parse(MessagesManager.getString("language.set_success",
                        "<green>✔</green> <white>Language changed: </white>"
                                + "<gray>%previous% → </gray><yellow>%current%</yellow>")
                .replace("%previous%", previous)
                .replace("%current%", MessagesManager.currentLanguage())));
        sender.sendMessage(MessageUtil.parse(MessagesManager.getString("language.reload_hint",
                "<yellow>⚠</yellow> <gray>Some components pick the language up only after "
                        + "a server restart or <white>/ui reload</white>.</gray>")));
        return true;
    }

    private static void sendUsage(CommandSender sender) {
        sender.sendMessage(MessageUtil.parse(MessagesManager.getString("language.usage",
                "<red>❌ Usage:</red>\n"
                        + "<white>/ui language status</white> <gray>— show the current language</gray>\n"
                        + "<white>/ui language set <en|ru></white> <gray>— change the language</gray>")));
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 2) {
            return List.of("status", "set");
        }
        if (args.length == 3 && args[1].equalsIgnoreCase("set")) {
            return List.of("en", "ru");
        }
        return List.of();
    }
}
