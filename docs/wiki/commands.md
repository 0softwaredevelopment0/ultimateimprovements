# Commands reference

All primary commands use the \`/ui\` root (also available as \`/ultimateimprovements\`). The current implementation registers a large command tree with dynamic suggestions.

> **Always use \`/ui help\` on the running server as the final authority.** This page groups the main command families, but command availability and arguments can change between releases and may be permission-gated.

## Help and management

| Command | Purpose |
|---|---|
| \`/ui help\` | Paginated command list |
| \`/ui help <page>\` | Open a help page |
| \`/ui help <command>\` | Detailed usage for a command |
| \`/ui help <command> <subcommand>\` | Detailed help for a subcommand |
| \`/ui reload\` | Reload the family's configs, messages and reloadable modules |
| \`/ui modules list\` | Show the hierarchical module tree and current states |
| \`/ui modules enable <path>\` | Enable a registered module by path or name |
| \`/ui modules disable <path>\` | Disable a non-essential module by path or name |
| \`/ui addon list\` | List addon presence/status |
| \`/ui language status\` | Show the current UI language |
| \`/ui language set <en\|ru>\` | Change the UI language |
| \`/ui config regen <UI-Addon.toml>\` | Back up and regenerate a config |
| \`/ui config reset <addon\|all>\` | Back up and reset an addon config |

Module mutations are restricted to operators or senders with the literal \`*\` permission. Essential modules cannot be disabled. Module toggles are runtime actions; they do not edit TOML settings or save a persistent enabled/disabled preference. Use [Runtime module reference](module-reference.md) for path matching, module groups and datapack gates.

Config regeneration/reset requires the relevant permission, the config-command feature gate and a confirmation step. See [Configuration](configuration.md).

## Updates

| Command | Purpose |
|---|---|
| \`/ui checkver\` | Check release/version status |
| \`/ui updatejar\` | Download an update and schedule the replacement |
| \`/ui update [tag]\` | Download release JARs into the update staging directory |
| \`/ui swapjar\` | Apply staged JAR files to the plugins directory |
| \`/ui uninstall\` | Delete every \`UI-*.jar\` from the plugins directory and staged update JARs |
| \`/ui plugin\` | Manage other installed plugins |

Staged updates are not active until the corresponding JARs are loaded on server restart.

\`/ui uninstall\` is disabled by default (\`uninstall.enabled = false\` in \`UI-Other.toml\`) and answers with error \`018\` while off. It needs the special \`ui.command.uninstall\` permission (default \`FALSE\` — neither players nor OPs get it without an explicit permissions-plugin grant) and the \`uninstall.mode\`/\`uninstall.players\` nickname list (whitelist by default). Because \`ui uninstall\` is in \`sudo.dangerous_commands\`, players also need an active sudo session. A restart finishes the removal; on Windows running JARs are locked and are listed for manual deletion after the server stops.

## Authentication

| Command | Purpose |
|---|---|
| \`/ui auth 2fa\` | Show/setup 2FA status |
| \`/ui auth 2fa setup\` | Start GitHub 2FA setup |
| \`/ui auth 2fa disable\` | Disable 2FA |
| \`/ui auth logout\` | Confirm logout and reset the session |
| \`/ui auth changepassword\` | Change password through a dialog |
| \`/ui auth forcelogin <player>\` | Force a player's authenticated state |
| \`/ui auth resetauth <player>\` | Reset a player's registration |
| \`/ui auth delsession <player>\` | Delete a player's saved session |
| \`/ui auth chgpass [player] [new_password]\` | Password recovery; console form takes arguments |

Passwords are entered using the plugin's dialog flow, not in public chat. Do not put passwords into command logs, chat or screenshots.

## Moderation and server protection

| Command | Purpose |
|---|---|
| \`/ui punish <player> <ban\|mute\|kick\|warn> <reason> [options]\` | Issue a punishment |
| \`/ui punish actionlist\` | List active punishments |
| \`/ui punish unban\|unmute\|unwarn <player>\` | Pardon a player |
| \`/ui whitelist ...\` | Manage the plugin's custom whitelist |
| \`/ui blacklist ...\` | Manage the plugin's custom blacklist |
| \`/ui opwhitelist ...\` | Manage the OP whitelist |
| \`/ui opblacklist ...\` | Manage the OP blacklist |
| \`/ui check <player>\` | Start a player check/freeze |
| \`/ui uncheck <player>\` | End a player check |
| \`/ui anticheat [player]\` | Anti-cheat status |
| \`/ui report <player> <reason>\` | Submit a report |
| \`/ui reports [page]\` | List reports |
| \`/ui modreport <id> accept\|decline\` | Moderate a report |
| \`/ui repstatus <id>\` | Check report status |
| \`/ui lockdown ...\` | Server lockdown controls |
| \`/ui console ...\` | Console lockdown controls |
| \`/ui stresstest ...\` | Run a controlled benchmark |

Punishment durations accept forms such as \`30s\`, \`5m\`, \`2h\` and \`7d\`; the command also supports permanent and optional IP/hardware scopes. Verify the running help for the exact flag syntax.

## Player and inventory utilities

| Command | Purpose |
|---|---|
| \`/ui home\`, \`/ui sethome\`, \`/ui delhome\`, \`/ui listhomes\` | Personal homes |
| \`/ui spawn\`, \`/ui setspawn\` | Server spawn |
| \`/ui rtp\` | Random safe teleport |
| \`/ui near [radius]\` | Nearby players |
| \`/ui getpos <player>\`, \`/ui askpos <player>\` | Player coordinates/request |
| \`/ui uuid [player]\` | Player UUID |
| \`/ui invsee <player>\` | View/edit inventory |
| \`/ui endersee <player>\` | View/edit ender chest |
| \`/ui vanish [player]\` | Vanish toggle/target |
| \`/ui notes\` | Personal notes GUI |
| \`/ui heal [player]\`, \`/ui feed [player]\` | Restore health/hunger |
| \`/ui fly [player]\`, \`/ui flyspeed <0-10>\` | Flight controls |
| \`/ui god [player]\` | God mode |
| \`/ui suicide\` | Confirmed suicide action |
| \`/ui forcesuicide <player>\` | Force-suicide action |

Offline \`invsee\`/ \`endersee\` edits touch the player data file and make a backup. See [Security and moderation](security-and-moderation.md).

## Gameplay, items and world systems

| Command | Purpose |
|---|---|
| \`/ui enchant give <player\|all> <enchantment> <level> [slot]\` | Apply an enchantment to a player or everyone |
| \`/ui enchant take <player\|all> <enchantment> <level> [slot]\` | Remove an enchantment |
| \`/ui enchant check <player> [page]\` | Inspect item enchantments |
| \`/ui showcraft <namespace:key>\` | Show the station, ingredients and parameters of a recipe |
| \`/ui durability multiply\|divide [raw] <value>\` | Scale the held item's vanilla durability |
| \`/ui pdc ...\` | Inspect/edit item PersistentDataContainer |
| \`/ui advancement start <woodcutter\|teleport>\` | Start a timed challenge |
| \`/ui advancement stop\` | Stop the active challenge |
| \`/ui turret ...\` | Configure end-crystal turrets |
| \`/ui str dfc assemble\|stats\` | Dark Fusion Reactor |
| \`/ui str magnet assemble\|stats\` | Magnet structure |
| \`/ui str lightning ...\` | Lightning structure |
| \`/ui redstone list\|clear\` | Manage guarded redstone chunks |
| \`/ui sculk status\|start\|stop\` | Inspect/control sculk growth |
| \`/ui netherportal status\|on\|off\|mode player\|entity\` | Nether portal breaking |
| \`/ui radview on\|off\` | Admin radiation overlay |
| \`/ui setrad <player> <mSv>\` | Set radiation level (administrative/debug) |
| \`/ui checkrad [player]\` | Inspect radiation level |
| \`/ui meteor spawn [count]\` | Spawn meteor(s) (OP by default) |
| \`/ui meteor status\` | Time until the next meteor (everyone) |
| \`/ui meteor skip\` \| \`unskip\` | Skip the next meteor / cancel that skip |
| \`/ui meteor reset\` | Restart the meteor spawn timer from now |

## Economy, chat and displays

| Command | Purpose |
|---|---|
| \`/ui money ...\` | Economy management |
| \`/ui vote ...\` | Voting system |
| \`/ui rep ...\` | Reputation system |
| \`/ui msg <player> <text>\` | Private message |
| \`/ui chatchnl ...\` | Change chat channel |
| \`/ui broadcast <text> [-clean]\` | Server announcement |
| \`/ui clear chat <player\|all>\` | Clear the chat window |
| \`/ui clear attributes <player\|UUID\|all>\` | Reset entity attributes to vanilla defaults |
| \`/ui bossbar on\|off\` | Toggle bossbar |
| \`/ui scoreboard on\|off\` | Toggle scoreboard |
| \`/ui scoreboardnick ...\` | Change scoreboard nickname |
| \`/ui pingsound on\|off\` | Toggle ping sound |
| \`/ui wirelessbind on\|off\` | Wireless redstone bind mode |

## Server power and restart schedule

| Command | Purpose |
|---|---|
| \`/ui power off\` | Schedule/confirm shutdown flow |
| \`/ui power reboot\` | Schedule/confirm restart flow |
| \`/ui power schedule\` | Show next scheduled action |
| \`/ui power undo\` | Cancel an active countdown |
| \`/ui autorestart status\` | Show automatic restart status |
| \`/ui autorestart on\|off\` | Toggle automatic restarts |
| \`/ui autorestart skip\` | Skip next scheduled run |
| \`/ui autorestart undoskip\` | Clear skip |
| \`/ui autorestart reset\` | Reset the timer |

The scheduled restart system is configured in \`UI-Essentials.toml\`. See [Configuration](configuration.md). When the module is disabled in the config, \`/ui autorestart\` stays registered but every action except \`on\` answers with error \`018\` ("the command belongs to a disabled module").

## Console versus player execution

Not every command accepts both a player and the console. Menus, inventory screens, personal notes and player-only dialogs require a player. Some administrative commands have separate console syntax. Use the in-game detailed help and command error messages to determine the supported sender type.

## Permissions

A command can exist but remain inaccessible to a sender without its permission. See [Permissions](permissions.md). The live \`/ui help\` output is the best way to discover current commands and argument suggestions.
