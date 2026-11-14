package com.ultimateimprovments.datapack;

import com.moandjiezana.toml.Toml;
import io.papermc.paper.datapack.Datapack;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * DatapackPackBootstrap — prepares the bundled UI-Datapack for the
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
 * {@code datapack.modules.*} from the same TOML, builds the enabled subset of the
 * selected pack <b>in memory as a zip</b> and materialises it as
 * {@code plugins/UltimateImprovments/datapack/UI-Datapack-<lang>.zip} (or returns
 * {@code null} when the master toggle is off). Paper discovers a {@code .zip} file
 * just like a folder ({@code FilePackResources}), so no file tree is written.
 * <p>
 * The archive is built deterministically (sorted entries, fixed entry timestamp,
 * fixed deflate level), which lets the next server start hash it and skip the write
 * entirely when the bytes did not change. {@code datapack/hash.txt} stores
 * {@code <sha256>  <zip name>}; a missing file, a different hash, a damaged archive
 * or a stale entry all force a rebuild. The zip is only written before the pack is
 * discovered — never while the server holds it open (Windows file locks).
 * <p>
 * Custom achievements are filtered per achievement by {@link AdvancementFilter}
 * from {@code configs/UI-Advancements.toml}: they are datapack JSON, not a registry,
 * so this build is the only place a single achievement can be switched off.
 */
final class DatapackPackBootstrap {

    private DatapackPackBootstrap() {}

    /** Bundled resource folder prefix inside the JAR (the language suffix follows). */
    private static final String JAR_PREFIX = "datapacks/UI-Datapack-";

    /** Name prefix of the pack on disk (the language suffix follows). */
    static final String PACK_PREFIX = "UI-Datapack";

    /** Every materialised pack is a single zip file. */
    private static final String ZIP_SUFFIX = ".zip";

    /** File next to the zip holding {@code "<sha256>  <zip name>"}. */
    private static final String HASH_FILE = "hash.txt";

    /**
     * Fixed zip entry timestamp (2000-01-01 00:00, local fields). The content, not
     * the build time, must decide the bytes: otherwise the hash changes every start
     * and the cache never hits.
     */
    private static final LocalDateTime ENTRY_TIME = LocalDateTime.of(2000, 1, 1, 0, 0, 0);

    private static final String EN = PackLanguage.EN;
    private static final String RU = PackLanguage.RU;

    /**
     * The prepared pack, where it goes in the datapack stack
     * ({@code datapack.position}: "top" by default, "bottom" to let other packs win),
     * its pack id, and whether the archive was reused instead of rebuilt.
     */
    record Prepared(Path path, Datapack.Position position, String packId, boolean reused) {}

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
        String zipName = packName + ZIP_SUFFIX;
        File packRootFile = new File(shared, "datapack");
        if (!packRootFile.isDirectory() && !packRootFile.mkdirs() && !packRootFile.isDirectory()) {
            throw new IOException("Cannot create datapack folder: " + packRootFile.getAbsolutePath());
        }
        Path packRoot = packRootFile.toPath();
        Path target = packRoot.resolve(zipName);
        Path hashFile = packRoot.resolve(HASH_FILE);

        String jarPrefix = JAR_PREFIX + lang + "/";
        AdvancementFilter filter = AdvancementFilter.load(shared,
                advancementReader(source, lang), log, legacyAdvancementsOff);

        byte[] archive = buildZip(source, jarPrefix, parts, filter);
        if (archive == null) {
            throw new IOException(packName + " build produced no files");
        }
        String hash = sha256(archive);

        boolean reused = isCached(target, hashFile, zipName, hash);
        if (reused) {
            log.info("Bundled datapack unchanged (sha256 " + shortHash(hash)
                    + ") — reusing " + target);
        } else {
            writeAtomically(target, archive);
            writeHash(hashFile, hash, zipName);
            log.info("Built bundled datapack " + zipName + " (" + archive.length / 1024
                    + " KB, sha256 " + shortHash(hash) + ") at " + target);
        }

        // Only after the current archive is in place: drop older variants and the
        // folders written by previous releases (they would be duplicate packs).
        cleanupStale(packRoot, zipName, log);

        log.info("Datapack position: " + position.name().toLowerCase(Locale.ROOT)
                + " (datapack.position: " + configuredPosition + ")");
        return new Prepared(target, position, packName, reused);
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
    // ARCHIVE BUILD
    // =========================

    /**
     * Reads the enabled subset of one language variant from the pack source.
     * Single pass: the JAR is opened once, not once per file.
     *
     * @return relative pack path to file content (unsorted; callers sort)
     */
    private static Map<String, byte[]> collect(File source, String jarPrefix,
                                               Map<String, Boolean> parts,
                                               AdvancementFilter filter) throws Exception {
        Map<String, byte[]> entries = new HashMap<>();
        if (source.isDirectory()) {
            Path root = source.toPath().resolve(jarPrefix);
            if (!Files.isDirectory(root)) return entries;
            try (var walk = Files.walk(root)) {
                for (Path file : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
                    String rel = root.relativize(file).toString().replace('\\', '/');
                    if (!keep(rel, parts, filter)) continue;
                    entries.put(rel, Files.readAllBytes(file));
                }
            }
            return entries;
        }
        try (ZipFile zip = new ZipFile(source)) {
            var iterator = zip.entries();
            while (iterator.hasMoreElements()) {
                ZipEntry entry = iterator.nextElement();
                if (entry.isDirectory() || !entry.getName().startsWith(jarPrefix)) continue;
                String rel = entry.getName().substring(jarPrefix.length());
                if (rel.isEmpty() || !keep(rel, parts, filter)) continue;
                try (InputStream in = zip.getInputStream(entry)) {
                    entries.put(rel, in.readAllBytes());
                }
            }
        }
        return entries;
    }

