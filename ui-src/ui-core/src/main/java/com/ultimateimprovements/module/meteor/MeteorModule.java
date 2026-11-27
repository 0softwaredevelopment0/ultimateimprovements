package com.ultimateimprovements.module.meteor;

import com.ultimateimprovements.core.Main;
import com.ultimateimprovements.module.PluginModule;
import com.ultimateimprovements.util.ConsoleLogger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * MeteorModule — manages the meteor shower.
 * <p>
 * On a timer picks a random player in one of the configured meteor worlds,
 * finds the surface near them and launches {@link MeteorTask} — a vertical
 * meteor fall that ends with an explosion and a meteorite structure.
 * <p>
 * The timer is driven by the configurable cooldown from
 * {@link MeteorCooldown} ({@code [meteor.cooldown]}): a fixed value, a random
 * entry of a list, or a random number of seconds inside a range. The scheduler
 * polls the wall-clock deadline once per second, which is what makes
 * {@code /ui meteor status|skip|unskip|reset} exact.
 * <p>
 * Target worlds come from {@code meteor.worlds} (a list); an empty list falls
 * back to the single {@code meteor.world} name. The old
 * {@code space.meteor_space_only} switch used to redirect meteors to the void
 * {@code ui_space} dimension, where there is no surface to land on, so the
 * mechanic silently spawned nothing anywhere. It is obsolete since
 * 1.8.5-alpha.19: list {@code "ui_space"} in {@code meteor.worlds} instead.
 */
public class MeteorModule extends PluginModule {

    /** Extra random surface probes before giving up on the whole spawn radius. */
    private static final int RETRY_ATTEMPTS = 5;
    /** Minimum free room above the surface for a proper fall from the sky. */
    private static final int MIN_FALL_GAP = 20;
    /** Meteor start height above the impact point, in blocks (random range). */
    private static final int FALL_HEIGHT_MIN = 80;
    private static final int FALL_HEIGHT_MAX = 120;

    /** How often the scheduler compares the wall clock with the next spawn. */
    private static final long SCHEDULER_PERIOD_TICKS = 20L;

    private static MeteorModule instance;

    /** One-time flag for the obsolete key notices (module re-inits on every soft reload). */
    private static boolean obsoleteSpaceKeyLogged = false;
    private static boolean obsoleteMeteorSizeKeyLogged = false;

    private BukkitTask scheduler;
    private final List<MeteorTask> activeMeteors = new ArrayList<>();
    private Random random = new Random();

    /** Cooldown rules loaded from {@code [meteor.cooldown]}. */
    private MeteorCooldown cooldown = MeteorCooldown.defaults();
    /** Wall-clock deadline of the next spawn; 0 when the scheduler is off. */
    private long nextSpawnAtMillis;
    /** The next due meteor is skipped (cleared when it fires or by unskip). */
    private boolean skipPending;

    public MeteorModule() {
        super("Meteor", "modules/meteor", false);
    }

    public static MeteorModule getInstance() {
        return instance;
    }

    @Override
    protected void onInit(JavaPlugin plugin) throws Exception {
        instance = this;
        random = new Random();

        warnAboutObsoleteSpaceOnlyKey();
        warnAboutObsoleteMeteorKeys();
        rescheduleNext(plugin);
    }

    @Override
    protected void onReloadConfig(JavaPlugin plugin) {
        // onInit runs only at startup — re-read meteor.enabled/cooldown on /ui reload.
        rescheduleNext(plugin);
    }

    @Override
    protected void onDisable(JavaPlugin plugin) {
        if (scheduler != null) { scheduler.cancel(); scheduler = null; }
        nextSpawnAtMillis = 0L;
        skipPending = false;

        for (MeteorTask task : new ArrayList<>(activeMeteors)) {
            try {
                task.cancel();
                task.cleanup(); // cancel() alone does not remove the Marker/BlockDisplay entities
            } catch (Exception ignored) {}
        }
        activeMeteors.clear();

        ConsoleLogger.info("[Meteor] Module disabled.");
    }

    // ========================================================================
    // HELPERS
    // ========================================================================

    private FileConfiguration cfg() {
        return Main.getInstance().getConfig();
    }

