package com.ultimateimprovements.core;

import com.ultimateimprovements.addon.AddonListener;
import com.ultimateimprovements.addon.AddonRegistry;
import com.ultimateimprovements.config.ConfigIntegrityValidator;
import com.ultimateimprovements.config.MessagesManager;
import com.ultimateimprovements.module.CoreModules;
import com.ultimateimprovements.module.ModuleManager;
import com.ultimateimprovements.module.VersionCheckModule;
import com.ultimateimprovements.util.ConsoleLogger;
import com.ultimateimprovements.util.MessageUtil;
import com.ultimateimprovements.util.PlaceholderResolver;

/**
 * PluginStartup — core-only initialization.
 * Feature modules are loaded by UI-Other (UIOther.java).
 */
public class PluginStartup {

    private final Main plugin;
    private static boolean startupPerformed = false;

    public PluginStartup(Main plugin) {
        this.plugin = plugin;
    }

    /**
     * Resets the JVM-wide JAR file cache (JDK 14+: {@code JarFile.reset()}).
     * After a hot JAR replacement ({@code /ui updatejar} / {@code /ui swapjar})
     * the on-disk file changes while old cached {@code JarFile} handles may
     * still exist; reopening them throws a fatal {@code ZipError}. Resetting
     * the cache before the re-enable phase of {@code /ui reload} forces the
     * JVM to re-read the fresh JAR from disk. Safe no-op on older JDKs.
     */
    public static void clearJarFileCaches() {
        try {
            java.lang.reflect.Method reset = java.util.jar.JarFile.class
                    .getDeclaredMethod("reset");
            reset.setAccessible(true);
            reset.invoke(null);
            ConsoleLogger.info("[Reload] JarFile cache reset (post-update safety).");
        } catch (Exception ignored) {
            // JDK < 14 or reset() unavailable — no-op
        }
    }

    public void startupPlugin() {
        if (startupPerformed) {
            ConsoleLogger.warn("[Startup] Already performed!");
            try { new PluginShutdown(plugin).shutdownPlugin(); }
            catch (Exception e) { ConsoleLogger.warn("[Startup] Reset: " + e.getMessage()); }
        }
        startupPerformed = true;

        ConsoleLogger.init();
        com.ultimateimprovements.util.AuthCommandLogFilter.register();

        ConsoleLogger.info("");
        ConsoleLogger.info("===========================================");
        ConsoleLogger.info("  UltimateImprovements v" + plugin.getPluginMeta().getVersion());
        ConsoleLogger.info("  Data: plugins/" + UltimateDirs.DIR_NAME);
        ConsoleLogger.info("===========================================");
        ConsoleLogger.info("");

        checkJavaVersion();
        initInfrastructure();
        initModuleSystem();
        initOpState();
        printBanner();
        ConsoleLogger.success("[PLUGIN] UI-Core enabled!");
        ConsoleLogger.success("[PLUGIN] UltimateImprovements loaded successfully!");
    }

    private void initInfrastructure() {
        // Must run before the shared folder is created: the pre-1.8.6 folder is
        // `plugins/UltimateImprovments` (typo) and is renamed, not recreated.
        UltimateDirs.migrateLegacyFolderName();

        Permissions.registerAll();

        // Shared family folder: plugins/UltimateImprovements (one folder, one DB,
        // per-addon configs — see UltimateDirs / AddonConfigManager).
        com.ultimateimprovements.util.FileLogger.ensureDirectory(
                UltimateDirs.base(), "UltimateImprovements");
        com.ultimateimprovements.util.FileLogger.ensureDirectory(
                UltimateDirs.configsDir(), "Configs");

        loadConfigFile();
        ConsoleLogger.refreshDebugFlags();
        ConfigIntegrityValidator.validate(plugin);
        MessageUtil.reloadPrefix();
        MessagesManager.init(plugin);
        PlaceholderResolver.init();

        if (PlaceholderResolver.isPapiAvailable()) {
            try {
                var exp = new com.ultimateimprovements.hook.UIPlaceholderExpansion();
                if (exp.register()) ConsoleLogger.info("[PAPI] Expansion registered");
            } catch (Throwable t) {
                ConsoleLogger.warn("[PAPI] Failed: " + t.getMessage());
            }
        }

        Keys.init(plugin);
        ConsoleLogger.info("[Init] Infrastructure ready.");
    }

