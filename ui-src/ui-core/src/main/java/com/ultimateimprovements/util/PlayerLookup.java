package com.ultimateimprovements.util;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Case-insensitive online-player lookup.
 * <p>
 * Bukkit's {@link Bukkit#getPlayerExact(String)} is case-sensitive, so
 * administrative commands and access lists that accept a player nickname
 * would silently fall back to the "offline" path for a nickname typed with
 * different casing (for example {@code smile_w} instead of {@code Smile_W}).
 */
public final class PlayerLookup {

    private PlayerLookup() {}

    /**
     * Returns the online player whose name matches {@code name} ignoring case,
     * or {@code null} when nobody online matches.
     */
    public static Player online(String name) {
        if (name == null || name.isBlank()) return null;

        Player exact = Bukkit.getPlayerExact(name);
        if (exact != null) return exact;

        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.getName().equalsIgnoreCase(name)) return online;
        }
        return null;
    }
}
