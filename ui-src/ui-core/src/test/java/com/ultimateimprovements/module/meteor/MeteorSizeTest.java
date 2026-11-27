package com.ultimateimprovements.module.meteor;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure parser tests for the meteor size rule. */
class MeteorSizeTest {

    @Test
    void parsesAndNormalisesRanges() {
        assertArrayEquals(new int[]{1, 8}, MeteorSize.parseRange("1-8"));
        assertArrayEquals(new int[]{1, 8}, MeteorSize.parseRange("8 - 1"));
        assertArrayEquals(new int[]{1, 12}, MeteorSize.parseRange("0-20"));
    }

    @Test
    void rejectsInvalidRanges() {
        assertThrows(IllegalArgumentException.class, () -> MeteorSize.parseRange("8"));
        assertThrows(IllegalArgumentException.class, () -> MeteorSize.parseRange("1-"));
        assertThrows(IllegalArgumentException.class, () -> MeteorSize.parseRange("a-b"));
        assertThrows(IllegalArgumentException.class, () -> MeteorSize.parseRange(null));
    }

    @Test
    void rangeModePicksInsideTheConfiguredBounds() {
        MeteorSize size = MeteorSize.parse("range", 2, List.of(3), "2-6");
        Random random = new Random(42L);
        for (int i = 0; i < 500; i++) {
            int picked = size.nextSize(random);
            assertTrue(picked >= 2 && picked <= 6, "picked " + picked + " is outside 2-6");
        }
    }

    @Test
    void listModePicksOnlyConfiguredEntries() {
        MeteorSize size = MeteorSize.parse("list", 2, List.of(1, 4, 7), "1-8");
        Random random = new Random(7L);
        for (int i = 0; i < 200; i++) {
            int picked = size.nextSize(random);
            assertTrue(picked == 1 || picked == 4 || picked == 7, "picked unexpected " + picked);
        }
    }

    @Test
    void staticModeAlwaysReturnsTheSameValue() {
        MeteorSize size = MeteorSize.parse("static", 5, List.of(1), "1-8");
        assertEquals(5, size.nextSize(new Random(1L)));
        assertEquals(5, size.nextSize(new Random(2L)));
    }

    @Test
    void invalidValuesFallBackToDefaults() {
        MeteorSize size = MeteorSize.parse("nonsense", 0, List.of(), "oops");
        assertEquals(MeteorSize.Mode.RANGE, size.getMode());
        assertEquals(MeteorSize.DEFAULT_STATIC, size.getStaticSize());
        assertEquals(MeteorSize.DEFAULT_RANGE_MIN, size.getRangeMin());
        assertEquals(MeteorSize.DEFAULT_RANGE_MAX, size.getRangeMax());
        int picked = size.nextSize(new Random(3L));
        assertTrue(picked >= 1 && picked <= 8);
    }

    @Test
    void defaultsAreRangeOneToEight() {
        MeteorSize size = MeteorSize.defaults();
        assertEquals(MeteorSize.Mode.RANGE, size.getMode());
        assertEquals(1, size.getRangeMin());
        assertEquals(8, size.getRangeMax());
    }
}