    /**
     * The old {@code space.meteor_space_only} key is ignored now. It still lives
     * in already-deployed configs (repair only adds keys, it never removes
     * them), so say it once at startup instead of leaving the admin guessing.
     */
    private void warnAboutObsoleteSpaceOnlyKey() {
        if (obsoleteSpaceKeyLogged) return;
        obsoleteSpaceKeyLogged = true;
        try {
            if (cfg().isSet("space.meteor_space_only")) {
                ConsoleLogger.info("[Meteor] 'space.meteor_space_only' is obsolete and ignored — "
                        + "list the meteor worlds in [meteor] worlds = [\"world\", \"ui_space\"] instead "
                        + "(an empty list falls back to meteor.world).");
            }
        } catch (Exception ignored) {
            // A deprecated key must never break module startup.
        }
    }

    /**
     * {@code meteor.sphere_radius} and {@code meteor.explosion_power} were
     * replaced by the size rule ({@code [meteor.size]}) and the scaling formula
     * ({@code [meteor.explosion]}). They stay in deployed configs because the
     * repair only adds keys, so say it once.
     */
    private void warnAboutObsoleteMeteorKeys() {
        if (obsoleteMeteorSizeKeyLogged) return;
        obsoleteMeteorSizeKeyLogged = true;
        try {
            if (cfg().isSet("meteor.sphere_radius") || cfg().isSet("meteor.explosion_power")) {
                ConsoleLogger.info("[Meteor] 'meteor.sphere_radius' and 'meteor.explosion_power' are obsolete "
                        + "and ignored — use [meteor.size] (mode/static/list/range) and [meteor.explosion] "
                        + "(base/per_size/max) instead.");
            }
            if (cfg().isSet("meteor.display_blocks")) {
                ConsoleLogger.info("[Meteor] 'meteor.display_blocks' is obsolete and ignored — use the weighted "
                        + "[meteor.blocks] section instead.");
            }
        } catch (Exception ignored) {
            // A deprecated key must never break module startup.
        }
    }

    // ========================================================================
    // SCHEDULING
    // ========================================================================

    private void rescheduleNext(JavaPlugin plugin) {
        if (scheduler != null) {
            scheduler.cancel();
            scheduler = null;
        }
        nextSpawnAtMillis = 0L;
        skipPending = false;

        var cfg = cfg();
        if (!cfg.getBoolean("meteor.enabled", false)) return;

        cooldown = MeteorCooldown.fromConfig(cfg);
        armNext(plugin);

        // Poll the wall-clock deadline instead of sleeping on a single delayed
        // task: status/skip/unskip/reset can move the deadline at any moment.
        scheduler = new BukkitRunnable() {
            @Override
            public void run() {
                tickScheduler(plugin);
            }
        }.runTaskTimer(plugin, SCHEDULER_PERIOD_TICKS, SCHEDULER_PERIOD_TICKS);
    }

    /**
     * Arms the next spawn: picks a fresh cooldown with the configured mode and
     * stores the deadline. Shared by the initial schedule, the post-spawn/skip
     * re-arm and {@code /ui meteor reset}.
     */
    private void armNext(JavaPlugin plugin) {
        long seconds = cooldown.nextSeconds(random);
        nextSpawnAtMillis = System.currentTimeMillis() + seconds * 1000L;
        if (schedulerLogging(plugin)) {
            ConsoleLogger.info("[Meteor] Next meteor in " + MeteorCooldown.formatSeconds(seconds)
                    + " (" + seconds + "s, mode=" + cooldown.getMode().name().toLowerCase() + ").");
        }
    }

