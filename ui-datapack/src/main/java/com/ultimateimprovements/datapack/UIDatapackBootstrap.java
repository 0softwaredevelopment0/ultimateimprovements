package com.ultimateimprovements.datapack;

import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.plugin.bootstrap.PluginBootstrap;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;

/**
 * UIDatapackBootstrap — registers the bundled UI-Datapack through the Paper
 * {@code DATAPACK_DISCOVERY} lifecycle event, i.e. <b>before the worlds (and their
 * datapacks) are loaded</b>.
 * <p>
 * This replaces the old "copy into {@code world/datapacks} then /datapack enable +
 * reload/restart" flow: the pack is built from the plugin JAR on the first discovery
 * event and registered with {@code autoEnableOnServerStart(true)}, so it is active
 * from the first world load with no restart.
 * <p>
 * The addon also registers the 18 custom enchantments through
 * {@link EnchantmentRegistryBootstrap}: they used to be datapack files, and the item tags
 * that survived the migration still ship here.
 * <p>
 * The archive is language-specific ({@code UI-Datapack-en.zip} or
 * {@code UI-Datapack-ru.zip}, see {@link DatapackPackBootstrap}) and its pack id is
 * used for {@code /datapack list}, so the active variant is visible there. Paper reads
 * a zip exactly like a folder ({@code FilePackResources}).
 */
public class UIDatapackBootstrap implements PluginBootstrap {

    /**
     * The discovery event can fire several times (each time the server scans for
     * datapacks). The archive is built exactly once and the same path is
     * re-registered afterwards, so an in-use datapack file is never rewritten
     * (Windows would refuse to replace an open zip anyway).
     */
    private DatapackPackBootstrap.Prepared preparedPack;

    @Override
    public void bootstrap(BootstrapContext context) {
        // Enchantments moved out of the datapack into the registry; registering them here
        // keeps them tied to this addon (and to datapack.modules.enchantments) as before.
        EnchantmentRegistryBootstrap.register(context);

        context.getLifecycleManager().registerEventHandler(LifecycleEvents.DATAPACK_DISCOVERY, event -> {
            try {
                if (preparedPack == null) {
                    preparedPack = DatapackPackBootstrap.prepare(context.getLogger());
                }
                if (preparedPack == null) {
                    return; // master toggle off — nothing to register
                }
                // The pack id carries the language (UI-Datapack-en / UI-Datapack-ru).
                String packId = preparedPack.packId();
                event.registrar().discoverPack(preparedPack.path(), packId, configurer -> configurer
                        .autoEnableOnServerStart(true)
                        .position(true, preparedPack.position()));
                context.getLogger().info("Registered bundled datapack '" + packId
                        + "' (" + preparedPack.path().getFileName()
                        + (preparedPack.reused() ? ", unchanged" : ", rebuilt")
                        + ", auto-enabled on start, "
                        + preparedPack.position().name().toLowerCase(java.util.Locale.ROOT) + ").");
            } catch (Throwable t) {
                context.getLogger().error("Failed to register the bundled datapack: "
                        + t.getMessage(), t);
            }
        });
    }
}
