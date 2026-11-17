# Nether Portal Break Plan

> Status: **implemented** (2026-11-17). Feature owner: `UI-World`
> (`NetherPortal` module), config file: `configs/UI-World.toml`.

## 1. Goal

Break a nether portal when a player (or any entity) enters it. The check is
periodic: every `check_interval_ticks` the module scans the worlds allowed by
the world filter and, for every matching entity standing inside a
`minecraft:nether_portal` block, breaks that block naturally
(`Block#breakNaturally()`). Vanilla then collapses the portal exactly as if a
player had hit the portal block — the obsidian frame stays.

The feature is **off by default**. It is independent from the portal gamerules:
those are only enforced while the feature is enabled.

## 2. Config

Root section `[nether_portal]` in `configs/UI-World.toml`
(routed to `UI-World` in `AddonCatalog`):

```toml
[nether_portal]
enabled = false
target = "players"              # players | entities
check_interval_ticks = 20

world_mode = "all"              # all | whitelist | blacklist
worlds = ["minecraft:example"]

set_default_delay_gamerule = true
set_creative_delay_gamerule = true
gamerule_interval_ticks = 200
```

- `enabled` — master switch (default `false`).
- `target` — `players` (default) or `entities` (players are included).
- `check_interval_ticks` — scan period in ticks (default `20`, minimum `1`).
- `world_mode` — `all` (default), `whitelist` or `blacklist`. In `all` the
  `worlds` list is ignored entirely (no validation warnings for it).
- `worlds` — dimension IDs (`minecraft:overworld`, `minecraft:the_nether`,
  `namespace:path`), compared case-insensitively against `World#getKey()`.
  The bundled example value `minecraft:example` does not exist.
- `set_default_delay_gamerule` / `set_creative_delay_gamerule` — which
  gamerules to enforce (both `true` by default).
- `gamerule_interval_ticks` — how often the gamerules are re-applied
  (default `200` = 10 s, minimum `1`).

Invalid values fall back safely (bad `target`/`world_mode` → default with one
warning; non-positive intervals → 1). `ConfigRules` validates the types.

## 3. World filter

Worlds are validated **once per start / `/ui reload`** (not per iteration):

- `all` → every loaded world is allowed; the list is not inspected.
- `whitelist` / `blacklist` → each configured entry is resolved against the
  loaded worlds' dimension keys. Missing dimensions are logged once with the
  plugin prefix:

  `[NetherPortal] World 'minecraft:example' does not exist — ignoring it.`

  and dropped from the active set for all subsequent scans/gamerule passes.
- `whitelist` with no valid entries → nothing is processed.
- `blacklist` with no valid entries → every world is processed.

The same active set is used by both the portal scan and the gamerule task, so
gamerules are never forced onto worlds excluded by the filter.

## 4. Gamerule enforcement

Vanilla waits 80 ticks (survival default) before a player is teleported by a
nether portal; creative is faster. While the feature is enabled, the module
sets, for every allowed world:

- `minecraft:players_nether_portal_default_delay = 80`
- `minecraft:players_nether_portal_creative_delay = 80`

immediately at start/reload and then every `gamerule_interval_ticks`. The value
is intentionally the vanilla survival default: 80 > 20 (default scan period),
so the portal is always broken before vanilla can teleport, and switching the
feature off leaves the server on a sane default. Each gamerule has its own
toggle; both are on by default.

## 5. Portal break

Per tick of the scan task:

1. Iterate the allowed worlds.
2. Collect candidates: `world.getPlayers()` for `target = "players"`,
   `world.getEntities()` for `target = "entities"`.
3. For each entity, take `entity.getLocation().getBlock()`. If it is
   `Material.NETHER_PORTAL`, call `block.breakNaturally()` once.
4. Vanilla block updates invalidate and remove the rest of the portal; the
   obsidian frame remains. No drops are produced (portal blocks have none).

The scan and block breaking run on the main server thread. No portal events are
cancelled and no teleports are intercepted — the gamerule delay is the only
guard that keeps the entity in place until the scan runs.

## 6. Commands

`/ui netherportal <on|off|mode|status>` registered as a normal `SubCommand`
through the Paper Brigadier `/ui` root (`UIWorld.registerCommands`).

| Command | Permission | Default | Behaviour |
|---------|------------|---------|-----------|
| `/ui netherportal on` | `ui.command.netherportal.toggle` | OP | Enable the mechanic; writes `nether_portal.enabled = true` to the TOML. |
| `/ui netherportal off` | `ui.command.netherportal.toggle` | OP | Disable the mechanic; writes `false` and stops both tasks. |
| `/ui netherportal mode player` | `ui.command.netherportal.mode` | OP | Target players only; writes `nether_portal.target = "players"`. |
| `/ui netherportal mode entity` | `ui.command.netherportal.mode` | OP | Target every entity; writes `nether_portal.target = "entities"`. |
| `/ui netherportal status` | `ui.command.netherportal.status` | everyone | Read-only: state, target, interval, world filter and gamerule state, with the plugin prefix. |

