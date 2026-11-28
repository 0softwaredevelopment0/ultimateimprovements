package com.ultimateimprovements.command.subcommands;

import com.ultimateimprovements.command.CommandErrors;
import com.ultimateimprovements.config.MessagesManager;
import com.ultimateimprovements.core.Permissions;
import com.ultimateimprovements.mechanics.features.integrity.ItemDurabilityUtil;
import com.ultimateimprovements.util.MessageUtil;
import com.ultimateimprovements.util.Registries;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Locale;

/**
 * /ui item durability — durability scaling on the held item.
 * <p>
 * Every operation works on the <b>vanilla</b> {@code damage} component (the old
 * percentage-based integrity system was removed long ago). Two variants for
 * every operation:
 * <ul>
 *   <li><b>mechanics</b> (default) — the vanilla gates apply to reductions:
 *       an unbreakable item loses nothing and the lost points are rolled
 *       through the vanilla Unbreaking chance tables;</li>
 *   <li><b>raw</b> — editor mode ({@code raw} prefix): gates are ignored,
 *       the numbers apply exactly as typed.</li>
 * </ul>
 * All user-facing texts live in the composite config under
 * {@code messages.durability.*} (RU) and {@code messages_en.durability.*} (EN).
 * Permission: {@code ui.command.item.durability} (or the {@code ui.command.item}
 * umbrella), default FALSE.
 */
public final class DuraSubcommand {

    private DuraSubcommand() {}

    public static boolean execute(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(MessageUtil.parse(msg("durability.player_only",
                    "<dark_red>❌ <red>Only a player can use this command!</red>")));
            return true;
        }
        if (!player.hasPermission(Permissions.CMD_ITEM_DURABILITY)
                && !player.hasPermission(Permissions.CMD_ITEM)) {
            CommandErrors.noPermission(player, Permissions.CMD_ITEM_DURABILITY);
            return true;
        }

