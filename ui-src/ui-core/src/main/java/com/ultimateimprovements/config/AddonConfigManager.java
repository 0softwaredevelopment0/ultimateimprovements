package com.ultimateimprovements.config;

import com.ultimateimprovements.core.Main;
import com.ultimateimprovements.core.UltimateDirs;
import com.ultimateimprovements.util.ConsoleLogger;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * AddonConfigManager — one TOML config per addon under {@code plugins/UltimateImprovements/configs/}.
 * <p>
 * The family shares a single data folder ({@link UltimateDirs}); each addon gets its own
 * {@code configs/UI-<Addon>.toml} that holds BOTH its settings and its messages
 * ({@code messages} RU + {@code messages_en} EN) so the whole addon is configured
 * in one editable file. The per-addon language lives in {@code lang} inside each file
 * ("en" | "ru"); {@code messages.lang} in UI-Core.toml keeps working as the
 * global fallback.
 * <p>
 * The public surface of {@code Main.getConfig()} is unchanged: keys are routed to the
 * owning addon's file by their first path segment ({@link #routeKey(String)}).
 */
public final class AddonConfigManager {

    /** Internal marker used to track dirty per-addon files. */
    public static final String DIRTY_KEY = "__dirty";

    /** Sub-folder inside the shared data folder holding all per-addon TOML files. */
    public static final String CONFIGS_DIR = UltimateDirs.CONFIGS;

    private static final Map<String, FileConfiguration> loaded = new LinkedHashMap<>();
    private static final List<PerAddonFile> files = new ArrayList<>();
    private static volatile boolean initialized = false;

    private AddonConfigManager() {}

    /** Bookkeeping for one per-addon TOML file. */
    private static final class PerAddonFile {
        final String addon;
        final File toml;
        PerAddonFile(String addon, File toml) { this.addon = addon; this.toml = toml; }
    }

    // ========================================================================
    // LIFECYCLE
    // ========================================================================

    /**
     * Generates every missing {@code configs/UI-<Addon>.toml} (settings + messages
     * templates from the bundled fragments) and loads all existing files into memory.
     */
    public static synchronized void init() {
        loaded.clear();
        files.clear();

        for (String addon : AddonCatalog.catalog()) {
            File toml = UltimateDirs.addonConfigToml(addon);
            files.add(new PerAddonFile(addon, toml));
            if (!toml.exists()) {
                generateFromBundle(addon, toml);
            }
            loaded.put(addon, loadAddonToml(addon, toml));
            if (addon.equals(AddonCatalog.CORE)) {
                // The core file is loaded — its read budget applies to the rest
                applyTimeoutFromCore();
            }
        }

        ConsoleLogger.info("[Config] Addon configs in " + UltimateDirs.CONFIGS + ": "
                + files.size() + " file(s)");
        migrateMovedSections();
        initialized = true;
    }

    /** Applies {@code config.read_timeout_sec} from the core view (default 5s, clamp 1..300). */
    private static void applyTimeoutFromCore() {
        FileConfiguration core = loaded.get(AddonCatalog.CORE);
        if (core != null) {
            ConfigReadGuard.setTimeoutSec(core.getInt("config.read_timeout_sec", 5));
        }
    }

    /** Whether {@link #init()} has run (composite config layer is active). */
    public static boolean isInitialized() {
        return initialized;
    }

    // ========================================================================
    // MOVED BLOCKS (settings + messages relocated between addon files)
    // ========================================================================

    /** One config block that moved from one addon's TOML to another. */
    private record MovedBlock(String sourceAddon, String prefix, String targetAddon) {}

