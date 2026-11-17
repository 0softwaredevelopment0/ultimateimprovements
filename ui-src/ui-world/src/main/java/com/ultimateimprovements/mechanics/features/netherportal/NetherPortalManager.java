package com.ultimateimprovements.mechanics.features.netherportal;

import com.ultimateimprovements.core.Main;
import com.ultimateimprovements.util.ConsoleLogger;
import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.GameRules;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Breaks nether portals when players or entities enter them.
 * <p>
 * The manager runs two independent tasks while {@code nether_portal.enabled} is
 * true:
 * <ul>
 *   <li>the <b>scan task</b> (every {@code check_interval_ticks}) walks the
 *       worlds allowed by the world filter and calls
 *       {@link Block#breakNaturally()} on a {@code NETHER_PORTAL} block at an
 *       entity's position — vanilla then collapses the portal exactly like a
 *       player breaking one portal block;</li>
 *   <li>the <b>gamerule task</b> (every {@code gamerule_interval_ticks})
 *       re-applies the configured portal delays, so a datapack or another
 *       plugin cannot lower them below the scan interval.</li>
 * </ul>
 * Both gamerules are set to 80 ticks (the vanilla survival default) whenever
 * they are enabled: 80 is greater than the default 20-tick scan interval, so
 * the portal is always broken before vanilla teleports the entity, and turning
 * the feature off leaves the server on a sane default.
 * <p>
 * <b>World filter.</b> {@code world_mode = "all"} processes every loaded world
 * and ignores {@code worlds}. {@code whitelist} / {@code blacklist} resolve the
 * configured dimension IDs (for example {@code minecraft:the_nether}) against
 * the loaded worlds. Missing dimensions are logged once per start/reload and
 * dropped from the active set for every later scan and gamerule pass.
 */
public final class NetherPortalManager {

    /** Vanilla survival portal delay; 80 > 20 (default scan interval). */
    static final int PORTAL_DELAY_TICKS = 80;

    /** Who is scanned for portal entry. */
    public enum Target {
        PLAYERS, ENTITIES;

        public String configValue() {
            return this == PLAYERS ? "players" : "entities";
        }
    }

    /** How the configured world list is interpreted. */
    public enum WorldMode {
        ALL, WHITELIST, BLACKLIST;

        public String configValue() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private static NetherPortalManager instance;

    private Main plugin;
    private BukkitTask scanTask;
    private BukkitTask gameruleTask;

    // ===== CONFIG =====
    private boolean enabled;
    private Target target = Target.PLAYERS;
    private int checkIntervalTicks = 20;
    private WorldMode worldMode = WorldMode.ALL;
    private Set<String> configuredWorlds = Set.of();
    /** Configured keys that existed on the last start/reload (lower-cased). */
    private Set<String> validWorlds = Set.of();
    private boolean setDefaultDelayGamerule = true;
    private boolean setCreativeDelayGamerule = true;
    private int gameruleIntervalTicks = 200;

    /** Portals broken since the last start/reload (status counter). */
    private long brokenPortals;

    private NetherPortalManager() {}

    // =========================
    // LIFECYCLE
    // =========================

    public static void init(Main plugin) {
        if (instance != null) instance.shutdownInternal();
        instance = new NetherPortalManager();
        instance.start(plugin);
    }

    /** Restarts both tasks with fresh config (called by {@code /ui reload}). */
    public static void reload() {
        if (instance != null) instance.start(instance.plugin);
    }

    public static void shutdown() {
        if (instance != null) {
            instance.shutdownInternal();
            instance = null;
        }
    }

    public static NetherPortalManager getInstance() {
        return instance;
    }

    /** Reads the config and (re)starts the tasks. Idempotent. */
    public synchronized void start(Main plugin) {
        this.plugin = plugin;
        stopTasks();
        brokenPortals = 0L;

        if (plugin == null) return;

        loadConfig();

        if (!enabled) {
            ConsoleLogger.info("[NetherPortal] Disabled in config (nether_portal.enabled: false)");
            return;
        }

        if ((setDefaultDelayGamerule || setCreativeDelayGamerule) && checkIntervalTicks >= PORTAL_DELAY_TICKS) {
            ConsoleLogger.warn("[NetherPortal] check_interval_ticks (" + checkIntervalTicks
                    + ") is not below the portal delay (" + PORTAL_DELAY_TICKS
                    + "t) — vanilla may teleport the entity before the scan.");
        }

        applyGamerules();
        if (setDefaultDelayGamerule || setCreativeDelayGamerule) {
            gameruleTask = Bukkit.getScheduler().runTaskTimer(
                    plugin, this::applyGamerules, gameruleIntervalTicks, gameruleIntervalTicks);
        }

        scanTask = Bukkit.getScheduler().runTaskTimer(
                plugin, this::scan, checkIntervalTicks, checkIntervalTicks);

        ConsoleLogger.info("[NetherPortal] Enabled: target=" + target.configValue()
                + ", check=" + checkIntervalTicks + "t"
                + ", worlds=" + describeWorldsPlain()
                + ", gamerules=" + describeGamerulesPlain()
                + (gameruleTask != null ? " every " + gameruleIntervalTicks + "t" : ""));
    }

    private void shutdownInternal() {
        stopTasks();
        enabled = false;
        plugin = null;
        configuredWorlds = Set.of();
        validWorlds = Set.of();
    }

    private void stopTasks() {
        if (scanTask != null) {
            scanTask.cancel();
            scanTask = null;
        }
        if (gameruleTask != null) {
            gameruleTask.cancel();
            gameruleTask = null;
        }
    }

    private void loadConfig() {
        ConfigurationSection sec = plugin.getConfig().getConfigurationSection("nether_portal");
        if (sec == null) {
            enabled = false;
            ConsoleLogger.warn("[NetherPortal] Config section 'nether_portal' not found — mechanic DISABLED."
                    + " Add it to configs/UI-World.toml to enable it.");
            return;
        }

        enabled = sec.getBoolean("enabled", false);
        target = parseTarget(sec.getString("target", "players"));
        checkIntervalTicks = clampInterval(sec.getInt("check_interval_ticks", 20));
        worldMode = parseWorldMode(sec.getString("world_mode", "all"));
        configuredWorlds = normalizeWorlds(sec.getStringList("worlds"));
        setDefaultDelayGamerule = sec.getBoolean("set_default_delay_gamerule", true);
        setCreativeDelayGamerule = sec.getBoolean("set_creative_delay_gamerule", true);
        gameruleIntervalTicks = clampInterval(sec.getInt("gamerule_interval_ticks", 200));

        // The list is only meaningful for whitelist/blacklist. In "all" mode it
        // is ignored completely (the bundled minecraft:example stays silent).
        validWorlds = worldMode == WorldMode.ALL ? Set.of() : resolveWorlds(configuredWorlds);
    }

    static int clampInterval(int value) {
        return Math.max(1, value);
    }

    static Target parseTarget(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "players", "player" -> Target.PLAYERS;
            case "entities", "entity", "all" -> Target.ENTITIES;
            default -> {
                ConsoleLogger.warn("[NetherPortal] Unknown target '" + raw + "' — using 'players'");
                yield Target.PLAYERS;
            }
        };
    }

    static WorldMode parseWorldMode(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "all" -> WorldMode.ALL;
            case "whitelist", "white" -> WorldMode.WHITELIST;
            case "blacklist", "black" -> WorldMode.BLACKLIST;
            default -> {
                ConsoleLogger.warn("[NetherPortal] Unknown world_mode '" + raw + "' — using 'all'");
                yield WorldMode.ALL;
            }
        };
    }

    /** Lower-cases and de-duplicates configured dimension IDs (order preserved). */
    static Set<String> normalizeWorlds(List<String> names) {
        if (names == null || names.isEmpty()) return Set.of();
        Set<String> out = new LinkedHashSet<>();
        for (String name : names) {
            if (name != null && !name.isBlank()) {
                out.add(name.trim().toLowerCase(Locale.ROOT));
            }
        }
        return Collections.unmodifiableSet(out);
    }

    /**
     * Resolves configured dimension IDs against the loaded worlds. Unknown
     * entries are logged once and dropped; they are not retried on the gamerule
     * iterations.
     */
    private Set<String> resolveWorlds(Set<String> configured) {
        if (configured.isEmpty()) return Set.of();

        Set<String> valid = new LinkedHashSet<>();
        for (String id : configured) {
            World world = findWorld(id);
            if (world == null) {
                ConsoleLogger.warn("[NetherPortal] World '" + id + "' does not exist — ignoring it.");
                continue;
            }
            valid.add(world.getKey().toString().toLowerCase(Locale.ROOT));
        }
        return Collections.unmodifiableSet(valid);
    }

    private World findWorld(String id) {
        for (World world : Bukkit.getWorlds()) {
            if (world.getKey().toString().equalsIgnoreCase(id)) return world;
        }
        return null;
    }

    // =========================
    // TASKS
    // =========================

    private void scan() {
        if (!enabled || plugin == null) return;

        for (World world : allowedWorlds()) {
            if (target == Target.PLAYERS) {
                for (Player player : world.getPlayers()) {
                    tryBreakPortal(player);
                }
            } else {
                for (Entity entity : world.getEntities()) {
                    tryBreakPortal(entity);
                }
            }
        }
    }

    private void tryBreakPortal(Entity entity) {
        if (entity == null || entity.isDead()) return;

        Block block = entity.getLocation().getBlock();
        if (block.getType() != Material.NETHER_PORTAL) return;

        // Natural break: vanilla block updates invalidate the portal and remove
        // the connected portal blocks, exactly as if a player had hit it.
        block.breakNaturally();
        brokenPortals++;
    }

    private void applyGamerules() {
        if (!enabled || plugin == null) return;

        for (World world : allowedWorlds()) {
            if (setDefaultDelayGamerule) {
                applyGamerule(world, GameRules.PLAYERS_NETHER_PORTAL_DEFAULT_DELAY);
            }
            if (setCreativeDelayGamerule) {
                applyGamerule(world, GameRules.PLAYERS_NETHER_PORTAL_CREATIVE_DELAY);
            }
        }
    }

    private static void applyGamerule(World world, GameRule<Integer> rule) {
        Integer current = world.getGameRuleValue(rule);
        if (current == null || current != PORTAL_DELAY_TICKS) {
            world.setGameRule(rule, PORTAL_DELAY_TICKS);
        }
    }

    /** Loaded worlds allowed by the current filter (main-thread use). */
    private List<World> allowedWorlds() {
        List<World> out = new ArrayList<>();
        for (World world : Bukkit.getWorlds()) {
            String key = world.getKey().toString().toLowerCase(Locale.ROOT);
            boolean allowed = switch (worldMode) {
                case ALL -> true;
                case WHITELIST -> validWorlds.contains(key);
                case BLACKLIST -> !validWorlds.contains(key);
            };
            if (allowed) out.add(world);
        }
        return out;
    }

    // =========================
    // COMMANDS
    // =========================

    /**
     * Turns the mechanic on/off from an in-game command and writes the value
     * back to {@code UI-World.toml}.
     *
     * @return true when the config write was performed
     */
    public synchronized boolean setEnabled(boolean value) {
        Main main = plugin;
        if (main == null) return false;

        main.getConfig().set("nether_portal.enabled", value);
        main.saveConfig();
        start(main);
        return true;
    }

    /**
     * Changes the scan target from an in-game command and writes the value back
     * to {@code UI-World.toml}.
     *
     * @return true when the config write was performed
     */
    public synchronized boolean setTarget(Target value) {
        Main main = plugin;
        if (main == null || value == null) return false;

        main.getConfig().set("nether_portal.target", value.configValue());
        main.saveConfig();
        start(main);
        return true;
    }

    // =========================
    // QUERIES
    // =========================

    public boolean isEnabled() {
        return enabled;
    }

    public Target getTarget() {
        return target;
    }

    public int getCheckIntervalTicks() {
        return checkIntervalTicks;
    }

    public WorldMode getWorldMode() {
        return worldMode;
    }

    public boolean isSetDefaultDelayGamerule() {
        return setDefaultDelayGamerule;
    }

    public boolean isSetCreativeDelayGamerule() {
        return setCreativeDelayGamerule;
    }

    public int getGameruleIntervalTicks() {
        return gameruleIntervalTicks;
    }

    public long getBrokenPortals() {
        return brokenPortals;
    }

    /** Effective dimension IDs (validated); empty in {@code all} mode. */
    public Set<String> getEffectiveWorlds() {
        return validWorlds;
    }

    /** Human-readable world filter for {@code /ui netherportal status}. */
    public String describeWorlds() {
        if (worldMode == WorldMode.ALL) return "all worlds";
        if (validWorlds.isEmpty()) return worldMode.configValue() + ": (none)";
        List<String> sorted = new ArrayList<>(validWorlds);
        Collections.sort(sorted);
        return worldMode.configValue() + ": " + String.join(", ", sorted);
    }

    private String describeWorldsPlain() {
        return describeWorlds();
    }

    /** Human-readable gamerule state for {@code /ui netherportal status}. */
    public String describeGamerules() {
        if (!setDefaultDelayGamerule && !setCreativeDelayGamerule) return "off";
        List<String> parts = new ArrayList<>(2);
        if (setDefaultDelayGamerule) parts.add("default=" + PORTAL_DELAY_TICKS + "t");
        if (setCreativeDelayGamerule) parts.add("creative=" + PORTAL_DELAY_TICKS + "t");
        return String.join(", ", parts);
    }

    private String describeGamerulesPlain() {
        return describeGamerules();
    }
}
