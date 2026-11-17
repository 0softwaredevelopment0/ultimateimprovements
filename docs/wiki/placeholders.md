# Built-in placeholders

UltimateImprovements has an internal placeholder resolver. The same internal syntax is used in the plugin's own configurable text (chat, tab, scoreboard, messages and similar components).

When PlaceholderAPI is installed, the plugin also registers an expansion with identifier **\`ui\`**. Therefore:

- Internal plugin text: \`%player_name%\`
- PlaceholderAPI in another plugin: \`%ui_player_name%\`

If PlaceholderAPI is absent, internal resolution still works inside UltimateImprovements. External plugins will not see the UI expansion unless PlaceholderAPI and the expansion are available.

## Player placeholders

| Placeholder | Description |
|---|---|
| \`%player_name%\` | Player account name |
| \`%player_scoreboard_nick%\` | Configured scoreboard nickname when enabled; otherwise the real player name |
| \`%player_displayname%\` | Plain-text display name |
| \`%player_uuid%\` | UUID |
| \`%player_ping%\` | Ping in milliseconds |
| \`%player_ping_color%\` | Color for the player's current ping |
| \`%player_ping_gradient%\` | Gradient-formatted ping value |
| \`%player_gamemode%\` | Lowercase game mode |
| \`%player_health%\` | Integer health |
| \`%player_food%\` | Food level |
| \`%player_level%\` | Experience level |
| \`%player_xp%\` | Progress toward the next level as a rounded percentage-like value |

## World placeholders

| Placeholder | Description |
|---|---|
| \`%world_name%\` | Player's current world name |
| \`%world_players%\` | Number of players in the current world |
| \`%player_world%\` | Alias for the player's current world |
| \`%player_coords%\` | Block coordinates as \`x/y/z\` |

## Server placeholders

| Placeholder | Description |
|---|---|
| \`%online%\` | Current online player count |
| \`%max_players%\` | Server maximum player count |
| \`%server_name%\` | Bukkit/Paper server name |
| \`%server_version%\` | Server version string |
| \`%uptime%\` | JVM uptime formatted as days/hours/minutes |
| \`%server_time%\` | Server operating-system local time (\`HH:mm:ss\`) |
| \`%server_date%\` | Server operating-system local date (\`yyyy/MM/dd\`) |
| \`%tps%\` | Current 1-minute TPS value, formatted to two decimals |
| \`%mspt%\` | Current average tick time in milliseconds |
| \`%ram%\` | Used JVM heap as a percentage of max heap |
| \`%tps_color%\` | TPS with a color prefix |
| \`%mspt_color%\` | MSPT with a color prefix |
| \`%ram_color%\` | RAM percentage with a color prefix |

## LuckPerms placeholders

When the LuckPerms hook is available:

| Placeholder | Description |
|---|---|
| \`%prefix%\` | LuckPerms cached prefix |
| \`%suffix%\` | LuckPerms cached suffix |
| \`%group%\` | LuckPerms primary group |

These use cached user metadata. When the player or metadata is unavailable, the resolver returns an empty value.

## Dynamic statistics

The dynamic metric syntax supports:

\`\`\`text
%<metric>[_<stat>][_ <window>][_color]%
\`\`\`

Remove the illustrative space before the window. Supported metrics:

- \`tps\`
- \`mspt\`
- \`online\`
- \`ram\`

Supported statistics:

- \`avg\`
- \`min\`
- \`max\`

Supported windows:

- \`10s\`, \`30s\`, \`5m\`, \`1h\`, \`1d\` (number + \`s\`, \`m\`, \`h\` or \`d\`)
- \`ss\` — the current server session (up to the retained sample capacity).

Examples:

| Placeholder | Meaning |
|---|---|
| \`%tps_5m%\` | Average TPS over the last five minutes |
| \`%tps_min_5m%\` | Minimum TPS over the last five minutes |
| \`%tps_max_1h_color%\` | Maximum TPS over the last hour, color-prefixed |
| \`%mspt_avg_30s%\` | Average MSPT over 30 seconds |
| \`%ram_max_1h_color%\` | Maximum JVM heap percentage over one hour |
| \`%online_min_1d%\` | Minimum online count over one day |
| \`%tps_avg_ss%\` | Average TPS for the retained session samples |

The tracker collects one sample per second (20 ticks) and keeps up to 86,400 samples (approximately 24 hours). At startup, a window can contain fewer samples than its nominal duration because the buffer is still filling.

For \`online\`, the current value is returned for the ordinary/average form; \`min\` and \`max\` use the selected history window.

## Ping placeholders

Ping syntax supports a target/scope, a statistic, a time window and an optional color suffix.

Examples:

| Placeholder | Meaning |
|---|---|
| \`%ping%\` | Current ping of the player whose text is being resolved |
| \`%ping_color%\` | Current ping with a color prefix |
| \`%ping_gradient%\` | Gradient-formatted ping for the current player |
| \`%ping_Steve%\` | Current ping for online player Steve |
| \`%ping_all%\` | Average ping of all online players |
| \`%ping_avg_5m_all%\` | Average of the sampled server-wide average ping over five minutes |
| \`%ping_min_30s_all_color%\` | Minimum sampled server-wide ping over 30 seconds, with color |

A named player target must be online. When the named target is not found, the resolver returns \`0\`. Server-wide aggregation uses the one-second sampling tracker; a player-specific scope reads that player's current ping.

The ping color gradient uses configured stops from green at low latency through yellow/orange to red at high latency.

## Click actions

Two special placeholders are converted into MiniMessage click actions:

- \`%copy:"text"%\` — click to copy the text to the clipboard.
- \`%link:"https://example.com"%\` — click to open the URL.

Use trusted, valid URLs in configured messages. These placeholders are intended for text rendered by a component-capable Minecraft client.

## Resolution order and troubleshooting

The internal resolver processes:

1. Static built-in names.
2. Dynamic TPS/MSPT/online/RAM metrics.
3. Ping placeholders.
4. Copy/link actions.
5. PlaceholderAPI placeholders, if PlaceholderAPI is available.

Unknown placeholders are left unchanged by the internal resolver so another stage (including PlaceholderAPI) can process them.

If a placeholder stays literal:

- confirm the spelling and underscores;
- for another plugin, use the \`ui_\` PAPI prefix;
- confirm PlaceholderAPI is installed and enabled before UI-Core;
- check the startup log for the PlaceholderResolver integration messages;
- confirm the text context has a player when using player-scoped placeholders.
