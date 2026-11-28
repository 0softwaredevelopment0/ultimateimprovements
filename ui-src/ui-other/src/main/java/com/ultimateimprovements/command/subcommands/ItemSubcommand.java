package com.ultimateimprovements.command.subcommands;

import com.ultimateimprovements.command.SubCommand;
import com.ultimateimprovements.util.MessageUtil;
import org.bukkit.command.CommandSender;

import java.util.List;
import java.util.Locale;

/**
 * /ui item — item data subcommands.
 *
 * <pre>
 *   /ui item durability …   — scale the held item's durability
 *   /ui item nbt …          — edit item data components / NBT
 *   /ui item view &lt;player&gt; [slot] — read-only full item data viewer
 * </pre>
 *
 * <p>The children keep their own permission nodes
 * ({@code ui.command.item.durability}, {@code ui.command.item.nbt},
 * {@code ui.command.item.view}); the umbrella {@code ui.command.item} grants
 * all three. The nested commands are dispatched through the same
 * {@link SubCommand} path as every other /ui command, so tab-complete and the
 * command logger keep working unchanged.</p>
 */
public final class ItemSubcommand implements SubCommand {

    private static final List<String> SUBCOMMANDS = List.of("durability", "nbt", "view");

    /** Reused instance — the nbt implementation holds no state. */
    private static final ItemNbtSubcommand NBT = new ItemNbtSubcommand();

    @Override
    public String getName() {
        return "item";
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sendUsage(sender);
            return true;
        }

        return switch (args[1].toLowerCase(Locale.ROOT)) {
            case "durability" -> DuraSubcommand.execute(sender, shift(args));
            case "nbt" -> NBT.execute(sender, shift(args));
            case "view" -> ItemViewSubcommand.execute(sender, args);
            default -> {
                sendUsage(sender);
                yield true;
            }
        };
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 2) return SUBCOMMANDS;

        if (args.length >= 3) {
            return switch (args[1].toLowerCase(Locale.ROOT)) {
                case "durability" -> DuraSubcommand.tabComplete(sender, shift(args));
                case "nbt" -> NBT.tabComplete(sender, shift(args));
                case "view" -> ItemViewSubcommand.tabComplete(sender, args);
                default -> List.of();
            };
        }
        return List.of();
    }

    /**
     * Drops the leading {@code item} token so a nested command sees the same
     * argument layout it used before the move:
     * {@code item nbt setname X} → {@code nbt setname X}.
     */
    private static String[] shift(String[] args) {
        String[] out = new String[args.length - 1];
        System.arraycopy(args, 1, out, 0, args.length - 1);
        return out;
    }

    private static void sendUsage(CommandSender sender) {
        sender.sendMessage(MessageUtil.parse("<dark_red>❌ <red>Usage:</red>"));
        sender.sendMessage(MessageUtil.parse("<white>/ui item durability <multiply|divide> <value> <gray>— durability scaling"));
        sender.sendMessage(MessageUtil.parse("<white>/ui item durability raw <multiply|divide> <value> <gray>— no vanilla gates"));
        sender.sendMessage(MessageUtil.parse("<white>/ui item nbt <…> <gray>— edit item data components (NBT-level)"));
        sender.sendMessage(MessageUtil.parse("<white>/ui item view <player> [slot] <gray>— full item data viewer"));
    }
}
