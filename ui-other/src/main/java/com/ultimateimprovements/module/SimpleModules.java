package com.ultimateimprovements.module;

import com.ultimateimprovements.broadcast.AutoBroadcastManager;
import com.ultimateimprovements.command.PowerManager;
import com.ultimateimprovements.command.vote.VoteManager;
import com.ultimateimprovements.core.CommandRegistrar;
import com.ultimateimprovements.core.Main;
import com.ultimateimprovements.core.TaskManager;
import com.ultimateimprovements.database.AsyncAutoSaveManager;
import com.ultimateimprovements.database.DatabaseInit;
import com.ultimateimprovements.database.DatabaseManager;
import com.ultimateimprovements.energy.generation.basic.GeneratorManager;
import com.ultimateimprovements.energy.generation.reactor.ReactorListener;
import com.ultimateimprovements.energy.generation.reactor.ReactorManager;
import com.ultimateimprovements.energy.machines.furnace.ElectricFurnaceManager;
import com.ultimateimprovements.energy.storage.battery.BatteryManager;
import com.ultimateimprovements.energy.transfer.cable.CableNetwork;
import com.ultimateimprovements.hook.PluginHook;
import com.ultimateimprovements.listener.BlockBreakListener;
import com.ultimateimprovements.listener.BlockPlaceListener;
import com.ultimateimprovements.listener.FishingListener;
import com.ultimateimprovements.listener.MOTDListener;
import com.ultimateimprovements.listener.MultimeterListener;
import com.ultimateimprovements.listener.PluginHideListener;
import com.ultimateimprovements.listener.PowerInterceptListener;
import com.ultimateimprovements.listener.ServerBrandListener;
import com.ultimateimprovements.listener.ShulkerBulletListener;
import com.ultimateimprovements.mechanics.environment.lightning.LightningManager;
import com.ultimateimprovements.mechanics.environment.magnet.MagnetConfig;
import com.ultimateimprovements.mechanics.environment.magnet.MagnetEventListener;
import com.ultimateimprovements.mechanics.environment.magnet.MagnetManager;
import com.ultimateimprovements.mechanics.environment.radiation.RadiationManager;
import com.ultimateimprovements.mechanics.environment.sunburn.SunburnManager;
import com.ultimateimprovements.mechanics.features.blocks.BlockDmgManager;
import com.ultimateimprovements.mechanics.features.blocks.BoostedCobwebManager;
import com.ultimateimprovements.mechanics.features.blocks.ContainerTriggerManager;
import com.ultimateimprovements.mechanics.features.blocks.EnderChestManager;
import com.ultimateimprovements.mechanics.features.blocks.GlassBreakManager;
import com.ultimateimprovements.mechanics.features.blocks.TerracotaSpeedManager;
import com.ultimateimprovements.mechanics.features.creativeitem.CreativeItemValidator;
import com.ultimateimprovements.mechanics.features.integrity.ItemDurabilityUtil;
import com.ultimateimprovements.mechanics.features.integrity.LowDurabilityWarningListener;
import com.ultimateimprovements.mechanics.features.integrity.PiercingListener;
import com.ultimateimprovements.mechanics.features.structure.StructureIndicatorListener;
import com.ultimateimprovements.mechanics.features.structure.StructureIntegrityManager;
import com.ultimateimprovements.util.ConsoleLogger;

import org.bukkit.Bukkit;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * SimpleModules — registry of all simple (thin) wrapper modules.
 * <p>
 * Previously each such module lived in its own file (~60 files of 17-77 lines):
 * constructor + calling {@code XxxManager.init()} in {@code onInit} and an empty
 * {@code onDisable}. Now they're all collected in this single file as anonymous
 * classes grouped by domain — exactly in the order {@code PluginStartup} registered them.
 * <p>
 * Modules with non-trivial logic (Core, Database, Economy)
 * remain separate classes.
 * <p>
 * IMPORTANT: these modules are registered manually via {@code PluginStartup} —
 * auto-scanning (ModuleScanner) won't find them because they're anonymous classes.
 */
