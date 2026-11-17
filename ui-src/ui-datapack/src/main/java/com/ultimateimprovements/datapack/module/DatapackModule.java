package com.ultimateimprovements.datapack.module;

import com.ultimateimprovements.datapack.DatapackInstaller;
import com.ultimateimprovements.module.PluginModule;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Datapack — reports the state of the bundled UI-Datapack and verifies that the
 * discovered pack is enabled in the world (the pack itself is built into
 * {@code plugins/UltimateImprovements/datapack/UI-Datapack-<lang>.zip} by the
 * bootstrapper, before the worlds load).
 * <p>
 * Enabled parts are read from {@code datapack.*} in the UI-Datapack config
 * (see {@code DatapackModules}).
 */
public final class DatapackModule extends PluginModule {

    public DatapackModule() {
        super("Datapack", "infrastructure/datapack", false);
    }

    @Override
    protected void onInit(JavaPlugin plugin) throws Exception {
        DatapackInstaller.init(plugin);
        DatapackInstaller.getInstance().reportStatus(plugin);
        // The pack itself is registered before the worlds by UIDatapackBootstrap.
    }

    @Override
    protected void onDisable(JavaPlugin plugin) {}
}
