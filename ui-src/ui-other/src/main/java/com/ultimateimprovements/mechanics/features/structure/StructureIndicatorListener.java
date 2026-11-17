package com.ultimateimprovements.mechanics.features.structure;

import com.ultimateimprovements.core.hooks.CoreHooks;
import com.ultimateimprovements.energy.consumption.light.LightManager;
import com.ultimateimprovements.energy.generation.basic.GeneratorManager;
import com.ultimateimprovements.energy.generation.nuclear.NuclearReactor;
import com.ultimateimprovements.energy.generation.nuclear.NuclearReactorManager;
import com.ultimateimprovements.energy.generation.reactor.ReactorManager;
import com.ultimateimprovements.energy.storage.battery.BatteryManager;
import com.ultimateimprovements.mechanics.environment.lightning.LightningManager;
import com.ultimateimprovements.mechanics.environment.magnet.MagnetManager;
import com.ultimateimprovements.structure.StructureMarker;
import com.ultimateimprovements.util.LocationUtil;
import com.ultimateimprovements.util.MessageUtil;
import com.ultimateimprovements.util.StructureIndicatorApi;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Locale;
import java.util.UUID;

/**
 * Generic Structure Indicator readout: the structural counterpart of
 * {@code MultimeterListener}.
 * <p>
 * The Multimeter answers for energy (cables, junctions, batteries, buffers);
 * this listener answers for <b>structures</b>:
 * <ul>
 *   <li>ender chest — stress / degradation / integrity report
 *       ({@link StructureIntegrityManager});</li>
 *   <li>DFC reactor — core/case integrity, temperatures, meltdown state;</li>
 *   <li>compact nuclear reactor (CER) — status, temperature, cooling damage;</li>
 *   <li>coal generator — assembly state and redstone gate;</li>
 *   <li>battery multiblock — block count, shared capacity, mode;</li>
 *   <li>particle accelerator components — component type, buffer, last speed;</li>
 *   <li>magnet — cluster size, power tier, radius;</li>
 *   <li>lightning rod — structure state;</li>
 *   <li>light cluster — lamps, junctions, consumption;</li>
 *   <li>chunk loader — the kept-loaded chunk;</li>
 *   <li>any other {@link StructureMarker} row — type, id and block count.</li>
 * </ul>
 * The tool is SHIFT+RMB only, exactly like the multimeter. When the block is
 * not part of a structure the listener asks
 * {@link StructureIndicatorApi#finishMeasurement(Player)}, which sends the
 * standard {@code [STRUCT] Can't measure the structure of this block.} error
 * unless another subsystem already answered this click.
 */
public class StructureIndicatorListener implements Listener {

