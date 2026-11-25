# Configuration

## Where configuration is stored

All family members share one folder:

\`plugins/UltimateImprovements/configs/\`

The configuration system uses **one TOML file per logical addon/config owner**. Each file contains that owner's settings and message templates:

- \`messages.*\` — Russian message set.
- \`messages_en.*\` — English message set.

The global UI language is selected in \`UI-Core.toml\` with \`messages.lang = "en"\` or \`"ru"\`.

## Config files

The current AddonCatalog defines the following logical files:

| File | Main configuration areas |
|---|---|
| \`UI-Core.toml\` | Shared/global settings, config controls, version check, language, global messages |
| \`UI-Shared.toml\` | Shared gameplay systems, radiation and related common mechanics |
| \`UI-MBS.toml\` | Multiblock structures, Dark Fusion Reactor and compact nuclear reactor |
| \`UI-Datapack.toml\` | Bundled datapack switches, language, pack order and module parts |
| \`UI-Advancements.toml\` | Achievement master switch, per-achievement switches and thresholds |
| \`UI-Enchantments.toml\` | Custom enchantment registry settings and level/cost values |
| \`UI-Chat.toml\` | Chat formatting, filters, channels, pings and auto-broadcast |
| \`UI-Clans.toml\` | Clan system |
| \`UI-Combat.toml\` | Combat and turret settings |
| \`UI-Punish.toml\` | Punishments, lists, moderation messages |
| \`UI-Essentials.toml\` | Homes, reports, scheduled restart and general utilities |
| \`UI-Energy.toml\` | Energy transfer, batteries, generators and consumers |
| \`UI-Anticheat.toml\` | Anti-cheat settings |
| \`UI-Enchant.toml\` | Enchantment mechanics |
| \`UI-Auth.toml\` | Authentication, password policy, lockouts and 2FA |
| \`UI-Protection.toml\` | Protection and void protection |
| \`UI-Display.toml\` | Tab, scoreboard and bossbar |
| \`UI-Admin.toml\` | Economy and OP-related settings |
| \`UI-Player.toml\` | Player-state and equipment effects |
| \`UI-Guard.toml\` | Packet/proxy/redstone/bot/lockdown guards |
| \`UI-World.toml\` | World mechanics, sculk, meteor and portal behavior |
| \`UI-Items.toml\` | Item mechanics and item-related features |
| \`UI-Other.toml\` | Additional features and mechanics |

**Important:** this is a list of config owners, not a list of JARs. \`UI-Advancements.toml\` and \`UI-Enchantments.toml\` are logical config files; they are not extra plugin projects.

## Editing workflow

1. Stop and back up the server before making risky changes.
2. Edit the appropriate file in a text editor that preserves UTF-8.
3. Preserve TOML syntax, types and section names.
4. For ordinary live-reloadable settings, run \`/ui reload\`.
5. Check the console for parse errors, validation warnings and module reload results.
6. Restart when the setting controls startup-time plugin loading, datapack discovery or registry registration.

Do not rename config files unless a migration or release note explicitly instructs you to do so.

## Automatic repair and recovery

On startup the config manager creates missing files from templates bundled in the plugin family. Missing keys are repaired from the defaults. If a TOML file cannot be parsed, the broken file is backed up using a numbered name such as:

\`UI-Addon-broken-1.toml\`

The plugin then recreates the config from its bundled template. Do not delete a broken backup until you have recovered any custom values from it.

The manager also contains migrations for settings moved between addon files. Those migrations are intended to preserve existing values and remove the old duplicate section.

## Useful UI-Core settings

The bundled core template currently documents:

| Key | Default | Purpose |
|---|---:|---|
| \`config.commands_enabled\` | \`false\` | Master gate for in-game config regeneration/reset commands |
| \`config.read_timeout_sec\` | \`5\` | Maximum read/parse time per TOML file; validated/clamped to 1–300 seconds |
| \`debug.startup_scans\` | \`false\` | Enables extra startup/structure/energy scan logs |
| \`version_check.enabled\` | \`true\` | Enables the server API compatibility check |
| \`version_check.min_api_version\` | \`"26.3"\` | Minimum runtime API version checked by the core |

The version check reads the actual running server version. Editing the JAR's \`api-version\` metadata does not bypass the runtime check.

## Language and messages

- Set \`messages.lang\` in UI-Core.toml to \`"en"\` or \`"ru"\`.
- Message text is stored alongside the settings in each owner's file.
- Messages use MiniMessage formatting, for example \`<red>\`, \`<yellow>\`, \`<gradient:...>\` and clickable components where supported.
- A few addon-local settings also document their own language fallback. Follow the comments in the generated file.

## Reload versus restart

Usually reloadable through \`/ui reload\`:

- ordinary TOML values;
- messages and formatting;
- settings explicitly handled by the owning module's reload implementation.

Restart required:

- datapack pack discovery/position/language changes that affect startup registration;
- custom enchantment registry settings;
- custom advancement/datapack registration changes;
- changes to the installed plugin JAR family or Paper dependency graph.

A reload command cannot retroactively repeat Paper's bootstrap phase. When in doubt, follow the comment next to the setting in the generated TOML file.

## Config recovery commands

The command tree includes:

\`\`\`text
/ui config regen <UI-Addon.toml>
/ui config reset <addon|all>
/ui config confirm
/ui config cancel
\`\`\`

These operations are deliberately protected:

- the relevant config permission must be granted;
- \`config.commands_enabled\` must be enabled;
- the request must be confirmed through the plugin's confirmation flow.

A reset/regeneration backs up the affected live config before replacing it. Use a filesystem backup for large changes; do not rely exclusively on the in-game recovery command.

## Selected addon examples

### Datapack

\`UI-Datapack.toml\` controls the bundled pack. The current template defaults are:

| Key | Default | Effect |
|---|---:|---|
| \`datapack.enabled\` | \`true\` | Master switch; when false, the pack and its parts are not registered. |
| \`datapack.lang\` | \`auto\` | \`en\`, \`ru\` or \`auto\` (follows \`messages.lang\`). |
| \`datapack.position\` | \`top\` | \`top\` makes this pack win conflicts; \`bottom\` lets world/other packs win. |
| \`datapack.modules.custom_recipes\` | \`true\` | Custom item recipes. |
| \`datapack.modules.vanilla_recipes\` | \`true\` | Vanilla recipe overrides. |
| \`datapack.modules.loot_tables\` | \`true\` | Loot-table overrides. |
| \`datapack.modules.structures\` | \`true\` | Custom structures and their loot tables. |
| \`datapack.modules.worldgen\` | \`true\` | Biomes, dimensions, features and structure generation; affects new chunks only. |
| \`datapack.modules.dimension_limits\` | \`true\` | Dimension build-height/type data; applies to worlds using that dimension type. |
| \`datapack.modules.vanilla_tags\` | \`true\` | Overrides vanilla item/mineable tags. |

These options are consumed during startup bootstrap. Restart after changing the master switch, language, pack position or any part switch. Custom enchantments and achievements are intentionally configured separately in \`UI-Enchantments.toml\` and \`UI-Advancements.toml\`; they are not toggled by the datapack part switches.

### Advancements

\`UI-Advancements.toml\` controls the master switch and individual achievement nodes. Tunables include challenge goals/time limits and selected server-event thresholds. Restart after changing the content that is installed into the achievement tree.

### Enchantments

\`UI-Enchantments.toml\` stores the master switch, per-enchantment \`enabled\` flags, display names, maximum levels, anvil cost, equipment slots and enchanting cost/weight behavior. Registry entries are filled during startup; restart after changing them.

### Scheduled restart

\`UI-Essentials.toml\` has a \`[scheduled_restart]\` section. The documented modes are daily fixed times (\`mode = "times"\`, \`times = ["04:00"]\`, timezone) or an interval (\`mode = "timer"\`, for example \`timer = "48h"\`). The action can be restart or shutdown; warning, countdown and online-player gates are configurable. Announcements are \`warning_times\` (time values with \`s\`/\`m\`/\`h\`/\`d\` units, default \`["1h", "30m", "15m", "5m", "1m", "45s", "30s", "15s"]\`), and \`second_report_from\` (default \`"10s"\`) starts a per-second report; both use the \`power.scheduled_announce\` template with \`%time%\` and \`%unit%\` (unit words are \`power.unit_seconds|minutes|hours|days\`). Announcements are written to chat and to the console with the \`[UI]\` prefix. \`delay\` (time value, default \`"0s"\`) is the gap between the scheduled moment and the action — \`0s\` restarts immediately because the announcements already counted down; a positive value (\`"60s"\`) runs the usual BossBar countdown first. \`/ui autorestart\` stays registered when the module is off and answers with error \`018\` instead. The timer deadline and skip state are persisted.

### Uninstall access

\`UI-Other.toml\` has an \`[uninstall]\` section for \`/ui uninstall\`. \`enabled\` (default \`false\`) is the master switch; while it is off the command answers with error \`018\`. \`mode\` is \`whitelist\` (default: only the nicknames in \`players\` may run it) or \`blacklist\` (every player except those nicknames). Nicknames are matched case-insensitively and the console is always exempt from the list. The special permission \`ui.command.uninstall\` is required in both modes and defaults to \`FALSE\` (not even OPs get it). The command is also listed in \`sudo.dangerous_commands\` in \`UI-Guard.toml\`, so players must have an active sudo session.

### Meteor cooldown

`UI-World.toml` has a `[meteor.cooldown]` section that controls the spawn timer. `mode` is `static` (always `static`), `list` (a random entry of `list`) or `range` (a random number of seconds between the two bounds of `range`). Every time value is a number plus one suffix — `s` seconds, `m` minutes, `h` hours, `d` days — e.g. `30m`, `2h`, `1d`; a range is written `40m-50m`. The bundled defaults are `mode = "range"`, `static = "30m"`, `list = ["15m", "10m", "5m", "20m"]` and `range = "40m-50m"`. Invalid entries are reported in the console and replaced by the defaults. The timer starts over after every meteor and after every skipped meteor; `/ui meteor status|skip|unskip|reset` reads and controls it at runtime.

### Punishment messages

\`UI-Punish.toml\` stores Russian and English MiniMessage templates. Documented placeholders include \`%player%\`, \`%punisher%\`, \`%reason%\`, \`%duration%\` and \`%discord_url%\`.

### Whitelist removal warnings

\`UI-Punish.toml\` has an \`[access_control.warnings]\` section for scheduled whitelist removals (\`/ui whitelist remove <player> -t <time>\` and expiring \`add-temp\` entries). \`enabled\` (default \`true\`) is the master switch. \`warning_times\` is a list of countdown thresholds with \`s\`/\`m\`/\`h\`/\`d\` units (default \`["1h", "30m", "15m", "5m", "1m", "45s", "30s", "15s"]\`); \`second_report_from\` (default \`"10s"\`, or \`0\`/\`off\`) starts a per-second report at the end. The messages are \`messages[_en].punishment.timed.removal_warning\`, \`removal_kick\` and \`readded_notice\` in \`UI-Core.toml\`, with \`%list%\`, \`%time%\` and \`%unit%\` placeholders (unit words are \`unit_seconds|minutes|hours|days\` in the same section).

## Source of truth

The templates are bundled under \`ui-src/ui-core/src/main/resources/config/\`. For the precise accepted keys and defaults of a release, inspect the TOML template in that release rather than copying a config example from an older guide.
