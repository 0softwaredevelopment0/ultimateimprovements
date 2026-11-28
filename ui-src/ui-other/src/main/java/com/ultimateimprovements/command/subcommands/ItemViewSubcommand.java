package com.ultimateimprovements.command.subcommands;

import com.ultimateimprovements.command.CommandErrors;
import com.ultimateimprovements.core.Permissions;
import com.ultimateimprovements.util.MessageUtil;
import io.papermc.paper.datacomponent.DataComponentType;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemEnchantments;
import io.papermc.paper.datacomponent.item.ItemLore;
import io.papermc.paper.persistence.PersistentDataContainerView;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.TranslationArgument;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.minecraft.locale.Language;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.CommandSender;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * /ui item view &lt;player&gt; [slot] — read-only full item-data viewer.
 *
 * <p>Prints the item's name/lore, enchantments, durability, PDC tags, every
 * set data component and the complete vanilla NBT (SNBT). The command never
 * mutates the item or the player, so it is open to every player by default
 * ({@code ui.command.item.view}); {@code ui.command.item} also grants it.</p>
 *
 * <p>Slots mirror {@code /ui enchant}: mainhand (default), offhand, bothhand,
 * cursor, hotbar, armor, inventory, all — plus helmet/chestplate/leggings/
 * boots and raw numbers 0-40 for the underlying player inventory.</p>
 */
final class ItemViewSubcommand {

    private static final int MAX_NBT_CHARS = 200_000;
    private static final int CHUNK_CHARS = 1_600;

    private static final List<String> SLOTS = List.of(
            "mainhand", "offhand", "bothhand", "cursor", "hotbar", "armor",
            "inventory", "all", "helmet", "chestplate", "leggings", "boots");

    private static final List<PersistentDataType<?, ?>> PDC_TYPES = List.of(
            PersistentDataType.BYTE,
            PersistentDataType.SHORT,
            PersistentDataType.INTEGER,
            PersistentDataType.LONG,
            PersistentDataType.FLOAT,
            PersistentDataType.DOUBLE,
            PersistentDataType.BOOLEAN,
            PersistentDataType.STRING,
            PersistentDataType.BYTE_ARRAY,
            PersistentDataType.INTEGER_ARRAY,
            PersistentDataType.LONG_ARRAY,
            PersistentDataType.TAG_CONTAINER);

    private record SlotRef(String label, Supplier<ItemStack> getter) {
        ItemStack get() {
            return getter.get();
        }
    }

    private ItemViewSubcommand() {}

    // ══════════════════════════════════════════════════════════════
    // EXECUTE
    // ══════════════════════════════════════════════════════════════

    static boolean execute(CommandSender sender, String[] args) {
        if (!sender.hasPermission(Permissions.CMD_ITEM_VIEW)
                && !sender.hasPermission(Permissions.CMD_ITEM)) {
            CommandErrors.noPermission(sender, Permissions.CMD_ITEM_VIEW);
            return true;
        }
        if (args.length < 3) {
            sendUsage(sender);
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[2]);
        if (target == null) {
            sender.sendMessage(MessageUtil.parse("<red>❌ Player </red><yellow>" + args[2]
                    + "</yellow><red> is not online!</red>"));
            return true;
        }

        String slotName = args.length >= 4 ? args[3].toLowerCase(Locale.ROOT) : "mainhand";
        List<SlotRef> slots = slots(target, slotName);
        if (slots == null) {
            sender.sendMessage(MessageUtil.parse("<red>❌ Unknown slot: </red><yellow>" + args[3]
                    + "</yellow><gray>. Use mainhand, offhand, bothhand, cursor, hotbar, armor, "
                    + "inventory, all, helmet, chestplate, leggings, boots or a number 0-40.</gray>"));
            return true;
        }

        sender.sendMessage(MessageUtil.parse("<dark_gray>┏━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┓</dark_gray>"));
        sender.sendMessage(MessageUtil.parse("<dark_gray>┃</dark_gray> <gold>✦</gold> <white>Item data</white>"
                + " <gray>—</gray> <yellow>" + target.getName() + "</yellow>"
                + " <gray>(</gray><white>" + slotName + "</white><gray>)</gray>"));

        for (SlotRef slot : slots) {
            ItemStack item = slot.get();
            sender.sendMessage(MessageUtil.parse("<dark_gray>┃</dark_gray> <aqua>▸ " + slot.label() + "</aqua>"));
            if (item == null || item.getType() == Material.AIR) {
                sender.sendMessage(MessageUtil.parse("<dark_gray>┃</dark_gray>   <gray>— empty —</gray>"));
                continue;
            }
            dumpItem(sender, item);
        }

        sender.sendMessage(MessageUtil.parse("<dark_gray>┗━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┛</dark_gray>"));
        return true;
    }

