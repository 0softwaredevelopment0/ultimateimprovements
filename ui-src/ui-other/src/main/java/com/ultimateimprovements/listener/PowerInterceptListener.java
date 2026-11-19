package com.ultimateimprovements.listener;

import com.ultimateimprovements.command.VanillaPowerCommand;
import org.bukkit.command.CommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.ServerCommandEvent;

import java.util.Locale;

/**
 * Routes the <b>namespaced</b> vanilla {@code /stop}/{@code /restart} variants
 * ({@code /minecraft:stop}, {@code /bukkit:restart}, …) into
 * {@link VanillaPowerCommand}.
 * <p>
 * The bare labels are CommandMap overrides (see
 * {@code CommandRegistrar}), so they already work for players, the console and
 * the sudo re-run. Namespaced labels bypass that override and would otherwise
 * reach the unmodified vanilla implementation without the console confirmation
 * flow — this listener cancels them and hands the call to the same handler.
 */
public class PowerInterceptListener implements Listener {

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        if (event.isCancelled()) return;
        Parsed parsed = parse(event.getMessage());
        if (parsed == null) return;

        event.setCancelled(true);
        VanillaPowerCommand.handle(event.getPlayer(), parsed.restart(), parsed.display());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onConsoleCommand(ServerCommandEvent event) {
        if (event.isCancelled()) return;
        Parsed parsed = parse("/" + event.getCommand());
        if (parsed == null) return;

        event.setCancelled(true);
        CommandSender sender = event.getSender();
        VanillaPowerCommand.handle(sender, parsed.restart(), parsed.display());
    }

    private record Parsed(boolean restart, String display) {}

    /**
     * Parses only namespaced {@code minecraft:}/{@code bukkit:} stop/restart
     * forms; bare labels are handled by the CommandMap override.
     */
    private static Parsed parse(String raw) {
        String text = raw == null ? "" : raw.trim();
        if (text.startsWith("/")) text = text.substring(1);
        String head = text.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        int colon = head.indexOf(':');
        if (colon < 0) return null;
        String namespace = head.substring(0, colon);
        if (!namespace.equals("minecraft") && !namespace.equals("bukkit")) return null;

        String label = head.substring(colon + 1);
        if (label.equals("stop")) return new Parsed(false, "/" + namespace + ":stop");
        if (label.equals("restart")) return new Parsed(true, "/" + namespace + ":restart");
        return null;
    }
}
