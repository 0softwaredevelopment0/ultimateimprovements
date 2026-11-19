# Runtime module reference

This page documents the runtime module system, how administrators control it, and the principal modules registered by the current `main` source tree.

> **Live state is authoritative.** Use `/ui modules list` on the server you are running. Modules can be added, renamed, moved or gated by configuration between releases. An addon can also provide a service that does not have its own independently switchable module.

## Three different concepts

| Concept | What it is | Example |
|---|---|---|
| **Addon / plugin JAR** | A Paper plugin loaded by Paper's plugin loader. UI-Core is required; the other 20 JARs provide feature domains. | `UI-World`, `UI-Energy`, `UI-Auth` |
| **Runtime module** | A feature or service registered with the shared `ModuleManager`. It has a name, a hierarchy path, enabled state and an essential flag. | `SculkGrowth`, `Auth`, `PacketGuard` |
| **Config owner** | A logical namespace that owns a TOML file. It does not necessarily map one-to-one to a plugin JAR or module. | `UI-Advancements.toml`, `UI-Enchantments.toml` |

For example, `UI-World` is an addon JAR; `SculkGrowth` is a module inside the runtime system; `sculk.*` settings are stored in `UI-World.toml`.

## Commands

Use the current command forms:

```text
/ui modules list
/ui modules enable <path-or-name>
/ui modules disable <path-or-name>
```

### List

`/ui modules list` prints a tree of the modules registered in the current process. The tree is built from each module's declared path. A leaf includes its module name and state:

- **✔** — initialized/enabled.
- **❌** — currently disabled or initialization failed.
- **⚡** — the module is marked essential.

Intermediate folders in the tree are path groups, not modules themselves. The list is useful for discovering the exact module names and paths available on the installed build.

### Enable and disable

`/ui modules enable` and `/ui modules disable` accept a path or a module name.

Resolution behavior in the current implementation:

1. An exact module path is preferred.
2. A unique suffix of a path can be used.
3. If no path matches, the module name is matched case-insensitively.

Use the full path or module name when there are similarly named modules. Some modules may share a path, so a module name is safer in those cases.

**Permission check:** list is available without the mutation check. Enabling or disabling requires the sender to be an operator or to have the literal `*` permission. The implementation does not use a normal `ui.command.modules` node for these two actions.

**Essential modules cannot be disabled** by this command. The command checks the module's essential flag and rejects the action. Do not work around that check by editing internal state or disabling core JARs.

### Runtime-only behavior

These commands call the in-memory module manager. They do not write a module preference to TOML or persist a requested enabled/disabled state to SQLite. Treat them as runtime controls, not as a permanent configuration system.

For a setting that must survive a restart, use the owning TOML option when one exists. Many modules also have a master `enabled` option in their config section. A datapack part is different again: it is selected during bootstrap and requires a restart to be rediscovered.

## Module lifecycle

A registered module is a small lifecycle unit with the following properties:

| Property | Meaning |
|---|---|
| Name | Human-readable identifier used in status output and name-based command matching. |
| Path | Slash-separated grouping path used to build the tree and resolve admin commands. |
| Essential | Whether the module is considered critical. Essential modules are protected from manual disable through the command. |
| Enabled state | Whether initialization completed successfully in the current runtime. |
| Disable reason | The captured initialization failure reason, when initialization did not succeed. |

The shared manager initializes modules independently. If one module throws during initialization, the error is logged and the manager continues with the next registered module. Essential failures receive a prominent warning because they can leave the suite incomplete. Shutdown runs in reverse registration order.

Modules may optionally implement a config-reload callback. This does **not** mean every setting is hot-reloadable, and it does not repeat Paper's bootstrap phase. Consult the owning config template and [Configuration](configuration.md) for restart requirements.

## Relationship with the datapack

UI-Datapack has two related mechanisms:

1. It registers the bundled datapack during Paper's bootstrap/discovery phase, before worlds load.
2. It installs a gate into the module manager. Modules tied to a disabled datapack part are skipped during registration.

The part switches live in `UI-Datapack.toml`:

| Part | Content family |
|---|---|
| `custom_recipes` | Custom item recipes |
| `vanilla_recipes` | Overrides of vanilla recipes |
| `loot_tables` | Loot-table overrides |
| `structures` | Custom structures and associated loot tables |
| `worldgen` | Biomes, dimensions, features and structure generation |
| `dimension_limits` | Dimension-type/build-limit data |
| `vanilla_tags` | Vanilla item and mineable tag overrides |

The master switch `datapack.enabled` gates all parts. If a part is disabled, code modules bound to that part can be omitted from registration too. This means an administrator cannot necessarily re-enable such a module with `/ui modules enable` while the part remains off: it is not present in the module manager to enable.

Custom enchantments and custom advancements have dedicated configuration owners. Their entries can disable individual registry/data entries and associated listener modules. See [Configuration](configuration.md) and [Gameplay content](gameplay-content.md).

## Principal module catalog

The names below are taken from the current addon registrars and module classes. This is a useful orientation list, not a replacement for `/ui modules list`: the live command shows the exact names, paths and states of the installed build.

### UI-Core

| Module | Role |
|---|---|
| `Database` ⚡ | Opens the shared SQLite connection and initializes the schema/player settings store. |
| `CoreInfra` ⚡ | Core infrastructure registration. |
| `VersionCheck` | Reports plugin/server versions and checks the configured minimum Paper API version. |

### UI-Other

