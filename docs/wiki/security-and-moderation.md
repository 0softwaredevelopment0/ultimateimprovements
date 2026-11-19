# Security and moderation

This page summarizes the authentication, moderation and protective systems documented in the source and bundled guide. Security controls must be configured deliberately; do not enable a feature on a production server without understanding its network and permission requirements.

## Authentication

UI-Auth provides registration and login using a custom dialog/screen rather than asking players to type passwords into public chat.

Documented features include:

- Argon2id password hashing;
- configurable password policy (default length 8–32 characters, with digit, lowercase, uppercase and special-character requirements);
- a configurable list of common/forbidden passwords;
- IP checks and account-per-IP limits;
- persisted login sessions and timeouts;
- maximum incorrect attempts and lockouts;
- administrative force-login, registration reset and session reset;
- GitHub-based OAuth 2FA.

### Password handling

Player registration, password change and login use the plugin dialog flow. Do not use chat, public command arguments or external logs to transmit a player's password.

The administrative console form of **/ui auth chgpass** accepts a password argument. That can expose the secret through shell history, terminal capture or command logging. Prefer a secure dialog path where possible and restrict console access.

### Lockouts

Incorrect login attempts can lock both the account and the associated IP. Lockout state is persisted in the database, so reconnecting or restarting does not erase it. The penalty increases according to the configured backoff multiplier and is capped by the configured maximum duration. The implementation serializes password checks for an account/IP to prevent parallel submit bursts from bypassing the attempt limit.

### Proxy-aware IP detection

The implementation reads the proxy-aware client address from Paper's player connection API. With Velocity, configure forwarding correctly so the backend receives the real client address.

Missing, unresolved, wildcard and multicast addresses are rejected instead of silently treating the proxy address as the player. Loopback addresses are accepted by default for local testing; **auth.reject_loopback_ips = true** can reject them.

Do not expose the authentication callback or backend to untrusted networks without reviewing the server's proxy and firewall configuration.

### GitHub 2FA network warning

The documented GitHub OAuth callback listener uses plain HTTP. Set **auth.twofa.github.public_url** to an HTTPS reverse-proxy endpoint, or keep the callback port reachable only through a trusted network/VPN. Do not expose the callback directly to the public internet over plain HTTP. The OAuth state/code travel through the callback URL.

See **UI-Auth.toml** for the current keys and comments.

## Punishments

UI-Punish includes:

- kick, ban, mute and warn;
- temporary and permanent durations;
- optional IP and hardware-ID scopes;
- expiration handling;
- active punishment listings and pardon operations;
- punishment history/logging;
- configurable kick screens and chat messages.

Example command shape:

- **/ui punish <player> ban <reason> -time:7d**
- **/ui punish <player> mute <reason> -time:30m**
- **/ui punish <player> warn <reason>**
- **/ui punish actionlist**

The command guide also documents **-permanent**, **-ip** and **-hw** options. Check **/ui help punish** for exact current syntax and subcommands.

Punishment messages are MiniMessage templates in **UI-Punish.toml**; documented substitutions include **%player%**, **%punisher%**, **%reason%**, **%duration%** and **%discord_url%**.

## Whitelists and access control

The plugin maintains its own database-backed whitelist and blacklist systems, separate from the vanilla whitelist. There are also OP whitelist and OP blacklist controls.

The vanilla `/whitelist` command keeps working, but every execution prints a deprecation notice asking to use `/ui whitelist | /ui blacklist` instead: running two independent whitelists side by side can cause confusing access conflicts. For players both command names are clickable — hover shows a hint and click inserts the command into chat. The notice text is `messages.plugin_hide.whitelist_deprecated` in **UI-Core.toml**.

Because these controls affect operator privileges and login access, test their behavior with a controlled account before enabling them on a production server. Use the in-game help for exact commands and temporary-operation flags.

Vanilla `/stop` and `/restart` also remain available but are deprecated. A player request is forwarded to the console: the console must type the same command within `power.vanilla_confirm_seconds` (default 15 s); the player cannot self-confirm. On confirmation the player is notified and the action runs after `power.vanilla_execute_delay_ticks` (default 20 ticks). A console-initiated command needs the same repeat-within-the-window confirmation. Without confirmation both sides receive a timeout notice. Both commands are in the sudo dangerous-command list by default and use `ui.command.power.off` / `ui.command.power.reboot` (OP-only by default).

The generic command policy (`command_policy.enabled`, off by default) blocks every prefix listed in `command_policy.disabled_commands` (empty by default) with error 011. It applies to players and the console/RCON equally; the list is fully configurable.

Vanilla `/op` and `/deop` remain available but are deprecated. They use the same permissions as `/ui op` / `/ui deop` (`ui.command.op`, `ui.command.deop`; OP-only by default), print a deprecation notice with clickable `/ui op` / `/ui deop` suggestions, and players must repeat the exact command within `op.vanilla_confirm_seconds` (default 15 s) or the action is cancelled. The console is not asked to confirm. Both commands are in the sudo dangerous-command list by default and reconcile the plugin's OP list against the real `ops.json` set after every execution.

## Anti-cheat and server guards

The family includes multiple layers:

- **Player check/freeze:** moderators can freeze a player for investigation.
- **PacketGuard:** limits/guards packet payloads and protects against malformed or oversized traffic.
- **RedstoneGuard:** limits excessive redstone update activity.
- **BotProtection:** join-rate protection.
- **CreativeItemValidator:** validates creative-mode items and their data.
- **Command block tracker:** enumerates active command blocks and their locations.
- **Server lockdown:** additional protection during a bot attack or other incident.
- **Console lockdown:** restricts dangerous console interactions.
- **GSit spin guard:** optional mitigation for a documented GSit version-mismatch invisibility issue.

Bypass permissions exist for selected subsystems. Do not grant them broadly. See [Permissions](permissions.md).

## Offline inventory editing

**/ui invsee <player>** and **/ui endersee <player>** support online and offline players.

- Online inventories are edited through the live player API.
- Offline inventories are read from and written to the player's .dat file.
- Before offline saves, a backup file named like **<uuid>-backup.dat** is created beside the player data file.
- The implementation looks up the correct world player-data folder, including Multiverse-style world layouts.

This is a high-impact administrative feature. Restrict its permissions, make regular world/player-data backups and avoid editing the same offline player file from another tool while the server is running.

## Security checklist

- Use least-privilege groups; do not grant ui.* to ordinary players or broad staff ranks by default.
- Keep GitHub OAuth callback traffic behind HTTPS or a trusted network.
- Verify Velocity forwarding and firewall rules before enabling IP-based auth.
- Restrict force-login, password resets, OP changes, sudo, lockdown, power management and offline inventory editing.
- Keep UI-Core and every addon on the same release.
- Back up the shared SQLite DB and player data before migrations or bulk moderation operations.