    /**
     * Blocks relocated out of UI-Other (and one block out of UI-Core) into the
     * addon whose context they belong to. On an existing installation the values
     * are copied to the target file (overriding the freshly repaired defaults)
     * and removed from the source, so no duplicate survives and user
     * customisation is preserved. On a fresh installation the bundled templates
     * already hold the blocks only in their new files — nothing to move.
     */
    private static final List<MovedBlock> MOVED_BLOCKS = List.of(
            // settings
            new MovedBlock("UI-Other", "features.antimatter", "UI-Items"),
            new MovedBlock("UI-Other", "features.attributes", "UI-Player"),
            new MovedBlock("UI-Other", "features.beacon", "UI-Player"),
            new MovedBlock("UI-Other", "features.blockdmg", "UI-Player"),
            new MovedBlock("UI-Other", "features.boostedcobweb", "UI-Player"),
            new MovedBlock("UI-Other", "features.block_collapse", "UI-World"),
            new MovedBlock("UI-Other", "features.deathbell", "UI-Player"),
            new MovedBlock("UI-Other", "features.dragonegg", "UI-World"),
            new MovedBlock("UI-Other", "features.enderchest", "UI-World"),
            new MovedBlock("UI-Other", "features.entitylocator", "UI-Items"),
            new MovedBlock("UI-Other", "features.exp_bottle_upgrade", "UI-Items"),
            new MovedBlock("UI-Other", "features.glassbreak", "UI-Player"),
            new MovedBlock("UI-Other", "features.magnet", "UI-World"),
            new MovedBlock("UI-Other", "features.modeprotect", "UI-Essentials"),
            new MovedBlock("UI-Other", "features.shieldslowness", "UI-Player"),
            new MovedBlock("UI-Other", "features.terracotaspeed", "UI-Player"),
            new MovedBlock("UI-Other", "features.unbreakable_breaker", "UI-World"),
            new MovedBlock("UI-Other", "brand_spoof", "UI-Essentials"),
            new MovedBlock("UI-Other", "sunburn", "UI-Player"),
            new MovedBlock("UI-Other", "power", "UI-Essentials"),
            new MovedBlock("UI-Other", "scheduled_restart", "UI-Essentials"),
            new MovedBlock("UI-Other", "suicide", "UI-Player"),
            new MovedBlock("UI-Other", "motd", "UI-Display"),
            new MovedBlock("UI-Other", "structure_integrity", "UI-World"),
            // messages
            new MovedBlock("UI-Other", "messages.power", "UI-Essentials"),
            new MovedBlock("UI-Other", "messages_en.power", "UI-Essentials"),
            new MovedBlock("UI-Other", "messages.suicide", "UI-Player"),
            new MovedBlock("UI-Other", "messages_en.suicide", "UI-Player"),
            new MovedBlock("UI-Core", "messages.features.modeprotect", "UI-Essentials"),
            new MovedBlock("UI-Core", "messages_en.features.modeprotect", "UI-Essentials")
    );

    /**
     * Copies every moved block to its new file, removes it from the old file and
     * persists both. Runs after all files are loaded (so the target view exists)
     * and is idempotent: an installation already cleaned up has no source keys.
     */
    private static void migrateMovedSections() {
        boolean any = false;
        boolean attributesMoved = false;
        for (MovedBlock block : MOVED_BLOCKS) {
            FileConfiguration source = loaded.get(block.sourceAddon());
            FileConfiguration target = loaded.get(block.targetAddon());
            if (source == null || target == null) continue;

            List<String> leaves = new ArrayList<>();
            for (String key : source.getKeys(true)) {
                if (source.isConfigurationSection(key)) continue;
                if (key.equals(block.prefix()) || key.startsWith(block.prefix() + ".")) {
                    leaves.add(key);
                }
            }
            if (leaves.isEmpty()) continue;

            for (String key : leaves) {
                target.set(key, source.get(key));
            }
            for (String key : leaves) {
                source.set(key, null);
            }
            source.set(DIRTY_KEY, true);
            target.set(DIRTY_KEY, true);
            if (block.targetAddon().equals("UI-Player") && block.prefix().equals("features.attributes")) {
                attributesMoved = true;
            }
            ConsoleLogger.info("[Config] Moved " + block.prefix() + ": "
                    + block.sourceAddon() + ".toml → " + block.targetAddon() + ".toml");
            any = true;
        }
        if (!any) return;

        if (attributesMoved) {
            FileConfiguration player = loaded.get("UI-Player");
            if (player instanceof YamlConfiguration yaml) upgradeAttributeUnits(yaml);
        }
        saveAll();
    }

    /** Re-reads every per-addon TOML file (called by {@code /ui reload}). */
    public static synchronized void reloadAll() {
        for (PerAddonFile f : files) {
            loaded.put(f.addon, loadAddonToml(f.addon, f.toml));
            if (f.addon.equals(AddonCatalog.CORE)) {
                applyTimeoutFromCore();
            }
        }
        migrateMovedSections();
        ConsoleLogger.info("[Config] Addon configs reloaded (" + files.size() + " file(s))");
    }