    /**
     * Ender chests are read at {@link EventPriority#LOWEST}: their own
     * {@code EnderChestManager} listener cancels the interaction at NORMAL to
     * open the custom GUI, so a HIGHEST handler would never see the click.
     * This handler must therefore run first and cancel the event itself.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onEnderChest(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block block = e.getClickedBlock();
        if (block == null || block.getType() != Material.ENDER_CHEST) return;

        Player player = e.getPlayer();
        if (!player.isSneaking()) return;
        if (!StructureIndicatorApi.isStructureIndicator(e.getItem())) return;
        if (StructureIndicatorApi.wasMeasured(player)) return;

        StructureIntegrityManager manager = StructureIntegrityManager.getInstance();
        if (manager == null) return;

        // The report owns the click: the ender chest GUI must not open.
        e.setCancelled(true);
        player.playSound(block.getLocation(), Sound.UI_BUTTON_CLICK, 1.0f, 1.0f);
        manager.showInfo(player, block.getLocation());
        StructureIndicatorApi.markMeasured(player);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onUse(PlayerInteractEvent e) {

        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (e.getClickedBlock() == null) return;

        Player player = e.getPlayer();
        Block block = e.getClickedBlock();

        // The item of THIS event's hand (the event fires per hand).
        ItemStack item = e.getItem();
        if (!StructureIndicatorApi.isStructureIndicator(item)) return;

        // SHIFT+RMB only: a plain right-click keeps the vanilla interaction
        // (chest opening, furnace GUI, block placement, ...).
        if (!player.isSneaking()) return;

        // A subsystem readout may already have answered this click (the DFC
        // panel sign, the CER, ...). Never append a second readout on top.
        if (StructureIndicatorApi.wasMeasured(player) || com.ultimateimprovements.util.MultimeterApi.wasMeasured(player)) {
            return;
        }

        player.playSound(block.getLocation(), Sound.UI_BUTTON_CLICK, 1.0f, 1.0f);

        if (!measure(player, block)) {
            // Nothing here is a structure: the shared API reports it in chat,
            // but only when no other readout answered this click.
            StructureIndicatorApi.finishMeasurement(player);
            return;
        }

        // The measurement owns the click: no container GUI, no sign editor.
        e.setCancelled(true);
    }

    /**
     * Delivers the matching structure report.
     *
     * @return true when a report was sent
     */
    private boolean measure(Player player, Block block) {
        Location loc = LocationUtil.normalize(block.getLocation());
        if (loc == null) return false;

        // =========================
        // DFC — dark fusion reactor
        // =========================
        ReactorManager reactor = ReactorManager.getReactorForBlock(loc);
        if (reactor != null && reactor.isValid()) {
            sendReactorReport(player, reactor);
            StructureIndicatorApi.markMeasured(player);
            return true;
        }

        // =========================
        // CER — compact nuclear reactor
        // =========================
        NuclearReactor nuclear = NuclearReactorManager.getByBlockLocation(loc);
        if (nuclear != null) {
            sendNuclearReport(player, nuclear);
            StructureIndicatorApi.markMeasured(player);
            return true;
        }

        // =========================
        // COAL GENERATOR
        // =========================
        if (GeneratorManager.isAssembled(loc)) {
            sendGeneratorReport(player, block, loc);
            StructureIndicatorApi.markMeasured(player);
            return true;
        }

        // =========================
        // BATTERY (multiblock)
        // =========================
        BatteryManager.BatteryCluster battery = BatteryManager.getCluster(loc);
        if (battery != null) {
            sendBatteryReport(player, loc, battery);
            StructureIndicatorApi.markMeasured(player);
            return true;
        }

        // =========================
        // PARTICLE ACCELERATOR COMPONENT
        // =========================
        StructureMarker.StructureData marker = StructureMarker.getAt(loc);
        String markerType = marker != null ? marker.type() : null;
        boolean acceleratorBlock = "accelerator".equals(markerType)
                || (StructureMarker.existsAt(loc)
                    && (CoreHooks.isParticleEngine(block.getType())
                        || CoreHooks.isParticleSensor(block.getType())));
        if (acceleratorBlock) {
            sendAcceleratorReport(player, block, loc);
            StructureIndicatorApi.markMeasured(player);
            return true;
        }

        // =========================
        // LIGHTNING ROD
        // =========================
        Location lightningCenter = LightningManager.getCenterForBlock(loc);
        if (lightningCenter != null) {
            sendLightningReport(player, lightningCenter);
            StructureIndicatorApi.markMeasured(player);
            return true;
        }

        // =========================
        // MAGNET
        // =========================
        MagnetManager.MagnetCluster magnet = findMagnetCluster(loc);
        if (magnet != null) {
            sendMagnetReport(player, loc, magnet);
            StructureIndicatorApi.markMeasured(player);
            return true;
        }

        // =========================
        // LIGHT CLUSTER
        // =========================
        LightManager.LightCluster light = LightManager.getCluster(loc);
        if (light != null) {
            sendLightReport(player, light);
            StructureIndicatorApi.markMeasured(player);
            return true;
        }

        // =========================
        // GENERIC STRUCTURE REGISTRY ROW
        // =========================
        if (marker != null && sendGenericReport(player, loc, marker)) {
            StructureIndicatorApi.markMeasured(player);
            return true;
        }

        return false;
    }

