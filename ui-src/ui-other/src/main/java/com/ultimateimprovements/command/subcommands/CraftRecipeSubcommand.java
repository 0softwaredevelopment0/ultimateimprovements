package com.ultimateimprovements.command.subcommands;

import com.ultimateimprovements.command.CommandErrors;
import com.ultimateimprovements.config.MessagesManager;
import com.ultimateimprovements.util.MessageUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;

import java.util.List;
import java.util.Map;

/**
 * /ui craftrecipe &lt;namespace:key | item name&gt; — admin free-craft.
 * <p>
 * "Crafts" the requested recipe without consuming any resources and puts the
 * result straight into the player's inventory. If the inventory cannot fit the
 * result, the command says so and drops nothing (nothing is lost).
 * <p>
 * The recipe can be addressed by its exact {@code namespace:key} (e.g.
 * {@code minecraft:diamond_sword}, {@code ui:lead_ingot}) or by the item it
 * produces (a material name such as {@code cherry_planks} or a custom item name
 * such as {@code lead_ingot}). One item can be produced by several recipes, so
 * an ambiguous request is never guessed: the command stops, lists every matching
 * recipe and waits for the player to pick one. Tab-complete lists every
 * registered recipe and every result item name.
 * <p>
 * Permission: {@code ui.command.craftrecipe} (admin-only, default OP).
 */
public final class CraftRecipeSubcommand {

    private CraftRecipeSubcommand() {}

    private static final String PERMISSION = "ui.command.craftrecipe";

    public static boolean execute(CommandSender sender, String[] args) {
        if (!sender.hasPermission(PERMISSION)) {
            CommandErrors.noPermission(sender, PERMISSION);
            return true;
        }
        if (!(sender instanceof Player player)) {
            CommandErrors.playerOnly(sender);
            return true;
        }
        if (args.length < 2) {
            player.sendMessage(MessageUtil.parse(MessagesManager.getString(
                    "craftrecipe.usage",
                    "<red>❌ Usage: </red><white>/ui craftrecipe <namespace:key | item name></white> <gray>(tab-complete lists all)</gray>")));
            return true;
        }

        String input = RecipeLookup.selector(args, 1);
        NamespacedKey key;
        Recipe direct = RecipeLookup.byKey(input);
        if (direct != null && direct instanceof Keyed keyed) {
            key = keyed.getKey();
        } else {
            List<RecipeLookup.Entry> matches = RecipeLookup.byResult(input);
            if (matches.isEmpty()) {
                player.sendMessage(notFound(input));
                return true;
            }
            if (matches.size() > 1) {
                // Ambiguous result: show every recipe that makes it and wait for a pick.
                RecipeLookup.sendChooser(player, "craftrecipe", "craftrecipe", input, matches);
                return true;
            }
            key = matches.get(0).key();
        }

        Recipe recipe = Bukkit.getRecipe(key);
        if (recipe == null) {
            player.sendMessage(notFound(input));
            return true;
        }

        ItemStack result = recipe.getResult();
        if (result == null || result.getType().isAir()) {
            player.sendMessage(MessageUtil.parse(MessagesManager.getString(
                    "craftrecipe.no_result", "<red>❌ This recipe has no craftable result.</red>")));
            return true;
        }

        ItemStack toGive = result.clone();
        Map<Integer, ItemStack> overflow = player.getInventory().addItem(toGive);
        int given = toGive.getAmount() - overflow.values().stream()
                .mapToInt(ItemStack::getAmount).sum();

        if (overflow.isEmpty()) {
            player.sendMessage(MessageUtil.parse(MessagesManager.getString(
                            "craftrecipe.success",
                            "<green>✔</green> <white>Crafted </white><yellow>%item%</yellow><white> ×%amount% (no resources used).</white>")
                    .replace("%item%", prettify(result.getType()))
                    .replace("%amount%", String.valueOf(result.getAmount()))));
        } else {
            player.sendMessage(MessageUtil.parse(MessagesManager.getString(
                            "craftrecipe.inventory_full",
                            "<yellow>⚠ Inventory full! Only</yellow> <white>%given%/%total%</white> <yellow>fit — nothing was dropped.</yellow>")
                    .replace("%given%", String.valueOf(given))
                    .replace("%total%", String.valueOf(result.getAmount()))));
        }
        return true;
    }

    private static Component notFound(String input) {
        return MessageUtil.parse(MessagesManager.getString(
                        "craftrecipe.not_found",
                        "<red>❌ Recipe not found: </red><white>%recipe%</white> <gray>(use namespace:key or an item name; tab-complete lists all)</gray>")
                .replace("%recipe%", input));
    }

    /** Tab-complete: every registered recipe key and every result item name. */
    public static List<String> tabComplete(String[] args) {
        if (args.length != 2) return List.of();
        return RecipeLookup.suggest(args[1]);
    }

    private static String prettify(Material material) {
        String[] parts = material.name().toLowerCase().split("_");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
        }
        return sb.toString();
    }
}