public final class SimpleModules {

    private SimpleModules() {}

    // ==========================================================================
    // 🧩 REGISTRATION GROUPS (order = PluginStartup order)
    // ==========================================================================

    // --------------------------------------------------------------------------
    // CORE (Database + Core — registered right after VersionCheckModule)
    // --------------------------------------------------------------------------

    public static void registerCoreModules(ModuleManager mm) {
        // NOTE: The "Database" module is already registered by CoreModules.registerAll()
        // in UI-Core's PluginStartup. We must NOT re-register it here (ModuleManager
        // silently skips duplicates). VoteManager.init() is called separately below
        // after all core modules are initialized.

        // Core (essential — base systems: tasks, commands, common listeners)
        mm.register(new SimpleModule("Core", "infrastructure/core", true) {
            @Override
            protected void onInit(JavaPlugin plugin) throws Exception {
                Main main = (Main) plugin;

                // TASK MANAGER & COMMANDS
                TaskManager.init(main);
                CommandRegistrar.init(main);

                // GENERAL LISTENERS
                var pm = main.getServer().getPluginManager();
                pm.registerEvents(new BlockPlaceListener(), main);
                pm.registerEvents(new BlockBreakListener(), main);
                // Multimeter: reading cables/junctions/batteries/generators with
                // SHIFT+RMB while holding the multimeter item. Runs at HIGHEST
                // and defers to subsystem readouts through MultimeterApi, so a
                // real readout is never followed by the generic "nothing here"
                // error (see MultimeterListener).
                pm.registerEvents(new MultimeterListener(), main);
                pm.registerEvents(new PluginHideListener(), main);
                pm.registerEvents(new ServerBrandListener(), main);
                pm.registerEvents(new ShulkerBulletListener(), main);
                pm.registerEvents(FishingListener.getInstance(), main);

            }
        });
    }

    /**
     * Initializes subsystems that depend on the Database module but live in UI-Other.
     * Must be called AFTER all core modules are initialized (Database is ready).
     * Previously VoteManager.init() was embedded in a duplicate Database module
     * registration which was silently skipped by ModuleManager.
     */
    public static void initPostCoreSubsystems() {
        VoteManager.init();
    }

    // --------------------------------------------------------------------------
    // SYSTEM
    // --------------------------------------------------------------------------

    public static void registerSystem(ModuleManager mm) {
        // Power
        mm.register(new SimpleModule("Power", "infrastructure/core", false) {
            @Override
            protected void onInit(JavaPlugin plugin) throws Exception {
                Main main = (Main) plugin;
                PowerManager.init();
                main.getServer().getPluginManager().registerEvents(new PowerInterceptListener(), main);
            }

            @Override
            protected void onReloadConfig(JavaPlugin plugin) {
                PowerManager.reloadConfig();
            }
        });
    }

    // --------------------------------------------------------------------------
    // CRAFTING (registered before Sudo)
    // --------------------------------------------------------------------------
    // --------------------------------------------------------------------------
    // MECHANICS
    // --------------------------------------------------------------------------