    /**
     * One scheduler tick: fires the due meteor (or consumes the pending skip)
     * and re-arms the timer. A pending skip does not shorten the wait — the
     * timer runs out, the meteor is skipped and a brand-new cooldown starts.
     */
    private void tickScheduler(JavaPlugin plugin) {
        if (!cfg().getBoolean("meteor.enabled", false)) {
            if (scheduler != null) { scheduler.cancel(); scheduler = null; }
            nextSpawnAtMillis = 0L;
            skipPending = false;
            return;
        }
        if (nextSpawnAtMillis <= 0L || System.currentTimeMillis() < nextSpawnAtMillis) return;

        try {
            if (skipPending) {
                skipPending = false;
                ConsoleLogger.info("[Meteor] The due meteor was skipped by /ui meteor skip.");
            } else {
                spawnRandomMeteor();
            }
        } catch (Throwable t) {
            // The scheduler is the only thing that keeps the mechanic alive:
            // a single unexpected throw must not cancel the next cycle.
            ConsoleLogger.warn("[Meteor] Scheduler cycle failed: " + describe(t));
        } finally {
            armNext(plugin);
        }
    }

    /** Periodic scheduler log toggle ({@code meteor.log_scheduler}, default off). */
    private static boolean schedulerLogging(JavaPlugin plugin) {
        return plugin.getConfig().getBoolean("meteor.log_scheduler", false);
    }

    // ========================================================================
    // SCHEDULE CONTROL (/ui meteor status|skip|unskip|reset)
    // ========================================================================

    /** @return true when the meteor mechanic is enabled and a timer is armed. */
    public boolean isTimerActive() {
        return scheduler != null && nextSpawnAtMillis > 0L
                && cfg().getBoolean("meteor.enabled", false);
    }

    /**
     * @return whole seconds until the next meteor (rounded up), or {@code -1}
     *         when no timer is armed.
     */
    public long getSecondsUntilNextMeteor() {
        if (!isTimerActive()) return -1L;
        long remainingMillis = nextSpawnAtMillis - System.currentTimeMillis();
        if (remainingMillis <= 0L) return 0L;
        return (remainingMillis + 999L) / 1000L;
    }

    /** @return true when the next due meteor will be skipped instead of spawned. */
    public boolean isSkipPending() {
        return skipPending;
    }

    /**
     * Marks the next due meteor to be skipped. The timer keeps running: when it
     * fires, the meteor is consumed without spawning and a fresh cooldown starts.
     *
     * @return false when no timer is armed (module disabled)
     */
    public boolean skipNextMeteor() {
        if (!isTimerActive()) return false;
        skipPending = true;
        return true;
    }

    /**
     * Cancels a pending skip. The meteor has not been skipped yet — it simply
     * spawns when the timer fires.
     *
     * @return false when no skip was pending
     */
    public boolean cancelSkipNextMeteor() {
        if (!skipPending) return false;
        skipPending = false;
        return true;
    }

    /**
     * Resets the spawn timer to a brand-new cooldown measured from now.
     *
     * @return false when no timer is armed (module disabled)
     */
    public boolean resetMeteorTimer() {
        if (!isTimerActive()) return false;
        armNext(Main.getInstance());
        return true;
    }

    // ========================================================================
    // SPAWN
    // ========================================================================

    public void spawnRandomMeteor() {
        var cfg = cfg();
        if (!cfg.getBoolean("meteor.enabled", false)) return;

        List<World> worlds = targetWorlds();
        Player target = pickRandomPlayer(worlds);
        if (target == null) {
            if (schedulerLogging(Main.getInstance())) {
                ConsoleLogger.info("[Meteor] No players online in " + worldNames(worlds) + ", skipping.");
            }
            return;
        }

        Location playerLoc = target.getLocation();
        int spawnMeteorsCount = cfg.getInt("meteor.count_per_spawn", 1);
        if (spawnMeteorsCount < 1) spawnMeteorsCount = 1;
        if (spawnMeteorsCount > 100) spawnMeteorsCount = 100;
        for (int i = 0; i < spawnMeteorsCount; i++) {
            spawnMeteorNear(target.getWorld(),
                    playerLoc.getBlockX(), playerLoc.getBlockZ(), playerLoc.getY());
        }
    }

