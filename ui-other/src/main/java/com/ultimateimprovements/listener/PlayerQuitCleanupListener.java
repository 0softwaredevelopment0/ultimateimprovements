package com.ultimateimprovements.listener;

import com.ultimateimprovements.command.AskCordsManager;
import com.ultimateimprovements.command.MsgCommand;
import com.ultimateimprovements.mechanics.features.omniscanner.OmniscannerManager;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Central per-player cleanup on quit.
 * <p>
 * Several subsystems keep small per-UUID entries in static maps (command
 * cooldowns, reply targets, code-panel sessions) but never dropped them
 * when the player left, so the maps grew slowly over the uptime of the
 * server. This listener gives every one of them a single quit hook.
 */
public class PlayerQuitCleanupListener implements Listener {

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        var id = event.getPlayer().getUniqueId();

        // ui-essentials
        com.ultimateimprovements.command.subcommands.RtpSubcommand.cleanup(id);

        // ui-other
        MsgCommand.cleanup(id);
        AskCordsManager.cleanup(id);

        // ui-shared
        OmniscannerManager.cleanup(id);
    }
}