    /**
     * Saves in-memory changes back to disk.
     * Only files that were actually touched are rewritten ({@link #touch(String)}).
     */
    public static synchronized void saveAll() {
        for (PerAddonFile f : files) {
            FileConfiguration cfg = loaded.get(f.addon);
            if (cfg == null || !cfg.getBoolean("__dirty", false)) continue;
            writeAddonToml(f.addon, f.toml, cfg);
            cfg.set("__dirty", null);
        }
    }

    /** Marks an addon's config as modified (persisted by the next saveAll()). */
    public static void touch(String addon) {
        FileConfiguration cfg = loaded.get(addon);
        if (cfg != null) cfg.set("__dirty", true);
    }

    /** Marks the config that owns {@code key} as modified. */
    public static void touchKey(String key) {
        touch(addonOfKey(key));
    }

    // ========================================================================
    // VIEWS (for CompositeConfig routing)
    // ========================================================================

    /** @return the in-memory view of one addon's config (never null for known addons). */
    public static synchronized FileConfiguration viewOf(String addon) {
        FileConfiguration cfg = loaded.get(addon);
        if (cfg != null) return cfg;
        // unknown addon name — create an ephemeral in-memory view (not persisted)
        YamlConfiguration fresh = new YamlConfiguration();
        loaded.put(addon, fresh);
        return fresh;
    }

    /** @return snapshot of every loaded per-addon view (including the core's). */
    public static synchronized List<FileConfiguration> allViews() {
        return new ArrayList<>(loaded.values());
    }

    // ========================================================================
    // KEY ROUTING
    // ========================================================================

    /** @return the addon owning the given config key (path-aware, longest prefix wins). */
    public static String addonOfKey(String key) {
        return AddonCatalog.addonOfPath(key);
    }

    /** @return true if the key belongs to a per-addon file (false = UI-Core). */
    public static boolean isAddonKey(String key) {
        return !addonOfKey(key).equals(AddonCatalog.CORE);
    }

    /**
     * Reads a raw value from the owning addon's config.
     * Falls back to the bundled commented TOML template when the key is missing on disk.
     */
    public static Object get(String key) {
        String addon = addonOfKey(key);
        FileConfiguration cfg = loaded.get(addon);
        if (cfg == null) return null;
        if (cfg.isSet(key)) return cfg.get(key);
        Main plugin = Main.getInstance();
        if (plugin == null) return null;
        CommentedTomlTemplate tmpl = CommentedTomlTemplate.loadResource(plugin, "config/" + addon + ".toml");
        return tmpl == null ? null : tmpl.toBukkit().get(key);
    }

    /** Set + immediate persist into the owning addon's TOML. */
    public static void set(String key, Object value) {
        String addon = addonOfKey(key);
        FileConfiguration cfg = loaded.get(addon);
        if (cfg == null) return;
        cfg.set(key, value);
        writeAddonToml(addon, UltimateDirs.addonConfigToml(addon), cfg);
    }

    // ========================================================================
    // INTERNALS
    // ========================================================================

    /**
     * Loads (or creates) the in-memory view of one addon TOML. When the file
     * is unreadable (or the read exceeds {@code config.read_timeout_sec}) its
     * contents are salvaged ({@link TomlCrashSalvage}); if it still cannot be
     * parsed the file is backed up and regenerated from the bundled commented
     * TOML template. Comments in the file are preserved on every write (see
     * {@link CommentedTomlTemplate}).
     */
    private static FileConfiguration loadAddonToml(String addon, File toml) {
        TomlCrashSalvage.salvageFile(toml, m -> logSalvage(addon, m));
        YamlConfiguration view = new YamlConfiguration();
        if (toml.exists()) {
            if (!parseIntoView(toml, view)) {
                ConsoleLogger.warn("[Config/" + addon + "] unreadable — backing up and regenerating from the commented TOML template");
                backupFile(toml);
                generateFromBundle(addon, toml);
                if (toml.exists()) {
                    parseIntoView(toml, view);
                }
            }
        }
        // Repair: fill missing keys from the bundled commented TOML template so new
        // plugin versions add new settings/messages even when the file already exists.
        int repaired = repairFromBundle(addon, view);
        // Upgrades of values that ALREADY exist on disk but changed their default shape
        // in a newer version — repairFromBundle only fills MISSING keys.
        int upgraded = upgradeExistingValues(addon, view);
        if (repaired > 0 || upgraded > 0) {
            writeAddonToml(addon, toml, view);
        }
        return view;
    }