    public int spawnMeteors(int count) {
        var cfg = cfg();
        if (!cfg.getBoolean("meteor.enabled", false)) {
            ConsoleLogger.warn("[Meteor] Module is disabled in config!");
            return 0;
        }
        if (count < 1) count = 1;
        if (count > 100) count = 100;

        List<World> worlds = targetWorlds();
        if (worlds.isEmpty()) {
            ConsoleLogger.warn("[Meteor] No meteor worlds available — nothing to spawn in.");
            return 0;
        }

        List<Player> players = collectPlayers(worlds);
        int spawned = 0;
        for (int i = 0; i < count; i++) {
            World world;
            int centerX;
            int centerZ;
            double fallbackY;
            if (!players.isEmpty()) {
                Player p = players.get(random.nextInt(players.size()));
                Location loc = p.getLocation();
                world = p.getWorld();
                centerX = loc.getBlockX();
                centerZ = loc.getBlockZ();
                fallbackY = loc.getY();
            } else {
                // Console-initiated force spawn with nobody online: use the world
                // spawn point so admins can still test/trigger the mechanic.
                world = worlds.get(i % worlds.size());
                Location spawn = world.getSpawnLocation();
                centerX = spawn.getBlockX();
                centerZ = spawn.getBlockZ();
                fallbackY = spawn.getY();
                ConsoleLogger.info("[Meteor] No players online in " + worldNames(worlds)
                        + " — using the spawn point of '" + world.getName() + "'.");
            }
            if (spawnMeteorNear(world, centerX, centerZ, fallbackY)) {
                spawned++;
            }
        }

        ConsoleLogger.info("[Meteor] Force-spawned " + spawned + " of " + count + " meteor(s).");
        return spawned;
    }

