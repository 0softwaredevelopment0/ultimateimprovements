# UltimateImprovements Wiki

Welcome to the documentation for **UltimateImprovements**, a modular gameplay, administration and server-systems suite for modern Paper servers.

> **Documentation target:** the repository state represented by the source files on the default branch. The project is actively developed; command registrations, defaults and implementation details can change. For version-specific information, use the source and bundled TOML templates for the exact release you run.

## At a glance

| Property | Current repository configuration |
|---|---|
| Project | UltimateImprovements |
| Author | [rizer001](https://github.com/rizer001) |
| Repository | [0softwaredevelopment0/ultimateimprovements](https://github.com/0softwaredevelopment0/ultimateimprovements) |
| Server API | Paper 26.3+ (compatible forks may work; verify against the release) |
| Java | 26+ |
| Build system | Gradle multi-project build |
| Runtime layout | UI-Core + 20 addon JARs |
| Database | One shared SQLite database |
| Configuration | Per-addon TOML files |
| License | GNU AGPL v3 |

## Start here

### Server owners

1. [Installation and updates](installation.md)
2. [Architecture and addons](architecture.md)
3. [Runtime module reference](module-reference.md)
4. [Commands reference](commands.md)
5. [Permissions](permissions.md)
6. [Configuration](configuration.md)
7. [Built-in placeholders](placeholders.md)

### Feature guides

- [Gameplay content: items, enchantments, achievements and turrets](gameplay-content.md)
- [Energy network and multiblock structures](energy-and-structures.md)
- [Authentication, moderation and security](security-and-moderation.md)
- [World and player systems](world-and-player.md)
- [Database, persistence and backups](database-and-backups.md)

### Contributors

- [Development and building](development.md)
- [Troubleshooting](troubleshooting.md)

## Important terminology

The repository contains **21 Gradle plugin projects/JARs**: UI-Core and 20 addons. The configuration catalog has additional logical TOML files, including \`UI-Advancements.toml\` and \`UI-Enchantments.toml\`. Those two are configuration namespaces, not additional plugin JARs. See [Architecture](architecture.md) and [Configuration](configuration.md).

The suite has two levels of modularity:

- **Addons** are independently loaded Paper plugins. UI-Core is required; addons provide feature domains.
- **Modules** are feature units registered by the suite and managed at runtime through \`/ui modules list|enable|disable\`. A module is not the same thing as an addon JAR.

## Documentation conventions

- Commands are written with the \`/ui\` root. Run them in-game or from console only where the command supports console execution.
- \`<value>\` means a required argument; \`[value]\` means optional.
- Permission defaults are stated only where they are confirmed by the implementation or current guide. A permission constant existing in source does not, by itself, prove its default grant.
- TOML keys and paths are case-sensitive in examples.
- “Restart required” means a normal server restart, not merely \`/ui reload\`.

## Canonical sources

- [README](../../README.md) — short project overview and quick install.
- [CHANGELOG.md](../../CHANGELOG.md) — historical changes.
- [Releases](https://github.com/0softwaredevelopment0/ultimateimprovements/releases) — versioned artifacts.
- [Source tree](https://github.com/0softwaredevelopment0/ultimateimprovements/tree/main) — implementation and bundled defaults.

When this Wiki and a release's bundled config or runtime behavior disagree, treat the **release artifacts and source for that exact tag** as authoritative and update this Wiki accordingly.
