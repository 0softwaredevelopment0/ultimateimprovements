package com.ultimateimprovements.config;

import com.ultimateimprovements.core.Main;
import com.ultimateimprovements.util.ConsoleLogger;

import java.io.File;

/**
 * Validates config values at plugin startup.
 * <p>
 * With the per-addon TOML backend ({@code configs/UI-<Addon>.toml}) duplicate
 * sections are impossible (toml4j map model) and missing keys are repaired by
 * {@link AddonConfigManager} from the bundled fragments — so only value validation
 * ({@link ConfigValueValidator}) runs here. The former legacy YAML cleanup paths
 * were removed together with the monolithic config.
 */
public class ConfigIntegrityValidator {

    private ConfigIntegrityValidator() {}

    // =========================
    // CONFIG VALIDATION (per-addon TOML backend)
    // =========================
    public static void validate(Main plugin) {
        // ── Per-addon TOML backend: duplicates are impossible, missing keys are
        // filled by AddonConfigManager.loadAddonToml(). Only value validation runs.
        ConfigValidationMessages messages = new ConfigValidationMessages(plugin.getConfig());
        File legacyToml = new File(com.ultimateimprovements.core.UltimateDirs.base(), "config.toml");
        if (legacyToml.exists()) {
            // A stray legacy monolithic config.toml from the pre-split layout —
            // keep the file (user data), it is simply no longer read.
            ConsoleLogger.info(messages.get(ConfigValidationMessages.Text.LEGACY_MONOLITHIC,
                    "dir", com.ultimateimprovements.core.UltimateDirs.DIR_NAME));
        }
        ConfigValueValidator.validateValues(plugin, plugin.getConfig());
    }
}