    /**
     * Parses one TOML file into the view under the read timeout
     * ({@code config.read_timeout_sec}). False when the file is broken OR too
     * slow to read — both recover through the backup + regenerate path.
     */
    private static boolean parseIntoView(File toml, YamlConfiguration view) {
        try {
            Map<String, Object> map = ConfigReadGuard.read(toml, () -> {
                try (InputStream in = new java.io.FileInputStream(toml)) {
                    return new com.moandjiezana.toml.Toml()
                            .read(new InputStreamReader(in, StandardCharsets.UTF_8)).toMap();
                } catch (Exception e) {
                    throw new IllegalArgumentException(e.getMessage(), e);
                }
            });
            if (map != null) flattenInto(map, "", view);
            return true;
        } catch (Exception e) {
            ConsoleLogger.warn("[Config] read failed: " + e.getMessage());
            return false;
        }
    }

    /** Fills missing keys from the bundled commented TOML template. @return number of added keys. */
    private static int repairFromBundle(String addon, YamlConfiguration view) {
        Main plugin = Main.getInstance();
        if (plugin == null) return 0;
        CommentedTomlTemplate tmpl = CommentedTomlTemplate.loadResource(plugin, "config/" + addon + ".toml");
        if (tmpl == null) return 0;
        FileConfiguration ref = tmpl.toBukkit();
        int added = 0;
        for (String path : ref.getKeys(true)) {
            if (ref.isConfigurationSection(path)) continue;
            if (!view.isSet(path)) {
                view.set(path, ref.get(path));
                added++;
            }
        }
        return added;
    }

    /** Standard "Time remaining" line of the block dialog (added by {@link #upgradeExistingValues}). */
    private static final String BLOCK_TIME_LINE =
            "<gray>Time remaining <dark_gray>»</dark_gray> <white>%duration%</white>";

    /**
     * Upgrades values that already exist on disk but changed their default shape in a
     * newer plugin version. {@link #repairFromBundle(String, YamlConfiguration)} only
     * fills MISSING keys, so a list template that gained a line would otherwise stay
     * stale forever on an existing installation (e.g. the block dialog gained the
     * "Time remaining" line in 1.8.5-alpha.34, but the live {@code block} key already
     * existed — the new line never appeared).
     * <p>
     * Currently: {@code messages.punishment.block} and {@code messages_en.punishment.block} —
     * when the stored list has no {@code %duration%} entry, the standard time line is
     * inserted right after the {@code %reason%} line (i.e. between Reason and the
     * appeal/Discord lines). Idempotent: a list that already has the line is untouched.
     *
     * @return number of upgraded values
     */
    private static int upgradeExistingValues(String addon, YamlConfiguration view) {
        if ("UI-Chat".equals(addon)) return upgradeChatFormatColors(view);
        if ("UI-Player".equals(addon)) return upgradeAttributeUnits(view);
        if ("UI-MBS".equals(addon)) return upgradeMeltdownCountdowns(view);
        if ("UI-Guard".equals(addon)) return upgradeGuardCommandLists(view);
        if (!"UI-Core".equals(addon)) return 0;
        int upgraded = 0;
        for (String root : new String[]{"messages", "messages_en"}) {
            String path = root + ".punishment.block";
            if (!view.isSet(path)) continue;
            List<String> lines = view.getStringList(path);
            if (lines == null || lines.isEmpty()) continue;

            boolean hasDuration = false;
            for (String line : lines) {
                if (line != null && line.contains("%duration%")) {
                    hasDuration = true;
                    break;
                }
            }
            if (hasDuration) continue;

            List<String> updated = new ArrayList<>(lines);
            int insertAt = updated.size();
            for (int i = 0; i < updated.size(); i++) {
                String line = updated.get(i);
                if (line != null && line.contains("%reason%")) {
                    insertAt = i + 1;
                    break;
                }
            }
            updated.add(insertAt, BLOCK_TIME_LINE);
            view.set(path, updated);
            upgraded++;
            ConsoleLogger.info("[Config/UI-Core] Upgraded " + path
                    + ": inserted the \"Time remaining\" line into the block dialog template");
        }
        return upgraded;
    }

