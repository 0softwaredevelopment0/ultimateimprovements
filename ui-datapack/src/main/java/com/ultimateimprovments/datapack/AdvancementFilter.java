package com.ultimateimprovments.datapack;

import com.moandjiezana.toml.Toml;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AdvancementFilter — per-achievement filtering for the bundled advancement JSON.
 * <p>
 * Advancements are data-driven in Minecraft, not a registry, so the only way to
 * switch one off is to keep its file out of the extracted pack. Doing that naively
 * breaks the tree: every child names its parent, and a missing parent makes the
 * server drop the child too. This class therefore
 * <ul>
 *   <li>reads {@code configs/UI-Advancements.toml} ({@code advancements.enabled} +
 *       {@code advancements.<id>.enabled});</li>
 *   <li>drops the file of a disabled achievement;</li>
 *   <li>re-attaches its descendants to the nearest enabled ancestor by rewriting
 *       the {@code "parent"} field, so nothing below it disappears.</li>
 * </ul>
 * Parents are read from the pack source (JAR entry or exploded folder) on demand,
 * so the filter always follows the shipped JSON instead of a hand-kept copy.
 * <p>
 * The bootstrap runs before UI-Core exists, which is why the config is read with
 * toml4j here instead of the family's {@code CompositeConfig}.
 */
final class AdvancementFilter {

    /** Config file, relative to {@code plugins/UltimateImprovments/configs/}. */
    static final String CONFIG_FILE = "UI-Advancements.toml";

    /** Config root section. */
    static final String ROOT = "advancements";

    /** Prefix of the advancement folder inside the pack ({@code data/} included). */
    static final String PATH_PREFIX = "data/ui/advancement/";

    /** Namespace + folder every parent points at: {@code ui:datapack/<id>}. */
    private static final String PARENT_NAMESPACE = "ui:datapack/";

    private static final Pattern PARENT_FIELD =
            Pattern.compile("(\"parent\"\\s*:\\s*\"ui:datapack/)([a-z0-9_./-]+)(\")");

    /** Reader for one source JSON by achievement id (without {@code .json}). */
    private final Function<String, byte[]> reader;

    /** Whether the whole achievement system is on ({@code advancements.enabled}). */
    private final boolean master;

    /** Achievement tables from {@code [advancements.<id>]}; empty = all enabled. */
    private final Map<String, Object> tables;

    /** Cache: id -> declared parent ({@code null} for a root), {@code MISSING} if unreadable. */
    private final Map<String, String> parents = new HashMap<>();
    private static final String MISSING = "\u0000missing";

    private AdvancementFilter(Function<String, byte[]> reader, boolean master,
                             Map<String, Object> tables) {
        this.reader = reader;
        this.master = master;
        this.tables = tables;
    }

    /**
     * Builds the filter for one release.
     *
     * @param shared      {@code plugins/UltimateImprovments} (config folder parent)
     * @param reader      reads {@code ui/advancement/datapack/<id>.json} from the pack source
     * @param log         bootstrap logger
     * @param legacyOff   legacy {@code datapack.modules.advancements: false} from UI-Datapack.toml
     */
    static AdvancementFilter load(File shared, Function<String, byte[]> reader,
                                  ComponentLogger log, boolean legacyOff) {
        File configFile = new File(new File(shared, "configs"), CONFIG_FILE);
        Map<String, Object> tables = Map.of();
        boolean master = !legacyOff;

        if (configFile.isFile()) {
            try {
                Toml root = new Toml().read(configFile).getTable(ROOT);
                if (root != null) {
                    Map<String, Object> map = root.toMap();
                    Boolean enabled = asBoolean(map.get("enabled"));
                    // An explicit advancements.enabled wins over the legacy pack key.
                    if (enabled != null) master = enabled;
                    tables = map;
                }
            } catch (Exception e) {
                log.warn("Could not read " + configFile + " (" + e.getMessage()
                        + ") — every custom achievement stays enabled.");
            }
        } else if (legacyOff) {
            log.info("datapack.modules.advancements=false (legacy key) — "
                    + "custom achievements are not extracted. Move it to advancements.enabled.");
        }

        AdvancementFilter filter = new AdvancementFilter(reader, master, tables);
        int disabled = filter.disabledCount();
        if (!master) {
            log.info("advancements.enabled=false — no custom achievement is extracted.");
        } else if (disabled > 0) {
            log.info("Custom achievements: " + disabled + " disabled, the rest stay in the tree.");
        }
        return filter;
    }

