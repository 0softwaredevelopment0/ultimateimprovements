# Development and building

## Repository layout

The project is a Gradle multi-project build. The root `settings.gradle` discovers
21 plugin projects under `ui-src/`: `ui-core` and 20 addons. The Gradle project
names stay flat (`:ui-core`, `:ui-chat`, ...), only their directory differs.

The main source areas are:

| Path | Role |
|---|---|
| ui-src/ | All plugin modules (Gradle subprojects) |
| ui-src/ui-core/ | Common runtime, config, database, permissions, command APIs, shared services |
| ui-src/ui-shared/ | Shared feature implementations |
| ui-src/ui-datapack/ | Datapack packaging and bootstrap-time registration |
| ui-src/ui-mbs/ | Multiblock structure data and mechanics |
| ui-src/ui-energy/ | Energy graph, storage and machinery |
| ui-src/ui-auth/ | Authentication |
| ui-src/ui-essentials/ | General commands and player utilities |
| ui-src/ui-chat/, ui-src/ui-display/ | Communication and display features |
| ui-src/ui-guard/, ui-src/ui-anticheat/, ui-src/ui-protection/ | Security/protection systems |
| remaining ui-src/ui-*/ projects | Domain-specific addons |
| docs/ | Design notes and documentation |

## Toolchain

The current root Gradle build sets:

- Java toolchain: 26;
- group: com.ultimateimprovements;
- version in the current build file: 1.10.0-alpha.2;
- Shadow plugin: com.gradleup.shadow.

Use a JDK 26 installation and the included Gradle Wrapper.

## Build commands

From the repository root:

```bash
# Fast distribution build. Compiles and collects the shaded plugin JARs,
# skipping tests/check tasks.
./gradlew distributeJars

# Full build, including tests/check lifecycle.
./gradlew build

# Run tests for the core project.
./gradlew :ui-core:test
```

The distribution JARs are copied into:

**build/libs/UI-<Addon>-<version>-all.jar**

The release family contains 21 JARs. Validate that the expected set exists before packaging a release.

## Command development

The /ui root is registered via Paper's modern lifecycle/Brigadier command API in UI-Core.

The subcommand contract is in the core command package. A new command generally implements the shared subcommand interface and is registered with the command registry by the owning addon. Tab completion is wired into the command tree.

Before adding or changing a command:

1. identify the owning addon;
2. define its permission node in the core permission registry;
3. implement argument validation and sender checks;
4. provide tab suggestions;
5. add command help metadata;
6. add localized messages to the owner's TOML template;
7. test player and console execution separately;
8. verify reload/startup behavior.

Use the existing /ui help source metadata as a standard for command descriptions and argument help.

## Module development

Feature modules are managed by the core ModuleManager. A module should:

- initialize only the resources it owns;
- release listeners/tasks/resources during disable;
- handle initialization failures without corrupting unrelated modules;
- mark itself essential only when the server cannot function safely without it;
- support config reload when its settings are meant to be reloadable.

Metadata can be provided through the module metadata annotation. The module scanner and registration code should be reviewed before introducing new discovery behavior.

## Configuration development

Config keys are routed by AddonCatalog to the owning TOML file. When adding a key:

1. choose the correct config owner;
2. add a documented default to the bundled TOML template;
3. add validation in the config rules/value validator where necessary;
4. add migration logic if the key replaces/moves an existing setting;
5. add bilingual messages if the setting exposes player-facing output;
6. document reload versus restart requirements;
7. add or update config repair tests.

Do not add a key only to a live sample file; the bundled template is the source for fresh installs and missing-key repair.

## Placeholder development

Built-in placeholders are registered in the core placeholder resolver. Keep the name inventory and PlaceholderAPI expansion in sync. Player-dependent values should safely handle a null player for console/server text contexts. Dynamic metrics and ping formats should be documented with examples and tested with and without PlaceholderAPI.

See [Built-in placeholders](placeholders.md).

## Testing and release checks

Before merging a feature or publishing a release:

- run the full Gradle build and tests;
- build the fast JAR distribution and confirm all 21 artifacts;
- inspect every generated paper-plugin.yml;
- test on the minimum supported Paper API and the intended Java runtime;
- test fresh install as well as upgrade from a previous supported version;
- test config repair, migrations and rollback assumptions;
- test commands as console, OP, non-OP and restricted staff where relevant;
- test modules enabled/disabled and addon load failures;
- test datapack/registry changes after a full server restart.

Keep all addon JARs from one release version together.