    /**
     * Vanilla {@code /op}, {@code /deop}, {@code /stop} and {@code /restart}
     * no longer belong to the command policy: they have dedicated
     * deprecated-but-allowed flows (repeat-to-confirm, console confirmation).
     * Existing installations still list them in
     * {@code command_policy.disabled_commands} (blocked with 011) and miss
     * {@code stop}/{@code restart} in {@code sudo.dangerous_commands}.
     * <p>
     * This upgrade removes those four entries from the disabled list, appends
     * {@code op}/{@code deop}/{@code stop}/{@code restart} to the sudo list once,
     * and turns the policy off when its list becomes empty. Idempotent — a
     * config already in the new shape is untouched.
     *
     * @return number of upgraded values
     */
    private static int upgradeGuardCommandLists(YamlConfiguration view) {
        int upgraded = 0;

        List<String> disabled = view.getStringList("command_policy.disabled_commands");
        if (disabled != null && !disabled.isEmpty()) {
            List<String> filtered = disabled.stream()
                    .filter(entry -> entry != null && !isDedicatedFlowCommand(entry))
                    .toList();
            if (filtered.size() != disabled.size()) {
                view.set("command_policy.disabled_commands", new ArrayList<>(filtered));
                upgraded++;
                ConsoleLogger.info("[Config/UI-Guard] command_policy.disabled_commands: "
                        + "removed op/deop/stop/restart — they have dedicated flows now");
            }
            // A policy left without any entries is meaningless: default is off.
            if (filtered.isEmpty() && view.getBoolean("command_policy.enabled", false)) {
                view.set("command_policy.enabled", false);
                upgraded++;
                ConsoleLogger.info("[Config/UI-Guard] command_policy.enabled: disabled "
                        + "(the disabled-commands list is empty)");
            }
        }

        List<String> dangerous = view.getStringList("sudo.dangerous_commands");
        if (dangerous != null && !dangerous.isEmpty()) {
            List<String> updated = new ArrayList<>(dangerous);
            boolean changed = false;
            for (String entry : new String[]{"op", "deop", "stop", "restart"}) {
                if (updated.stream().noneMatch(existing -> entry.equalsIgnoreCase(existing.trim()))) {
                    updated.add(entry);
                    changed = true;
                }
            }
            if (changed) {
                view.set("sudo.dangerous_commands", updated);
                upgraded++;
                ConsoleLogger.info("[Config/UI-Guard] sudo.dangerous_commands: "
                        + "added vanilla op/deop/stop/restart");
            }
        }

        return upgraded;
    }

    /** Whether a command-policy entry points at a command with a dedicated flow. */
    private static boolean isDedicatedFlowCommand(String entry) {
        String normalized = entry.trim().toLowerCase(java.util.Locale.ROOT);
        if (normalized.startsWith("/")) normalized = normalized.substring(1);
        return normalized.equals("op") || normalized.equals("deop")
                || normalized.equals("stop") || normalized.equals("restart")
                || normalized.equals("minecraft:op") || normalized.equals("minecraft:deop")
                || normalized.equals("minecraft:stop") || normalized.equals("minecraft:restart")
                || normalized.equals("bukkit:op") || normalized.equals("bukkit:deop")
                || normalized.equals("bukkit:stop") || normalized.equals("bukkit:restart");
    }

    /**
     * Old installations still carry the former 10-second defaults for both
     * reactor countdowns. They were never changed by the user, so they are
     * raised to the new 20-second default once; any other value is kept as-is.
     * Idempotent: a config already at 20 is untouched.
     *
     * @return number of upgraded values
     */
    private static int upgradeMeltdownCountdowns(YamlConfiguration view) {
        int upgraded = 0;
        if (view.isSet("nuclear_reactor.meltdown_seconds")
                && view.getInt("nuclear_reactor.meltdown_seconds", 0) == 10) {
            view.set("nuclear_reactor.meltdown_seconds", 20);
            upgraded++;
        }
        if (view.isSet("reactor.shield_failure_countdown")
                && view.getInt("reactor.shield_failure_countdown", 0) == 10) {
            view.set("reactor.shield_failure_countdown", 20);
            upgraded++;
        }
        if (upgraded > 0) {
            ConsoleLogger.info("[Config/UI-MBS] Upgraded the reactor countdown default(s) from 10s to 20s");
        }
        return upgraded;
    }