    /** Whether a pack path belongs to the achievement folder. */
    static boolean isAdvancementPath(String relPath) {
        return relPath != null && relPath.startsWith(PATH_PREFIX) && relPath.endsWith(".json");
    }

    /** @return true when the file must be copied into the extracted pack */
    boolean allows(String relPath) {
        if (!isAdvancementPath(relPath)) return true;
        if (!master) return false;
        return isEnabled(idOf(relPath));
    }

    /**
     * Rewrites the {@code "parent"} field when the nearest enabled ancestor is not
     * the declared parent (because a node in between was disabled). Returns the
     * original data unchanged otherwise.
     */
    byte[] rewrite(String relPath, byte[] data) {
        if (!isAdvancementPath(relPath) || data == null) return data;
        String id = idOf(relPath);
        String parent = parentOf(id);
        String effective = nearestEnabledAncestor(parent);
        if (parent == null ? effective == null : parent.equals(effective)) return data;

        String json = new String(data, StandardCharsets.UTF_8);
        Matcher matcher = PARENT_FIELD.matcher(json);
        if (!matcher.find()) return data;
        String replacement = effective == null
                ? ""
                : matcher.group(1) + effective + matcher.group(3);
        // The whole `"parent": "...",` token goes away when the node becomes a root.
        String rewritten = effective == null
                ? json.substring(0, matcher.start()) + json.substring(matcher.end()).replaceFirst("^\\s*,", "")
                : json.substring(0, matcher.start()) + replacement + json.substring(matcher.end());
        return rewritten.getBytes(StandardCharsets.UTF_8);
    }

    // =========================
    // INTERNALS
    // =========================

    private boolean isEnabled(String id) {
        if (!master) return false;
        Object table = tables.get(id);
        if (!(table instanceof Map<?, ?> map)) return true; // no table = default on
        Boolean enabled = asBoolean(map.get("enabled"));
        return enabled == null || enabled;
    }

    private int disabledCount() {
        if (!master) return 0;
        int count = 0;
        for (Object table : tables.values()) {
            if (table instanceof Map<?, ?> map && Boolean.FALSE.equals(asBoolean(map.get("enabled")))) {
                count++;
            }
        }
        return count;
    }

    /** Walks up from {@code parent} until an enabled node (or the root) is found. */
    private String nearestEnabledAncestor(String parent) {
        Set<String> seen = new LinkedHashSet<>();
        String current = parent;
        while (current != null && !isEnabled(current)) {
            if (!seen.add(current)) return null; // cycle guard
            current = parentOf(current);
        }
        return current;
    }

    /** @return the declared parent id, or {@code null} for a root / unreadable file */
    private String parentOf(String id) {
        if (id == null) return null;
        if (parents.containsKey(id)) {
            String cached = parents.get(id);
            return MISSING.equals(cached) ? null : cached;
        }
        byte[] data;
        try {
            data = reader.apply(id);
        } catch (Exception e) {
            data = null;
        }
        if (data == null) {
            parents.put(id, MISSING);
            return null;
        }
        Matcher matcher = PARENT_FIELD.matcher(new String(data, StandardCharsets.UTF_8));
        String parent = matcher.find() ? normalizeParent(matcher.group(2)) : null;
        parents.put(id, parent == null ? MISSING : parent);
        return parent;
    }

    /** Turns {@code ui:datapack/foo} content into a bare id; foreign parents stay untouched. */
    private static String normalizeParent(String raw) {
        if (raw == null) return null;
        return raw.startsWith("datapack/") ? raw.substring("datapack/".length()) : raw;
    }

    /** {@code data/ui/advancement/datapack/kaboom.json} -> {@code kaboom}. */
    private static String idOf(String relPath) {
        String name = relPath.substring(PATH_PREFIX.length());
        int slash = name.lastIndexOf('/');
        if (slash >= 0) name = name.substring(slash + 1);
        return name.endsWith(".json") ? name.substring(0, name.length() - ".json".length()) : name;
    }

    private static Boolean asBoolean(Object value) {
        if (value instanceof Boolean b) return b;
        if (value instanceof String s) {
            if ("true".equalsIgnoreCase(s.trim())) return Boolean.TRUE;
            if ("false".equalsIgnoreCase(s.trim())) return Boolean.FALSE;
        }
        return null;
    }
}
