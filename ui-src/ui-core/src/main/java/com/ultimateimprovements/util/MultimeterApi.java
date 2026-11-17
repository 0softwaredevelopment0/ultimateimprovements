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
 * Shared contract of the multimeter tool, used by every addon.
 * <p>
 * One click can pass through several independent readout owners: the generic
 * {@code MultimeterListener} (cables / batteries / junctions / particle
 * accelerator blocks) in UI-Other, the generator readout in UI-Energy, the CER
 * readout and the DFC control-panel sign in UI-Energy. Structures are the
 * Structure Indicator's list ({@link StructureIndicatorApi}), not the
 * multimeter's.
 * Each of them listens to its own {@code PlayerInteractEvent} and the event is
 * fired once per hand, so before this API a successful reactor readout was
 * followed by the generic "nothing here" message of a listener that did not
 * know the reactor had answered.
 * <p>
 * The contract is:
 * <ol>
 *   <li>the subsystem that actually delivers a readout calls
 *       {@link #markMeasured(Player)};</li>
 *   <li>a generic listener asks {@link #wasMeasured(Player)} before printing
 *       anything on its own and calls {@link #finishMeasurement(Player)} when
 *       it reached the end without recognising the block — that method sends
 *       the standard {@code [METER] Can't measure parameters of this block.}
 *       error only when no readout was delivered for this click.</li>
 * </ol>
 * The "already measured" state is scoped to the current player tick, so both
 * hand events of one click (and handlers running in different priorities) agree
 * on the result, while the next click starts clean.
 * <p>
 * The item check lives here as well ({@link #isMultimeter(ItemStack)},
 * {@link #holdsMultimeter(Player)}) so the PDC tag is validated in exactly one
 * place instead of the four copies the family used to carry.
 */
public final class MultimeterApi {

    /** Chat tag word used inside {@code [<yellow>word<dark_gray>]}. */
    private static final String TAG_KEY = "structures.multimeter.prefix";
    private static final String TAG_DEFAULT = "METER";

    /** Body of the standard "this block has no parameters to read" error. */
    private static final String CANT_MEASURE_KEY = "structures.multimeter.cant_measure";
    private static final String CANT_MEASURE_DEFAULT =
            "Can't measure parameters of this block.";

    /**
     * How many ticks after a readout still count as "the same click". Both hand
     * events are fired in the same tick, so one tick of slack covers an
     * off-hand event that reaches the server a tick later.
     */
    private static final int SAME_CLICK_TICKS = 1;

    /** Player UUID → {@link Player#getTicksLived()} of the last delivered readout. */
    private static final Map<UUID, Integer> LAST_READOUT = new ConcurrentHashMap<>();

    private MultimeterApi() {}

    // =========================
    // ITEM CHECKS
    // =========================

    /**
     * @return true when the item is the custom multimeter (PDC tag
     *         {@code is_multimeter:1b}, base item: clock)
     */
    public static boolean isMultimeter(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        Byte value = meta.getPersistentDataContainer()
                .get(Keys.MULTIMETER, PersistentDataType.BYTE);
        return value != null && value == (byte) 1;
    }

    /**
     * @return true when the multimeter is in either hand — a readout must not
     *         depend on which hand holds the tool
     */
    public static boolean holdsMultimeter(Player player) {
        if (player == null) return false;
        return isMultimeter(player.getInventory().getItemInMainHand())
                || isMultimeter(player.getInventory().getItemInOffHand());
    }

    // =========================
    // SHIFT+RMB CLICK OWNERSHIP
    // =========================

    /**
     * What a SHIFT+RMB click on a block should do, decided by the item in the
     * hand that actually performed the click.
     */
    public enum ClickAction {
        /** The multimeter is in that hand — the subsystem delivers its readout. */
        MEASURE,
        /** The hand is empty — the click asks for a readout without the tool. */
        REQUIRE_MULTIMETER,
        /** Any other item is held — the click belongs to vanilla / other plugins. */
        PASS
    }

    /**
     * Resolves a SHIFT+RMB click against the item of <b>the hand that performed
     * the click</b> (the main hand when it holds an item, otherwise the off
     * hand — the vanilla right-click rule), not "a multimeter somewhere in the
     * inventory":
     * <ul>
     *   <li>multimeter in that hand → {@link ClickAction#MEASURE};</li>
     *   <li>empty hand → {@link ClickAction#REQUIRE_MULTIMETER} (the caller
     *       cancels the click and prints the "multimeter required" hint);</li>
     *   <li>any other item (a lever, a block being placed, ...) →
     *       {@link ClickAction#PASS}: the click must not be cancelled, so the
     *       vanilla placement / interaction still happens.</li>
     * </ul>
     * Callers act once per click (on the main-hand event), so the empty
     * off-hand event of the same click can never message or cancel anything.
     */
    public static ClickAction resolveClick(Player player) {
        if (player == null) return ClickAction.PASS;

        ItemStack item = player.getInventory().getItemInMainHand();
        if (item == null || item.getType().isAir()) {
            item = player.getInventory().getItemInOffHand();
        }

        if (isMultimeter(item)) return ClickAction.MEASURE;
        if (item == null || item.getType().isAir()) return ClickAction.REQUIRE_MULTIMETER;
        return ClickAction.PASS;
    }

    // =========================
    // PER-CLICK READOUT STATE
    // =========================

    /**
     * Marks that the given player's current multimeter click already produced
     * parameters. Call this only when the chat lines were really delivered.
     */
    public static void markMeasured(Player player) {
        if (player == null) return;
        LAST_READOUT.put(player.getUniqueId(), player.getTicksLived());
    }

    /**
     * @return true when a readout was delivered during the same click (same
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

    /** {@code [<yellow>METER<dark_gray>] } chat tag (MiniMessage). */
    public static String meterPrefix() {
        return "<dark_gray>[<yellow>"
                + MessageUtil.raw(TAG_KEY, TAG_DEFAULT) + "<dark_gray>] ";
    }

    /**
     * The full standard error (MiniMessage, unparsed): plugin prefix, the
     * {@code [METER]} tag and the localized body. Kept public so tests and
     * diagnostics can assert the exact rendered line.
     */
    public static String cantMeasureMessage() {
        return MessageUtil.PREFIX + meterPrefix()
                + "<white>" + MessageUtil.raw(CANT_MEASURE_KEY, CANT_MEASURE_DEFAULT);
    }

    /**
     * Sends the standard {@code <plugin prefix> [METER] Can't measure
     * parameters of this block.} error to the player. Callers usually prefer
     * {@link #finishMeasurement(Player)}, which only sends it when needed.
     */
    public static void sendCantMeasure(Player player) {
        if (player == null) return;
        player.sendMessage(MessageUtil.parse(cantMeasureMessage()));
    }

    /**
     * Finishes a multimeter interaction: confirms that some subsystem delivered
     * the parameters for this click; when none did, sends the standard
     * {@code [METER] Can't measure parameters of this block.} error to the
     * player instead.
     * <p>
     * A Structure Indicator report delivered for the same click also counts as
     * "answered" — with one tool in each hand the second hand's generic
     * fallback must not append its own error.
     *
     * @return true when a readout was already delivered (no error sent),
     *         false when the error was sent
     */
    public static boolean finishMeasurement(Player player) {
        if (wasMeasured(player) || StructureIndicatorApi.wasMeasured(player)) return true;
        sendCantMeasure(player);
        return false;
    }
}