    // ══════════════════════════════════════════════════════════════
    // SLOTS
    // ══════════════════════════════════════════════════════════════

    private static List<SlotRef> slots(Player player, String name) {
        PlayerInventory inv = player.getInventory();
        List<SlotRef> out = new ArrayList<>();

        switch (name) {
            case "mainhand" -> out.add(new SlotRef("Main Hand", inv::getItemInMainHand));
            case "offhand" -> out.add(new SlotRef("Off Hand", inv::getItemInOffHand));
            case "bothhand" -> {
                out.add(new SlotRef("Main Hand", inv::getItemInMainHand));
                out.add(new SlotRef("Off Hand", inv::getItemInOffHand));
            }
            case "cursor" -> out.add(new SlotRef("Cursor", () -> player.getOpenInventory().getCursor()));
            case "hotbar" -> {
                for (int i = 0; i < 9; i++) {
                    int idx = i;
                    out.add(new SlotRef("Hotbar " + (i + 1), () -> inv.getItem(idx)));
                }
            }
            case "armor" -> addArmor(out, inv);
            case "helmet", "head" -> addArmorSlot(out, inv, 3, "Helmet");
            case "chestplate", "chest" -> addArmorSlot(out, inv, 2, "Chestplate");
            case "leggings", "legs" -> addArmorSlot(out, inv, 1, "Leggings");
            case "boots", "feet" -> addArmorSlot(out, inv, 0, "Boots");
            case "inventory" -> {
                for (int i = 9; i < 36; i++) {
                    int idx = i;
                    out.add(new SlotRef("Inventory " + (i - 8), () -> inv.getItem(idx)));
                }
            }
            case "all" -> {
                out.addAll(slots(player, "mainhand"));
                out.addAll(slots(player, "offhand"));
                out.addAll(slots(player, "armor"));
                out.addAll(slots(player, "hotbar"));
                out.addAll(slots(player, "inventory"));
                out.addAll(slots(player, "cursor"));
            }
            default -> {
                try {
                    int index = Integer.parseInt(name);
                    if (index < 0 || index >= inv.getSize()) return null;
                    out.add(new SlotRef("Slot " + index, () -> inv.getItem(index)));
                } catch (NumberFormatException e) {
                    return null;
                }
            }
        }
        return out;
    }

    private static void addArmor(List<SlotRef> out, PlayerInventory inv) {
        addArmorSlot(out, inv, 0, "Boots");
        addArmorSlot(out, inv, 1, "Leggings");
        addArmorSlot(out, inv, 2, "Chestplate");
        addArmorSlot(out, inv, 3, "Helmet");
    }

    private static void addArmorSlot(List<SlotRef> out, PlayerInventory inv, int index, String label) {
        out.add(new SlotRef(label, () -> {
            ItemStack[] armor = inv.getArmorContents();
            return index < armor.length ? armor[index] : null;
        }));
    }

