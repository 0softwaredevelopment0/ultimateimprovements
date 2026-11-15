package com.ultimateimprovements.core;

import com.ultimateimprovements.mechanics.security.auth.AuthListener;
import com.ultimateimprovements.mechanics.security.auth.AuthManager;
import com.ultimateimprovements.module.ModuleManager;
import com.ultimateimprovements.module.SimpleModule;
import com.ultimateimprovements.util.ConsoleLogger;
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
            @Override
            protected void onInit(JavaPlugin plugin) throws Exception {
                Main main = (Main) plugin;
                AuthManager.init();
                main.getServer().getPluginManager().registerEvents(new AuthListener(), main);
                ConsoleLogger.info("[AuthModule] Auth system initialized.");
            }
        });
    }
}