    public static void registerMechanics(ModuleManager mm) {
        // Radiation
        mm.register(new SimpleModule("Radiation", "mechanics/environment/radiation", true) {
            @Override
            protected void onInit(JavaPlugin plugin) throws Exception {
                RadiationManager.init();
                com.ultimateimprovements.mechanics.environment.radiation.HazmatManager.init((Main) plugin);
                double msPerUnit = ((Main) plugin).getConfig()
                        .getDouble("radiation.dosimeter_ms_per_unit", 1.0);
                com.ultimateimprovements.mechanics.environment.radiation.DosimeterTask
                        .init((Main) plugin, msPerUnit);
            }

            @Override
            protected void onReloadConfig(JavaPlugin plugin) {
                RadiationManager.getInstance().reloadConfig();
                com.ultimateimprovements.mechanics.environment.radiation.HazmatManager
                        .reloadConfig((Main) plugin);
            }
        });

        // Sunburn — without this module registration SunburnManager.init() was
        // never called (listener not registered, config not loaded), so the
        // SunburnTask ticked into a null manager every tick and the feature
        // silently did nothing.
        mm.register(new SimpleModule("Sunburn", "mechanics/environment/sunburn", false) {
            @Override
            protected void onInit(JavaPlugin plugin) throws Exception {
                SunburnManager.init();
            }
        });

        // Lightning
        mm.register(new SimpleModule("Lightning", "mechanics/environment/lightning", false) {
            @Override
            protected void onInit(JavaPlugin plugin) throws Exception {
                LightningManager.init();
            }
        });
    }

    // --------------------------------------------------------------------------
    // FEATURES
    // --------------------------------------------------------------------------

    public static void registerFeatures(ModuleManager mm) {
        // BlockDmg
        mm.register(new SimpleModule("BlockDmg", "mechanics/features/block_damage", false) {
            @Override
            protected void onInit(JavaPlugin plugin) throws Exception {
                BlockDmgManager.init((Main) plugin);
            }

            @Override
            protected void onReloadConfig(JavaPlugin plugin) {
                BlockDmgManager.reloadConfig();
            }
        });
        // BoostedCobweb
        mm.register(new SimpleModule("BoostedCobweb", "mechanics/features/cobweb_boost", false) {
            @Override
            protected void onInit(JavaPlugin plugin) throws Exception {
                BoostedCobwebManager.init((Main) plugin);
            }

            @Override
            protected void onReloadConfig(JavaPlugin plugin) {
                BoostedCobwebManager.reloadConfig();
            }
        });
        // Magnet
        mm.register(new SimpleModule("Magnet", "mechanics/environment/magnet", false) {
            @Override
            protected void onInit(JavaPlugin plugin) throws Exception {
                Main main = (Main) plugin;
                MagnetManager.init(main);
                main.getServer().getPluginManager().registerEvents(new MagnetEventListener(), main);
            }

            @Override
            protected void onReloadConfig(JavaPlugin plugin) {
                MagnetConfig.reloadConfig();
            }
        });

        // TerracotaSpeed
        mm.register(new SimpleModule("TerracotaSpeed", "mechanics/features/terracotta_speed", false) {
            @Override
            protected void onInit(JavaPlugin plugin) throws Exception {
                TerracotaSpeedManager.init((Main) plugin);
            }

            @Override
            protected void onReloadConfig(JavaPlugin plugin) {
                TerracotaSpeedManager.reloadConfig();
            }
        });
        // Durability (thin vanilla layer)
        mm.register(new SimpleModule("Integrity", "mechanics/features/integrity", false) {
            @Override
            protected void onInit(JavaPlugin plugin) throws Exception {
                Main main = (Main) plugin;
                ItemDurabilityUtil.init();
                PiercingListener.init(main);
                LowDurabilityWarningListener.init(main);
            }

            @Override
            protected void onReloadConfig(JavaPlugin plugin) {
                ItemDurabilityUtil.reloadConfig();
            }
        });
        // DeathLogger — records every player death to deaths.log + console (debug; off by default)
        mm.register(new SimpleModule("DeathLogger", "mechanics/features/death_logger", false) {
            @Override
            protected void onInit(JavaPlugin plugin) throws Exception {
                com.ultimateimprovements.listener.DeathLogger.init((Main) plugin);
            }

            @Override
            protected void onReloadConfig(JavaPlugin plugin) {
                com.ultimateimprovements.listener.DeathLogger.reloadConfig();
            }
        });
        // EnderChest
        mm.register(new SimpleModule("EnderChest", "mechanics/features/ender_chest", false) {
            @Override
            protected void onInit(JavaPlugin plugin) throws Exception {
                EnderChestManager.init((Main) plugin);
            }

            @Override
            protected void onReloadConfig(JavaPlugin plugin) {
                EnderChestManager.reloadConfig();
            }
        });

        // GlassBreak
        mm.register(new SimpleModule("GlassBreak", "mechanics/features/glass_break", false) {
            @Override
            protected void onInit(JavaPlugin plugin) throws Exception {
                GlassBreakManager.init((Main) plugin);
            }

            @Override
            protected void onReloadConfig(JavaPlugin plugin) {
                GlassBreakManager.reloadConfig();
            }
        });

        // CreativeItemValidator
        mm.register(new PluginModule("CreativeItemValidator", "mechanics/features/creativeitem", false) {
            private CreativeItemValidator listener;

            @Override
            protected void onInit(JavaPlugin plugin) throws Exception {
                CreativeItemValidator.init((Main) plugin);
                listener = CreativeItemValidator.getInstance();
            }

            @Override
            protected void onDisable(JavaPlugin plugin) {
                if (listener != null) {
                    HandlerList.unregisterAll(listener);
                    listener = null;
                }
            }

            @Override
            protected void onReloadConfig(JavaPlugin plugin) {
                CreativeItemValidator.reloadConfig();
            }
        });

        // ContainerTrigger
        mm.register(new SimpleModule("ContainerTrigger", "mechanics/features/container_trigger", false) {
            @Override
            protected void onInit(JavaPlugin plugin) throws Exception {
                ContainerTriggerManager.init((Main) plugin);
            }

            @Override
            protected void onReloadConfig(JavaPlugin plugin) {
                ContainerTriggerManager.reloadConfig();
            }
        });

        // Kits — /ui kit give|create|rename|delete|import|export|list
        mm.register(new PluginModule("Kits", "mechanics/features/kits", false) {
            private com.ultimateimprovements.mechanics.features.kits.KitDialogHandler listener;

            @Override
            protected void onInit(JavaPlugin plugin) throws Exception {
                com.ultimateimprovements.mechanics.features.kits.KitStore.init();
                listener = new com.ultimateimprovements.mechanics.features.kits.KitDialogHandler();
                plugin.getServer().getPluginManager().registerEvents(listener, plugin);
            }

            @Override
            protected void onDisable(JavaPlugin plugin) {
                if (listener != null) {
                    HandlerList.unregisterAll(listener);
                    listener = null;
                }
            }

            @Override
            protected void onReloadConfig(JavaPlugin plugin) {
                com.ultimateimprovements.mechanics.features.kits.KitStore.reload();
            }
        });
    }

