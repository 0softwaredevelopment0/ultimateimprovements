package com.ultimateimprovements.command.subcommands;

import com.ultimateimprovements.command.CommandErrors;
import com.ultimateimprovements.config.MessagesManager;
import com.ultimateimprovements.core.Permissions;
import com.ultimateimprovements.mechanics.crafting.RecipeRegistry;
import com.ultimateimprovements.util.MessageUtil;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.CommandSender;
import org.bukkit.inventory.CookingRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.SmithingTransformRecipe;
import org.bukkit.inventory.SmithingTrimRecipe;
import org.bukkit.inventory.StonecuttingRecipe;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * /ui showcraft &lt;namespace:key | item name&gt; — explains how a recipe is made.
 * <p>
 * Shows the result, the station (crafting table, furnace, blast furnace,
 * smoker, campfire, stonecutter, smithing table or the custom Crafter), the
 * ingredient grid/list and the station parameters (e.g. cooking time and
 * experience for a furnace).
 * <p>
 * The recipe can be addressed by its exact {@code namespace:key}, or by the item
 * it produces (a material name such as {@code cherry_planks} or a custom item
 * name such as {@code lead_ingot}). A result can be produced by several recipes,
 * so an ambiguous request is never guessed: the command stops and lists every
 * candidate as a clickable entry. Tab-complete suggests both keys and item names.
 * <p>
 * Permission: {@code ui.command.showcraft} (default OP).
 */
public final class ShowCraftCommand {

    private ShowCraftCommand() {}

    public static boolean execute(CommandSender sender, String[] args) {
        if (!sender.hasPermission(Permissions.CMD_SHOWCRAFT)) {
            CommandErrors.noPermission(sender, Permissions.CMD_SHOWCRAFT);
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(MessageUtil.parse(msg("showcraft.usage",
                    "<red>❌ Usage: </red><white>/ui showcraft <namespace:key | item name></white>"
                            + " <gray>(tab-complete lists all)</gray>")));
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
                sender.sendMessage(MessageUtil.parse(msg("showcraft.not_found",
                                "<red>❌ Recipe not found: </red><white>%recipe%</white>"
                                        + " <gray>(use namespace:key or an item name; tab-complete lists all)</gray>")
                        .replace("%recipe%", input)));
                return true;
            }
            if (matches.size() > 1) {
                // Ambiguous result: show every recipe that makes it and wait for a pick.
                RecipeLookup.sendChooser(sender, "showcraft", "showcraft", input, matches);
                return true;
            }
            key = matches.get(0).key();
        }

        Recipe recipe = Bukkit.getRecipe(key);
        if (recipe == null) {
            sender.sendMessage(MessageUtil.parse(msg("showcraft.not_found",
                            "<red>❌ Recipe not found: </red><white>%recipe%</white>"
                                    + " <gray>(use namespace:key or an item name; tab-complete lists all)</gray>")
                    .replace("%recipe%", input)));
            return true;
        }

        ItemStack result = recipe.getResult();
        if (result == null || result.getType().isAir()) {
            sender.sendMessage(MessageUtil.parse(msg("showcraft.no_result",
                    "<red>❌ This recipe has no craftable result.</red>")));
            return true;
        }

        boolean custom = RecipeRegistry.getCustomRecipes().contains(key);

        sender.sendMessage(MessageUtil.parse("<gold>═══════════════════════════════════════════════════"));
        sender.sendMessage(MessageUtil.parse(msg("showcraft.header",
                        "<gold>  ✦ <white>Recipe </white><dark_gray>%recipe%</dark_gray>")
                .replace("%recipe%", key.toString())));
        sender.sendMessage(MessageUtil.parse("<gold>═══════════════════════════════════════════════════"));

        sender.sendMessage(MessageUtil.parse(msg("showcraft.result",
                        "<gray>Result: <white>%item%</white> <gray>×</gray><yellow>%amount%</yellow>")
                .replace("%item%", itemName(result))
                .replace("%amount%", String.valueOf(result.getAmount()))));

