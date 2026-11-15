package com.ultimateimprovements.mechanics.crafting;

import com.ultimateimprovements.core.Keys;
import com.ultimateimprovements.core.Main;
import com.ultimateimprovements.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;

/**
 * Structure Indicator — the structural counterpart of the Multimeter.
 * <p>
 * The base item is a <b>recovery compass</b>, not a calibrated sculk sensor:
 * the indicator must be a tool, never a placeable block. The recipe is in the
 * multimeter's cost class (diamonds + iron) but a different layout:
 * <pre>
 *   D I D      D = diamond (sensor ring)
 *   I R I      I = iron ingot (frame)
 *   D I D      R = recovery compass (core)
 * </pre>
 * The result is rebuilt in {@code PrepareItemCraftEvent} so the PDC tag and
 * the lore are always present, even when the recipe is matched through a
 * vanilla click.
 */
public class StructureIndicatorCraftListener implements Listener {

    private static NamespacedKey RECIPE_KEY;

    public static void init() {
        RECIPE_KEY = new NamespacedKey(Main.getInstance(), "structure_indicator");
        registerRecipe();
    }

    /** Builds the Structure Indicator item (SHIFT+RMB on a structure). */
    public static ItemStack createItem() {
        ItemStack result = new ItemStack(Material.RECOVERY_COMPASS);
        result.editMeta(meta -> {
            meta.displayName(MessageUtil.parse("<i:false><white>Structure Indicator *</white>"));
            meta.lore(List.of(
                    MessageUtil.parse("<i:false><gray>Shift+RMB on a structure to inspect</gray>"),
                    MessageUtil.parse("<i:false><gray>its integrity, status and components.</gray>"),
                    MessageUtil.parse(""),
                    MessageUtil.parse("<i:false><dark_gray>Structure tool: the Multimeter</dark_gray>"),
                    MessageUtil.parse("<i:false><dark_gray>reads cables and energy only.</dark_gray>")
            ));
            meta.getPersistentDataContainer().set(Keys.STRUCTURE_INDICATOR, PersistentDataType.BYTE, (byte) 1);
        });
        return result;
    }

    private static void registerRecipe() {
        Main plugin = Main.getInstance();

        ItemStack result = createItem();

        Bukkit.removeRecipe(RECIPE_KEY);

        ShapedRecipe recipe = new ShapedRecipe(RECIPE_KEY, result);
        recipe.setGroup(RECIPE_KEY.getKey());
        recipe.shape(
                "DID",
                "IRI",
                "DID"
        );
        recipe.setIngredient('D', Material.DIAMOND);
        recipe.setIngredient('I', Material.IRON_INGOT);
        recipe.setIngredient('R', Material.RECOVERY_COMPASS);

        plugin.getServer().addRecipe(recipe);
        RecipeRegistry.registerRecipe(RECIPE_KEY);
    }

    @EventHandler
    public void onCraft(PrepareItemCraftEvent e) {
        Recipe recipe = e.getRecipe();
        if (!(recipe instanceof ShapedRecipe sr)) return;
        if (!sr.getKey().equals(RECIPE_KEY)) return;

        CraftingInventory inv = e.getInventory();
        inv.setResult(createItem());
    }
}