        String op = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "info";
        switch (op) {
            case "info" -> showInfo(player);
            case "multiply", "divide" -> {
                if (args.length < 3) { sendUsage(player); return true; }
                scale(player, op, args[2], false);
            }
            case "raw" -> {
                if (args.length < 4) { sendUsage(player); return true; }
                scale(player, args[2].toLowerCase(Locale.ROOT), args[3], true);
            }
            default -> sendUsage(player);
        }
        return true;
    }

    private static void scale(Player player, String op, String valueStr, boolean raw) {
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held == null || held.getType() == Material.AIR) {
            player.sendMessage(MessageUtil.parse(msg("durability.hold_item",
                    "<dark_red>❌ <red>You must hold an item in your hand!</red>")));
            return;
        }
        if (ItemDurabilityUtil.getMaxDurability(held) <= 0) {
            player.sendMessage(MessageUtil.parse(msg("durability.no_durability",
                    "<dark_red>❌ <red>This item has no durability!</red>")));
            return;
        }
        if (!op.equals("multiply") && !op.equals("divide")) { sendUsage(player); return; }

        double value;
        try {
            // Accept the Russian decimal comma as well as the dot.
            value = Double.parseDouble(valueStr.trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            player.sendMessage(MessageUtil.parse(msg("durability.invalid_number",
                    "<dark_red>❌ <red>Invalid number: <white>%value%</white></red>")
                    .replace("%value%", valueStr)));
            return;
        }
        if (op.equals("multiply") ? value < 0 : value <= 0) {
            player.sendMessage(MessageUtil.parse(msg(op.equals("multiply")
                            ? "durability.factor_non_negative"
                            : "durability.factor_positive",
                    op.equals("multiply")
                            ? "<dark_red>❌ <red>The number must be 0 or greater!</red>"
                            : "<dark_red>❌ <red>The number must be greater than 0!</red>")));
            return;
        }

        int max = ItemDurabilityUtil.getMaxDurability(held);
        int beforeRemaining = max - ItemDurabilityUtil.getVanillaDamage(held);
        boolean unbreakable = ItemDurabilityUtil.isUnbreakable(held);
        org.bukkit.enchantments.Enchantment unbreaking = Registries.unbreaking();
        int unbreakingLevel = unbreaking != null ? held.getEnchantmentLevel(unbreaking) : 0;

        double result = op.equals("multiply")
                ? (raw ? ItemDurabilityUtil.multiplyItemIntegrity(held, value)
                       : ItemDurabilityUtil.multiplyItemIntegrity(held, value, player))
                : (raw ? ItemDurabilityUtil.divideItemIntegrity(held, value)
                       : ItemDurabilityUtil.divideItemIntegrity(held, value, player));
        if (result < 0) {
            player.sendMessage(MessageUtil.parse(msg("durability.failed",
                    "<dark_red>❌ <red>Failed to change the durability.</red>")));
            return;
        }

        int afterRemaining = max - ItemDurabilityUtil.getVanillaDamage(held);
        String opWord = op.equals("multiply") ? "× " + value : "÷ " + value;
        String body;
        if (afterRemaining <= 0) {
            body = msg("durability.broken",
                    "<red>✖ <white>The item broke (<yellow>%op%</yellow><white>).</white>")
                    .replace("%op%", opWord);
        } else {
            body = msg("durability.scaled",
                    "<green>✔ <white>Durability: <yellow>%before%</yellow><gray> → </gray>"
                            + "<yellow>%after%</yellow><gray>/%max%</gray> "
                            + "<gray>(<white>%percent%%<gray>)</gray>")
                    .replace("%before%", String.valueOf(beforeRemaining))
                    .replace("%after%", String.valueOf(afterRemaining))
                    .replace("%max%", String.valueOf(max))
                    .replace("%percent%", String.format(Locale.ROOT, "%.1f", result));
        }
        if (raw) {
            body += msg("durability.raw_suffix", " <dark_gray>[no gates]");
        }
        player.sendMessage(MessageUtil.parse(body));

        // Mechanics-only feedback for reductions
        boolean reduces = op.equals("multiply") ? value < 1 : value > 1;
        if (!raw && reduces && beforeRemaining > 0 && afterRemaining > 0) {
            if (unbreakable) {
                if (afterRemaining == beforeRemaining) {
                    player.sendMessage(MessageUtil.parse(msg("durability.unbreakable_blocked",
                            "<gray>The item is <aqua>unbreakable</aqua><gray> — wear was blocked.</gray>")));
                }
            } else if (unbreakingLevel > 0) {
                int expected = expectedRemaining(max, beforeRemaining, op, value);
                int saved = afterRemaining - expected;
                if (saved > 0) {
                    player.sendMessage(MessageUtil.parse(msg("durability.unbreaking_saved",
                                    "<gray>Unbreaking <yellow>%level%</yellow> saved "
                                            + "<green>%saved%</green><gray> point(s) (without it "
                                            + "<white>%expected%</white><gray> would remain).</gray>")
                            .replace("%level%", String.valueOf(unbreakingLevel))
                            .replace("%saved%", String.valueOf(saved))
                            .replace("%expected%", String.valueOf(expected))));
                }
            }
        }
    }

    /** Remaining points the scaling would give with no Unbreaking roll. */
    private static int expectedRemaining(int max, int beforeRemaining, String op, double value) {
        double factor = op.equals("multiply") ? value : 1.0 / value;
        return Math.max(0, Math.min(max, (int) Math.round(beforeRemaining * factor)));
    }

    private static void showInfo(Player player) {
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held == null || held.getType() == Material.AIR) {
            player.sendMessage(MessageUtil.parse(msg("durability.hold_item",
                    "<dark_red>❌ <red>You must hold an item in your hand!</red>")));
            return;
        }
        int max = ItemDurabilityUtil.getMaxDurability(held);
        if (max <= 0) {
            player.sendMessage(MessageUtil.parse(msg("durability.no_durability",
                    "<dark_red>❌ <red>This item has no durability!</red>")));
            return;
        }
        int damage = ItemDurabilityUtil.getVanillaDamage(held);
        int unbreakingLevel = Registries.unbreaking() != null
                ? held.getEnchantmentLevel(Registries.unbreaking())
                : held.getEnchantmentLevel(org.bukkit.enchantments.Enchantment.UNBREAKING);

        player.sendMessage(MessageUtil.parse("<gold>═══════════════════════"));
        player.sendMessage(MessageUtil.parse(msg("durability.info_title",
                "<gold>  ✦ <white>Item durability <gray>(/ui item durability)</gray>")));
        player.sendMessage(MessageUtil.parse("<gold>═══════════════════════"));
        player.sendMessage(MessageUtil.parse(msg("durability.info_remaining",
                        "<gray>Remaining: <green>%remaining%</green><gray>/%max% "
                                + "<gray>(<white>%percent%%<gray>)</gray>")
                .replace("%remaining%", String.valueOf(max - damage))
                .replace("%max%", String.valueOf(max))
                .replace("%percent%", String.format(Locale.ROOT,
                        "%.1f", ItemDurabilityUtil.getItemIntegrityPercent(held)))));
        player.sendMessage(MessageUtil.parse(msg("durability.info_unbreakable",
                        "<gray>Unbreakable: <white>%value%</white>")
                .replace("%value%", String.valueOf(ItemDurabilityUtil.isUnbreakable(held)))));
        player.sendMessage(MessageUtil.parse(msg("durability.info_unbreaking",
                        "<gray>Unbreaking level: <white>%level%</white>")
                .replace("%level%", String.valueOf(unbreakingLevel))));
        player.sendMessage(MessageUtil.parse(msg("durability.info_variants",
                "<gray>Variants: <white>multiply|divide <gray>(gates) / "
                        + "<white>raw multiply|divide <gray>(no gates)</gray>")));
        player.sendMessage(MessageUtil.parse("<gold>═══════════════════════"));
    }

    private static void sendUsage(Player player) {
        player.sendMessage(MessageUtil.parse(msg("durability.usage_header", "<dark_red>❌ <red>Usage:</red>")));
        player.sendMessage(MessageUtil.parse(msg("durability.usage_info",
                "<white>/ui item durability <gray>— information")));
        player.sendMessage(MessageUtil.parse(msg("durability.usage_multiply",
                "<white>/ui item durability multiply <gray><factor> <dark_gray>— remaining × factor (with gates)")));
        player.sendMessage(MessageUtil.parse(msg("durability.usage_divide",
                "<white>/ui item durability divide <gray><divisor> <dark_gray>— remaining ÷ divisor (with gates)")));
        player.sendMessage(MessageUtil.parse(msg("durability.usage_raw_multiply",
                "<white>/ui item durability raw multiply <gray><factor> <dark_gray>— no gates")));
        player.sendMessage(MessageUtil.parse(msg("durability.usage_raw_divide",
                "<white>/ui item durability raw divide <gray><divisor> <dark_gray>— no gates")));
    }

    public static List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 2) return List.of("info", "multiply", "divide", "raw");
        if (args.length == 3 && args[1].equalsIgnoreCase("raw")) return List.of("multiply", "divide");
        if (args.length == 3) return List.of(""); // numeric value — suppress player-name fallback
        if (args.length == 4 && args[1].equalsIgnoreCase("raw")) return List.of("");
        return List.of();
    }

    private static String msg(String path, String def) {
        return MessagesManager.getString(path, def);
    }
}