Error handling uses the shared `CommandErrors` mechanism:

- `001` — missing/unknown subcommand (plus a usage line);
- `002` — missing permission (the exact node is shown);
- `006` — command-specific "invalid mode" (`mode` accepts only
  `player|players|entity|entities`);
- `018` — the module is not initialised.

The `autorestart` command is checked in the same pass: its missing-argument and
unknown-subcommand paths will report `001` via `CommandErrors.syntaxError`
instead of a bare usage line, and the unavailable path will use `018`
(`moduleDisabled`) instead of an un-coded message. Permission checks already use
`002` and stay unchanged.

## 7. Help and permissions

- `Permissions`: new constants and registrations
  (`toggle`/`mode` = OP, `status` = TRUE).
- `HelpSubCommand`: add the `netherportal` card and fix permission display for
  commands whose access is split across sub-permissions. `Cmd` gets an optional
  permission summary; `hasAccess` checks every node in it, so
  `/ui autorestart` and `/ui netherportal` no longer show a false
  "(no permission)" for staff who hold the actual sub-nodes.

## 8. Messages and logs

Bilingual keys in `UI-World.toml` (`messages.nether_portal.*` RU,
`messages_en.nether_portal.*` EN): enabled/disabled, mode changed, invalid
mode, status header/state/target/worlds/gamerules. Console warnings for
invalid worlds and invalid config values go through `ConsoleLogger`
(automatic `[UI]` prefix). All in-game replies go through `MessageUtil.PREFIX`.

## 9. Architecture

| Piece | Responsibility |
|-------|----------------|
| `mechanics/features/netherportal/NetherPortalManager` | Config load, world validation, scan task, gamerule task, runtime setters, status getters. |
| `mechanics/features/netherportal/NetherPortalSubcommand` | `/ui netherportal` parsing, permissions, config writes, status output. |
| `core/WorldModules` (`NetherPortal`) | Init / reload / shutdown hooks. |
| `core/UIWorld` | Command registration; `OWNED_MODULES` entry. |
| `core/Permissions` | Three new nodes. |
| `config/AddonCatalog` | Root `nether_portal` + message group routed to `UI-World`. |
| `config/ConfigRules` | Type/range validation for the new keys. |
| `ui-core/resources/config/UI-World.toml` | Bundled section + bilingual messages. |
| `ui-essentials/.../HelpSubCommand` | Help card + permission summary support. |

## 10. Edge cases

| Case | Result |
|------|--------|
| Feature disabled | No scan task and no gamerule task; `/status` shows OFF. |
| `world_mode = "all"` | `worlds` ignored; every world scanned, no invalid-world warnings. |
| Invalid dimension in the list | Warning once at startup/reload; entry ignored afterwards. |
| Whitelist with no valid worlds | Nothing is scanned; gamerules untouched. |
| Blacklist with no valid worlds | All worlds are processed. |
| `/ui reload` | Tasks restart, world list is revalidated, changed intervals are applied. |
| Creative player | Creative delay is also forced to 80, so the scan wins. |
| Gamerule toggle off | That gamerule is no longer enforced (its current value is left alone). |
| Entity leaves the portal block | The next scan no longer matches it; nothing to break. |
| `check_interval_ticks` >= 80 | The manager warns at startup/reload: vanilla may teleport the entity before the scan. |
| Portal frame | Not touched; the portal blocks are broken naturally. |

## 11. Tests

- Unit test `NetherPortalConfigTest` (pure helpers): target/world-mode parsing,
  world-name normalization, interval clamping, whitelist/blacklist resolution
  against a synthetic world-key set.
- Existing `Sculk`/world-filter tests remain untouched.
- Manual: enable the feature, set `check_interval_ticks = 20`, enter a portal in
  a whitelisted world → portal collapses within a second, frame stays; verify
  `players_nether_portal_default_delay` / `creative_delay` are 80; add a bogus
  world and `/ui reload` → warning; `/ui netherportal status` and permission
  codes for a non-OP player.

## 12. Files

```
ui-world/.../mechanics/features/netherportal/NetherPortalManager.java
ui-world/.../mechanics/features/netherportal/NetherPortalSubcommand.java
ui-world/.../core/WorldModules.java
ui-world/.../core/UIWorld.java
ui-world/src/test/.../netherportal/NetherPortalConfigTest.java
ui-core/.../core/Permissions.java
ui-core/.../config/AddonCatalog.java
ui-core/.../config/ConfigRules.java
ui-core/src/main/resources/config/UI-World.toml
ui-essentials/.../command/subcommands/HelpSubCommand.java
ui-other/.../command/subcommands/AutorestartSubcommand.java
docs/Nether-Portal-Break-Plan.md
GUIDE.md / README.md / CHANGELOG.md
```
