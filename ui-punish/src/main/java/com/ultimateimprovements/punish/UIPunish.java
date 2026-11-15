package com.ultimateimprovements.punish;

import com.ultimateimprovements.core.Main;
import com.ultimateimprovements.whitelist.BlacklistManager;
import com.ultimateimprovements.whitelist.OpWhitelistManager;
import com.ultimateimprovements.whitelist.WhitelistManager;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public class UIPunish extends JavaPlugin {

    private static UIPunish instance;

    @Override
    public void onEnable() {
        runStartup();
    }

    @Override
    public void onDisable() {
        // AccessListCheckTask is owned by UI-Other — do not stop it here.
        BlockEnforcer.shutdown();
        org.bukkit.event.HandlerList.unregisterAll(this);
        com.ultimateimprovements.server.OpListsCheckTask.stop();
        getLogger().info("UI-Punish disabled!");
        instance = null;
    }

    /**
     * In-place reload (soft /ui reload): private cleanup + startup path.
     * Never disables the plugin — on Paper that would close the JAR and
     * re-enabling does not reopen it ("zip file closed" zombie). Command
     * re-registration is safe: SubCommandRegistry replaces by name.
     */
        public void softReload() {
        getLogger().info("UI-Punish soft reload (in place)...");
        BlockEnforcer.shutdown();
        org.bukkit.event.HandlerList.unregisterAll(this);
        runStartup();
    }

    private void runStartup() {
        instance = this;
        Main main = Main.getInstance();
        if (main == null) {
            getLogger().severe("UI-Core not loaded! UI-Punish cannot start.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        var pm = getServer().getPluginManager();

        // Register punishment listener
        pm.registerEvents(new PunishJoinListener(), main);

        // BLOCK punishment: join check, modal dialog, movement/interaction lock
        // and the periodic dialog re-open task.
        BlockEnforcer.init(main);

        // Register /ui punish in the shared SubCommandRegistry. The CommandScanner
        // only scans the UI-Core JAR, so module subcommands must register
        // themselves — otherwise /ui punish falls into "unknown command".
        com.ultimateimprovements.command.SubCommandRegistry registry =
                com.ultimateimprovements.command.SubCommandRegistry.getInstance();
        if (registry != null) {
            registry.register(com.ultimateimprovements.command.subcommands.LegacySubCommandAdapter.of(
                    "punish",
                    com.ultimateimprovements.command.subcommands.PunishSubcommand::execute,
                    com.ultimateimprovements.command.subcommands.LegacySubCommandAdapter.tc(
                            (s, a) -> com.ultimateimprovements.command.subcommands.PunishSubcommand.tabComplete(a))));

            // Access-list commands live in this module too. They lost their
            // registration in the multi-module refactor (the CommandScanner only
            // scans the UI-Core JAR), so /ui whitelist, /ui blacklist and
            // /ui opwhitelist answered "unknown command".
            registry.register(com.ultimateimprovements.command.subcommands.LegacySubCommandAdapter.of(
                    "whitelist",
                    com.ultimateimprovements.command.subcommands.WhitelistSubcommand::execute,
                    com.ultimateimprovements.command.subcommands.LegacySubCommandAdapter.tc(
                            (s, a) -> com.ultimateimprovements.command.subcommands.WhitelistSubcommand.tabComplete(a))));
            registry.register(com.ultimateimprovements.command.subcommands.LegacySubCommandAdapter.of(
                    "blacklist",
                    com.ultimateimprovements.command.subcommands.BlacklistSubcommand::execute,
                    com.ultimateimprovements.command.subcommands.LegacySubCommandAdapter.tc(
                            (s, a) -> com.ultimateimprovements.command.subcommands.BlacklistSubcommand.tabComplete(a))));
            registry.register(com.ultimateimprovements.command.subcommands.LegacySubCommandAdapter.of(
                    "opwhitelist",
                    com.ultimateimprovements.command.subcommands.OpWhitelistSubcommand::execute,
                    com.ultimateimprovements.command.subcommands.LegacySubCommandAdapter.tc(
                            (s, a) -> com.ultimateimprovements.command.subcommands.OpWhitelistSubcommand.tabComplete(a))));
            registry.register(com.ultimateimprovements.command.subcommands.LegacySubCommandAdapter.of(
                    "opblacklist",
                    com.ultimateimprovements.command.subcommands.OpBlacklistSubcommand::execute,
                    com.ultimateimprovements.command.subcommands.LegacySubCommandAdapter.tc(
                            (s, a) -> com.ultimateimprovements.command.subcommands.OpBlacklistSubcommand.tabComplete(a))));
        } else {
            getLogger().severe("SubCommandRegistry not available — /ui punish is not registered!");
        }

        // Initialize whitelist/blacklist
        WhitelistManager.init(main);
        BlacklistManager.init(main);
        OpWhitelistManager.init(main);
        com.ultimateimprovements.whitelist.OpBlacklistManager.init(main);

        // Re-arm persisted scheduled operations (timed_list_tasks) — after the
        // managers are up, so overdue effects can apply immediately.
        com.ultimateimprovements.whitelist.TimedAccessLists.restore(main);

        // ONE periodic check for both OP lists (opwhitelist + opblacklist),
        // interval op_lists.check_interval_ticks.
        com.ultimateimprovements.server.OpListsCheckTask.start(main);

        // NOTE: the periodic AccessListCheckTask is started by UI-Other
        // (initPostModuleSystems) — starting it here too only recreated the
        // task with a new id on every enable (double kicks/deops on one pass).

        // Clean old kicks async
        Bukkit.getScheduler().runTaskAsynchronously(main, PunishmentManager::deleteOldKicks);

        getLogger().info("UI-Punish enabled!");
    }

    public static UIPunish getInstance() {
        return instance;
    }
}
