package com.ultimateimprovments.datapack;

import com.moandjiezana.toml.Toml;
import io.papermc.paper.datapack.Datapack;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * DatapackPackBootstrap — prepares the bundled UI-Datapack on disk for the
 * {@code DATAPACK_DISCOVERY} event (runs very early, before UI-Core is loaded, so it
 * cannot use the family's {@code CompositeConfig}).
 * <p>
 * The addon ships the SAME pack in two language variants:
 * {@code datapacks/UI-Datapack-en/**} and {@code datapacks/UI-Datapack-ru/**} — the
 * content is identical, only the displayed names/descriptions are translated. The
 * variant is selected by {@code datapack.lang} in
 * {@code plugins/UltimateImprovments/configs/UI-Datapack.toml} ({"en"} or {"ru"};
 * {@code auto} follows {@code messages.lang} from {@code UI-Core.toml}).
 * <p>
 * It also reads {@code datapack.enabled} / {@code datapack.position} /
 * {@code datapack.modules.*} from the same TOML, extracts the enabled subset of the
 * selected pack into {@code plugins/UltimateImprovments/datapack/UI-Datapack-<lang>},
 * and returns that folder (or {@code null} when the master toggle is off).
 * <p>
 * Custom achievements are filtered per achievement by {@link AdvancementFilter}
 * from {@code configs/UI-Advancements.toml}: they are datapack JSON, not a registry,
 * so this extraction is the only place a single achievement can be switched off.
 */
final class DatapackPackBootstrap {

    private DatapackPackBootstrap() {}

    /** Bundled resource folder prefix inside the JAR (the language suffix follows). */
    private static final String JAR_PREFIX = "datapacks/UI-Datapack-";

    /** Name prefix of the pack on disk (the language suffix follows). */
    static final String PACK_PREFIX = "UI-Datapack";

    private static final String EN = PackLanguage.EN;
    private static final String RU = PackLanguage.RU;

    /** Language used when nothing is configured and UI-Core has no preference yet. */
    private static final String DEFAULT_LANG = PackLanguage.DEFAULT_LANG;

    /**
     * The prepared pack and where it goes in the datapack stack
     * ({@code datapack.position}: "top" by default, "bottom" to let other packs win).
     */
    record Prepared(Path path, Datapack.Position position) {}

    static Prepared prepare(ComponentLogger log) throws Exception {
        File shared = new File(new File("plugins"), "UltimateImprovments");
        File configFile = new File(new File(shared, "configs"), "UI-Datapack.toml");

        boolean enabled = true;
        String configuredLang = "auto";
        String configuredPosition = "top";
        boolean legacyAdvancementsOff = false;
        Map<String, Boolean> parts = new HashMap<>();
        for (String part : DatapackParts.all()) parts.put(part, true);

        if (configFile.isFile()) {
            try {
                Toml toml = new Toml().read(configFile);
                enabled = !Boolean.FALSE.equals(toml.getBoolean(DatapackParts.MASTER_KEY, true));
                configuredLang = toml.getString(DatapackParts.LANG_KEY, "auto");
                configuredPosition = toml.getString(DatapackParts.POSITION_KEY, "top");
                // Legacy (pre-UI-Advancements) switch, honoured when the new config
                // has no explicit advancements.enabled yet.
                legacyAdvancementsOff =
                        Boolean.FALSE.equals(toml.getBoolean(DatapackParts.CONFIG_ROOT + ".advancements", true));
                Toml modules = toml.getTable(DatapackParts.CONFIG_ROOT);
                if (modules != null) {
                    for (String part : DatapackParts.all()) {
                        parts.put(part, !Boolean.FALSE.equals(modules.getBoolean(part, true)));
                    }
                }
            } catch (Exception e) {
                log.warn("Could not read " + configFile + " (" + e.getMessage()
                        + ") — using defaults (all parts enabled, language auto, position top).");
            }
        } else {
            log.info("Config not found yet (" + configFile
                    + ") — using defaults (all parts enabled, language auto, position top).");
        }

        if (!enabled) {
            log.info("datapack.enabled=false — the bundled datapack is not registered.");
            return null;
        }

        Datapack.Position position = parsePosition(configuredPosition, log);
        String lang = PackLanguage.resolve(configuredLang, shared, log);

        File source = locateSource(lang, log);
        if (source == null) {
            // The requested variant is not in this JAR (e.g. a stripped build) — the
            // other one is better than shipping no datapack at all.
            String fallback = EN.equals(lang) ? RU : EN;
            source = locateSource(fallback, log);
            if (source != null) {
                log.warn("Bundled datapack '" + PACK_PREFIX + "-" + lang + "' was not found — "
                        + "falling back to '" + PACK_PREFIX + "-" + fallback + "'.");
                lang = fallback;
            }
        }
        if (source == null) {
            throw new IOException("UI-Datapack resources not found — no JAR/dir contains '"
                    + JAR_PREFIX + "<lang>/pack.mcmeta'");
        }

        log.info("Datapack language: " + lang + " (datapack.lang: " + configuredLang + ")");

        String packName = PACK_PREFIX + "-" + lang;
        File packRoot = new File(shared, "datapack");
        deleteStalePacks(packRoot);
        File target = new File(packRoot, packName);
        if (!target.mkdirs() && !target.isDirectory()) {
            throw new IOException("Cannot create datapack folder: " + target.getAbsolutePath());
        }

        String jarPrefix = JAR_PREFIX + lang + "/";
        AdvancementFilter filter = AdvancementFilter.load(shared,
                advancementReader(source, lang), log, legacyAdvancementsOff);
        int copied = source.isDirectory()
                ? copyFromDirectory(source, target, parts, filter, log, jarPrefix)
                : copyFromJar(source, target, parts, filter, jarPrefix);
        if (copied == 0) {
            throw new IOException(packName + " extraction copied 0 files");
        }

        log.info("Extracted " + copied + " datapack file(s) from " + packName
                + " to " + target.getAbsolutePath() + " (position: "
                + position.name().toLowerCase(java.util.Locale.ROOT) + ")");
        return new Prepared(target.toPath(), position);
    }

    /** @return the configured stack position; unknown values fall back to TOP */
    private static Datapack.Position parsePosition(String configured, ComponentLogger log) {
        if (configured != null && "bottom".equalsIgnoreCase(configured.trim())) {
            return Datapack.Position.BOTTOM;
        }
        if (configured != null && !configured.isBlank()
                && !"top".equalsIgnoreCase(configured.trim())) {
            log.warn("Unknown datapack.position '" + configured
                    + "' (expected 'top' or 'bottom') — using 'top'.");
        }
        return Datapack.Position.TOP;
    }

    // =========================
    // SOURCE LOCATION
    // =========================

    private static File locateSource(String lang, ComponentLogger log) {
        List<File> candidates = new ArrayList<>();
        File self = codeSource(UIDatapackBootstrap.class);
        if (self != null) {
            candidates.add(self);
            if (self.isDirectory()) {
                // Exploded dev layout: build/classes/java/main → build/resources/main
                File dir = self;
                for (int i = 0; i < 3 && dir != null; i++) {
                    File res = new File(dir, "resources/main");
                    if (res.isDirectory()) candidates.add(res);
                    dir = dir.getParentFile();
                }
            }
        }
        for (File candidate : candidates) {
            if (hasDatapack(candidate, lang)) return candidate;
        }
        // Last resort: scan the plugins folder for the addon JAR.
        File plugins = new File("plugins");
        File[] jars = plugins.listFiles((d, n) -> n.endsWith(".jar"));
        if (jars != null) {
            for (File jar : jars) {
                if (hasDatapack(jar, lang)) return jar;
            }
        }
        log.warn("Datapack source not found via code source; will rely on discovered pack if any.");
        return null;
    }

    private static File codeSource(Class<?> clazz) {
        try {
            var location = clazz.getProtectionDomain().getCodeSource().getLocation();
            return location == null ? null : new File(location.toURI());
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean hasDatapack(File file, String lang) {
        if (file == null) return false;
        String entry = JAR_PREFIX + lang + "/pack.mcmeta";
        try {
            if (file.isFile()) {
                try (ZipFile zip = new ZipFile(file)) {
                    return zip.getEntry(entry) != null;
                }
            }
            if (file.isDirectory()) {
                return new File(file, entry).isFile();
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /**
     * Reads one achievement JSON from the pack source so {@link AdvancementFilter}
     * can follow the parent chain without a hand-kept copy of the tree.
     */
    private static java.util.function.Function<String, byte[]> advancementReader(
            File source, String lang) {
        String folder = JAR_PREFIX + lang + "/" + AdvancementFilter.PATH_PREFIX + "datapack/";
        if (source.isDirectory()) {
            return id -> {
                try {
                    return Files.readAllBytes(source.toPath().resolve(folder + id + ".json"));
                } catch (Exception e) {
                    return null;
                }
            };
        }
        return id -> {
            try (ZipFile zip = new ZipFile(source)) {
                ZipEntry entry = zip.getEntry(folder + id + ".json");
                if (entry == null) return null;
                try (InputStream in = zip.getInputStream(entry)) {
                    return in.readAllBytes();
                }
            } catch (Exception e) {
                return null;
            }
        };
    }

    // =========================
    // EXTRACTION
    // =========================

    private static int copyFromJar(File jar, File target, Map<String, Boolean> parts,
                                   AdvancementFilter filter, String jarPrefix) throws Exception {
        int copied = 0;
        try (ZipFile zip = new ZipFile(jar)) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.getName().startsWith(jarPrefix)) continue;
                String rel = entry.getName().substring(jarPrefix.length());
                if (rel.isEmpty()) continue;
                if (entry.isDirectory()) {
                    new File(target, rel).mkdirs();
                    continue;
                }
                if (!isPathEnabled(parts, rel)) continue;
                if (!filter.allows(rel)) continue;
                try (InputStream in = zip.getInputStream(entry)) {
                    byte[] data = AdvancementFilter.isAdvancementPath(rel)
                            ? filter.rewrite(rel, in.readAllBytes())
                            : null;
                    if (write(target, rel, data != null ? new ByteArrayInputStream(data) : in)) copied++;
                }
            }
        }
        return copied;
    }

    private static int copyFromDirectory(File root, File target, Map<String, Boolean> parts,
                                         AdvancementFilter filter, ComponentLogger log,
                                         String jarPrefix) throws Exception {
        Path packRoot = root.toPath().resolve(jarPrefix);
        if (!Files.isDirectory(packRoot)) return 0;
        List<Path> files;
        try (var walk = Files.walk(packRoot)) {
            files = walk.filter(Files::isRegularFile).toList();
        }
        int copied = 0;
        for (Path file : files) {
            String rel = packRoot.relativize(file).toString().replace('\\', '/');
            if (!isPathEnabled(parts, rel)) continue;
            if (!filter.allows(rel)) continue;
            byte[] data = Files.readAllBytes(file);
            if (AdvancementFilter.isAdvancementPath(rel)) data = filter.rewrite(rel, data);
            if (write(target, rel, new ByteArrayInputStream(data))) copied++;
        }
        return copied;
    }

    private static boolean write(File target, String rel, InputStream in) throws Exception {
        try (in) {
            File outFile = new File(target, rel);
            File parent = outFile.getParentFile();
            if (parent != null) parent.mkdirs();
            try (FileOutputStream out = new FileOutputStream(outFile)) {
                in.transferTo(out);
            }
            return true;
        }
    }

    /** Delegates to the shared part registry so the copy step and the gate agree. */
    private static boolean isPathEnabled(Map<String, Boolean> parts, String relPath) {
        return DatapackParts.isPathEnabled(parts, relPath);
    }

    /**
     * Removes every previously extracted pack ({@code UI-Datapack},
     * {@code UI-Datapack-en}, {@code UI-Datapack-ru}, ...) so switching the language
     * never leaves a stale copy behind.
     */
    private static void deleteStalePacks(File packRoot) {
        File[] children = packRoot.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isDirectory() && child.getName().startsWith(PACK_PREFIX)) {
                deleteRecursively(child.toPath());
            }
        }
    }

    private static void deleteRecursively(Path path) {
        if (!Files.exists(path)) return;
        try (var walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                }
            });
        } catch (Exception ignored) {
        }
    }
}