    /**
     * Migrates the legacy flat {@code features.attributes.*} keys (one global
     * interval, three fixed attributes) into the numbered
     * {@code features.attributes.units.<n>} units used by the scheduled-grant
     * manager: the old {@code attack_damage} / {@code sneak_speed} /
     * {@code attack_speed} values become units 5-7 (their stock numbering in the
     * bundled template) and inherit the old global {@code interval_ticks}.
     * <p>
     * Only fires once per installation — the legacy keys are removed after the
     * conversion, so a later {@code /ui reload} is a no-op. Idempotent: a config
     * without legacy keys is untouched.
     *
     * @return number of upgraded units / removed keys
     */
    private static int upgradeAttributeUnits(YamlConfiguration view) {
        final String base = "features.attributes";

        int upgraded = upgradeLegacyAttributeKeys(view, base) + pruneForeignUnitParams(view, base);
        return upgraded;
    }

    /**
     * Legacy flat keys ({@code features.attributes.attack_damage|sneak_speed|attack_speed})
     * → {@code units.5-7}. Those keys were base-value overrides applied on a timer;
     * they are migrated as scheduled MODIFIERS with a {@code target}, so the
     * effective value stays exactly the old one (the manager derives the modifier
     * amount from the live base value) while nothing overwrites the base value.
     */
    private static int upgradeLegacyAttributeKeys(YamlConfiguration view, String base) {
        // legacy key -> [unit number, attribute key]
        final String[][] legacy = {
                {"attack_damage", "5", "minecraft:attack_damage"},
                {"sneak_speed", "6", "minecraft:sneaking_speed"},
                {"attack_speed", "7", "minecraft:attack_speed"}
        };

        int upgraded = 0;
        boolean hasLegacyKeys = false;
        for (String[] row : legacy) {
            if (!view.isSet(base + "." + row[0])) continue;
            hasLegacyKeys = true;

            // A customized old interval applied to all three attributes — carry it over.
            int interval = Math.max(1, view.getInt(base + ".interval_ticks", 200));

            String unit = base + ".units." + row[1];
            view.set(unit + ".type", "modifier");
            view.set(unit + ".attribute", row[2]);
            view.set(unit + ".target", view.getDouble(base + "." + row[0]));
            view.set(unit + ".operation", "add_number");
            view.set(unit + ".slot", "any");
            view.set(unit + ".schedule", true);
            view.set(unit + ".interval_ticks", interval);
            if (!view.isSet(unit + ".targets")) view.set(unit + ".targets", List.of("all"));

            view.set(unit + ".value", null);     // base-only key, no longer used
            view.set(base + "." + row[0], null);  // legacy key is gone
            upgraded++;
            ConsoleLogger.info("[Config/UI-Player] Upgraded " + base + "." + row[0]
                    + " → " + unit + " (target-valued modifier unit)");
        }

        if (hasLegacyKeys) {
            view.set(base + ".interval_ticks", null); // interval now lives per unit
        }
        return upgraded;
    }

    /**
     * Removes the parameters that do not belong to a unit's EXPLICIT type:
     * {@code base} units own {@code value} only, {@code modifier} units own
     * {@code target}/{@code amount} (a target-valued unit drops a stale
     * {@code amount}). Configs written before the {@code type} field otherwise
     * keep such keys in the file forever and confuse readers.
     */
    private static int pruneForeignUnitParams(YamlConfiguration view, String base) {
        org.bukkit.configuration.ConfigurationSection units =
                view.getConfigurationSection(base + ".units");
        if (units == null) return 0;

        int pruned = 0;
        for (String id : units.getKeys(false)) {
            String unit = base + ".units." + id;
            String type = view.getString(unit + ".type");
            if (type == null) continue;

            if ("base".equalsIgnoreCase(type)) {
                pruned += dropKey(view, unit + ".amount");
                pruned += dropKey(view, unit + ".target");
            } else {
                pruned += dropKey(view, unit + ".value");
                if (view.isSet(unit + ".target")) {
                    pruned += dropKey(view, unit + ".amount");
                }
            }
        }
        return pruned;
    }

    /** Removes one stale key. @return 1 when it existed, 0 otherwise. */
    private static int dropKey(YamlConfiguration view, String path) {
        if (!view.isSet(path)) return 0;
        view.set(path, null);
        ConsoleLogger.info("[Config/UI-Player] Removed stale " + path
                + " (does not belong to the unit type)");
        return 1;
    }

    /** Stock chat prefix colour (plain green) — the value migrated away from. */
    private static final String CHAT_PREFIX_GREEN = "<green>";

    /** Brand hex colour of the chat prefix — harder to imitate in a forged line. */
    private static final String CHAT_PREFIX_HEX = "<#32c800>";

