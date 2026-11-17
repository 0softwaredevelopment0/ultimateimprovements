package com.ultimateimprovements.config;

import com.moandjiezana.toml.Toml;
import com.ultimateimprovements.util.MessageUtil;
import com.ultimateimprovements.util.StructureIndicatorApi;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the shared Structure Indicator error (see
 * {@code com.ultimateimprovements.util.StructureIndicatorApi}).
 * <p>
 * The generic reader renders {@code <plugin prefix> <dark_gray>[<yellow>STRUCT
 * <dark_gray>] <white>Can't measure the structure of this block.} — the tag
 * word and the body must stay present (and in sync) in both language sections
 * of {@code UI-Other.toml}, because the code falls back to hardcoded defaults
 * only when a key is missing.
 */
class StructureIndicatorMessagesTest {

    @SuppressWarnings("unchecked")
    private static Map<String, Object> indicatorSection(Map<String, Object> root, String messagesKey) {
        Map<String, Object> messages = (Map<String, Object>) root.get(messagesKey);
        assertNotNull(messages, messagesKey + " section missing");
        Map<String, Object> structures = (Map<String, Object>) messages.get("structures");
        assertNotNull(structures, messagesKey + ".structures section missing");
        Map<String, Object> indicator = (Map<String, Object>) structures.get("structure_indicator");
        assertNotNull(indicator, messagesKey + ".structures.structure_indicator section missing");
        return indicator;
    }

    @Test
    @DisplayName("Bundled UI-Other.toml has the Structure Indicator [STRUCT] tag and error in BOTH languages")
    void bundledTemplateHasIndicatorMessagesInBothLanguages() throws Exception {
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream("config/UI-Other.toml")) {
            assertNotNull(in, "bundled config/UI-Other.toml missing from resources");
            Map<String, Object> root = new Toml()
                    .read(new InputStreamReader(in, StandardCharsets.UTF_8)).toMap();

            Map<String, Object> ru = indicatorSection(root, "messages");
            Map<String, Object> en = indicatorSection(root, "messages_en");

            assertEquals("STRUCT", ru.get("prefix"), "ru structures.structure_indicator.prefix");
            assertEquals("STRUCT", en.get("prefix"), "en structures.structure_indicator.prefix");

            assertEquals("Can't measure the structure of this block.", en.get("cant_measure"),
                    "en structures.structure_indicator.cant_measure");
            String ruError = String.valueOf(ru.get("cant_measure"));
            assertTrue(ruError.contains("измерить") && ruError.contains("структуру"),
                    "ru structures.structure_indicator.cant_measure should translate the error: " + ruError);
        }
    }

    @Test
    @DisplayName("The composed error renders as '<plugin prefix> [STRUCT] Can't measure the structure of this block.'")
    void composedErrorRendersAsRequested() {
        String plain = MessageUtil.toPlainText(StructureIndicatorApi.cantMeasureMessage());
        assertEquals("[UI] [STRUCT] Can't measure the structure of this block.", plain);
    }
}
