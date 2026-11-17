package com.ultimateimprovements.mechanics.features.netherportal;

import com.ultimateimprovements.mechanics.features.netherportal.NetherPortalManager.Target;
import com.ultimateimprovements.mechanics.features.netherportal.NetherPortalManager.WorldMode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure config helpers of the nether portal mechanic. */
class NetherPortalConfigTest {

    @Test
    void targetAcceptsSingularAndPluralValues() {
        assertEquals(Target.PLAYERS, NetherPortalManager.parseTarget("players"));
        assertEquals(Target.PLAYERS, NetherPortalManager.parseTarget("PLAYER"));
        assertEquals(Target.ENTITIES, NetherPortalManager.parseTarget("entities"));
        assertEquals(Target.ENTITIES, NetherPortalManager.parseTarget("entity"));
        assertEquals(Target.PLAYERS, NetherPortalManager.parseTarget("garbage"));
        assertEquals(Target.PLAYERS, NetherPortalManager.parseTarget(null));
    }

    @Test
    void worldModeAcceptsAliasesAndFallsBackToAll() {
        assertEquals(WorldMode.ALL, NetherPortalManager.parseWorldMode("all"));
        assertEquals(WorldMode.WHITELIST, NetherPortalManager.parseWorldMode("whitelist"));
        assertEquals(WorldMode.WHITELIST, NetherPortalManager.parseWorldMode("white"));
        assertEquals(WorldMode.BLACKLIST, NetherPortalManager.parseWorldMode("blacklist"));
        assertEquals(WorldMode.BLACKLIST, NetherPortalManager.parseWorldMode("BLACK"));
        assertEquals(WorldMode.ALL, NetherPortalManager.parseWorldMode("garbage"));
    }

    @Test
    void intervalsAreClampedToOneTick() {
        assertEquals(1, NetherPortalManager.clampInterval(0));
        assertEquals(1, NetherPortalManager.clampInterval(-20));
        assertEquals(20, NetherPortalManager.clampInterval(20));
        assertEquals(200, NetherPortalManager.clampInterval(200));
    }

    @Test
    void worldNamesAreTrimmedLowerCasedAndDeduplicated() {
        Set<String> worlds = NetherPortalManager.normalizeWorlds(
                List.of(" minecraft:the_nether ", "MINECRAFT:THE_NETHER", "", "  ",
                        "my_namespace:arena"));
        assertEquals(Set.of("minecraft:the_nether", "my_namespace:arena"), worlds);
    }

    @Test
    void emptyWorldListIsAnEmptyImmutableSet() {
        assertTrue(NetherPortalManager.normalizeWorlds(List.of()).isEmpty());
        assertTrue(NetherPortalManager.normalizeWorlds(null).isEmpty());
    }
}
