package com.ultimateimprovements.mechanics.environment.sunburn;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Guards the default {@code sunburn.block_blacklist}: it must cover exactly
 * every vanilla glass block and pane, so a glass roof really shields players
 * from sunburn without touching the config.
 * <p>
 * The check is name-based on purpose: touching {@link org.bukkit.Material}
 * outside a running server fails its static initializer, and the keys
 * ({@code white_stained_glass}, ...) are what the config file actually stores.
 */
class SunburnManagerTest {

    private static final String[] GLASS_COLORS = {
            "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink",
            "gray", "light_gray", "cyan", "purple", "blue", "brown", "green",
            "red", "black"
    };

    @Test
    @DisplayName("Default sunburn blacklist covers every vanilla glass block and pane")
    void defaultBlacklistCoversGlass() {
        Set<String> expected = new LinkedHashSet<>(
                List.of("glass", "tinted_glass", "glass_pane"));
        for (String color : GLASS_COLORS) {
            expected.add(color + "_stained_glass");
            expected.add(color + "_stained_glass_pane");
        }

        List<String> defaults = SunburnManager.defaultBlockBlacklist();

        assertEquals(expected, new LinkedHashSet<>(defaults),
                "Default sunburn blacklist must match all glass blocks and panes");
        assertEquals(defaults.size(), new HashSet<>(defaults).size(),
                "Default sunburn blacklist must not contain duplicate entries");
    }
}
