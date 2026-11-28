package com.ultimateimprovements.command.subcommands;

import com.ultimateimprovements.config.MessagesManager;
import com.ultimateimprovements.mechanics.crafting.RecipeRegistry;
import com.ultimateimprovements.util.MessageUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.CommandSender;
import org.bukkit.inventory.BlastingRecipe;
import org.bukkit.inventory.CampfireRecipe;
import org.bukkit.inventory.FurnaceRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.SmithingTransformRecipe;
import org.bukkit.inventory.SmithingTrimRecipe;
import org.bukkit.inventory.SmokingRecipe;
import org.bukkit.inventory.StonecuttingRecipe;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Shared recipe lookup for {@code /ui showcraft} and {@code /ui craftrecipe}.
 * <p>
 * Both commands accept either an exact recipe key ({@code namespace:key}) or a
 * result selector — a vanilla material name ({@code cherry_planks}) or a custom
 * item display name ({@code lead_ingot}). A result selector is ambiguous by
 * nature: several recipes can produce the same item. In that case the caller
 * must ask the player which recipe they meant via {@link #sendChooser} and stop;
 * nothing is shown or crafted until a concrete key is picked.
 * <p>
 * The chooser entries are clickable ({@code /ui <command> <namespace:key>}), so a
 * click resolves the ambiguity by key.
 */
final class RecipeLookup {

    /** A recipe together with its registered key. */
    record Entry(NamespacedKey key, Recipe recipe) {}

    private RecipeLookup() {}

    // =========================
    // SELECTOR
    // =========================

    /**
     * Joins the command arguments into one selector: {@code /ui showcraft diamond sword}
     * and {@code /ui showcraft DIAMOND_SWORD} both become {@code diamond_sword}.
     */
    static String selector(String[] args, int from) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < args.length; i++) {
            String part = args[i] == null ? "" : args[i].trim();
            if (part.isEmpty()) continue;
            if (sb.length() > 0) sb.append('_');
            sb.append(part);
        }
        return normalize(sb.toString());
    }

    /** Lowercases and folds spaces into underscores; keeps the characters a key or item name may use. */
    static String normalize(String raw) {
        if (raw == null) return "";
        String lower = raw.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        StringBuilder sb = new StringBuilder(lower.length());
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '_' || c == ':' || c == '.' || c == '-') {
                sb.append(c);
            }
        }
        // Custom display names often end with decorations ("Lead Ingot *"), which
        // must not leave a trailing underscore behind.
        int start = 0;
        int end = sb.length();
        while (start < end && sb.charAt(start) == '_') start++;
        while (end > start && sb.charAt(end - 1) == '_') end--;
        return sb.substring(start, end);
    }

    // =========================
    // LOOKUP
    // =========================

    /**
     * Resolves an exact {@code namespace:key} recipe.
     *
     * @return the registered recipe, or {@code null} when the input is not a key
     *         or no recipe is registered under it
     */
    static Recipe byKey(String input) {
        if (input == null || !input.contains(":")) return null;
        NamespacedKey key;
        try {
            key = NamespacedKey.fromString(input);
        } catch (IllegalArgumentException e) {
            return null;
        }
        return key == null ? null : Bukkit.getRecipe(key);
    }

    /**
     * Finds every keyed recipe whose result matches the selector. Matching is by
     * result material name or by the custom item's display name, so both
     * {@code cherry_planks} and a custom {@code lead_ingot} work.
     *
     * @return deterministic list (plain vanilla results first, then the recipe
     *         keyed exactly after the selector, then by key); empty when nothing
     *         matches
     */
    static List<Entry> byResult(String selector) {
        if (selector == null || selector.isEmpty()) return List.of();

        String bare = selector.contains(":") ? selector.substring(selector.lastIndexOf(':') + 1) : selector;
        Material material = matchMaterial(bare);

        Map<String, Entry> found = new LinkedHashMap<>();
        Bukkit.recipeIterator().forEachRemaining(recipe -> {
            if (!(recipe instanceof Keyed keyed)) return;
            ItemStack result = recipe.getResult();
            if (result == null || result.getType().isAir()) return;

            boolean matches = material != null && result.getType() == material;
            if (!matches) {
                String display = displaySelector(result);
                matches = !display.isEmpty() && (display.equals(selector) || display.equals(bare));
            }
            // A custom recipe is often keyed after the item it makes (ui:lead_ingot),
            // even when its result stack is a plain material with no display name.
            if (!matches) matches = keyed.getKey().getKey().equals(bare);
            if (!matches) return;

            NamespacedKey key = keyed.getKey();
            found.putIfAbsent(key.toString(), new Entry(key, recipe));
        });

        List<Entry> out = new ArrayList<>(found.values());
        out.sort(Comparator
                .comparing((Entry e) -> hasDisplayName(e.recipe().getResult()))
                .thenComparing(e -> !e.key().getKey().equals(bare))
                .thenComparing(e -> e.key().toString()));
        return out;
    }

    private static Material matchMaterial(String raw) {
        if (raw.isEmpty()) return null;
        Material material = Material.matchMaterial(raw);
        if (material == null) material = Material.matchMaterial(raw.toUpperCase(Locale.ROOT));
        return material;
    }

    // =========================
    // CHOOSER
    // =========================

    /**
     * Explains that the selector matched several recipes and offers them as
     * clickable entries. The caller must NOT show or craft anything afterwards —
     * the ambiguous request stops here until a concrete key is picked.
     *
     * @param command   subcommand name used in the click/run hint ({@code showcraft}, {@code craftrecipe})
     * @param prefix    message key prefix ({@code showcraft}, {@code craftrecipe})
     * @param selector  the ambiguous selector, shown in the header
     * @param entries   the matching recipes, never empty
     */
    static void sendChooser(CommandSender sender, String command, String prefix,
                            String selector, List<Entry> entries) {
        String label = entries.isEmpty() ? prettify(selector)
                : itemName(entries.get(0).recipe().getResult());
        String header = msg(prefix + ".choose_header",
                        "<gold>  ✦ </gold><white>%count% recipes make </white>"
                                + "<yellow>%item%</yellow><white> — choose one:</white>")
                .replace("%count%", String.valueOf(entries.size()))
                .replace("%item%", label);
        sender.sendMessage(MessageUtil.parse(header));

        String entryFormat = msg(prefix + ".choose_entry",
                "<gray> [%index%] </gray><white>%recipe%</white> <dark_gray>— %station%</dark_gray>");
        String hover = msg(prefix + ".choose_hover", "<gray>Click to use this recipe</gray>");

        int index = 1;
        for (Entry entry : entries) {
            Component line = MessageUtil.parse(entryFormat
                    .replace("%index%", String.valueOf(index))
                    .replace("%recipe%", entry.key().toString())
                    .replace("%station%", station(entry.recipe())));
            line = line
                    .clickEvent(ClickEvent.runCommand("/ui " + command + " " + entry.key()))
                    .hoverEvent(HoverEvent.showText(MessageUtil.parse(hover)));
            sender.sendMessage(line);
            index++;
        }

        sender.sendMessage(MessageUtil.parse(msg(prefix + ".choose_hint",
                        "<dark_gray>Click an entry or run </dark_gray>"
                                + "<white>/ui %command% <namespace:key></white>")
                .replace("%command%", command)));
    }

    // =========================
    // PRESENTATION
    // =========================

    /** Station of a recipe (crafting table, furnace, Crafter for custom recipes, ...). */
    static String station(Recipe recipe) {
        if (recipe instanceof Keyed keyed && RecipeRegistry.getCustomRecipes().contains(keyed.getKey())) {
            return "Crafter";
        }
        if (recipe instanceof FurnaceRecipe) return "Furnace";
        if (recipe instanceof BlastingRecipe) return "Blast Furnace";
        if (recipe instanceof SmokingRecipe) return "Smoker";
        if (recipe instanceof CampfireRecipe) return "Campfire";
        if (recipe instanceof StonecuttingRecipe) return "Stonecutter";
        if (recipe instanceof SmithingTransformRecipe || recipe instanceof SmithingTrimRecipe) {
            return "Smithing Table";
        }
        if (recipe instanceof ShapedRecipe || recipe instanceof ShapelessRecipe) {
            return "Crafting Table";
        }
        return recipe.getClass().getSimpleName();
    }

    /** Display name of a result: custom display name first, then a prettified material name. */
    static String itemName(ItemStack item) {
        if (item == null || item.getType().isAir()) return "·";
        if (hasDisplayName(item)) {
            return PlainTextComponentSerializer.plainText().serialize(item.getItemMeta().displayName());
        }
        return prettify(item.getType().name().toLowerCase(Locale.ROOT).replace('_', ' '));
    }

    private static boolean hasDisplayName(ItemStack item) {
        return item != null && item.hasItemMeta() && item.getItemMeta().hasDisplayName();
    }

    /** Item display name as a selector ({@code Lead Ingot} → {@code lead_ingot}); empty for plain items. */
    private static String displaySelector(ItemStack item) {
        if (!hasDisplayName(item)) return "";
        return normalize(PlainTextComponentSerializer.plainText().serialize(item.getItemMeta().displayName()));
    }

    private static String prettify(String raw) {
        StringBuilder sb = new StringBuilder();
        for (String part : raw.replace('_', ' ').split(" ")) {
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
     * Suggests both exact recipe keys ({@code namespace:key}) and bare result
     * selectors (material / custom display name), so an admin can type the item
     * they want and pick from the chooser afterwards.
     */
    static List<String> suggest(String partial) {
        String p = partial == null ? "" : partial.toLowerCase(Locale.ROOT);
        LinkedHashSet<String> out = new LinkedHashSet<>();
        Bukkit.recipeIterator().forEachRemaining(recipe -> {
            if (recipe instanceof Keyed keyed) {
                String key = keyed.getKey().toString();
                if (key.startsWith(p)) out.add(key);
            }
            ItemStack result = recipe.getResult();
            if (result == null || result.getType().isAir()) return;
            String materialName = result.getType().name().toLowerCase(Locale.ROOT);
            if (materialName.startsWith(p)) out.add(materialName);
            String display = displaySelector(result);
            if (!display.isEmpty() && display.startsWith(p)) out.add(display);
        });
        return new ArrayList<>(out);
    }
}