| Module | Role |
|---|---|
| `Core` ⚡ | Task manager, command registrar and general shared listeners. |
| `Tasks` ⚡ | Core task infrastructure. |
| `Power` | Power countdown and scheduled restart/shutdown handling. |
| `Radiation` ⚡ | Radiation and hazmat mechanics. |
| `Sunburn`, `Lightning`, `Magnet` | Environmental effects and attraction mechanics. |
| `BlockDmg`, `BoostedCobweb`, `TerracotaSpeed` | Block damage and movement/block interaction mechanics. |
| `Integrity` | Item integrity/durability support. |
| `DeathLogger`, `EnderChest`, `GlassBreak`, `ContainerTrigger` | Death logging and selected block/container interactions. |
| `CreativeItemValidator` | Validates creative-mode item data. |
| `Kits` | Kit data and kit dialogs/commands. |
| `MOTD`, `AutoBroadcast` | Server MOTD and automated broadcast features. |
| `StructureIntegrity` | Structure integrity behavior. |
| `AutoSave` | Related persistence/autosave service. |

### UI-Auth

| Module | Role |
|---|---|
| `Auth` ⚡ | Registration, login, password policy, session and authentication listeners. |

### UI-World

| Module | Role |
|---|---|
| `Beacon`, `DragonEgg`, `BlockCollapse`, `BlockFriction` | World/block mechanics. |
| `BedrockBreak`, `Kaboom`, `EarthCore`, `NetheriteKing` | Achievement/challenge trigger modules. |
| `WoodcutterChallenge`, `EnderPearlChallenge` | Timed challenge listeners. |
| `OutOfMemory`, `ServerFreeze` | Server-event/condition achievement triggers. |
| `CmdBlockTracker` | Tracks command blocks for administrative inspection. |
| `DeathBell`, `MinecartSpeed` | Death-bell and minecart mechanics. |
| `NetherPortal`, `WirelessRedstone` | Portal behavior and wireless redstone. |
| `SculkGrowth`, `SculkHazards` | Persistent sculk spreading and separate player-contact hazards. |

### UI-Items

| Module | Role |
|---|---|
| `Crafting` ⚡ | Custom crafting infrastructure. |
| `EntityLocator`, `Antimatter`, `ExpBottleUpgrade` | Locator, antimatter and experience-bottle mechanics. |
| `Notes` | Player utility notes feature. |
| `UnbreakableBreaker`, `ParticleAccelerator` | Special block-breaker and accelerator mechanics. |
| `ItemEnchantListeners` | Item/enchantment listener bundle. |
| `RemoteDetonator`, `FireCharge` | Remote TNT binding and fire-charge projectile behavior. |
| `Omniscanner` | Admin scanner item and its configuration/menu interfaces. |

### UI-Guard

| Module | Role |
|---|---|
| `RedstoneGuard` | Limits excessive redstone update activity. |
| `PacketGuard` | Guards packet payloads and oversized/malformed traffic. |
| `ProxyServer` | Proxy-related server integration/guarding. |
| `BotProtection` | Join-rate/bot mitigation. |
| `ServerOverload` | Server overload monitoring/warnings. |
| `Check` | Player check/freeze flow. |
| `CodePanel` | Numeric code panel/key system. |
| `Sudo` | Sudo and its related controls. |
| `GSitSpinGuard` | Guard for the configured GSit spin behavior. |
| `Maintenance` | Maintenance join mode. |
| `ConsoleLockdown`, `ServerLockdown` | Emergency console and server access lockouts. |
| `StressTest` | Controlled server stress-test/benchmark tool. |

### Other addons

| Addon | Modules / roles |
|---|---|
| UI-Admin | `Economy` — economy service and integrations. |
| UI-Player | `ArmorEffects`, `ArmorTrimEffects`, `Attributes`, `JoinInvulnerableReset`, `ModeProtect`, `ShieldSlowness`, `Vanish`, `Leash`, `ElytraWear`. |
| UI-Protection | `ProtectionBlock` — claim/protection block and its GUI; `VoidProtection` — void rescue. |
| UI-Enchant | `AOEEnchantment`, `AttackAoeEnchantment`, `AutoSmeltEnchantment`, `VeinMinerEnchantment`, `TreeCapitatorEnchantment`, `FlightEnchantment`, `LevitationEnchantment`, `MagnetEnchantment`, `IgnitingEnchantment`, `RepairingEnchantment`, `LavaWalkerEnchantment`, `ContainerStealingEnchantment`, `ItemStealingEnchantment`, `SelfDestructEnchantment`, `DegradationEnchantment`, `BluntingEnchantment`, `VulnerabilityEnchantment`, `DisappearanceEnchantment`. |
| UI-Display | `Tab`, `Scoreboard`, `BossBar`. |
| UI-Anticheat | `AntiCheat` — anti-cheat checks and detection manager. |
| UI-Datapack | `Datapack` — reports bundled pack state; bootstrap registration itself occurs before world load. |

The energy network, reactor and other systems are not necessarily represented by a single module with the same product name. Do not assume that every row of the plugin overview maps to one same-named module.

## Troubleshooting module state

1. Run `/ui modules list` and copy the exact module name/path.
2. Check that the owning addon JAR is present and that all 21 JARs come from the same release.
3. Search the startup log for `[Module:<name>]`, `[Modules]` and the owning addon's startup lines.
4. Inspect the corresponding config owner and its master switch.
5. For datapack-bound features, inspect `datapack.enabled`, `datapack.modules.*`, and the dedicated enchantment/advancement switches; restart after changing bootstrap-time settings.
6. If initialization failed, fix the underlying exception before repeatedly issuing enable commands.

A module shown as disabled does not automatically mean the addon JAR failed to load. The addon can be loaded while an optional module is disabled, gated by configuration or unable to initialize.
