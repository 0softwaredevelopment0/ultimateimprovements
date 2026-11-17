# Architecture and modules

## The plugin family

UltimateImprovements is distributed as 21 Paper plugin projects:

| Plugin | Primary responsibility |
|---|---|
| UI-Core | Required foundation: startup, command root, config routing, shared database and common services |
| UI-Shared | Shared gameplay systems and common mechanics |
| UI-MBS | Multiblock structure definitions, validation and structure mechanics |
| UI-Datapack | Bundled datapack installation and bootstrap-time registry content |
| UI-Chat | Chat formats, filters, channels and pings |
| UI-Clans | Clan features |
| UI-Combat | Combat-related features, including turrets |
| UI-Punish | Punishments, moderation records and related access controls |
| UI-Essentials | General server/player commands and utility systems |
| UI-Energy | Energy graph, storage, generators and consumers |
| UI-Anticheat | Anti-cheat features |
| UI-Enchant | Enchantment gameplay mechanics |
| UI-Auth | Registration, login, sessions and 2FA |
| UI-Protection | Protection systems |
| UI-Display | Tab, scoreboard, bossbar and display features |
| UI-Admin | Administration and economy features |
| UI-Player | Player-state and equipment effects |
| UI-Guard | Packet, proxy, bot and server guards |
| UI-World | World-oriented mechanics |
| UI-Items | Custom items and item mechanics |
| UI-Other | Additional features and integrations |

The exact module list is defined by the root Gradle \`settings.gradle\`. The table above describes responsibility areas, not a promise that every feature is exclusive to one JAR.

### Addons require UI-Core

UI-Core is the mandatory foundation. Addons are loaded through Paper's plugin loader and declare their dependencies in \`paper-plugin.yml\`. Each addon joins the required classpath dependencies explicitly. This is important: do not treat the project as one shaded monolithic Bukkit plugin.

The repository also uses indirection layers such as \`CoreHooks\` for cross-addon interactions. Specialized bridges, such as \`MbsEnergy\`, keep structure code decoupled from the energy implementation.

## Addons versus modules

An **addon** is a separately loaded Paper plugin/JAR, such as UI-Energy.

A **module** is an independently initialized feature unit inside the family. Examples include optional display, gameplay, protection or world mechanics. Modules are registered with the module manager and can be listed or toggled at runtime:

\`\`\`text
/ui modules
/ui modules enable <module>
/ui modules disable <module>
\`\`\`

The module manager guards initialization and shutdown of each module. A failure in one optional module is intended not to prevent unrelated modules from starting. A failed essential module is logged as a high-severity condition and can leave the suite partially functional.

Some modules are essential to the suite's operation (for example Core, Database, Auth, Crafting, Energy, Reactor and Radiation). They are not equivalent to ordinary optional feature switches. Check the live module list for the names and state in the release you run.

## Startup flow (conceptual)

1. Paper loads UI-Core and its declared server-side dependencies.
2. UI-Core registers the root \`/ui\` command through Paper's lifecycle/Brigadier command API.
3. Core startup initializes the shared data directory and per-addon config layer.
4. The shared SQLite database and common services are initialized.
5. The addon catalog discovers the expected UI family members.
6. Addons register their feature modules and optional integrations.
7. The module manager initializes the registered modules with guarded error handling.
8. UI-Datapack's bootstrap path registers bundled datapack/registry content before worlds load.

Actual startup ordering is governed by each JAR's \`paper-plugin.yml\` dependency declaration. Use the console log when diagnosing a load-order issue.

## Config routing

The public config API is presented as one composite view, but values are stored in separate files:

\`plugins/UltimateImprovements/configs/UI-<Addon>.toml\`

The AddonCatalog maps a config path to its owning addon. For example:

- \`auth.*\` → UI-Auth
- \`energy.*\` → UI-Energy
- \`reactor.*\` and \`nuclear_reactor.*\` → UI-MBS
- \`turret.*\` → UI-Combat
- \`sculk.*\` and \`nether_portal.*\` → UI-World
- \`chat.*\` → UI-Chat

Longest matching sub-path overrides take precedence over a broad root. This is why a few nested \`features.*\` settings can live in a different addon file from the general \`features\` block. See [Configuration](configuration.md).

## Plugin projects and config namespaces are different lists

The Gradle build has 21 plugin projects. The config catalog also defines logical config owners such as \`UI-Advancements\` and \`UI-Enchantments\`. These produce TOML files but are not separate plugin projects/JARs. Do not count them as extra addons.

## Commands

The root command is \`/ui\` with the \`/ultimateimprovements\` alias. Its Brigadier tree is backed by a shared subcommand registry. Use \`/ui help\` for the current command list, detailed usage and live tab suggestions.

## Optional integrations

The core can detect integrations such as:

- PlaceholderAPI — exports the \`ui\` placeholder expansion.
- LuckPerms — exposes prefix, suffix and primary group metadata to placeholders.
- Vault — used by economy-related integration where available.

These integrations are optional unless a specific feature requires them. See the relevant addon config and startup messages to confirm whether an integration initialized.