        sender.sendMessage(MessageUtil.parse(msg("showcraft.station",
                        "<gray>Crafted in: <yellow>%station%</yellow>")
                .replace("%station%", RecipeLookup.station(recipe))));
        if (custom) {
            sender.sendMessage(MessageUtil.parse(msg("showcraft.crafter_only",
                    "<gray>Custom item: <red>Crafter block only</red>"
                            + " <gray>— it cannot be made on a crafting table.</gray>")));
        }

        renderContent(sender, recipe);
        return true;
    }

    // =========================
    // CONTENT
    // =========================

    private static void renderContent(CommandSender sender, Recipe recipe) {
        if (recipe instanceof ShapedRecipe shaped) {
            renderShaped(sender, shaped);
        } else if (recipe instanceof ShapelessRecipe shapeless) {
            renderShapeless(sender, shapeless);
        } else if (recipe instanceof CookingRecipe<?> cooking) {
            renderCooking(sender, cooking);
        } else if (recipe instanceof StonecuttingRecipe stonecutting) {
            renderStonecutting(sender, stonecutting);
        } else if (recipe instanceof SmithingTransformRecipe transform) {
            renderSmithing(sender, transform.getTemplate(), transform.getBase(), transform.getAddition());
        } else if (recipe instanceof SmithingTrimRecipe trim) {
            renderSmithing(sender, trim.getTemplate(), trim.getBase(), trim.getAddition());
        }
    }

    private static void renderShaped(CommandSender sender, ShapedRecipe recipe) {
        sender.sendMessage(MessageUtil.parse(msg("showcraft.pattern_header", "<gray>Pattern:</gray>")));
        Map<Character, RecipeChoice> map = recipe.getChoiceMap();
        for (String row : recipe.getShape()) {
            StringBuilder line = new StringBuilder(" <dark_gray>▪</dark_gray> ");
            for (int i = 0; i < row.length(); i++) {
                char c = row.charAt(i);
                if (i > 0) line.append(" <dark_gray>|</dark_gray> ");
                if (c == ' ') {
                    line.append(msg("showcraft.empty_cell", "<dark_gray>·</dark_gray>"));
                } else {
                    line.append(" <white>").append(choiceName(map.get(c)));
                }
            }
            sender.sendMessage(MessageUtil.parse(line.toString()));
        }
        sender.sendMessage(MessageUtil.parse(
                msg("showcraft.grid_hint", "<dark_gray>Each row is a crafting-grid row (3 slots per row).</dark_gray>")));
    }

    private static void renderShapeless(CommandSender sender, ShapelessRecipe recipe) {
        sender.sendMessage(MessageUtil.parse(msg("showcraft.ingredients_header", "<gray>Ingredients:</gray>")));
        for (RecipeChoice ingredient : recipe.getChoiceList()) {
            sender.sendMessage(MessageUtil.parse(msg("showcraft.ingredient",
                            " <dark_gray>▪</dark_gray> <white>%item%</white>")
                    .replace("%item%", choiceName(ingredient))));
        }
    }

    private static void renderCooking(CommandSender sender, CookingRecipe<?> recipe) {
        sender.sendMessage(MessageUtil.parse(msg("showcraft.ingredients_header", "<gray>Ingredients:</gray>")));
        sender.sendMessage(MessageUtil.parse(msg("showcraft.ingredient",
                        " <dark_gray>▪</dark_gray> <white>%item%</white>")
                .replace("%item%", choiceName(recipe.getInputChoice()))));

        sender.sendMessage(MessageUtil.parse(msg("showcraft.params_header", "<gray>Parameters:</gray>")));
        int ticks = recipe.getCookingTime();
        sender.sendMessage(MessageUtil.parse(msg("showcraft.cook_time",
                        "<gray> ▪ Cook time: <yellow>%seconds%s</yellow> <dark_gray>(%ticks% ticks)</dark_gray>")
                .replace("%seconds%", String.format(Locale.ROOT, "%.1f", ticks / 20.0))
                .replace("%ticks%", String.valueOf(ticks))));
        sender.sendMessage(MessageUtil.parse(msg("showcraft.experience",
                        "<gray> ▪ Experience: <yellow>%xp%</yellow>")
                .replace("%xp%", String.format(Locale.ROOT, "%.1f", recipe.getExperience()))));
    }

    private static void renderStonecutting(CommandSender sender, StonecuttingRecipe recipe) {
        sender.sendMessage(MessageUtil.parse(msg("showcraft.ingredients_header", "<gray>Ingredients:</gray>")));
        sender.sendMessage(MessageUtil.parse(msg("showcraft.ingredient",
                        " <dark_gray>▪</dark_gray> <white>%item%</white>")
                .replace("%item%", choiceName(recipe.getInputChoice()))));
    }

    private static void renderSmithing(CommandSender sender,
                                       RecipeChoice template, RecipeChoice base, RecipeChoice addition) {
        sender.sendMessage(MessageUtil.parse(msg("showcraft.ingredients_header", "<gray>Ingredients:</gray>")));
        sender.sendMessage(MessageUtil.parse(msg("showcraft.template",
                        "<gray> ▪ Template: </gray><white>%item%</white>")
                .replace("%item%", choiceName(template))));
        sender.sendMessage(MessageUtil.parse(msg("showcraft.base",
                        "<gray> ▪ Base: </gray><white>%item%</white>")
                .replace("%item%", choiceName(base))));
        sender.sendMessage(MessageUtil.parse(msg("showcraft.addition",
                        "<gray> ▪ Addition: </gray><white>%item%</white>")
                .replace("%item%", choiceName(addition))));
    }

    // =========================
    // HELPERS
    // =========================

    /** Display name of an ingredient choice: a stack, one material or a small list of alternatives. */
    private static String choiceName(RecipeChoice choice) {
        if (choice == null) return msg("showcraft.empty_cell", "<dark_gray>·</dark_gray>");
        if (choice instanceof RecipeChoice.ExactChoice exact) {
            List<ItemStack> items = exact.getChoices();
            return items.isEmpty() ? msg("showcraft.empty_cell", "<dark_gray>·</dark_gray>")
                    : itemName(items.get(0));
        }
        if (choice instanceof RecipeChoice.MaterialChoice materials) {
            List<Material> choices = materials.getChoices();
            if (choices.isEmpty()) return msg("showcraft.empty_cell", "<dark_gray>·</dark_gray>");
            if (choices.size() == 1) return materialName(choices.get(0));
            StringBuilder sb = new StringBuilder();
            int shown = Math.min(3, choices.size());
            for (int i = 0; i < shown; i++) {
                if (i > 0) sb.append(" / ");
                sb.append(materialName(choices.get(i)));
            }
            if (choices.size() > shown) sb.append(" …");
            return sb.toString();
        }
        return choice.toString();
    }

    private static String materialName(Material material) {
        return prettify(material.name().toLowerCase(Locale.ROOT).replace('_', ' '));
    }

    /** Display name of an item: custom display name first, then a prettified material name. */
    private static String itemName(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) {
            return msg("showcraft.empty_cell", "<dark_gray>·</dark_gray>");
        }
        if (item.hasItemMeta() && item.getItemMeta().hasDisplayName()) {
            return PlainTextComponentSerializer.plainText().serialize(item.getItemMeta().displayName());
        }
        String raw = item.getType().name().toLowerCase(Locale.ROOT).replace('_', ' ');
        String name = prettify(raw);
        return item.getAmount() > 1 ? name + " ×" + item.getAmount() : name;
    }

    private static String prettify(String raw) {
        StringBuilder sb = new StringBuilder();
        for (String part : raw.split(" ")) {
            if (part.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.toString();
    }

    private static String msg(String path, String def) {
        return MessagesManager.getString(path, def);
    }

    // =========================
    // TAB-COMPLETE
    // =========================

    /**
     * Suggests exact recipe keys ({@code namespace:key}) and result selectors
     * (material or custom item names) so an ambiguous result can be picked from
     * the chooser.
     */
    public static List<String> tabComplete(String[] args) {
        if (args.length != 2) return List.of();
        return RecipeLookup.suggest(args[1]);
    }
}