    /** First glyph of the stock small-caps prefix word ({@code ᴄ} of ᴄʜᴀᴛ / ᴄᴜᴄᴇ). */
    private static final char CHAT_PREFIX_GLYPH = '\u1D04';

    /** Chat format keys whose stock prefix colour is migrated to the hex brand colour. */
    private static final String[] CHAT_FORMAT_KEYS = {
            "chat.format",
            "chat.channels.local.format",
            "chat.channels.global.format",
            "chat.channels.world.format",
            "chat.channels.private.format",
            "chat.channels.admin.format",
            "chat.channels.check.format"
    };

    /**
     * Recolours the stock chat prefix word (small-caps "chat") from plain green to the
     * brand hex colour {@code #32c800}, which is harder to imitate in a forged chat line:
     * a player who fakes the word in plain green now produces a visibly different colour.
     * <p>
     * Only formats that still START with the stock {@code <green>ᴄ} prefix are touched,
     * so a manually recoloured or fully customised format is left alone. Idempotent:
     * a format already using the hex colour no longer starts with {@code <green>ᴄ}.
     *
     * @return number of upgraded values
     */
    private static int upgradeChatFormatColors(YamlConfiguration view) {
        int upgraded = 0;
        String stockPrefix = CHAT_PREFIX_GREEN + CHAT_PREFIX_GLYPH;
        for (String key : CHAT_FORMAT_KEYS) {
            String value = view.getString(key, null);
            if (value == null || !value.startsWith(stockPrefix)) continue;
            view.set(key, CHAT_PREFIX_HEX + value.substring(CHAT_PREFIX_GREEN.length()));
            ConsoleLogger.info("[Config/UI-Chat] Upgraded " + key
                    + ": stock chat prefix " + CHAT_PREFIX_GREEN + " → " + CHAT_PREFIX_HEX);
            upgraded++;
        }
        return upgraded;
    }

    /**
     * Routes one crash-salvage report line to the matching console level instead of
     * painting every line yellow: a healthy file (✔) is a success, a commented broken
     * line (⚠) is a warning, a failed salvage (✗) is an error and the remaining lines
     * (e.g. «file does not exist on first run») are plain info.
     */
    private static void logSalvage(String addon, String message) {
        String line = "[ConfigSalvage/" + addon + "] " + message;
        if (message.startsWith("✔")) {
            ConsoleLogger.success(line);
        } else if (message.startsWith("✗")) {
            ConsoleLogger.error(line);
        } else if (message.startsWith("⚠")) {
            ConsoleLogger.warn(line);
        } else {
            ConsoleLogger.info(line);
        }
    }

    /** Writes the in-memory view back to {@code configs/<addon>.toml}, preserving comments. */
    private static void writeAddonToml(String addon, File toml, FileConfiguration view) {
        try {
            Map<String, Object> root = TomlConfigManager.sectionToMap(view);
            // internal markers must not leak into the file
            root.remove(DIRTY_KEY);
            CommentedTomlTemplate onDisk = CommentedTomlTemplate.parse(toml);
            Main plugin = Main.getInstance();
            CommentedTomlTemplate bundled = plugin == null ? null
                    : CommentedTomlTemplate.loadResource(plugin, "config/" + addon + ".toml");
            CommentedTomlTemplate.write(toml, root, onDisk, bundled);
        } catch (Exception e) {
            ConsoleLogger.warn("[Config/" + addon + "] Failed to save " + toml.getName()
                    + ": " + e.getMessage());
        }
    }

    /** Creates the initial {@code configs/<addon>.toml} as a verbatim copy of the bundled template. */
    private static void generateFromBundle(String addon, File toml) {
        Main plugin = Main.getInstance();
        if (plugin != null) {
            CommentedTomlTemplate tmpl = CommentedTomlTemplate.loadResource(plugin, "config/" + addon + ".toml");
            if (tmpl != null && tmpl.writeTo(toml)) {
                ConsoleLogger.info("[Config] Generated " + UltimateDirs.CONFIGS + "/" + toml.getName()
                        + " (from the commented template)");
                return;
            }
        }
        ConsoleLogger.warn("[Config] No bundled TOML template for " + addon + " — generating an empty file");
        try {
            toml.getParentFile().mkdirs();
            new com.moandjiezana.toml.TomlWriter().write(new LinkedHashMap<>(), toml);
        } catch (Exception ignored) {
        }
    }

