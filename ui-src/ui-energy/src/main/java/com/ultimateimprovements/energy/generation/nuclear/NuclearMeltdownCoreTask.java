package com.ultimateimprovements.energy.generation.nuclear;

import com.ultimateimprovements.core.Main;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * Periodic lava-core check for reactors in a meltdown countdown.
 * <p>
 * The countdown itself is owned by {@link NuclearReactorTask} and runs once per
 * second. This task exists only to re-read the center block every
 * {@code nuclear_reactor.meltdown_core_check_ticks} ticks (default 20 = once
 * per second), so the lookup interval is tunable in real ticks without
 * changing the countdown cadence. The final countdown second is also checked
 * by the main task, so a removed core can never be missed.
 * <p>
 * The task is scheduled at startup with the configured period; a soft reload
 * restarts it, so a changed value applies without a server restart.
 */
public class NuclearMeltdownCoreTask extends BukkitRunnable {

    @Override
    public void run() {
        Main main = Main.getInstance();
        if (main == null) return;

        FileConfiguration cfg = main.getConfig();
        if (!cfg.getBoolean("nuclear_reactor.enabled", true)) return;

        for (NuclearReactor reactor : NuclearReactorManager.getReactors()) {
            if (reactor == null || !reactor.isMeltdown()) continue;
            if (!reactor.isChunkLoaded()) continue;
            NuclearReactorManager.verifyMeltdownCore(reactor);
        }
    }
}
