package com.ultimateimprovements.mechanics.security.auth;

import io.papermc.paper.connection.PlayerGameConnection;
import org.bukkit.entity.Player;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;

/**
 * Resolves the real client IP using Paper's proxy-aware connection API.
 *
 * <p>Do not replace this with {@link Player#getAddress()} or
 * {@link io.papermc.paper.connection.PlayerGameConnection#getAddress()}:
 * those expose the transport peer and may identify Velocity/the backend socket.
 * Paper's {@link io.papermc.paper.connection.PlayerGameConnection#getClientAddress()}
 * returns the actual client address extracted from the proxy handshake.
 */
public final class AuthClientAddress {

    private AuthClientAddress() {}

    /**
     * @return normalized numeric client IP, or {@code null} when Paper cannot
     *         provide a usable client address
     */
    public static String getClientIp(Player player) {
        if (player == null || !player.isOnline()) return null;

        try {
            PlayerGameConnection connection = player.getConnection();
            if (connection == null) return null;
            return normalize(connection.getClientAddress());
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    static String normalize(InetSocketAddress address) {
        if (address == null || address.isUnresolved()) return null;

        InetAddress resolved = address.getAddress();
        if (resolved == null
                || resolved.isAnyLocalAddress()
                || resolved.isLoopbackAddress()
                || resolved.isMulticastAddress()) {
            return null;
        }

        byte[] rawAddress = resolved.getAddress();
        if (rawAddress == null || (rawAddress.length != 4 && rawAddress.length != 16)) {
            return null;
        }

        try {
            // Rebuild from the address bytes to drop any IPv6 interface scope id
            // and persist a stable numeric representation.
            String ip = InetAddress.getByAddress(rawAddress).getHostAddress();
            return ip == null || ip.isBlank() ? null : ip;
        } catch (UnknownHostException impossible) {
            return null;
        }
    }
}
