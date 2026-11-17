package com.ultimateimprovements.mechanics.security.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Validation for the proxy-aware client-address parser. */
class AuthClientAddressTest {

    @Test
    @DisplayName("Accepts resolved IPv4 and IPv6 client addresses")
    void acceptsResolvedClientAddresses() throws Exception {
        InetSocketAddress ipv4 = new InetSocketAddress("192.0.2.25", 25565);
        assertEquals("192.0.2.25", AuthClientAddress.normalize(ipv4, false));

        InetAddress ipv6Address = InetAddress.getByName("2001:db8::25");
        InetSocketAddress ipv6 = new InetSocketAddress(ipv6Address, 25565);
        assertEquals(ipv6Address.getHostAddress(), AuthClientAddress.normalize(ipv6, false));
    }

    @Test
    @DisplayName("Accepts loopback addresses when loopback rejection is disabled")
    void acceptsLoopbackWhenDisabled() throws Exception {
        InetSocketAddress ipv4Loopback = new InetSocketAddress("127.0.0.1", 25565);
        InetSocketAddress ipv6Loopback = new InetSocketAddress("::1", 25565);

        assertEquals("127.0.0.1", AuthClientAddress.normalize(ipv4Loopback, false));
        assertEquals(InetAddress.getByName("::1").getHostAddress(),
                AuthClientAddress.normalize(ipv6Loopback, false));
    }

    @Test
    @DisplayName("Rejects loopback addresses when loopback rejection is enabled")
    void rejectsLoopbackWhenEnabled() throws Exception {
        assertNull(AuthClientAddress.normalize(
                new InetSocketAddress("127.0.0.1", 25565), true));
        assertNull(AuthClientAddress.normalize(
                new InetSocketAddress("::1", 25565), true));
    }

    @Test
    @DisplayName("Always rejects missing, unresolved, wildcard, multicast and broadcast addresses")
    void rejectsOtherUnusableAddresses() throws Exception {
        assertNull(AuthClientAddress.normalize(null, false));
        assertNull(AuthClientAddress.normalize(
                InetSocketAddress.createUnresolved("192.0.2.25", 25565), false));
        assertNull(AuthClientAddress.normalize(new InetSocketAddress("0.0.0.0", 25565), false));
        assertNull(AuthClientAddress.normalize(new InetSocketAddress("224.0.0.1", 25565), false));
        assertNull(AuthClientAddress.normalize(new InetSocketAddress("255.255.255.255", 25565), false));
    }

    @Test
    @DisplayName("Does not transform a valid client IP into the proxy socket address")
    void preservesClientIpRatherThanTransportPeer() {
        String clientIp = AuthClientAddress.normalize(
                new InetSocketAddress("198.51.100.42", 25565), false);
        assertNotNull(clientIp);
        assertEquals("198.51.100.42", clientIp);
    }
}
