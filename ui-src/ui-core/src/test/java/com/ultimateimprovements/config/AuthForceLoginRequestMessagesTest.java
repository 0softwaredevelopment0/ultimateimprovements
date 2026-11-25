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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the configurable "request a force login" texts and settings
 * ({@code UI-Auth.toml}) in both language sections.
 * <p>
 * Regression: the notification must stay click-to-run for chat while the
 * console copy has no events at all, so the template has to keep the
 * {@code %player%} / {@code %command%} placeholders the code replaces.
 */
class AuthForceLoginRequestMessagesTest {

    private static YamlConfiguration loadBundledTemplate() throws Exception {
        try (InputStream in = AuthForceLoginRequestMessagesTest.class.getClassLoader()
                .getResourceAsStream("config/UI-Auth.toml")) {
            assertNotNull(in, "bundled config/UI-Auth.toml missing from resources");
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
    @DisplayName("Force-login request texts exist in BOTH language sections")
    void textsExistInBothLanguages() throws Exception {
        YamlConfiguration cfg = loadBundledTemplate();
        for (String path : new String[]{
                "messages.auth.forcelogin_request.sent",
                "messages.auth.forcelogin_request.cooldown",
                "messages.auth.forcelogin_request.notify",
                "messages.auth.forcelogin_request.hover",
                "messages.auth.forcelogin_request.toggle_on",
                "messages.auth.forcelogin_request.toggle_off",
                "messages.auth.forcelogin_request.usage",
                "messages.auth.dialog.forcelogin_request_button",
                "messages_en.auth.forcelogin_request.sent",
                "messages_en.auth.forcelogin_request.cooldown",
                "messages_en.auth.forcelogin_request.notify",
                "messages_en.auth.forcelogin_request.hover",
                "messages_en.auth.forcelogin_request.toggle_on",
                "messages_en.auth.forcelogin_request.toggle_off",
                "messages_en.auth.forcelogin_request.usage",
                "messages_en.auth.dialog.forcelogin_request_button"}) {
            String value = cfg.getString(path, null);
            assertNotNull(value, path + " missing");
            assertFalse(value.isBlank(), path + " must not be blank");
        }
    }

    @Test
    @DisplayName("Force-login request settings exist in the bundled template")
    void settingsExist() throws Exception {
        YamlConfiguration cfg = loadBundledTemplate();
        assertTrue(cfg.isSet("auth.forcelogin_request.enabled"),
                "auth.forcelogin_request.enabled missing");
        assertTrue(cfg.isSet("auth.forcelogin_request.cooldown_seconds"),
                "auth.forcelogin_request.cooldown_seconds missing");
    }

    @Test
    @DisplayName("Notification keeps the placeholders the code fills in")
    void notificationKeepsPlaceholders() throws Exception {
        YamlConfiguration cfg = loadBundledTemplate();
        for (String path : new String[]{
                "messages.auth.forcelogin_request.notify",
                "messages_en.auth.forcelogin_request.notify"}) {
            String value = cfg.getString(path);
            assertNotNull(value, path + " missing");
            assertTrue(value.contains("%player%"), path + " must keep %player%");
            assertTrue(value.contains("%command%"), path + " must keep %command%");
        }
        for (String path : new String[]{
                "messages.auth.forcelogin_request.cooldown",
                "messages_en.auth.forcelogin_request.cooldown"}) {
            String value = cfg.getString(path);
            assertNotNull(value, path + " missing");
            assertTrue(value.contains("%seconds%"), path + " must keep %seconds%");
        }
    }

    @Test
    @DisplayName("Configured texts survive MiniMessage parsing")
    void configuredTextsParse() throws Exception {
        YamlConfiguration cfg = loadBundledTemplate();
        for (String path : new String[]{
                "messages.auth.forcelogin_request.notify",
                "messages.auth.forcelogin_request.hover",
                "messages_en.auth.forcelogin_request.notify",
                "messages_en.auth.forcelogin_request.hover",
                "messages.auth.dialog.forcelogin_request_button",
                "messages_en.auth.dialog.forcelogin_request_button"}) {
            String value = cfg.getString(path);
            assertNotNull(value, path + " missing");
            assertNotNull(MessageUtil.parse(value), path + " must render");
        }
    }
}
