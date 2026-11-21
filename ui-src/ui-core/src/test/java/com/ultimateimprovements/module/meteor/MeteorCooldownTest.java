package com.ultimateimprovements.module.meteor;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure parser/format tests for the meteor spawn cooldown. */
class MeteorCooldownTest {

    @Test
    void parsesEveryTimeSuffix() {
        assertEquals(45L, MeteorCooldown.parseSeconds("45s"));
        assertEquals(15L * 60L, MeteorCooldown.parseSeconds("15m"));
        assertEquals(2L * 3600L, MeteorCooldown.parseSeconds("2h"));
        assertEquals(86400L, MeteorCooldown.parseSeconds("1d"));
        assertEquals(30L * 60L, MeteorCooldown.parseSeconds(" 30M "));
    }

    @Test
    void rejectsInvalidTimeValues() {
        assertThrows(IllegalArgumentException.class, () -> MeteorCooldown.parseSeconds("30"));
        assertThrows(IllegalArgumentException.class, () -> MeteorCooldown.parseSeconds("30x"));
        assertThrows(IllegalArgumentException.class, () -> MeteorCooldown.parseSeconds("m"));
        assertThrows(IllegalArgumentException.class, () -> MeteorCooldown.parseSeconds("0m"));
        assertThrows(IllegalArgumentException.class, () -> MeteorCooldown.parseSeconds("-5m"));
        assertThrows(IllegalArgumentException.class, () -> MeteorCooldown.parseSeconds(""));
        assertThrows(IllegalArgumentException.class, () -> MeteorCooldown.parseSeconds(null));
    }

    @Test
    void parsesAndNormalisesRanges() {
        long[] range = MeteorCooldown.parseRange("40m-50m");
        assertEquals(40L * 60L, range[0]);
        assertEquals(50L * 60L, range[1]);

        long[] reversed = MeteorCooldown.parseRange("50m - 40m");
        assertEquals(40L * 60L, reversed[0]);
        assertEquals(50L * 60L, reversed[1]);
    }

    @Test
    void rejectsInvalidRanges() {
        assertThrows(IllegalArgumentException.class, () -> MeteorCooldown.parseRange("40m"));
        assertThrows(IllegalArgumentException.class, () -> MeteorCooldown.parseRange("40m-"));
        assertThrows(IllegalArgumentException.class, () -> MeteorCooldown.parseRange(null));
    }

    @Test
    void rangeModePicksInsideTheConfiguredBounds() {
        MeteorCooldown cooldown = MeteorCooldown.parse("range", "30m",
                List.of("15m"), "10s-20s");
        Random random = new Random(42L);
        for (int i = 0; i < 200; i++) {
            long picked = cooldown.nextSeconds(random);
            assertTrue(picked >= 10L && picked <= 20L,
                    "picked " + picked + " is outside 10s-20s");
        }
    }

    @Test
    void listModePicksOnlyConfiguredEntries() {
        MeteorCooldown cooldown = MeteorCooldown.parse("list", "30m",
                List.of("5m", "10m", "20m"), "40m-50m");
        Random random = new Random(7L);
        for (int i = 0; i < 100; i++) {
            long picked = cooldown.nextSeconds(random);
            assertTrue(picked == 300L || picked == 600L || picked == 1200L,
                    "picked unexpected " + picked);
        }
    }

    @Test
    void staticModeAlwaysReturnsTheSameValue() {
        MeteorCooldown cooldown = MeteorCooldown.parse("static", "12m",
                List.of("5m"), "40m-50m");
        assertEquals(12L * 60L, cooldown.nextSeconds(new Random(1L)));
        assertEquals(12L * 60L, cooldown.nextSeconds(new Random(2L)));
    }

    @Test
    void defaultsAreRangeFortyToFiftyMinutes() {
        MeteorCooldown cooldown = MeteorCooldown.defaults();
        assertEquals(MeteorCooldown.Mode.RANGE, cooldown.getMode());
        assertEquals(40L * 60L, cooldown.getRangeMinSeconds());
        assertEquals(50L * 60L, cooldown.getRangeMaxSeconds());
    }

    @Test
    void formatsDurationsCompactly() {
        assertEquals("0s", MeteorCooldown.formatSeconds(0));
        assertEquals("45s", MeteorCooldown.formatSeconds(45));
        assertEquals("2m 5s", MeteorCooldown.formatSeconds(125));
        assertEquals("1h", MeteorCooldown.formatSeconds(3600));
        assertEquals("1d 2h 3m 4s", MeteorCooldown.formatSeconds(86400 + 7200 + 180 + 4));
    }
}
