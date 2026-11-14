package com.ultimateimprovments.datapack;

import com.moandjiezana.toml.Toml;
import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.registry.RegistryKey;
import io.papermc.paper.registry.TypedKey;
import io.papermc.paper.registry.data.EnchantmentRegistryEntry;
import io.papermc.paper.registry.event.RegistryComposeEvent;
import io.papermc.paper.registry.event.RegistryEvents;
import io.papermc.paper.registry.keys.EnchantmentKeys;
import io.papermc.paper.registry.set.RegistryKeySet;
import io.papermc.paper.registry.set.RegistrySet;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemType;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * EnchantmentRegistryBootstrap — registers the 18 custom enchantments through Paper's
 * experimental {@code RegistryEvents} API instead of shipping
 * {@code data/ui/enchantment/*.json} in the bundled datapack.
 * <p>
 * Why: the datapack is built and discovered at server start, so a malformed
 * enchantment only showed up as a warning in the log and a silently missing entry,
 * while plugin code kept referencing {@code ui:<id>}. Registering in the bootstrap phase
 * makes the server validate every builder before it accepts a world, and the registry is
 * the same one the client receives — no file copying involved.
 * <p>
 * Two consequences worth knowing:
 * <ul>
 *   <li><b>The display name is baked in per server, not per player.</b> A tooltip line
 *       comes from the registry entry, which the server sends once for everyone. Only a
 *       resource pack could localise it per player, so the name follows the server-wide
 *       {@code datapack.lang} / {@code messages.lang} resolution in {@link PackLanguage} —
 *       and both {@code name_en} / {@code name_ru} come from
 *       {@code configs/UI-Enchantments.toml}, so a restart can change them.</li>
 *   <li><b>Keys are unchanged</b> ({@code ui:aoe}, {@code ui:veinminer}, ...), so items
 *       already carrying these enchantments keep working; no migration pass over
 *       inventories, ender chests or stored player data is needed.</li>
 * </ul>
 * Everything that used to be a constant in {@link EnchantmentDefinition} and can be
 * tuned per server — display names, max level, anvil cost, active slots, and the
 * shared weight/cost curve — is read from {@code UI-Enchantments.toml} here. Supported
 * items stay code-side: three entries need tag unions the registry API cannot build
 * from a config string.
 * <p>
 * Registration skips a single disabled enchantment ({@code enchantments.<id>.enabled}),
 * and the whole system when the master switch is off. The validation still runs before
 * any world loads.
 */
final class EnchantmentRegistryBootstrap {

    private EnchantmentRegistryBootstrap() {}

    /** Registers the compose handler; must be called from {@link UIDatapackBootstrap}. */
    static void register(BootstrapContext context) {
        ComponentLogger log = context.getLogger();
        File shared = new File(new File("plugins"), "UltimateImprovments");
        Settings settings = Settings.read(shared, log);

        if (!settings.enabled()) {
            log.info("enchantments.enabled=false — custom enchantments are not registered.");
            return;
        }
        if (!settings.packEnabled()) {
            List<String> tagBased = new ArrayList<>();
            for (EnchantmentDefinition definition : EnchantmentDefinition.all()) {
                if (definition.itemTag() != null && settings.isEnabled(definition.id())) {
                    tagBased.add(definition.id());
                }
            }
            if (!tagBased.isEmpty()) {
                log.warn("datapack.enabled=false but these enchantments need a pack item tag: "
                        + String.join(", ", tagBased)
                        + " — they will be registered with an empty item set.");
            }
        }

        String lang = PackLanguage.resolve(settings.configuredLang(), shared, log);
        boolean russian = PackLanguage.RU.equals(lang);
        log.info("Custom enchantments will be registered through RegistryEvents (language: "
                + lang + ", " + EnchantmentDefinition.all().size() + " entries).");

        context.getLifecycleManager().registerEventHandler(RegistryEvents.ENCHANTMENT.compose(),
                event -> compose(event, russian, settings, log));
    }

    /**
     * Fills the enchantment registry. One failing entry is logged and skipped instead of
     * aborting the whole server: the bootstrap phase turns an uncaught exception into a
     * shutdown before any world loads, so a single bad item key must not take everything
     * down with it.
     */
    private static void compose(RegistryComposeEvent<Enchantment, EnchantmentRegistryEntry.Builder> event,
                                boolean russian, Settings settings, ComponentLogger log) {
        int registered = 0;
        List<String> skipped = new ArrayList<>();

        for (EnchantmentDefinition definition : EnchantmentDefinition.all()) {
            if (!settings.isEnabled(definition.id())) {
                // Switched off individually (enchantments.<id>.enabled) or by the master.
                skipped.add(definition.id());
                continue;
            }
            try {
                register(event, definition, russian, settings);
                registered++;
            } catch (Throwable t) {
                log.error("Failed to register the 'ui:" + definition.id()
                        + "' enchantment — skipping it.", t);
                skipped.add(definition.id());
            }
        }

        String message = "Registered " + registered + " custom enchantment(s) through RegistryEvents";
        if (skipped.isEmpty()) {
            log.info(message + ".");
        } else {
            log.warn(message + "; skipped: " + String.join(", ", skipped) + ".");
        }
    }

    private static void register(RegistryComposeEvent<Enchantment, EnchantmentRegistryEntry.Builder> event,
                                 EnchantmentDefinition definition, boolean russian, Settings settings) {
        RegistryKeySet<ItemType> items = definition.itemTag() != null
                ? event.getOrCreateTag(definition.itemTag())
                : RegistrySet.keySet(RegistryKey.ITEM, itemKeys(definition.items()));

        event.registry().register(EnchantmentKeys.create(Key.key("ui", definition.id())),
                builder -> builder
                        .description(Component.text(russian
                                ? settings.nameRu(definition)
                                : settings.nameEn(definition)))
                        .supportedItems(items)
                        .primaryItems(items)
                        .weight(settings.weight())
                        .maxLevel(settings.maxLevel(definition))
                        .minimumCost(EnchantmentRegistryEntry.EnchantmentCost.of(
                                settings.minCostBase(), settings.minCostPerLevel()))
                        .maximumCost(EnchantmentRegistryEntry.EnchantmentCost.of(
                                settings.maxCostBase(), settings.maxCostPerLevel()))
                        .anvilCost(settings.anvilCost(definition))
                        .activeSlots(settings.activeSlots(definition)));
    }

    private static List<TypedKey<ItemType>> itemKeys(List<String> ids) {
        List<TypedKey<ItemType>> keys = new ArrayList<>(ids.size());
        for (String id : ids) keys.add(TypedKey.create(RegistryKey.ITEM, Key.key(id)));
        return keys;
    }

    /**
     * The subset of {@code UI-Enchantments.toml} this class cares about, plus the legacy
     * keys from {@code UI-Datapack.toml}. Read directly with toml4j because the
     * bootstrap runs long before UI-Core (and its config layer) exists.
     * <p>
     * Legacy migration: {@code datapack.modules.enchantments: false} switches the master
     * off, and {@code datapack.disabled_enchantments} switches single ids off — but only
     * while the new file does not carry the corresponding key yet. Config repair adds the
     * new keys on the first start, so the old values are honoured exactly once.
     */
    private record Settings(boolean enabled, boolean packEnabled, String configuredLang,
                            Map<String, Table> tables, Set<String> legacyDisabled, int weight,
                            int minCostBase, int minCostPerLevel, int maxCostBase, int maxCostPerLevel) {

        /** One {@code [enchantments.<id>]} table; {@code null} fields mean "use the code default". */
        private record Table(Boolean enabled, String nameEn, String nameRu, Integer maxLevel,
                             Integer anvilCost, String activeSlots) {}

        static Settings read(File shared, ComponentLogger log) {
            File configFile = new File(new File(shared, "configs"), "UI-Enchantments.toml");
            boolean enabled = true;
            boolean packEnabled = true;
            boolean masterConfigured = false;
            String configuredLang = "auto";
            Map<String, Table> tables = new HashMap<>();
            Set<String> legacyDisabled = new LinkedHashSet<>();
            int weight = EnchantmentDefinition.WEIGHT;
            int minCostBase = EnchantmentDefinition.MIN_COST_BASE;
            int minCostPerLevel = EnchantmentDefinition.MIN_COST_PER_LEVEL;
            int maxCostBase = EnchantmentDefinition.MAX_COST_BASE;
            int maxCostPerLevel = EnchantmentDefinition.MAX_COST_PER_LEVEL;

            if (configFile.isFile()) {
                try {
                    Toml toml = new Toml().read(configFile);
                    Boolean master = toml.getBoolean("enchantments.enabled");
                    if (master != null) {
                        enabled = master;
                        masterConfigured = true;
                    }
                    Toml defaults = toml.getTable("enchantments.defaults");
                    if (defaults != null) {
                        weight = intValue(defaults.toMap().get("weight"), weight);
                        minCostBase = intValue(defaults.toMap().get("min_cost_base"), minCostBase);
                        minCostPerLevel = intValue(defaults.toMap().get("min_cost_per_level"), minCostPerLevel);
                        maxCostBase = intValue(defaults.toMap().get("max_cost_base"), maxCostBase);
                        maxCostPerLevel = intValue(defaults.toMap().get("max_cost_per_level"), maxCostPerLevel);
                    }
                    Toml root = toml.getTable("enchantments");
                    if (root != null) {
                        for (Map.Entry<String, Object> entry : root.toMap().entrySet()) {
                            if (!(entry.getValue() instanceof Map<?, ?> map)) continue;
                            if ("defaults".equals(entry.getKey())) continue;
                            String id = entry.getKey().toLowerCase(Locale.ROOT);
                            tables.put(id, table(map, log, id));
                            if (!isKnownId(id)) {
                                log.warn("Unknown enchantment table [enchantments." + id
                                        + "] — ignored. Known ids: " + String.join(", ", knownIds()) + ".");
                            }
                        }
                    }
                } catch (Exception e) {
                    log.warn("Could not read " + configFile + " (" + e.getMessage()
                            + ") — registering every custom enchantment with its defaults.");
                }
            }

            File packFile = new File(new File(shared, "configs"), "UI-Datapack.toml");
            if (packFile.isFile()) {
                try {
                    Toml pack = new Toml().read(packFile);
                    configuredLang = pack.getString("datapack.lang", "auto");
                    packEnabled = !Boolean.FALSE.equals(pack.getBoolean("datapack.enabled", true));
                    if (!masterConfigured
                            && Boolean.FALSE.equals(pack.getBoolean("datapack.modules.enchantments"))) {
                        enabled = false;
                        log.info("datapack.modules.enchantments=false (legacy key) — custom "
                                + "enchantments are not registered. Move it to enchantments.enabled.");
                    }
                    List<String> legacy = pack.getList("datapack.disabled_enchantments");
                    if (legacy != null) {
                        for (Object raw : legacy) {
                            if (raw == null) continue;
                            String id = raw.toString().trim().toLowerCase(Locale.ROOT);
                            if (id.startsWith("ui:")) id = id.substring(3);
                            if (!id.isEmpty()) legacyDisabled.add(id);
                        }
                    }
                } catch (Exception e) {
                    log.warn("Could not read " + packFile + " (" + e.getMessage()
                            + ") — legacy enchantment toggles are ignored.");
                }
            }

            return new Settings(enabled, packEnabled, configuredLang, tables, legacyDisabled,
                    weight, minCostBase, minCostPerLevel, maxCostBase, maxCostPerLevel);
        }

        private static Table table(Map<?, ?> raw, ComponentLogger log, String id) {
            String slots = stringValue(raw.get("active_slots"));
            if (slots != null && EquipmentSlotGroup.getByName(slots.trim()) == null) {
                log.warn("[enchantments." + id + "] unknown active_slots '" + slots
                        + "' — using the built-in value.");
                slots = null;
            }
            return new Table(
                    booleanValue(raw.get("enabled")),
                    stringValue(raw.get("name_en")),
                    stringValue(raw.get("name_ru")),
                    integerValue(raw.get("max_level")),
                    integerValue(raw.get("anvil_cost")),
                    slots);
        }

        private Table tableOf(EnchantmentDefinition definition) {
            return tables.get(definition.id());
        }

        boolean isEnabled(String id) {
            if (!enabled) return false;
            Table table = tables.get(id);
            if (table != null && table.enabled() != null) return table.enabled();
            return !legacyDisabled.contains(id);
        }

        int maxLevel(EnchantmentDefinition definition) {
            Table table = tableOf(definition);
            return table != null && table.maxLevel() != null ? table.maxLevel() : definition.maxLevel();
        }

        int anvilCost(EnchantmentDefinition definition) {
            Table table = tableOf(definition);
            return table != null && table.anvilCost() != null ? table.anvilCost() : definition.anvilCost();
        }

        String nameEn(EnchantmentDefinition definition) {
            Table table = tableOf(definition);
            return table != null && table.nameEn() != null ? table.nameEn() : definition.nameEn();
        }

        String nameRu(EnchantmentDefinition definition) {
            Table table = tableOf(definition);
            return table != null && table.nameRu() != null ? table.nameRu() : definition.nameRu();
        }

        EquipmentSlotGroup activeSlots(EnchantmentDefinition definition) {
            Table table = tableOf(definition);
            if (table == null || table.activeSlots() == null) return definition.activeSlots();
            EquipmentSlotGroup group = EquipmentSlotGroup.getByName(table.activeSlots().trim());
            return group != null ? group : definition.activeSlots();
        }
    }

    // =========================
    // VALUE HELPERS
    // =========================

    private static List<String> knownIds() {
        List<String> ids = new ArrayList<>();
        for (EnchantmentDefinition definition : EnchantmentDefinition.all()) ids.add(definition.id());
        return ids;
    }

    private static boolean isKnownId(String id) {
        return knownIds().contains(id);
    }

    private static Integer integerValue(Object value) {
        if (value instanceof Number number) return number.intValue();
        if (value instanceof String string) {
            try {
                return (int) Double.parseDouble(string.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static int intValue(Object value, int fallback) {
        Integer parsed = integerValue(value);
        return parsed != null ? parsed : fallback;
    }

    private static String stringValue(Object value) {
        if (!(value instanceof String string)) return null;
        String trimmed = string.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static Boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) return bool;
        if (value instanceof String string) {
            if ("true".equalsIgnoreCase(string.trim())) return Boolean.TRUE;
            if ("false".equalsIgnoreCase(string.trim())) return Boolean.FALSE;
        }
        return null;
    }
}
