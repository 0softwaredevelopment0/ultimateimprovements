package com.ultimateimprovements.listener;

import com.ultimateimprovements.command.VanillaOpCommand;
import org.bukkit.command.CommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.ServerCommandEvent;

import java.util.Locale;

/**
 * Routes the <b>namespaced</b> vanilla {@code /op} and {@code /deop} variants
 * ({@code /minecraft:op}, {@code /bukkit:deop}, …) into
 * {@link VanillaOpCommand}.
 * <p>
 * The bare labels are registered through the CommandMap
 * ({@code CommandRegistrar}), so they are covered on every dispatch path
 * including the sudo re-run. Namespaced labels bypass the Bukkit CommandMap and
 * would otherwise reach the unmodified vanilla implementation — this listener
 * cancels them and hands the call to the same handler.
 * <p>
 * Events cancelled by another plugin (the sudo dialog, for example) are left
 * untouched.
 */
public class OpCommandBlocker implements Listener {

    public OpCommandBlocker() {
        // A soft reload creates a new listener — pending confirmations must not
        // survive it.
        VanillaOpCommand.resetPending();
    }

    // =========================
    // PLAYER
    // =========================
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        if (event.isCancelled()) return;
        Parsed parsed = parse(event.getMessage());
        if (parsed == null) return;

        event.setCancelled(true);
        VanillaOpCommand.handle(event.getPlayer(), parsed.grant(), parsed.args(), parsed.display());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        VanillaOpCommand.clearPending(event.getPlayer().getUniqueId());
    }

    // =========================
    // CONSOLE
    // =========================
    @EventHandler(priority = EventPriority.MONITOR)
    public void onConsoleCommand(ServerCommandEvent event) {
        if (event.isCancelled()) return;
        Parsed parsed = parse("/" + event.getCommand());
        if (parsed == null) return;

        event.setCancelled(true);
        CommandSender sender = event.getSender();
        VanillaOpCommand.handle(sender, parsed.grant(), parsed.args(), parsed.display());
    }

    // =========================
    // PARSING
    // =========================
    private record Parsed(boolean grant, String[] args, String display) {}

    /**
     * Parses only namespaced {@code minecraft:}/{@code bukkit:} op/deop forms.
     *
     * @return {@code null} for anything else (bare labels are handled by the
     *         CommandMap override)
     */
    private static Parsed parse(String raw) {
        String text = raw == null ? "" : raw.trim();
        if (text.startsWith("/")) text = text.substring(1);
        String[] parts = text.split("\\s+");
        if (parts.length == 0) return null;

        String head = parts[0].toLowerCase(Locale.ROOT);
        int colon = head.indexOf(':');
        if (colon < 0) return null;
        String namespace = head.substring(0, colon);
        if (!namespace.equals("minecraft") && !namespace.equals("bukkit")) return null;

        String label = head.substring(colon + 1);
        boolean grant;
        if (label.equals("op")) {
            grant = true;
        } else if (label.equals("deop")) {
            grant = false;
        } else {
            return null;
        }

        String[] args = new String[Math.max(0, parts.length - 1)];
        System.arraycopy(parts, 1, args, 0, args.length);
        return new Parsed(grant, args, "/" + namespace + ":" + label);
    }
}
