package com.ultimateimprovements.util;

import com.ultimateimprovements.core.Keys;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared contract of the Structure Indicator tool — the structural counterpart
 * of {@link MultimeterApi}.
 * <p>
 * The two tools split the plugin's blocks between them: the Multimeter reads
 * energy (cables, junctions, batteries, generators, reactors, accelerator
 * buffers), the Structure Indicator reads <b>structures</b> (ender-chest
 * integrity, DFC and compact reactors, generator and battery multiblocks,
 * magnets, lightning rods, chunk loaders, accelerator assemblies).
 * <p>
 * The interaction contract is the same as the multimeter's:
 * <ol>
 *   <li>the subsystem that actually delivers a report calls
 *       {@link #markMeasured(Player)};</li>
 *   <li>a generic listener asks {@link #wasMeasured(Player)} before printing
 *       anything on its own and calls {@link #finishMeasurement(Player)} when
 *       it reached the end without recognising the block — that method sends
 *       the standard {@code [STRUCT] Can't measure the structure of this
 *       block.} error only when no report was delivered for this click.</li>
 * </ol>
 * The "already measured" state is scoped to the current player tick, so both
 * hand events of one click agree on the result while the next click starts
 * clean. The state is kept separately from {@link MultimeterApi}, because a
 * player may carry both tools.
 */
public final class StructureIndicatorApi {

    /** Chat tag word used inside {@code [<yellow>word<dark_gray>]}. */
    private static final String TAG_KEY = "structures.structure_indicator.prefix";
    private static final String TAG_DEFAULT = "STRUCT";

    /** Body of the standard "this block has no structure to read" error. */
    private static final String CANT_MEASURE_KEY = "structures.structure_indicator.cant_measure";
    private static final String CANT_MEASURE_DEFAULT =
            "Can't measure the structure of this block.";

    /**
     * How many ticks after a report still count as "the same click". Both hand
     * events are fired in the same tick, so one tick of slack covers an
     * off-hand event that reaches the server a tick later.
     */
    private static final int SAME_CLICK_TICKS = 1;

    /** Player UUID → {@link Player#getTicksLived()} of the last delivered report. */
    private static final Map<UUID, Integer> LAST_READOUT = new ConcurrentHashMap<>();

    private StructureIndicatorApi() {}

    // =========================
    // ITEM CHECKS
    // =========================

    /**
     * @return true when the item is the custom Structure Indicator (PDC tag
     *         {@code is_structure_integrity_indicator:1b}, base item: recovery
     *         compass)
     */
    public static boolean isStructureIndicator(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        Byte value = meta.getPersistentDataContainer()
                .get(Keys.STRUCTURE_INDICATOR, PersistentDataType.BYTE);
        return value != null && value == (byte) 1;
    }

    /**
     * @return true when the Structure Indicator is in either hand — a report
     *         must not depend on which hand holds the tool
     */
    public static boolean holdsStructureIndicator(Player player) {
        if (player == null) return false;
        return isStructureIndicator(player.getInventory().getItemInMainHand())
                || isStructureIndicator(player.getInventory().getItemInOffHand());
    }

    // =========================
    // PER-CLICK READOUT STATE
    // =========================

    /**
     * Marks that the given player's current Structure Indicator click already
     * produced a report. Call this only when the chat lines were really
     * delivered.
     */
    public static void markMeasured(Player player) {
        if (player == null) return;
        LAST_READOUT.put(player.getUniqueId(), player.getTicksLived());
    }

    /**
     * @return true when a report was delivered during the same click (same
     *         player tick, see {@link #SAME_CLICK_TICKS}); stale entries are
     *         dropped on the way
     */
    public static boolean wasMeasured(Player player) {
        if (player == null) return false;
        Integer measuredAt = LAST_READOUT.get(player.getUniqueId());
        if (measuredAt == null) return false;
        int elapsed = player.getTicksLived() - measuredAt;
        if (elapsed < 0 || elapsed > SAME_CLICK_TICKS) {
            LAST_READOUT.remove(player.getUniqueId(), measuredAt);
            return false;
        }
        return true;
    }

    // =========================
    // SHARED "CAN'T MEASURE" ERROR
    // =========================

    /** {@code [<yellow>STRUCT<dark_gray>] } chat tag (MiniMessage). */
    public static String indicatorPrefix() {
        return "<dark_gray>[<yellow>"
                + MessageUtil.raw(TAG_KEY, TAG_DEFAULT) + "<dark_gray>] ";
    }

    /**
     * The full standard error (MiniMessage, unparsed): plugin prefix, the
     * {@code [STRUCT]} tag and the localized body. Kept public so tests and
     * diagnostics can assert the exact rendered line.
     */
    public static String cantMeasureMessage() {
        return MessageUtil.PREFIX + indicatorPrefix()
                + "<white>" + MessageUtil.raw(CANT_MEASURE_KEY, CANT_MEASURE_DEFAULT);
    }

    /**
     * Sends the standard {@code <plugin prefix> [STRUCT] Can't measure the
     * structure of this block.} error to the player. Callers usually prefer
     * {@link #finishMeasurement(Player)}, which only sends it when needed.
     */
    public static void sendCantMeasure(Player player) {
        if (player == null) return;
        player.sendMessage(MessageUtil.parse(cantMeasureMessage()));
    }

    /**
     * Finishes a Structure Indicator interaction: confirms that some
     * subsystem delivered the report for this click; when none did, sends the
     * standard {@code [STRUCT] Can't measure the structure of this block.}
     * error to the player instead.
     *
     * @return true when a report was already delivered (no error sent),
     *         false when the error was sent
     */
    public static boolean finishMeasurement(Player player) {
        if (wasMeasured(player) || MultimeterApi.wasMeasured(player)) return true;
        sendCantMeasure(player);
        return false;
    }
}