    private static void backupFile(File file) {
        try {
            File backup = nextBrokenBackupFile(file.getName());
            java.nio.file.Files.move(file.toPath(), backup.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            ConsoleLogger.warn("[Config] Backed up " + file.getName() + " as " + backup.getName());
        } catch (Exception e) {
            ConsoleLogger.warn("[Config] Failed to back up " + file.getName() + ": " + e.getMessage());
        }
    }

    // ========================================================================
    // BACKUPS + MANUAL REGENERATION (/ui config regen)
    // ========================================================================

    /**
     * Builds the next numbered broken-backup file for the given config file
     * name: {@code UI-<Addon>-broken-<N>.toml} where N is the smallest free
     * number (1, 2, 3, ...). The backup lives next to the config, inside
     * {@code configs/}.
     */
    static File nextBrokenBackupFile(String configFileName) {
        String base = configFileName.endsWith(".toml")
                ? configFileName.substring(0, configFileName.length() - ".toml".length())
                : configFileName;
        for (int n = 1; n < 10_000; n++) {
            File candidate = new File(UltimateDirs.configsDir(), base + "-broken-" + n + ".toml");
            if (!candidate.exists()) return candidate;
        }
        // Practically unreachable — fall back to overwrite of the last slot.
        return new File(UltimateDirs.configsDir(), base + "-broken-9999.toml");
    }

    /** {@code UI-<Addon>-broken-<N>} name the NEXT backup of this addon would get (for UI hints). */
    public static String brokenBackupName(String addon) {
        return nextBrokenBackupFile(addon + ".toml").getName();
    }

    /** @return the live config file of the addon inside {@code configs/}. */
    public static File configFileOf(String addon) {
        return UltimateDirs.addonConfigToml(addon);
    }

    /**
     * Resolves a config file name ({@code UI-<Addon>.toml}) to its addon.
     *
     * @return the addon name, or {@code null} when the file does not belong to
     *         any known addon
     */
    public static String addonForConfigFileName(String fileName) {
        if (fileName == null || !fileName.toLowerCase(Locale.ROOT).endsWith(".toml")) return null;
        String base = fileName.substring(0, fileName.length() - ".toml".length());
        for (String addon : AddonCatalog.catalog()) {
            if (addon.equalsIgnoreCase(base)) return addon;
        }
        return null;
    }

    /** All addons that can be regenerated via {@code /ui config regen} (any catalog addon). */
    public static List<String> regenerableAddons() {
        return AddonCatalog.catalog();
    }

    /**
     * Manually backs up the addon's live config as a numbered broken backup
     * and regenerates the file from the bundled template (like the automatic
     * recovery does). The in-memory view is reloaded afterwards.
     *
     * @return the backup file name (inside {@code configs/})
     * @throws java.io.IOException when the backup or regeneration fails
     */
    public static String backupAndRegenerate(String addon) throws java.io.IOException {
        File toml = UltimateDirs.addonConfigToml(addon);
        if (!toml.exists()) {
            throw new java.io.IOException(addon + ".toml does not exist — nothing to regenerate");
        }
        File backup = nextBrokenBackupFile(toml.getName());
        try {
            java.nio.file.Files.copy(toml.toPath(), backup.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            ConsoleLogger.warn("[Config] Backed up " + toml.getName() + " as " + backup.getName());
        } catch (java.io.IOException e) {
            throw new java.io.IOException("backup failed: " + e.getMessage(), e);
        }
        // Regenerate: verbatim template copy + fresh in-memory load.
        generateFromBundle(addon, toml);
        loaded.put(addon, loadAddonToml(addon, toml));
        return backup.getName();
    }

    /** Recursively flattens a TOML map into the Bukkit view (dotted paths). */
    private static void flattenInto(Map<String, Object> map, String prefix, YamlConfiguration target) {
        for (Map.Entry<String, Object> e : map.entrySet()) {
            // toml4j keeps the quotes of a quoted table-header segment (units."1"),
            // so normalize them away and use the plain path everywhere.
            String segment = CommentedTomlTemplate.stripQuotes(e.getKey());
            String path = prefix.isEmpty() ? segment : prefix + "." + segment;
            Object value = e.getValue();
            if (value instanceof Map<?, ?> nested) {
                @SuppressWarnings("unchecked")
                Map<String, Object> nestedMap = (Map<String, Object>) nested;
                flattenInto(nestedMap, path, target);
            } else {
                target.set(path, value);
            }
        }
    }
}
