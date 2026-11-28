package com.ultimateimprovements.command.subcommands;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Guards the selector parsing shared by {@code /ui showcraft} and
 * {@code /ui craftrecipe}. The selector is what decides whether the command
 * addresses a concrete recipe key or an ambiguous result item that must be
 * resolved through the chooser.
 */
class RecipeLookupTest {

    @Test
    @DisplayName("args are joined into one selector with underscores")
    void joinsArguments() {
        assertEquals("diamond_sword", RecipeLookup.selector(new String[]{"showcraft", "diamond", "sword"}, 1));
        assertEquals("diamond_sword", RecipeLookup.selector(new String[]{"showcraft", "DIAMOND_SWORD"}, 1));
        assertEquals("cherry_planks", RecipeLookup.selector(new String[]{"craftrecipe", " cherry ", "planks "}, 1));
    }

    @Test
    @DisplayName("namespaces, dots and hyphens survive so exact keys still resolve")
    void keepsKeyCharacters() {
        assertEquals("ui:lead_ingot", RecipeLookup.normalize("UI:Lead_Ingot"));
        assertEquals("minecraft:diamond_sword", RecipeLookup.normalize("minecraft:diamond_sword"));
        assertEquals("some-plugin:my.recipe", RecipeLookup.normalize("some-plugin:my.recipe"));
    }

    @Test
    @DisplayName("display-name decorations do not leave a trailing underscore behind")
    void trimsDecorations() {
        assertEquals("lead_ingot", RecipeLookup.normalize("Lead Ingot *"));
        assertEquals("lead_ingot", RecipeLookup.normalize("** Lead Ingot **"));
    }

    @Test
    @DisplayName("spaces and unsupported characters never produce an unparsable selector")
    void stripsUnsupportedCharacters() {
        assertEquals("lead_ingot", RecipeLookup.normalize("Lead Ingot!"));
        assertEquals("", RecipeLookup.normalize("   "));
        assertEquals("", RecipeLookup.normalize(null));
    }
}