    private static boolean keep(String relPath, Map<String, Boolean> parts, AdvancementFilter filter) {
        // Delegates to the shared part registry so the build and the gate agree.
        return DatapackParts.isPathEnabled(parts, relPath) && filter.allows(relPath);
    }

    /**
     * Builds the zip in memory. Deterministic on purpose: entries are sorted by
     * name, every entry gets {@link #ENTRY_TIME} and the deflate level is fixed, so
     * identical inputs always produce identical bytes (and the same SHA-256).
     *
     * @return the archive, or {@code null} when the pack source is empty
     */
    static byte[] buildZip(File source, String jarPrefix,
                           Map<String, Boolean> parts,
                           AdvancementFilter filter) throws Exception {
        Map<String, byte[]> files = collect(source, jarPrefix, parts, filter);
        if (files.isEmpty()) return null;

        List<String> names = new ArrayList<>(files.keySet());
        names.sort(Comparator.naturalOrder());

        ByteArrayOutputStream bytes = new ByteArrayOutputStream(1 << 20);
        try (ZipOutputStream out = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            out.setLevel(Deflater.DEFAULT_COMPRESSION);
            for (String rel : names) {
                byte[] data = files.get(rel);
                if (AdvancementFilter.isAdvancementPath(rel)) {
                    data = filter.rewrite(rel, data);
                }
                ZipEntry entry = new ZipEntry(rel);
                // Local fields (no time zone dependency, no extra timestamp field).
                entry.setTimeLocal(ENTRY_TIME);
                out.putNextEntry(entry);
                out.write(data);
                out.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    // =========================
    // CACHE (zip + hash.txt)
    // =========================

    /**
     * Whether the existing archive can be kept: it must exist, the hash file must
     * describe that same file with that same hash, and the file on disk must still
     * hash to it (catches a truncated write or a manual edit).
     */
    private static boolean isCached(Path target, Path hashFile, String zipName, String expectedHash) {
        if (!Files.isRegularFile(target)) return false;
        HashRecord stored = readHash(hashFile);
        if (stored == null || !stored.file().equals(zipName) || !stored.hash().equals(expectedHash)) {
            return false;
        }
        try {
            return expectedHash.equals(sha256(target));
        } catch (Exception e) {
            return false;
        }
    }

    private static String sha256(byte[] data) throws Exception {
        return hex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (DigestInputStream in = new DigestInputStream(Files.newInputStream(file), digest)) {
            byte[] buffer = new byte[8192];
            while (in.read(buffer) != -1) {
                // The digest is updated by the stream itself; only the read matters.
            }
        }
        return hex(digest.digest());
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            out.append(Character.forDigit((b >> 4) & 0xF, 16));
            out.append(Character.forDigit(b & 0xF, 16));
        }
        return out.toString();
    }

    private static String shortHash(String hash) {
        return hash.length() <= 12 ? hash : hash.substring(0, 12);
    }

    /** One parsed line of {@code hash.txt}. */
    private record HashRecord(String hash, String file) {}

    private static HashRecord readHash(Path hashFile) {
        if (!Files.isRegularFile(hashFile)) return null;
        try {
            for (String line : Files.readAllLines(hashFile, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                String[] tokens = trimmed.split("\\s+");
                if (tokens.length < 2) return null;
                return new HashRecord(tokens[0].toLowerCase(Locale.ROOT), tokens[1]);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static void writeHash(Path hashFile, String hash, String zipName) throws IOException {
        byte[] data = (hash + "  " + zipName + "\n").getBytes(StandardCharsets.UTF_8);
        writeAtomically(hashFile, data);
    }

    private static void writeAtomically(Path target, byte[] data) throws IOException {
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.write(temp, data);
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /**
     * Removes everything in {@code datapack/} that is not the current archive or its
     * hash file: the other language variant, {@code .tmp} leftovers and the folder
     * every release before 1.8.5-beta.49 extracted. Unknown files are left alone.
     */
    private static void cleanupStale(Path packRoot, String keepZip, ComponentLogger log) {
        File[] children = packRoot.toFile().listFiles();
        if (children == null) return;
        for (File child : children) {
            String name = child.getName();
            if (HASH_FILE.equals(name) || name.equals(keepZip)) continue;
            boolean temp = name.endsWith(".tmp");
            if (!temp && !name.startsWith(PACK_PREFIX)) continue;
            if (child.isDirectory()) {
                log.info("Removing legacy extracted datapack folder '" + name
                        + "' — the bundled pack is a single zip now.");
            }
            deleteRecursively(child.toPath());
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