    private void initModuleSystem() {
        ModuleManager.init(plugin);
        var mm = ModuleManager.getInstance();
        mm.register(new VersionCheckModule());
        CoreModules.registerAll(mm);
        mm.initAll();
        ConsoleLogger.info("[Init] Core modules ready.");

        // /ui addon — part of the addon system (Core = main, rest = addons).
        try {
            com.ultimateimprovements.command.SubCommandRegistry.getInstance()
                    .register(new com.ultimateimprovements.command.subcommands.AddonSubcommand());
        } catch (Exception e) {
            ConsoleLogger.warn("[Addons] Failed to register /ui addon: " + e.getMessage());
        }

        // /ui language — global ru/en switch for all addon messages.
        try {
            com.ultimateimprovements.command.SubCommandRegistry.getInstance()
                    .register(new com.ultimateimprovements.command.subcommands.LangSubcommand());
        } catch (Exception e) {
            ConsoleLogger.warn("[Lang] Failed to register /ui language: " + e.getMessage());
        }

        // /ui config — config management (regen with confirmation + permission gate).
        try {
            com.ultimateimprovements.command.SubCommandRegistry.getInstance()
                    .register(new com.ultimateimprovements.command.subcommands.ConfigSubcommand());
        } catch (Exception e) {
            ConsoleLogger.warn("[Config] Failed to register /ui config: " + e.getMessage());
        }

        // Addon discovery: Core is the mandatory host, everything else is an
        // addon recognized by the plugin.yml marker (addon-for: UI-Core).
        // Addons enable AFTER Core (depend: [UI-Core]) and register themselves
        // via AddonListener; this startup pass catches anything already loaded.
        AddonRegistry.discover();
        plugin.getServer().getPluginManager().registerEvents(new AddonListener(), plugin);

        // One-shot "loaded successfully" alert per session for alert holders
        // (ui.alerts / OP): at join, plus a sweep of players already online.
        plugin.getServer().getPluginManager().registerEvents(
                new com.ultimateimprovements.listener.LoadedAlertListener(), plugin);
        com.ultimateimprovements.listener.LoadedAlertListener.sweepOnline();
    }

    private void initOpState() {
        // The OP list lives in UI-Core so every addon (commands, OP gates,
        // troll/opself grants) can keep it in sync with the real OP flag.
        com.ultimateimprovements.op.OpManager.init();
        plugin.getServer().getPluginManager().registerEvents(
                new com.ultimateimprovements.op.OpJoinSyncListener(), plugin);
    }

    private void loadConfigFile() {
        // Composite backend: Main.reloadConfig() runs UltimateDirs.migrateLegacyFolders()
        // + AddonConfigManager.init(), which generate and load every
        // configs/UI-<Addon>.toml. Broken lines are salvaged, missing keys are
        // repaired from the bundled fragments.
        try {
            plugin.reloadConfig();
        } catch (Exception e) {
            // A broken file after salvage must not kill the startup — missing keys
            // fall back to the bundled fragment defaults.
            ConsoleLogger.warn("[Config] Could not load configs: " + e.getMessage());
        }
    }

    private void checkJavaVersion() {
        try {
            plugin.getClass().getClassLoader().loadClass(
                    "com.ultimateimprovements.util.FileLogger");
        } catch (IllegalArgumentException e) {
            String msg = e.getMessage() != null ? e.getMessage() : "";
            if (msg.contains("major version")) ConsoleLogger.warn("  Java version may be incompatible!");
        } catch (ClassNotFoundException ignored) {}
    }

    private void printBanner() {
        ConsoleLogger.info("");
        ConsoleLogger.info("==================================================");
        ConsoleLogger.info("  UI-Core v" + plugin.getPluginMeta().getVersion());
        ConsoleLogger.info("  Server: " + plugin.getServer().getName() + " " + plugin.getServer().getVersion());
        ConsoleLogger.info("==================================================");
        ConsoleLogger.info("");
    }

    public static void resetStartupFlag() {
        startupPerformed = false;
    }
}