    // --------------------------------------------------------------------------
    // ECONOMY (registered before AOEEnchantment)
    // --------------------------------------------------------------------------

    public static void registerEconomy(ModuleManager mm) {
        // Economy is registered by the UI-Admin addon (AdminModules).
    }

    public static void registerProtection(ModuleManager mm) {
        // Server guard modules are registered by the UI-Guard addon (GuardModules).
    }

    // --------------------------------------------------------------------------
    // UTILITY
    // --------------------------------------------------------------------------

    public static void registerUtility(ModuleManager mm) {
        // VoidProtection is registered by the UI-Protection addon (ProtectionModules).
    }

    // --------------------------------------------------------------------------
    // DISPLAY
    // --------------------------------------------------------------------------

    public static void registerDisplay(ModuleManager mm) {
        // Tab / Scoreboard / BossBar are registered by the UI-Display addon (DisplayModules).
    }

    // --------------------------------------------------------------------------
    // UTILITY (BotProtection — registered after registerUtility)
    // --------------------------------------------------------------------------

    public static void registerBotProtection(ModuleManager mm) {
        // BotProtection is registered by the UI-Guard addon (GuardModules).
    }

    // --------------------------------------------------------------------------
    // DISPLAY (MOTD — registered after registerDisplay)
    // --------------------------------------------------------------------------