    // =========================
    // DFC
    // =========================
    private void sendReactorReport(Player player, ReactorManager reactor) {
        Location loc = reactor.getReactorLocation();
        String status;
        if (reactor.isMeltdownCountdown()) {
            status = "<dark_red>!!! EXPLOSION IMMINENT !!!</dark_red>";
        } else if (reactor.getCoreShInt() < 100 || reactor.getCoreCaseInt() < 100 || reactor.isStructureDamaged()) {
            status = "<yellow>Degradation</yellow>";
        } else {
            status = "<green>Normal</green>";
        }

        header(player, "Dark Fusion Reactor");
        line(player, "ID", "<white>" + plain(reactor.getReactorId()));
        line(player, "Position", "<white>" + coords(loc));
        line(player, "Status", status);
        line(player, "Structure", reactor.isCaseBroken()
                ? "<dark_red>❌ Case breached</dark_red>"
                : reactor.isStructureDamaged()
                    ? "<yellow>⚠ Damaged</yellow>"
                    : "<green>✔ Assembled</green>");
        line(player, "Core integrity", integrity(reactor.getDisplayCoreShInt()));
        line(player, "Case integrity", integrity(reactor.getDisplayCoreCaseInt()));
        line(player, "Core temperature", "<white>" + reactor.getDisplayCoreTemp() + " C*");
        line(player, "Case temperature", "<white>" + reactor.getDisplayCoreCaseTemp() + " C*");
        if (reactor.isMeltdownCountdown()) {
            line(player, "Detonation in", "<dark_red>" + (reactor.getMeltdownTimer() / 20) + " s</dark_red>");
        }
        separator(player);
    }

    // =========================
    // CER
    // =========================
    private void sendNuclearReport(Player player, NuclearReactor reactor) {
        header(player, "Compact Nuclear Reactor");
        line(player, "Position", "<white>" + coords(reactor.getAnchor()));
        line(player, "Status", NuclearReactorManager.statusLabel(reactor));
        line(player, "Structure", reactor.isDamaged()
                ? "<dark_red>❌ Damaged</dark_red>"
                : "<green>✔ Intact</green>");
        line(player, "Temperature", "<white>" + reactor.getTemperature()
                + " / " + NuclearReactorManager.maxTemp() + " C*");
        line(player, "Cooling", "<white>" + reactor.getCooling()
                + "% <gray>(max " + NuclearReactorManager.coolingMax() + "%)</gray>");
        if (reactor.isMeltdown()) {
            line(player, "Detonation in", "<dark_red>" + reactor.getMeltdownSeconds() + " s</dark_red>");
        }
        separator(player);
    }

    // =========================
    // COAL GENERATOR
    // =========================
    private void sendGeneratorReport(Player player, Block block, Location loc) {
        boolean powered = block.isBlockPowered() || block.isBlockIndirectlyPowered();
        StructureMarker.StructureData marker = StructureMarker.getAt(loc);

        header(player, "Coal Generator");
        line(player, "Position", "<white>" + coords(loc));
        line(player, "Structure", "<green>✔ Assembled</green>");
        line(player, "Redstone gate", powered
                ? "<green>✔ On</green>"
                : "<red>❌ Off</red>");
        if (marker != null) {
            line(player, "Structure ID", "<dark_gray>" + shortId(marker.uuid()) + "</dark_gray>");
        }
        separator(player);
    }

    // =========================
    // BATTERY
    // =========================
    private void sendBatteryReport(Player player, Location loc, BatteryManager.BatteryCluster cluster) {
        int blocks = cluster.blockKeys.size();
        header(player, "Battery (multiblock)");
        line(player, "Position", "<white>" + coords(loc));
        line(player, "Structure", "<green>✔ " + blocks + " block"
                + (blocks == 1 ? "" : "s") + "</green>");
        line(player, "Shared capacity", "<white>" + cluster.capacity + " ⚡</white>"
                + (blocks > 0 ? " <gray>(" + (cluster.capacity / blocks) + " ⚡ per block)</gray>" : ""));
        line(player, "Mode", "<white>" + cluster.getModeDisplay());
        separator(player);
    }

