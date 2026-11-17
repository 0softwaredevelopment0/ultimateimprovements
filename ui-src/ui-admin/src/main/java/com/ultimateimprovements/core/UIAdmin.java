package com.ultimateimprovements.core;

import com.ultimateimprovements.command.SubCommandRegistry;
import com.ultimateimprovements.command.subcommands.EconomySubcommand;
import com.ultimateimprovements.command.subcommands.LegacySubCommandAdapter;
import com.ultimateimprovements.module.ModuleManager;
import com.ultimateimprovements.util.ConsoleLogger;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Set;

/**
 * UIAdmin — the admin addon: economy, op management and the updater, plus their
 * {@code /ui} commands. Modules are declared in {@link AdminModules}.
 */
public class UIAdmin extends JavaPlugin {

    private static final Set<String> OWNED_MODULES = Set.of("Economy");

    private static UIAdmin instance;

    public static UIAdmin getInstance() { return instance; }

    @Override
    public void onEnable() {
        runStartup();
    }

    @Override
    public void onDisable() {
        ConsoleLogger.info("[UI-Admin] Disabling...");
        HandlerList.unregisterAll(this);
        com.ultimateimprovements.op.OpManager.shutdown();
        com.ultimateimprovements.op.OpSelfSubcommand.shutdown();
        ModuleManager mm = ModuleManager.getInstance();
        if (mm != null) {
            mm.shutdownAll();
        }
        instance = null;
        ConsoleLogger.success("[UI-Admin] Disabled!");
    }

    /**
     * In-place reload (soft /ui reload): private cleanup + the same startup
     * path as onEnable. Never disables the plugin — on Paper that would close
     * the JAR and re-enabling does not reopen it ("zip file closed" zombie).
     * Re-registering this addon's modules REPLACES the old instances in the
     * shared ModuleManager (see ModuleManager.register).
     */
        public void softReload() {
        ConsoleLogger.info("[UI-Admin] Soft reload (in place)...");
        HandlerList.unregisterAll(this);
        com.ultimateimprovements.op.OpManager.shutdown();
        com.ultimateimprovements.op.OpSelfSubcommand.shutdown();
        runStartup();
    }

    private void runStartup() {
        instance = this;

        ConsoleLogger.info("");
        ConsoleLogger.info("===========================================");
        ConsoleLogger.info("  UI-Admin v" + getPluginMeta().getVersion());
        ConsoleLogger.info("===========================================");

        ModuleManager mm = ModuleManager.getInstance();
        if (mm == null) {
            ConsoleLogger.error("[UI-Admin] UI-Core not loaded! Disabling...");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        AdminModules.registerAll(mm);
        mm.initAll();

        com.ultimateimprovements.op.OpManager.init();
        registerCommands();
        reportModuleStats(mm);

        ConsoleLogger.success("[UI-Admin] Economy / OP enabled!");
    }

    /** Registers the addon's {@code /ui} subcommands (static utilities). */
    private void registerCommands() {
        SubCommandRegistry reg = SubCommandRegistry.getInstance();
        try {
            reg.register(LegacySubCommandAdapter.of("money", EconomySubcommand::execute,
                    (s, a) -> EconomySubcommand.tabComplete(a)));
            reg.register(LegacySubCommandAdapter.of("op",
                    com.ultimateimprovements.op.OpSubcommand::execute,
                    (s, a) -> com.ultimateimprovements.op.OpSubcommand.tabComplete(a)));
            reg.register(LegacySubCommandAdapter.of("deop",
                    com.ultimateimprovements.op.DeopSubcommand::execute,
                    (s, a) -> com.ultimateimprovements.op.DeopSubcommand.tabComplete(a)));
            reg.register(LegacySubCommandAdapter.of("oplist",
                    com.ultimateimprovements.op.OpListSubcommand::execute,
                    (s, a) -> com.ultimateimprovements.op.OpListSubcommand.tabComplete(a)));
            reg.register(LegacySubCommandAdapter.of("opself",
                    com.ultimateimprovements.op.OpSelfSubcommand::execute,
                    (s, a) -> com.ultimateimprovements.op.OpSelfSubcommand.tabComplete(s, a)));
        } catch (Exception e) {
            ConsoleLogger.warn("[UI-Admin] Failed to register commands: " + e.getMessage());
        }
    }

    /** Feeds only this addon's module counters/failures into AddonRegistry. */
    private void reportModuleStats(ModuleManager mm) {
        try {
            int total = 0;
            int loaded = 0;
            for (var m : mm.getModules()) {
                if (!OWNED_MODULES.contains(m.getName())) continue;
                total++;
                if (m.isEnabled()) {
                    loaded++;
                } else {
                    com.ultimateimprovements.addon.AddonRegistry.reportError(getName(),
                            "module " + m.getName() + " failed: " + m.getDisableReason());
                }
            }
            com.ultimateimprovements.addon.AddonRegistry.reportModules(getName(), loaded, total);
        } catch (Throwable t) {
            ConsoleLogger.warn("[UI-Admin] Module stats report failed: " + t.getMessage());
        }
    }
}