    static List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 3) {
            return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
        }
        if (args.length == 4) return SLOTS;
        return List.of();
    }

    private static void sendUsage(CommandSender sender) {
        sender.sendMessage(MessageUtil.parse("<red>❌ Usage: </red><white>/ui item view <player> [slot]</white>"));
        sender.sendMessage(MessageUtil.parse("<gray>Slots: mainhand (default), offhand, bothhand, cursor, hotbar, "
                + "armor, inventory, all, helmet, chestplate, leggings, boots, 0-40.</gray>"));
    }

    // ══════════════════════════════════════════════════════════════
    // DUMP
    // ══════════════════════════════════════════════════════════════

    private static void dumpItem(CommandSender sender, ItemStack item) {
        line(sender, "Type", item.getType().getKey().toString());
        line(sender, "Amount", item.getAmount() + " / " + item.getType().getMaxStackSize());

        Component customName = item.getData(DataComponentTypes.CUSTOM_NAME);
        if (customName != null) line(sender, "Custom name", formatComponent(customName));
        line(sender, "Display name", formatComponent(item.displayName()));

        ItemLore lore = item.getData(DataComponentTypes.LORE);
        if (lore == null || lore.lines().isEmpty()) {
            line(sender, "Lore", "(none)");
        } else {
            line(sender, "Lore", lore.lines().size() + " line(s)");
            for (int i = 0; i < lore.lines().size(); i++) {
                line(sender, "  [" + i + "]", formatComponent(lore.lines().get(i)));
            }
        }

        dumpEnchantments(sender, "Enchantments",
                item.getData(DataComponentTypes.ENCHANTMENTS));
        dumpEnchantments(sender, "Stored enchantments",
                item.getData(DataComponentTypes.STORED_ENCHANTMENTS));

        Integer maxDamage = item.getData(DataComponentTypes.MAX_DAMAGE);
        Integer damage = item.getData(DataComponentTypes.DAMAGE);
        if (maxDamage != null) {
            int used = damage == null ? 0 : damage;
            line(sender, "Durability", used + " / " + maxDamage + " used ("
                    + Math.max(0, maxDamage - used) + " remaining)");
        }
        line(sender, "Unbreakable", String.valueOf(item.hasData(DataComponentTypes.UNBREAKABLE)));

        dumpPdc(sender, item.getPersistentDataContainer());
        dumpComponents(sender, item);

        line(sender, "Full NBT (SNBT)", "");
        sendLong(sender, "    ", toSnbt(item));
    }

    private static void dumpEnchantments(CommandSender sender, String label, ItemEnchantments enchantments) {
        if (enchantments == null || enchantments.enchantments().isEmpty()) {
            line(sender, label, "(none)");
            return;
        }
        line(sender, label, enchantments.enchantments().size() + " entry/entries");
        for (Map.Entry<Enchantment, Integer> entry : enchantments.enchantments().entrySet()) {
            line(sender, "  " + entry.getKey().getKey(), String.valueOf(entry.getValue()));
        }
    }

    private static void dumpPdc(CommandSender sender, PersistentDataContainerView pdc) {
        if (pdc == null || pdc.isEmpty()) {
            line(sender, "PDC tags", "(none)");
            return;
        }
        line(sender, "PDC tags", pdc.getSize() + " key(s)");
        for (NamespacedKey key : pdc.getKeys()) {
            line(sender, "  " + key, readPdc(pdc, key));
        }
    }

    private static String readPdc(PersistentDataContainerView pdc, NamespacedKey key) {
        for (PersistentDataType<?, ?> type : PDC_TYPES) {
            String value = readTyped(pdc, key, type);
            if (value != null) return value;
        }
        return "(unknown type)";
    }

    private static <P, C> String readTyped(PersistentDataContainerView pdc,
                                            NamespacedKey key,
                                            PersistentDataType<P, C> type) {
        try {
            C value = pdc.get(key, type);
            if (value == null) return null;
            return type.getComplexType().getSimpleName().toUpperCase(Locale.ROOT)
                    + " = " + stringify(value);
        } catch (Throwable ignored) {
            // A key stored with another adapter — try the next type.
            return null;
        }
    }

    private static void dumpComponents(CommandSender sender, ItemStack item) {
        var types = item.getDataTypes();
        line(sender, "Data components", types.size() + " set");
        for (DataComponentType type : types) {
            String key = type.getKey().toString();
            if (type instanceof DataComponentType.Valued<?> valued) {
                String value = valuedValue(item, valued);
                line(sender, "  " + key, value == null ? "(null)" : value);
            } else {
                line(sender, "  " + key, "(present)");
            }
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static String valuedValue(ItemStack item, DataComponentType.Valued<?> type) {
        Object value = item.getData((DataComponentType.Valued) type);
        return value == null ? null : stringify(value);
    }

    private static String stringify(Object value) {
        if (value == null) return "null";
        if (value instanceof byte[] bytes) return Arrays.toString(bytes);
        if (value instanceof int[] ints) return Arrays.toString(ints);
        if (value instanceof long[] longs) return Arrays.toString(longs);
        if (value instanceof Component component) return formatComponent(component);
        return String.valueOf(value);
    }

    /**
     * Renders a component as readable plain text.
     *
     * <p>Adventure's MiniMessage serializer turns a translatable vanilla
     * component into a {@code <lang:item.minecraft.diamond_pickaxe>} tag, and
     * the plain serializer may fall back to the raw key. Both are unreadable,
     * so translatable nodes are resolved through the server's own vanilla
     * language table (NMS {@link Language}) and their arguments are filled in
     * recursively. The full machine-readable form stays in the SNBT dump.</p>
     */
    private static String formatComponent(Component component) {
        StringBuilder out = new StringBuilder();
        appendPlain(component, out);
        return out.toString();
    }

    private static void appendPlain(Component component, StringBuilder out) {
        if (component instanceof TextComponent text) {
            out.append(text.content());
        } else if (component instanceof TranslatableComponent translatable) {
            out.append(renderTranslatable(translatable));
        } else {
            // Score / selector / keybind / NBT components: the plain serializer
            // already produces a readable representation, including children.
            out.append(PlainTextComponentSerializer.plainText().serialize(component));
            return;
        }

        for (Component child : component.children()) {
            appendPlain(child, out);
        }
    }

    private static String renderTranslatable(TranslatableComponent translatable) {
        String key = translatable.key();
        String pattern = null;
        try {
            pattern = Language.getInstance().getOrDefault(key, null);
        } catch (Throwable ignored) {
            // Language is unavailable (early init / tests) — use the fallback.
        }
        if (pattern == null || pattern.isBlank() || pattern.equals(key)) {
            pattern = translatable.fallback();
        }
        if (pattern == null || pattern.isBlank()) {
            pattern = prettifyTranslationKey(key);
        }
        return applyArguments(pattern, translatable.arguments());
    }

    private static String applyArguments(String pattern, List<TranslationArgument> arguments) {
        if (arguments.isEmpty()) return pattern;

        Object[] values = new Object[arguments.size()];
        for (int i = 0; i < arguments.size(); i++) {
            Object value = arguments.get(i).value();
            values[i] = value instanceof Component component ? formatComponent(component) : String.valueOf(value);
        }
        try {
            return String.format(Locale.ROOT, pattern, values);
        } catch (IllegalArgumentException invalidFormat) {
            return pattern + " " + Arrays.toString(values);
        }
    }

    /** {@code item.minecraft.diamond_pickaxe} → {@code Diamond Pickaxe}. */
    private static String prettifyTranslationKey(String key) {
        int lastDot = key.lastIndexOf('.');
        String tail = lastDot >= 0 ? key.substring(lastDot + 1) : key;
        String[] words = tail.split("_");
        StringBuilder out = new StringBuilder(tail.length());
        for (String word : words) {
            if (word.isEmpty()) continue;
            if (!out.isEmpty()) out.append(' ');
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out.isEmpty() ? key : out.toString();
    }

    // ══════════════════════════════════════════════════════════════
    // FULL NBT
    // ══════════════════════════════════════════════════════════════

    private static String toSnbt(ItemStack item) {
        try {
            var nms = CraftItemStack.asNMSCopy(item);
            RegistryOps<Tag> ops = ((CraftServer) Bukkit.getServer())
                    .getServer()
                    .registryAccess()
                    .createSerializationContext(NbtOps.INSTANCE);
            Tag tag = net.minecraft.world.item.ItemStack.CODEC.encodeStart(ops, nms)
                    .result()
                    .orElse(null);
            if (tag == null) return "(unavailable)";
            String snbt = tag.toString();
            if (snbt.length() > MAX_NBT_CHARS) {
                return snbt.substring(0, MAX_NBT_CHARS) + "… (truncated at " + MAX_NBT_CHARS + " chars)";
            }
            return snbt;
        } catch (Throwable t) {
            return "(failed: " + t.getClass().getSimpleName() + ": " + t.getMessage() + ")";
        }
    }

    // ══════════════════════════════════════════════════════════════
    // OUTPUT HELPERS
    // ══════════════════════════════════════════════════════════════

    private static void line(CommandSender sender, String label, String value) {
        sender.sendMessage(Component.text("┃ ", NamedTextColor.DARK_GRAY)
                .append(Component.text(label, NamedTextColor.DARK_AQUA))
                .append(Component.text(": ", NamedTextColor.DARK_GRAY))
                .append(Component.text(value == null ? "" : value, NamedTextColor.WHITE)));
    }

    private static void sendLong(CommandSender sender, String prefix, String value) {
        if (value == null || value.isEmpty()) return;
        for (int i = 0; i < value.length(); i += CHUNK_CHARS) {
            int end = Math.min(value.length(), i + CHUNK_CHARS);
            sender.sendMessage(Component.text("┃ ", NamedTextColor.DARK_GRAY)
                    .append(Component.text(prefix, NamedTextColor.DARK_GRAY))
                    .append(Component.text(value.substring(i, end), NamedTextColor.GRAY)));
        }
    }
}
