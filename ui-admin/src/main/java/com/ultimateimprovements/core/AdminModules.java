package com.ultimateimprovements.core;

import com.ultimateimprovements.economy.EconomyManager;
import com.ultimateimprovements.economy.EconomyPlaceholderExpansion;
import com.ultimateimprovements.economy.VaultIntegration;
import com.ultimateimprovements.economy.listeners.IncomeListener;
import com.ultimateimprovements.economy.listeners.PlayerJoinListener;
import com.ultimateimprovements.hook.PluginHook;
import com.ultimateimprovements.module.ModuleManager;
import com.ultimateimprovements.module.SimpleModule;
import com.ultimateimprovements.util.ConsoleLogger;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * AdminModules — registration of the admin addon's modules (economy).
 * Moved out of ui-other's SimpleModules.
 */
public final class AdminModules {

    private AdminModules() {}

    public static void registerAll(ModuleManager mm) {
        // Economy — currency system (core + Vault + PAPI)
        mm.register(new SimpleModule("Economy", "economy", false) {
            @Override
            protected void onInit(JavaPlugin plugin) throws Exception {
                EconomyManager.init();

                if (PluginHook.check("Vault", "Economy")) {
                    new VaultIntegration(plugin);
                }

                var pm = plugin.getServer().getPluginManager();
                pm.registerEvents(new PlayerJoinListener(), plugin);
                pm.registerEvents(new IncomeListener(), plugin);

                try {
                    if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
                        new EconomyPlaceholderExpansion().register();
                        ConsoleLogger.info("[Economy] PlaceholderAPI expansion registered.");
                    }
                } catch (NoClassDefFoundError | Exception e) {
                    ConsoleLogger.info("[Economy] PlaceholderAPI not found — placeholders disabled.");
                }
            }
        });
    }
}
