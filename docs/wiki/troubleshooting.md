# Troubleshooting

Start with the exact server log and the version of every UI JAR. The family is modular but its JARs are designed to run together; a mixed installation is a common source of confusing symptoms.

## UI plugins fail to load

Check:

1. Server is Paper 26.3+ or a compatible fork.
2. Java runtime is 26+.
3. All 21 JARs are from the same release.
4. The JARs were not downloaded from an untrusted or incomplete archive.
5. Each plugin's paper-plugin.yml dependency requirements are satisfied.

Spigot/Bukkit-only environments are not supported. Updating only UI-Core while leaving old addons installed can cause class-loading or API linkage errors.

## Addons are missing

Run **/ui addon list**.

Check the plugin manager/server startup log for missing UI JARs. Confirm the file is present in plugins/, readable by the server process and built from the same release as UI-Core.

Remember: UI-Advancements.toml and UI-Enchantments.toml are config namespaces, not extra JARs.

## A module is disabled or failed

Run **/ui modules**.

Find the exact module name and inspect the startup log for its initialization failure. Optional module failure should not prevent unrelated modules from running, but a failed essential module indicates a potentially incomplete server feature set.

Check whether the owning addon is installed and whether a related master switch or datapack part is disabled.

## Config file is missing, malformed or resets

All runtime configs live in:

**plugins/UltimateImprovements/configs/**

Missing files are generated from bundled templates. Parse failures cause a numbered backup to be made and the file to be regenerated.

Actions:

1. Stop editing the file while the server is writing/reloading it.
2. Preserve the generated *-broken-N.toml backup.
3. Compare the broken file with the bundled template for the same release.
4. Copy custom values carefully into valid TOML syntax.
5. Restart or run the supported reload command as appropriate.

Do not remove the backup until all custom settings are recovered.

## /ui reload did not apply a change

Not every setting is reloadable. A normal reload handles per-addon TOML settings/messages/modules only where the module supports reload.

A server restart is required for startup-time Paper bootstrap behavior, datapack discovery/placement and custom registry registrations such as the enchantment registry. Follow the comment in the relevant TOML template.

## A permission is denied

- Verify the exact node, including sub-action nodes.
- Check the effective permission on the account and inherited groups.
- Test without relying on OP status.
- Check special exact-grant rules such as ui.sculk.hazards.bypass.
- For config reset/regeneration, verify both the permission and config.commands_enabled.

See [Permissions](permissions.md).

## Placeholders remain unchanged

Check the spelling and context:

- internal plugin text uses %player_name%;
- PlaceholderAPI consumers use %ui_player_name%;
- external expansion requires PlaceholderAPI to be installed and enabled;
- player-specific placeholders need a player context;
- LuckPerms placeholders require the LuckPerms hook to initialize;
- dynamic time windows may not have a full sample history immediately after startup.

See [Built-in placeholders](placeholders.md).

## Datapack or custom enchantment content is missing

- Confirm UI-Datapack is installed and loaded.
- Confirm the datapack master switch and relevant module switches in UI-Datapack.toml.
- Check the server log for bootstrap/registry registration errors.
- Restart the server after changing startup-time pack or enchantment settings.
- Confirm the correct language variant and pack position were selected.

Do not attempt to solve a bootstrap/registry issue solely with /ui reload.

## Database errors or lost state

The shared DB is **plugins/UltimateImprovements/database.db**.

Check filesystem permissions, free disk space and server shutdown state. In WAL mode, the active database may have -wal and -shm companion files. Do not delete them while the server is running.

When restoring a backup, stop the server and restore a consistent database/data-folder snapshot that matches the intended plugin release. See [Database, persistence and backups](database-and-backups.md).

Some machine buffers are RAM-only and are expected to reset on restart; for example, the compact nuclear reactor's local output buffer.

## Energy structure does not assemble or transfer power

- Verify the exact multiblock template and required item-frame position.
- Check the relevant block materials and orientation.
- Make sure the structure is complete and attached to the energy network.
- Check cable connection and consumer demand.
- Use the Multimeter for energy data and Structure Indicator for template/integrity diagnostics.
- Temporarily enable debug.startup_scans for additional structure/energy discovery logs, then disable it.
- Review UI-MBS.toml and UI-Energy.toml.

An isolated copper grate is not an assembled battery. Lamp junctions also have redstone requirements.

## Authentication or GitHub 2FA callback fails

- Check UI-Auth.toml for port and public URL values.
- Verify the configured callback is reachable from GitHub.
- Use HTTPS via a reverse proxy, or restrict plain-HTTP callback exposure to a trusted VPN/network.
- Confirm Velocity forwarding if the backend relies on real client IP addresses.
- Do not work around a failed 2FA check by silently disabling the security feature; the documented behavior is fail-closed.

## Offline inventory edits are not visible

Offline edits modify the player's data file. Verify that the correct world/player-data folder was selected and that the player was actually offline. Check for a nearby <uuid>-backup.dat backup and ensure no external editor is concurrently modifying the same file.

## Useful diagnostic sequence

When reporting a bug, include:

1. server implementation and exact version;
2. Java version;
3. version of every UI JAR;
4. complete relevant startup/error log (redact IPs, OAuth tokens, passwords and other secrets);
5. exact command or action that reproduces the issue;
6. relevant TOML section with secrets removed;
7. whether the issue persists on a clean test server with the same supported versions.

This information helps distinguish a plugin defect from an incompatible server build, a missing addon, a permission problem or an invalid configuration.
