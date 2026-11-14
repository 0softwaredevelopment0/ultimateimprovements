package com.ultimateimprovments.datapack;

import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the one property the datapack cache depends on: identical inputs must
 * produce byte-identical archives ({@code hash.txt} is only useful if the bytes do
 * not change between starts). Also checks that a disabled part really disappears
 * from the archive and that no untracked file sneaks in.
 */
class DatapackPackBootstrapTest {

    private Path temp;
    private File source;
    private AdvancementFilter filter;

    @BeforeEach
    void setUp() throws IOException {
        temp = Files.createTempDirectory("ui-datapack-test");
        Path pack = temp.resolve("datapacks/UI-Datapack-en");
        write(pack.resolve("pack.mcmeta"), "{\"pack\":{\"pack_format\":61}}");
        write(pack.resolve("data/ui/recipe/foo.json"), "{\"type\":\"minecraft:crafting_shaped\"}");
        write(pack.resolve("data/minecraft/recipe/bar.json"), "{\"type\":\"minecraft:smelting\"}");
        write(pack.resolve("data/ui/advancement/datapack/kaboom.json"),
                "{\"parent\":\"ui:datapack/start\",\"display\":{\"title\":\"Kaboom\"}}");
        write(pack.resolve("data/ui/advancement/datapack/start.json"), "{\"display\":{\"title\":\"Root\"}}");
        write(pack.resolve("data/ui/other/untracked.json"), "{}");
        source = temp.toFile();

        // No configs/ in the temp dir: the filter defaults to "everything enabled".
        filter = AdvancementFilter.load(temp.toFile(), id -> null, logger(), false);
    }

    @AfterEach
    void tearDown() throws IOException {
        try (var walk = Files.walk(temp)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        }
    }

    @Test
    void buildsByteIdenticalArchivesForIdenticalInputs() throws Exception {
        byte[] first = DatapackPackBootstrap.buildZip(source, "datapacks/UI-Datapack-en/", allParts(), filter);
        byte[] second = DatapackPackBootstrap.buildZip(source, "datapacks/UI-Datapack-en/", allParts(), filter);

        assertNotNull(first);
        assertArrayEquals(first, second, "the archive must be deterministic, otherwise hash.txt never matches");
    }

    @Test
    void writesEntriesInSortedOrder() throws Exception {
        byte[] archive = DatapackPackBootstrap.buildZip(source, "datapacks/UI-Datapack-en/", allParts(), filter);
        List<String> names = entryNames(archive);
        List<String> sorted = new ArrayList<>(names);
        sorted.sort(Comparator.naturalOrder());

        assertEquals(sorted, names, "entry order is part of the deterministic output");
    }

    @Test
    void leavesOutFilesOfDisabledPartsButKeepsUntrackedOnes() throws Exception {
        Map<String, Boolean> parts = allParts();
        parts.put(DatapackParts.CUSTOM_RECIPES, false);

        List<String> names = entryNames(
                DatapackPackBootstrap.buildZip(source, "datapacks/UI-Datapack-en/", parts, filter));

        assertFalse(names.contains("data/ui/recipe/foo.json"), "custom_recipes is off");
        assertTrue(names.contains("data/minecraft/recipe/bar.json"), "other parts stay enabled");
        assertTrue(names.contains("data/ui/other/untracked.json"), "a file in no part is always kept");
        assertTrue(names.contains("pack.mcmeta"), "the pack metadata is always kept");
    }

    @Test
    void returnsNullForAnEmptySource() throws Exception {
        assertNull(DatapackPackBootstrap.buildZip(
                temp.toFile(), "datapacks/UI-Datapack-missing/", allParts(), filter));
    }

    // =========================
    // HELPERS
    // =========================

    private static Map<String, Boolean> allParts() {
        Map<String, Boolean> parts = new HashMap<>();
        for (String part : DatapackParts.all()) parts.put(part, true);
        return parts;
    }

    private static List<String> entryNames(byte[] archive) throws IOException {
        List<String> names = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                names.add(entry.getName());
            }
        }
        return names;
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    /** The bootstrap logger is only used for warnings; a no-op proxy is enough. */
    private static ComponentLogger logger() {
        return (ComponentLogger) Proxy.newProxyInstance(
                DatapackPackBootstrapTest.class.getClassLoader(),
                new Class<?>[]{ComponentLogger.class},
                (proxy, method, args) -> {
                    if ("getName".equals(method.getName())) return "test";
                    if (method.getReturnType() == boolean.class) return false;
                    return null;
                });
    }
}