    /**
     * Resolves the worlds meteors may fall in: {@code meteor.worlds} first, then
     * the single {@code meteor.world} name. Missing world names are reported; if
     * none of the configured worlds exist the first loaded world is used instead
     * of silently disabling the whole mechanic (a renamed {@code level-name} used
     * to make meteors dead with a single console warning).
     */
    private List<World> targetWorlds() {
        var cfg = cfg();
        List<String> names = cfg.getStringList("meteor.worlds");
        boolean explicitList = !names.isEmpty();
        if (!explicitList) {
            String single = cfg.getString("meteor.world", "world");
            names = List.of(single == null || single.isBlank() ? "world" : single.trim());
        }

        List<World> worlds = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String name : names) {
            World world = name == null || name.isBlank() ? null : Bukkit.getWorld(name.trim());
            if (world == null) {
                missing.add(String.valueOf(name));
            } else if (!worlds.contains(world)) {
                worlds.add(world);
            }
        }
        if (!missing.isEmpty()) {
            ConsoleLogger.warn("[Meteor] Configured world(s) not found: " + String.join(", ", missing));
        }
        if (worlds.isEmpty() && explicitList) {
            // An explicit list is a deliberate choice — never substitute another
            // world behind the admin's back (e.g. "only ui_space" must not turn
            // into overworld meteors).
            ConsoleLogger.warn("[Meteor] None of the meteor.worlds entries exist — meteor spawning is inactive.");
            return List.of();
        }
        if (worlds.isEmpty()) {
            List<World> loaded = Bukkit.getWorlds();
            if (!loaded.isEmpty()) {
                worlds.add(loaded.get(0));
                ConsoleLogger.warn("[Meteor] Falling back to the first loaded world '"
                        + loaded.get(0).getName() + "'.");
            }
        }
        return worlds;
    }

    private Player pickRandomPlayer(List<World> worlds) {
        List<Player> players = collectPlayers(worlds);
        return players.isEmpty() ? null : players.get(random.nextInt(players.size()));
    }

    private List<Player> collectPlayers(List<World> worlds) {
        List<Player> players = new ArrayList<>();
        for (World world : worlds) {
            players.addAll(world.getPlayers());
        }
        return players;
    }

    private static String worldNames(List<World> worlds) {
        if (worlds.isEmpty()) return "the configured meteor worlds";
        List<String> names = new ArrayList<>();
        for (World world : worlds) names.add("'" + world.getName() + "'");
        return String.join(", ", names);
    }

    private boolean spawnMeteorNear(World world, int centerX, int centerZ, double fallbackY) {
        var cfg = cfg();

        // Cap on simultaneously active meteors (lag/crash guard)
        int maxActive = cfg.getInt("meteor.max_active", 10);
        if (maxActive < 1) maxActive = 1;
        if (maxActive > 1000) maxActive = 1000;
        if (activeMeteors.size() >= maxActive) {
            ConsoleLogger.warn("[Meteor] Active meteor limit reached (" + maxActive + "), skipping.");
            return false;
        }

        int spawnRadiusMin = cfg.getInt("meteor.spawn_radius.min", 30);
        int spawnRadiusMax = cfg.getInt("meteor.spawn_radius.max", 120);
        // Clamp min first: clamping only max could leave max < min and make
        // nextInt(max - min + 1) throw on every cycle.
        if (spawnRadiusMin <= 0) spawnRadiusMin = 1;
        if (spawnRadiusMin > 1000) spawnRadiusMin = 1000;
        if (spawnRadiusMax < spawnRadiusMin) spawnRadiusMax = spawnRadiusMin;
        if (spawnRadiusMax > 1000) spawnRadiusMax = 1000;

        int heightMax = cfg.getInt("meteor.height.max", world.getMaxHeight() - 1);
        int heightMin = cfg.getInt("meteor.height.min", world.getMinHeight());
        // Config values may be tuned for a different world (e.g. the overworld
        // values in a world with a lower build limit) — never leave this world.
        heightMax = Math.min(heightMax, world.getMaxHeight() - 2);
        heightMin = Math.max(heightMin, world.getMinHeight());
        if (heightMax < heightMin) {
            heightMax = world.getMaxHeight() - 2;
            heightMin = world.getMinHeight();
        }

        int targetX = centerX;
        int targetZ = centerZ;
        int targetY = -1;
        int lastSurface = -1;

        for (int attempt = 0; attempt < 1 + RETRY_ATTEMPTS; attempt++) {
            int dx = spawnRadiusMin + random.nextInt(spawnRadiusMax - spawnRadiusMin + 1);
            int dz = spawnRadiusMin + random.nextInt(spawnRadiusMax - spawnRadiusMin + 1);
            if (random.nextBoolean()) dx = -dx;
            if (random.nextBoolean()) dz = -dz;

            int x = centerX + dx;
            int z = centerZ + dz;
            int surface = findSurfaceY(world, x, z, heightMax, heightMin);
            if (surface < 0) continue;

            targetX = x;
            targetZ = z;
            lastSurface = surface;

            // A meteor must fall from the sky: require enough room above the surface.
            if (world.getMaxHeight() - 1 - surface >= MIN_FALL_GAP) {
                targetY = surface;
                break;
            }
        }
        // No headroom anywhere (e.g. a build at the build limit) — still land on
        // the surface, the fall will just be shorter.
        if (targetY < 0 && lastSurface >= 0) targetY = lastSurface;

        if (targetY < 0) {
            // No surface inside the configured height window. Probe at a random
            // point in the spawn radius — never on the player's own column: a
            // misconfigured height window must not drop the explosion at their feet.
            int dx = spawnRadiusMin + random.nextInt(spawnRadiusMax - spawnRadiusMin + 1);
            int dz = spawnRadiusMin + random.nextInt(spawnRadiusMax - spawnRadiusMin + 1);
            if (random.nextBoolean()) dx = -dx;
            if (random.nextBoolean()) dz = -dz;
            targetX = centerX + dx;
            targetZ = centerZ + dz;

            // Distinguish "the window excludes real terrain" from "there is no
            // terrain at all".
            int fullRangeSurface = findSurfaceY(world, targetX, targetZ,
                    world.getMaxHeight() - 2, world.getMinHeight());
            if (fullRangeSurface >= 0) {
                targetY = fullRangeSurface;
                ConsoleLogger.warn("[Meteor] meteor.height range (" + heightMin + ".." + heightMax
                        + ") excludes the surface at " + targetX + " " + targetY + " " + targetZ
                        + " — using it anyway.");
            } else {
                // Genuinely no ground (void/sky world such as the space dimension).
                // Aim at the caller's height at this random XZ instead of aborting.
                targetY = clamp((int) Math.floor(fallbackY) - 1,
                        world.getMinHeight() + 1, world.getMaxHeight() - 2);
                ConsoleLogger.warn("[Meteor] No surface found in '" + world.getName()
                        + "' near " + centerX + " " + centerZ
                        + " — using fallback height " + targetY + " (void world?).");
            }
        }

        // Parameters
        int fallDuration = cfg.getInt("meteor.fall_duration_ticks", 60);
        if (fallDuration < 1) fallDuration = 1;
        if (fallDuration > 20 * 60 * 10) fallDuration = 20 * 60 * 10; // 10 min hard cap

        // Size rule (radius of the sphere); the explosion scales with it.
        int sphereRadius = MeteorSize.fromConfig(cfg).nextSize(random);
        float explosionPower = computeExplosionPower(cfg, sphereRadius);

        WeightedBlockPool shellBlocks = WeightedBlockPool.fromConfig(cfg);
        Map<Material, Double> coreOres = loadCoreOres(cfg);
        MeteorType type = MeteorTypes.fromConfig(cfg).next(random);
        Material coreBlock = resolveCoreBlock(type, coreOres);

        // Start the fall high in the sky, but keep the whole visual sphere inside
        // the world build limits: the displays reach sphereRadius blocks above the
        // marker, so the marker's maximum start height is maxHeight-1-radius.
        int maxStartY = world.getMaxHeight() - 1 - sphereRadius;
        int fallHeight = FALL_HEIGHT_MIN + random.nextInt(FALL_HEIGHT_MAX - FALL_HEIGHT_MIN + 1);
        double startY = Math.min(targetY + (double) fallHeight, maxStartY);
        if (startY <= targetY + 1.0) {
            ConsoleLogger.warn("[Meteor] Not enough room above " + targetX + " " + targetY + " " + targetZ
                    + " in '" + world.getName() + "' for a fall (build limit "
                    + world.getMaxHeight() + "), skipping.");
            return false;
        }

        // The meteor is rendered by entities (Marker + BlockDisplay). Entities
        // spawned into an unloaded chunk are silently dropped, while getChunkAt()
        // forces the chunk to load synchronously.
        world.getChunkAt(targetX >> 4, targetZ >> 4);

        MeteorTask task;
        try {
            task = new MeteorTask(
                    Main.getInstance(),
                    world,
                    startY,
                    targetX + 0.5, targetY + 1.0, targetZ + 0.5,
                    fallDuration,
                    sphereRadius,
                    explosionPower,
                    shellBlocks,
                    coreBlock
            );
        } catch (Exception e) {
            ConsoleLogger.warn("[Meteor] Failed to create the falling meteor at "
                    + targetX + " " + targetY + " " + targetZ + " in '" + world.getName()
                    + "': " + describe(e));
            return false;
        }

        try {
            task.setOnComplete(() -> activeMeteors.remove(task));
            activeMeteors.add(task);
            task.runTaskTimer(Main.getInstance(), 1L, 1L);
        } catch (Exception e) {
            activeMeteors.remove(task);
            task.cleanup();
            ConsoleLogger.warn("[Meteor] Failed to start the meteor task: " + describe(e));
            return false;
        }

        ConsoleLogger.info("[Meteor] Spawned meteor in '" + world.getName() + "' → ("
                + targetX + ", " + targetY + ", " + targetZ + ") radius=" + sphereRadius
                + " type=" + type.getKey() + " core=" + coreBlock.name()
                + " power=" + explosionPower + " startY=" + (int) startY
                + " displays=" + task.displayCount());
        return true;
    }

    /**
     * Explosion power follows the size: {@code base + per_size * size}, clamped
     * to {@code [meteor.explosion] max}. The default formula (2 + 3×size) keeps
     * the old power of 8 for radius 2 while big meteors hit progressively harder.
     */
    private static float computeExplosionPower(FileConfiguration cfg, int size) {
        double base = cfg.getDouble("meteor.explosion.base", 2.0);
        double perSize = cfg.getDouble("meteor.explosion.per_size", 3.0);
        double max = cfg.getDouble("meteor.explosion.max", 30.0);
        if (!Double.isFinite(base) || base < 0) base = 2.0;
        if (!Double.isFinite(perSize) || perSize < 0) perSize = 3.0;
        if (!Double.isFinite(max) || max <= 0) max = 30.0;

        double power = base + perSize * size;
        if (power > max) power = max;
        if (power < 0) power = 0;
        return (float) power;
    }

    /** Maps a meteor type to the material of its single center block. */
    private Material resolveCoreBlock(MeteorType type, Map<Material, Double> coreOres) {
        return switch (type) {
            case WET -> Material.WATER;
            case HOT -> Material.LAVA;
            case BASIC -> Material.BLACKSTONE;
            case ORE -> pickCoreOre(coreOres);
            case ANCIENT -> Material.ANCIENT_DEBRIS;
        };
    }

    /** Picks an ore from {@code [meteor.core_ores]} by weighted random. */
    private Material pickCoreOre(Map<Material, Double> coreOres) {
        if (coreOres == null || coreOres.isEmpty()) {
            return Material.DEEPSLATE_DIAMOND_ORE;
        }

        double totalWeight = 0;
        for (double weight : coreOres.values()) totalWeight += weight;

        double roll = random.nextDouble() * totalWeight;
        double cumulative = 0;
        for (Map.Entry<Material, Double> entry : coreOres.entrySet()) {
            cumulative += entry.getValue();
            if (roll <= cumulative) return entry.getKey();
        }
        return coreOres.keySet().iterator().next();
    }

    /**
     * Loads the ore list with chances from the config.
     * Default: all deepslate ores with equal chances (1.0).
     */
    private Map<Material, Double> loadCoreOres(FileConfiguration cfg) {
        Map<Material, Double> ores = new HashMap<>();

        if (cfg.contains("meteor.core_ores") && cfg.isConfigurationSection("meteor.core_ores")) {
            var section = cfg.getConfigurationSection("meteor.core_ores");
            for (String key : section.getKeys(false)) {
                try {
                    Material mat = Material.valueOf(key.toUpperCase());
                    if (!mat.isBlock()) {
                        ConsoleLogger.warn("[Meteor] Core ore is not a block material: " + key);
                        continue;
                    }
                    double weight = section.getDouble(key, 1.0);
                    if (weight > 0) {
                        ores.put(mat, weight);
                    }
                } catch (IllegalArgumentException e) {
                    ConsoleLogger.warn("[Meteor] Invalid core ore: " + key);
                }
            }
        }

        // Default: all deepslate ores
        if (ores.isEmpty()) {
            ores.put(Material.DEEPSLATE_DIAMOND_ORE, 1.0);
            ores.put(Material.DEEPSLATE_IRON_ORE, 1.0);
            ores.put(Material.DEEPSLATE_GOLD_ORE, 1.0);
            ores.put(Material.DEEPSLATE_EMERALD_ORE, 1.0);
            ores.put(Material.DEEPSLATE_LAPIS_ORE, 1.0);
            ores.put(Material.DEEPSLATE_REDSTONE_ORE, 1.0);
            ores.put(Material.DEEPSLATE_COAL_ORE, 1.0);
            ores.put(Material.DEEPSLATE_COPPER_ORE, 1.0);
        }

        return ores;
    }

    private int findSurfaceY(World world, int x, int z, int maxY, int minY) {
        // Leave one block inside the world for the "block above" check.
        int top = Math.min(maxY, world.getMaxHeight() - 2);
        int bottom = Math.max(minY, world.getMinHeight());

        for (int y = top; y >= bottom; y--) {
            Block block = world.getBlockAt(x, y, z);
            if (block.isEmpty() || block.isLiquid()) continue;
            if (block.getType().isSolid()) {
                Block above = world.getBlockAt(x, y + 1, z);
                if (above.isEmpty() || !above.getType().isSolid()) {
                    return y;
                }
            }
        }
        // Integer.MIN_VALUE, not -1: a legitimate surface can exist at y = -1 in
        // custom worlds, and the old sentinel made such a surface unusable.
        return Integer.MIN_VALUE;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /** {@code ClassName: message} for log lines — message-only logging hid root causes. */
    private static String describe(Throwable t) {
        String message = t.getMessage();
        return message == null || message.isBlank()
                ? t.getClass().getSimpleName()
                : t.getClass().getSimpleName() + ": " + message;
    }

    public void removeMeteor(MeteorTask task) {
        activeMeteors.remove(task);
    }
}