    // =========================
    // PARTICLE ACCELERATOR
    // =========================
    private void sendAcceleratorReport(Player player, Block block, Location loc) {
        header(player, "Particle Accelerator");
        line(player, "Position", "<white>" + coords(loc));
        line(player, "Component", "<white>" + acceleratorComponent(block.getType()));

        if (CoreHooks.isParticleEngine(block.getType())) {
            int energy = CoreHooks.particleEngineEnergy(loc);
            boolean ready = CoreHooks.particleCanEngineAccelerate(loc);
            line(player, "Buffer", "<white>" + energy + "/1000 ⚡");
            line(player, "Status", ready
                    ? "<green>✔ Ready to accelerate</green>"
                    : "<yellow>⏳ Not ready (needs 1000⚡ + redstone)</yellow>");
        } else if (CoreHooks.isParticleSensor(block.getType())) {
            double speed = CoreHooks.particleSensorLastSpeed(loc);
            if (speed > 0) {
                double pct = speed / CoreHooks.particleMaxSpeed() * 100.0;
                line(player, "Last speed", "<white>" + String.format(Locale.US, "%.3f", pct) + "%");
            } else {
                line(player, "Last speed", "<gray>No particle has passed yet</gray>");
            }
        }
        separator(player);
    }

    private static String acceleratorComponent(Material type) {
        if (CoreHooks.isParticleEngine(type)) return "Engine";
        if (CoreHooks.isParticleSensor(type)) return "Speed sensor";
        return switch (type) {
            case GLASS -> "Ring";
            case REINFORCED_DEEPSLATE -> "Injector";
            default -> type.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        };
    }

    // =========================
    // LIGHTNING ROD
    // =========================
    private void sendLightningReport(Player player, Location center) {
        boolean enabled = LightningManager.isEnabled(center);
        header(player, "Lightning Rod");
        line(player, "Center", "<white>" + coords(center));
        line(player, "Status", enabled
                ? "<green>✔ On</green>"
                : "<red>❌ Off</red>");
        separator(player);
    }

    // =========================
    // MAGNET
    // =========================
    private void sendMagnetReport(Player player, Location loc, MagnetManager.MagnetCluster cluster) {
        int power = Math.max(1, cluster.power);
        header(player, "Magnet");
        line(player, "Position", "<white>" + coords(loc));
        line(player, "Structure", "<green>✔ " + cluster.blockKeys.size() + " block"
                + (cluster.blockKeys.size() == 1 ? "" : "s") + "</green>");
        if (cluster.center != null) {
            line(player, "Center", "<white>" + coords(cluster.center));
        }
        line(player, "Power", MagnetManager.getMagnetPowerTierStatic(power));
        line(player, "Radius", "<white>" + MagnetManager.getClusterRadiusForPower(power) + " blocks");
        separator(player);
    }

    /** Magnet clusters accept neighbours of a magnet block too. */
    private static MagnetManager.MagnetCluster findMagnetCluster(Location loc) {
        long key = MagnetManager.toKey(loc);
        for (MagnetManager.MagnetCluster cluster : MagnetManager.getClusters()) {
            if (cluster.world == null || !cluster.world.equals(loc.getWorld())) continue;
            if (cluster.blockKeys.contains(key)) return cluster;
            for (org.bukkit.block.BlockFace face : new org.bukkit.block.BlockFace[]{
                    org.bukkit.block.BlockFace.NORTH, org.bukkit.block.BlockFace.SOUTH,
                    org.bukkit.block.BlockFace.EAST, org.bukkit.block.BlockFace.WEST,
                    org.bukkit.block.BlockFace.UP, org.bukkit.block.BlockFace.DOWN}) {
                if (cluster.blockKeys.contains(MagnetManager.toKey(loc.clone().add(
                        face.getModX(), face.getModY(), face.getModZ())))) {
                    return cluster;
                }
            }
        }
        return null;
    }

