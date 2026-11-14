package com.ultimateimprovments.config;

import com.moandjiezana.toml.Toml;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Guards the configurable console texts of the config validation pass
 * ({@code messages.config_validation.*} / {@code messages_en.config_validation.*}).
 * <p>
 * Regression: the validator used to hard-code Russian-only strings, so server
 * owners could neither edit them nor get English output.
 */
class ConfigValidationMessagesTest {

    @SuppressWarnings("unchecked")
    private static YamlConfiguration loadBundledTemplate() throws Exception {
        try (InputStream in = ConfigValidationMessagesTest.class.getClassLoader()
                .getResourceAsStream("config/UI-Core.toml")) {
            assertNotNull(in, "bundled config/UI-Core.toml missing from resources");
            Map<String, Object> root = new Toml()
                    .read(new InputStreamReader(in, StandardCharsets.UTF_8)).toMap();
            YamlConfiguration view = new YamlConfiguration();
            flattenInto(root, "", view);
            return view;
        }
    }

    private static void flattenInto(Map<String, Object> map, String prefix, YamlConfiguration target) {
        for (Map.Entry<String, Object> e : map.entrySet()) {
            String path = prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey();
            Object value = e.getValue();
            if (value instanceof Map<?, ?> nested) {
                @SuppressWarnings("unchecked")
                Map<String, Object> nestedMap = (Map<String, Object>) nested;
                flattenInto(nestedMap, path, target);
            } else {
                target.set(path, value);
            }
        }
    }

    @Test
    @DisplayName("Bundled UI-Core.toml carries EVERY validation text in BOTH language sections")
    void bundledTemplateHasEveryTextInBothLanguages() throws Exception {
        YamlConfiguration cfg = loadBundledTemplate();
        for (ConfigValidationMessages.Text text : ConfigValidationMessages.Text.values()) {
            String ru = cfg.getString("messages.config_validation." + text.key(), null);
            String en = cfg.getString("messages_en.config_validation." + text.key(), null);
            assertNotNull(ru, "messages.config_validation." + text.key() + " (ru) missing");
            assertNotNull(en, "messages_en.config_validation." + text.key() + " (en) missing");
            assertFalse(ru.isEmpty(), "messages.config_validation." + text.key() + " (ru) is empty");
            assertFalse(en.isEmpty(), "messages_en.config_validation." + text.key() + " (en) is empty");
        }
    }

    @Test
    @DisplayName("Resolution follows messages.lang and substitutes %placeholders%")
    void resolvesActiveLanguageWithPlaceholders() throws Exception {
        YamlConfiguration cfg = loadBundledTemplate();

        cfg.set("messages.lang", "ru");
        String ru = new ConfigValidationMessages(cfg).get(
                ConfigValidationMessages.Text.EXPECTED_BOOLEAN,
                "type", "int", "value", 42);
        assertTrue(ru.contains("Ожидался"), "ru text expected, got: " + ru);
        assertTrue(ru.contains("int") && ru.contains("42"), "placeholders not substituted: " + ru);

        cfg.set("messages.lang", "en");
        String en = new ConfigValidationMessages(cfg).get(
                ConfigValidationMessages.Text.EXPECTED_BOOLEAN,
                "type", "int", "value", 42);
        assertTrue(en.contains("Expected"), "en text expected, got: " + en);
        assertTrue(en.contains("int") && en.contains("42"), "placeholders not substituted: " + en);
    }

    @Test
    @DisplayName("A key missing from the active language falls back to the other language")
    void fallsBackToOtherLanguage() throws Exception {
        YamlConfiguration cfg = loadBundledTemplate();
        cfg.set("messages.lang", "en");
        cfg.set("messages_en.config_validation.empty_string", null);
        String value = new ConfigValidationMessages(cfg)
                .get(ConfigValidationMessages.Text.EMPTY_STRING);
        assertEquals("Строка пуста", value, "ru fallback expected");
    }
}
