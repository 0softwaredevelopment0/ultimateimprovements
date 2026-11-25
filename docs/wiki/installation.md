# Installation and updates

## Requirements

The current build targets:

- **Paper 26.3+** or a compatible Paper fork. Compatibility with a fork should be verified using the exact plugin release.
- **Java 26 or newer**.
- A server where Paper's modern plugin loader and bootstrap APIs are available.

Spigot and legacy Bukkit-only servers are not supported. Do not try to install only selected addons: the project is designed and released as one family.

## Install from a release

1. Open the [GitHub Releases](https://github.com/0softwaredevelopment0/ultimateimprovements/releases) page.
2. Download the distribution archive for the release, or download the individual \`UI-*-all.jar\` files.
3. Stop the server and make a backup of the current plugin data folder.
4. Put **all 21 JARs** (UI-Core plus the 20 addon JARs) into the server's \`plugins/\` directory.
5. Start the server and inspect the console for plugin-load errors.
6. Confirm that \`UI-Core\` and the addons loaded, then check \`/ui help\` and \`/ui addon list\`.

Do not mix JARs from different releases. The family uses explicit plugin dependencies and shared APIs; mixed versions can lead to class-loading or linkage errors.

## Datapack installation

The datapack is shipped in the dedicated **UI-Datapack** plugin. The bootstrapper registers the bundled pack before worlds load. The normal installation flow does not require manually running \`/datapack enable\` or performing an extra restart solely to discover the pack.

The datapack has English and Russian distribution variants (the content is equivalent; display text differs). Its settings are in:

\`plugins/UltimateImprovements/configs/UI-Datapack.toml\`

Relevant settings include:

- \`datapack.enabled\` — master switch.
- \`datapack.lang\` — \`"en"\`, \`"ru"\` or \`"auto"\`; auto follows the language selected in UI-Core.
- \`datapack.position\` — \`"top"\` or \`"bottom"\` when datapack resources conflict with a world's own packs.
- \`datapack.modules.*\` — feature-level datapack switches.

Pack discovery happens during startup. Restart after changing settings that affect pack registration.

## First-start data layout

The family shares one data directory:

\`\`\`text
plugins/
└── UltimateImprovements/
    ├── database.db
    ├── configs/
    │   ├── UI-Core.toml
    │   ├── UI-Shared.toml
    │   ├── UI-MBS.toml
    │   └── ... one file per config owner
    ├── logs/
    └── addon-owned files
\`\`\`

The exact set of generated TOML files is defined by the current AddonCatalog. Missing config files are generated from templates bundled in the JARs. See [Configuration](configuration.md).

## Updating

### Manual update

1. Read the release notes and check the supported Paper/Java versions.
2. Stop the server.
3. Back up \`plugins/UltimateImprovements/\` and the existing UI JARs.
4. Replace the complete family with the JARs from the same release.
5. Start the server and review the startup log.
6. Verify modules and important systems in-game.

Replacing only UI-Core or only one addon is not a supported general update strategy.

### In-game update commands

The command set includes:

- \`/ui checkver\` — compare the installed version with the available release.
- \`/ui updatejar\` — download the current plugin update and schedule the JAR swap.
- \`/ui update [tag]\` — download release JARs into the suite's update staging directory; downloading does not itself apply them.
- \`/ui swapjar\` — move staged JARs into the server's \`plugins/\` directory. Restart to load the replacement JARs.
- \`/ui uninstall\` — delete every \`UI-*.jar\` from \`plugins/\` and the staged update JARs. Disabled by default, requires \`ui.command.uninstall\`, the \`uninstall\` nickname list in \`UI-Other.toml\` and an active sudo session; restart to finish the removal.

Read the command's confirmation and status messages before proceeding. Keep a manual backup even when using the updater.

## Verify installation

Run:

\`\`\`text
/ui help
/ui addon list
/ui modules
\`\`\`

Then verify:

- all expected UI plugins are shown as loaded by the server;
- the addon list does not report missing family members;
- essential modules initialized successfully;
- no configuration parse, database or class-loading errors appear in the console;
- the bundled datapack and custom registry content are available after startup.

## Rollback

1. Stop the server.
2. Restore the previous complete set of UI JARs.
3. Restore the matching data-folder backup if the new release migrated data or changed persistent state.
4. Start the server and inspect the log.

Do not restore only an old database while leaving newer configuration or JARs without checking the release's migration notes.