    // =========================
    // LIGHT CLUSTER
    // =========================
    private void sendLightReport(Player player, LightManager.LightCluster cluster) {
        header(player, "Light Structure");
        line(player, "Structure", "<green>✔ " + cluster.blockKeys.size() + " block"
                + (cluster.blockKeys.size() == 1 ? "" : "s") + "</green>");
        line(player, "Lamps", "<white>" + cluster.lampCount());
        line(player, "Junctions", "<white>" + cluster.junctionCount());
        line(player, "Status", cluster.lit
                ? "<green>✔ Lit</green>"
                : "<gray>Idle</gray>");
        line(player, "Consumption", "<white>" + cluster.consumptionPerSecond() + " ⚡/s");
        separator(player);
    }

    // =========================
    // GENERIC STRUCTURE REGISTRY ROW
    // =========================
    private boolean sendGenericReport(Player player, Location loc,
                                      StructureMarker.StructureData marker) {
        String type = marker.type();

        // Cables are the Multimeter's list, not the indicator's.
        if ("cable".equals(type)) return false;

        // Chunk loader: the interesting part is the kept-loaded chunk.
        if ("chunkloader".equals(type)) {
            header(player, "Chunk Loader");
            line(player, "Block", "<white>" + coords(loc));
            line(player, "Chunk", "<white>" + (loc.getBlockX() >> 4) + ", " + (loc.getBlockZ() >> 4));
            line(player, "Structure ID", "<dark_gray>" + shortId(marker.uuid()) + "</dark_gray>");
            separator(player);
            return true;
        }

        String display = switch (type) {
            case "reactor" -> "Dark Fusion Reactor";
            case "nuclearreactor" -> "Compact Nuclear Reactor";
            case "generator" -> "Coal Generator";
            case "battery" -> "Battery (multiblock)";
            case "accelerator" -> "Particle Accelerator";
            case "magnet" -> "Magnet";
            case "lightning" -> "Lightning Rod";
            case "light" -> "Light Structure";
            default -> null;
        };
        if (display == null) return false;

        int blocks = StructureMarker.getKeysByUuid(marker.uuid()).size();
        header(player, display);
        line(player, "Block", "<white>" + coords(loc));
        line(player, "Structure ID", "<dark_gray>" + shortId(marker.uuid()) + "</dark_gray>");
        line(player, "Blocks", "<white>" + Math.max(1, blocks));
        separator(player);
        return true;
    }

    // =========================
    // RENDER HELPERS
    // =========================
    private static void header(Player player, String title) {
        player.sendMessage(MessageUtil.parse("<gold>=== STRUCTURE ===</gold> <gray>»</gray> <white>"
                + title + "</white>"));
    }

    private static void separator(Player player) {
        player.sendMessage(MessageUtil.parse("<dark_gray>-----------------------------------</dark_gray>"));
    }

    private static void line(Player player, String label, String value) {
        player.sendMessage(MessageUtil.parse("<aqua>" + label + ": </aqua>" + value));
    }

    private static String integrity(int percent) {
        String color = percent >= 75 ? "green" : percent >= 40 ? "yellow" : "red";
        return "<" + color + ">" + percent + "%</" + color + ">";
    }

    private static String coords(Location loc) {
        if (loc == null) return "?";
        String world = loc.getWorld() != null ? loc.getWorld().getName() : "?";
        return world + " " + loc.getBlockX() + " " + loc.getBlockY() + " " + loc.getBlockZ();
    }

    /** First 8 characters of a structure UUID — enough to tell structures apart. */
    private static String shortId(UUID uuid) {
        return uuid == null ? "?" : uuid.toString().substring(0, 8);
    }

    private static String plain(String value) {
        return value == null ? "?" : value;
    }
}
