package com.ultimateimprovements.config;

import com.moandjiezana.toml.Toml;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Guards the bundled per-head sunburn chances: every listed skull keeps its
 * value in {@code config/UI-Player.toml}, so the defaults in the template stay
 * in sync with what the feature promises.
 */
class SunburnHeadChancesTest {

    @SuppressWarnings("unchecked")
    private static Map<String, Object> sunburn(Map<String, Object> root) {
        Map<String, Object> sunburn = (Map<String, Object>) root.get("sunburn");
        assertNotNull(sunburn, "sunburn section missing");
        return sunburn;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> section(Map<String, Object> root, String key) {
        Map<String, Object> section = (Map<String, Object>) sunburn(root).get(key);
        assertNotNull(section, "sunburn." + key + " section missing");
        return section;
    }

    @Test
    @DisplayName("Bundled UI-Player.toml has the default per-head break chances")
    void bundledTemplateHasDefaultHeadChances() throws Exception {
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream("config/UI-Player.toml")) {
            assertNotNull(in, "bundled config/UI-Player.toml missing from resources");
            Map<String, Object> root = new Toml()
                    .read(new InputStreamReader(in, StandardCharsets.UTF_8)).toMap();

            Map<String, Object> global = sunburn(root);
            Map<String, Object> chances = section(root, "head_break_chances");

            assertEquals(0.1, ((Number) global.get("head_break_chance_percent")).doubleValue(), 1e-9,
                    "fallback chance must stay 0.1%");
            assertEquals(0.4, ((Number) chances.get("SKELETON_SKULL")).doubleValue(), 1e-9);
            assertEquals(0.2, ((Number) chances.get("WITHER_SKELETON_SKULL")).doubleValue(), 1e-9);
            assertEquals(0.1, ((Number) chances.get("PLAYER_HEAD")).doubleValue(), 1e-9);
            assertEquals(0.5, ((Number) chances.get("ZOMBIE_HEAD")).doubleValue(), 1e-9);
            assertEquals(0.6, ((Number) chances.get("CREEPER_HEAD")).doubleValue(), 1e-9);
            assertEquals(0.3, ((Number) chances.get("PIGLIN_HEAD")).doubleValue(), 1e-9);
            assertEquals(0.1, ((Number) chances.get("DRAGON_HEAD")).doubleValue(), 1e-9);
        }
    }
}
