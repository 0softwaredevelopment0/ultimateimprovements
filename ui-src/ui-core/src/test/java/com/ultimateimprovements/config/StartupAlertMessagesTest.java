package com.ultimateimprovements.config;

import com.moandjiezana.toml.Toml;
import com.ultimateimprovements.util.MessageUtil;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the configurable startup alert texts
 * ({@code messages.loaded_alert.*} / {@code messages_en.loaded_alert.*} and
 * {@code messages.luckperms_warning.*} / {@code messages_en.luckperms_warning.*}).
 * <p>
 * Regression: {@code LoadedAlertListener} used to hard-code English-only
 * strings, so server owners could neither translate them nor edit the wording.
 */
class StartupAlertMessagesTest {

    private static YamlConfiguration loadBundledTemplate() throws Exception {
        try (InputStream in = StartupAlertMessagesTest.class.getClassLoader()
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
    @DisplayName("Startup alert texts exist in BOTH language sections and are not blank")
    void startupAlertTextsExistInBothLanguages() throws Exception {
        YamlConfiguration cfg = loadBundledTemplate();
        for (String path : new String[]{
                "messages.loaded_alert.message",
                "messages_en.loaded_alert.message",
                "messages.luckperms_warning.message",
                "messages.luckperms_warning.download",
                "messages_en.luckperms_warning.message",
                "messages_en.luckperms_warning.download"}) {
            String value = cfg.getString(path, null);
            assertNotNull(value, path + " missing");
            assertFalse(value.isBlank(), path + " must not be blank");
        }
    }

    @Test
    @DisplayName("Startup alert toggles exist in the bundled template")
    void startupAlertTogglesExist() throws Exception {
        YamlConfiguration cfg = loadBundledTemplate();
        assertTrue(cfg.isSet("loaded_alert.enabled"), "loaded_alert.enabled missing");
        assertTrue(cfg.isSet("luckperms_warning.enabled"), "luckperms_warning.enabled missing");
    }

    @Test
    @DisplayName("Download lines carry a MiniMessage open_url click event by default")
    void downloadLinesAreClickable() throws Exception {
        YamlConfiguration cfg = loadBundledTemplate();
        for (String path : new String[]{
                "messages.luckperms_warning.download",
                "messages_en.luckperms_warning.download"}) {
            String value = cfg.getString(path);
            assertNotNull(value, path + " missing");
            assertTrue(value.contains("click:open_url:"),
                    path + " must contain a MiniMessage open_url click event");
            assertTrue(value.contains("https://luckperms.net/download"),
                    path + " must contain the download URL");
        }
    }

    @Test
    @DisplayName("Configured texts survive MiniMessage parsing (no broken tags)")
    void configuredTextsParse() throws Exception {
        YamlConfiguration cfg = loadBundledTemplate();
        for (String path : new String[]{
                "messages.loaded_alert.message",
                "messages_en.loaded_alert.message",
                "messages.luckperms_warning.message",
                "messages.luckperms_warning.download",
                "messages_en.luckperms_warning.message",
                "messages_en.luckperms_warning.download"}) {
            String value = cfg.getString(path);
            assertNotNull(MessageUtil.parse(value), path + " must render");
        }
    }
}