    public static void registerMOTD(ModuleManager mm) {
        mm.register(new PluginModule("MOTD", "infrastructure/listeners/motd", false) {
            private MOTDListener listener;

            @Override
            protected void onInit(JavaPlugin plugin) throws Exception {
                this.listener = new MOTDListener();
                plugin.getServer().getPluginManager().registerEvents(listener, plugin);
            }

            @Override
            protected void onDisable(JavaPlugin plugin) {
                if (listener != null) {
                    // ⛔ MUST unsubscribe from Bukkit events,
                    // otherwise a duplicate listener lingers on every /ui reload
                    HandlerList.unregisterAll(listener);
                    this.listener = null;
                }
            }

            @Override
            protected void onReloadConfig(JavaPlugin plugin) {
                // On reload: disable() → onDisable() nulls the listener.
                // A new listener is created in onInit() after onReloadConfig() is called,
                // hence the null check here — the icon loads in the new listener's constructor.
                if (listener != null) {
                    listener.loadIcon();
                }
            }
        });
    }

    // --------------------------------------------------------------------------
    // SECURITY (Punish and StructureIntegrity)
    // Order in PluginStartup: registerPunish → registerStructureIntegrity
    // --------------------------------------------------------------------------

    private static boolean kickCleanupScheduled = false;

    public static void registerStructureIntegrity(ModuleManager mm) {
        // StructureIntegrity — structure integrity indicator (ender chests)
        mm.register(new PluginModule("StructureIntegrity", "mechanics/features/structure_integrity", false) {
            @Override
            protected void onInit(JavaPlugin plugin) throws Exception {
                Main main = (Main) plugin;

                // Initialize manager (starts ticker)
                StructureIntegrityManager.init(main);

                // Register interaction listener — the Structure Indicator
                // craft recipe lives in UI-Items together with the other tools.
                main.getServer().getPluginManager().registerEvents(new StructureIndicatorListener(), main);

                ConsoleLogger.info("[StructureIntegrityModule] ✔ Initialized.");
            }

            @Override
            protected void onDisable(JavaPlugin plugin) {
                StructureIntegrityManager mgr = StructureIntegrityManager.getInstance();
                if (mgr != null) {
                    mgr.shutdown();
                }
            }
        });
    }

    // --------------------------------------------------------------------------
    // PARTICLE ACCELERATOR (registered before OmniscannerModule)
    // --------------------------------------------------------------------------
    // --------------------------------------------------------------------------
    // BACKGROUND
    // --------------------------------------------------------------------------

    public static void registerBackground(ModuleManager mm) {
        // Tasks
        mm.register(new PluginModule("Tasks", "infrastructure/core", true) {
            @Override
            protected void onInit(JavaPlugin plugin) throws Exception {
                TaskManager.getInstance().startAll((Main) plugin);
            }

            @Override
            protected void onDisable(JavaPlugin plugin) {
                TaskManager.getInstance().stopAll();
            }
        });

        // AutoSave
        mm.register(new PluginModule("AutoSave", "infrastructure/database", false) {
            @Override
            protected void onInit(JavaPlugin plugin) throws Exception {
                AsyncAutoSaveManager.init((Main) plugin);
            }

            @Override
            protected void onDisable(JavaPlugin plugin) {
                AsyncAutoSaveManager.shutdown();

                // Save all systems synchronously on shutdown
                AsyncAutoSaveManager.saveAllNow();
            }
        });

        // AutoBroadcast
        mm.register(new PluginModule("AutoBroadcast", "infrastructure/auto_broadcast", false) {
            @Override
            protected void onInit(JavaPlugin plugin) throws Exception {
                AutoBroadcastManager.getInstance().start((Main) plugin);
            }

            @Override
            protected void onDisable(JavaPlugin plugin) {
                AutoBroadcastManager.getInstance().stop();
            }

            @Override
            protected void onReloadConfig(JavaPlugin plugin) {
                AutoBroadcastManager.getInstance().reload();
            }
        });
    }
}
