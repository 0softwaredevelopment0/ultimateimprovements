package com.ultimateimprovements.core;

import com.ultimateimprovements.mechanics.security.auth.AuthListener;
import com.ultimateimprovements.mechanics.security.auth.AuthManager;
import com.ultimateimprovements.module.ModuleManager;
import com.ultimateimprovements.module.SimpleModule;
import com.ultimateimprovements.util.ConsoleLogger;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * AuthModules — registration of the authentication module. Moved out of
 * ui-other's SimpleModules so the auth addon (UI-Auth) owns its registration.
 */
public final class AuthModules {

    private AuthModules() {}

    /** Registers the Auth module. */
    public static void registerAll(ModuleManager mm) {
        mm.register(new SimpleModule("Auth", "mechanics/security/auth", true) {
            private AuthListener listener;

            @Override
            protected void onInit(JavaPlugin plugin) throws Exception {
                AuthManager.init();
                // ModuleManager initializes modules with UI-Core's Main instance,
                // but the listener belongs to UI-Auth so its own reload/disable can
                // reliably unregister it.
                JavaPlugin listenerOwner = UIAuth.getInstance() != null
                        ? UIAuth.getInstance() : plugin;
                listener = new AuthListener();
                plugin.getServer().getPluginManager().registerEvents(listener, listenerOwner);
                ConsoleLogger.info("[AuthModule] Auth system initialized.");
            }

            @Override
            protected void onDisable(JavaPlugin plugin) {
                if (listener != null) {
                    HandlerList.unregisterAll(listener);
                    listener = null;
                }
                AuthManager.shutdown();
            }
        });
    }
}
