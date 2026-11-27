package com.ultimateimprovements.module.meteor;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure tests for the weighted meteor type table. */
class MeteorTypesTest {

    @Test
    void resolvesEveryConfigKey() {
        assertEquals(MeteorType.WET, MeteorType.fromKey("wet"));
        assertEquals(MeteorType.HOT, MeteorType.fromKey("HOT"));
        assertEquals(MeteorType.BASIC, MeteorType.fromKey(" Basic "));
        assertEquals(MeteorType.ORE, MeteorType.fromKey("ore"));
        assertEquals(MeteorType.ANCIENT, MeteorType.fromKey("ancient"));
        assertNull(MeteorType.fromKey("unknown"));
        assertNull(MeteorType.fromKey(null));
    }

    @Test
    void defaultsMakeAncientFiveTimesRarer() {
        MeteorTypes types = MeteorTypes.defaults();
        assertEquals(MeteorTypes.DEFAULT_WEIGHT, types.weight(MeteorType.WET));
        assertEquals(MeteorTypes.DEFAULT_WEIGHT, types.weight(MeteorType.HOT));
        assertEquals(MeteorTypes.DEFAULT_WEIGHT, types.weight(MeteorType.BASIC));
        assertEquals(MeteorTypes.DEFAULT_WEIGHT, types.weight(MeteorType.ORE));
        assertEquals(MeteorTypes.DEFAULT_ANCIENT_WEIGHT, types.weight(MeteorType.ANCIENT));
    }

    @Test
    void unknownTypesAndBadWeightsAreIgnored() {
        Map<String, Double> raw = new LinkedHashMap<>();
        raw.put("wet", 0.0);
        raw.put("nope", 1.0);
        raw.put("hot", 2.0);

        MeteorTypes types = MeteorTypes.parse(raw);
        assertFalse(types.has(MeteorType.WET));
        assertEquals(2.0, types.weight(MeteorType.HOT));
    }

    @Test
    void emptyTableFallsBackToDefaults() {
        MeteorTypes types = MeteorTypes.parse(Map.of());
        assertEquals(MeteorTypes.DEFAULT_ANCIENT_WEIGHT, types.weight(MeteorType.ANCIENT));
    }

    @Test
    void picksOnlyConfiguredTypes() {
        Map<String, Double> raw = new LinkedHashMap<>();
        raw.put("ancient", 1.0);
        MeteorTypes types = MeteorTypes.parse(raw);

        Random random = new Random(11L);
        for (int i = 0; i < 200; i++) {
            MeteorType picked = types.next(random);
            assertNotNull(picked);
            assertEquals(MeteorType.ANCIENT, picked);
        }
    }

    @Test
    void weightsInfluenceTheDistribution() {
        Map<String, Double> raw = new LinkedHashMap<>();
        raw.put("basic", 9.0);
        raw.put("ancient", 1.0);
        MeteorTypes types = MeteorTypes.parse(raw);

        Random random = new Random(5L);
        int basic = 0;
        int ancient = 0;
        for (int i = 0; i < 10_000; i++) {
            if (types.next(random) == MeteorType.BASIC) basic++;
            else ancient++;
        }
        assertTrue(basic > ancient * 5, "expected basic to dominate, got basic=" + basic + " ancient=" + ancient);
    }
}
