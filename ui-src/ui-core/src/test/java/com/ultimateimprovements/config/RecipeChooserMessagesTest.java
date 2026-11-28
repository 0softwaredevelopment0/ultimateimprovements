package com.ultimateimprovements.config;

import com.moandjiezana.toml.Toml;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the ambiguous-recipe chooser messages of {@code /ui showcraft} and
 * {@code /ui craftrecipe}: both commands stop and list the matching recipes, so
 * every chooser key must exist in BOTH language sections of {@code UI-Other.toml}.
 */
class RecipeChooserMessagesTest {

    private static final String[] SECTIONS = {"showcraft", "craftrecipe"};
    private static final String[] KEYS = {"choose_header", "choose_entry", "choose_hover", "choose_hint"};

    @SuppressWarnings("unchecked")
    private static Map<String, Object> root() throws Exception {
        try (InputStream in = RecipeChooserMessagesTest.class.getClassLoader()
                .getResourceAsStream("config/UI-Other.toml")) {
            assertNotNull(in, "bundled config/UI-Other.toml missing from resources");
            return new Toml().read(new InputStreamReader(in, StandardCharsets.UTF_8)).toMap();
        }
    }

    private static Map<String, Object> section(Map<String, Object> root, String messagesKey, String section) {
        Object messages = root.get(messagesKey);
        assertNotNull(messages, messagesKey + " section missing");
        Object sub = ((Map<String, Object>) messages).get(section);
        assertNotNull(sub, messagesKey + "." + section + " section missing");
        return (Map<String, Object>) sub;
    }

    @Test
    @DisplayName("Bundled UI-Other.toml has the chooser keys for showcraft and craftrecipe in both languages")
    void bundledTemplateHasChooserMessagesInBothLanguages() throws Exception {
        Map<String, Object> root = root();
        for (String messagesKey : new String[]{"messages", "messages_en"}) {
            for (String section : SECTIONS) {
                Map<String, Object> messages = section(root, messagesKey, section);
                for (String key : KEYS) {
                    Object value = messages.get(key);
                    assertNotNull(value, messagesKey + "." + section + "." + key + " missing");
                    assertTrue(!String.valueOf(value).isBlank(),
                            messagesKey + "." + section + "." + key + " must not be blank");
                }
                // The chooser lists the matching recipes and points at the exact key syntax.
                assertTrue(String.valueOf(messages.get("choose_header")).contains("%count%"),
                        messagesKey + "." + section + ".choose_header needs the %count% placeholder");
                assertTrue(String.valueOf(messages.get("choose_entry")).contains("%recipe%"),
                        messagesKey + "." + section + ".choose_entry needs the %recipe% placeholder");
                assertTrue(String.valueOf(messages.get("choose_hint")).contains("%command%"),
                        messagesKey + "." + section + ".choose_hint needs the %command% placeholder");
            }
        }
    }
}
