# World and player systems

UltimateImprovements includes player utilities, chat/display systems, environmental mechanics and world automation.

## Radiation

The radiation system exposes the following severity progression:

**Safe → Mild → Moderate → High → Critical → Deadly → Lethal**

Documented exposure sources include:

- ancient debris carried in a player's inventory;
- basalt deltas and the End;
- selected weapons/equipment such as mace, trident and elytra;
- the reactor and other configured environmental sources.

### Protection and measurement

- Each Hazmat Suit armor piece reduces radiation exposure by 20%.
- A full four-piece set therefore provides 80% reduction.
- A Dosimeter reports radiation level in the action bar.
- Eating can lower radiation by 10 when the player's radiation is at or above the documented threshold (200).
- Administrators can enable a persistent overlay with **/ui radview on** and disable it with **/ui radview off**.

The overlay uses the Dosimeter-style readout and is controlled by **ui.command.radview**.

Radiation-related values and sources are configured in the relevant shared/structure TOML files. The player-facing radiation level can be inspected and administered with the radiation commands; check **/ui help** for the currently registered forms.

## Homes and teleportation

The suite documents:

- personal homes: **/ui sethome**, **/ui home**, **/ui listhomes**, **/ui delhome**;
- operator home tools: **/ui ophomels**, **/ui opdelhome**;
- server spawn: **/ui setspawn**, **/ui spawn**;
- random teleport: **/ui rtp**;
- dimension/world menu: **/ui chgdim**;
- direct dimension teleport: **/ui chgdim_teleport <world>**;
- nearby-player and coordinate utilities: **/ui near**, **/ui getpos**, **/ui askpos**, **/ui uuid**.

Player homes and related settings are persistent. Random teleport and cross-dimension operations may have cooldowns, world filters or permission checks.

## Chat and communication

UI-Chat provides configurable chat formatting, per-group/per-world formats, player mentions and filtering.

Documented behavior includes:

- mentions such as **@everyone** and **@player**;
- configurable ping notifications and sound preferences;
- wildcard and regular-expression chat filtering;
- Cyrillic-aware filtering;
- chat channels, including local, global, world, private and admin-oriented channels;
- clear-chat and broadcast operations.

Permission bypass nodes exist for selected chat filtering and channel features. Do not grant bypasses to users who should be subject to moderation filters.

## Tab, scoreboard, bossbar and MOTD

UI-Display provides configurable presentation systems:

- tab list;
- scoreboard with MiniMessage gradients/rainbows and placeholders;
- bossbar;
- server MOTD.

Players can toggle some of these displays through commands such as **/ui scoreboard on|off** and **/ui bossbar on|off**. Group/world-specific formatting and placeholders are configured in the owning TOML files.

See [Built-in placeholders](placeholders.md).

## Player state and equipment effects

The player-oriented addons include:

- armor-material and armor-trim potion effects;
- configurable effect, amplifier, duration and update intervals;
- item integrity (0–100%) with anvil repair/combination and XP-based mending;
- charged totem mechanics;
- elytra wear;
- vanish and player-notes systems;
- minecart speed and related effects;
- player action utilities such as heal, feed, fly, god mode and suicide countdown.

Most equipment effects are controlled through **UI-Player.toml** or the appropriate addon TOML. Inspect the bundled defaults for exact effect names and intervals.

## Sculk growth and contact hazards

The world feature set includes two related but independently configurable systems.

### Persistent sculk growth

The growth system periodically pulses loaded sculk catalysts so vanilla sculk spreading can continue. It follows vanilla replaceability behavior rather than replacing arbitrary blocks. It supports world allow/deny lists and has a persisted enable/disable switch.

- **/ui sculk status**
- **/ui sculk start**
- **/ui sculk stop**

The [sculk] section in **UI-World.toml** controls pulse intervals, per-pulse catalyst limits, performance thresholds and world filters.

### Sculk contact hazards

A separate hazard module can apply effects when a player stands near/on configured sculk blocks. Documented rules include wither, evoker fangs, slowness and mining fatigue. Each hazard rule has its own enable switch, block list, offset/radius, effect duration/amplifier and visual settings.

Important details:

- creative and spectator players are skipped;
- the hazard bypass requires an explicitly granted **ui.sculk.hazards.bypass** node;
- Wildcard nodes such as ui.* and * and ordinary OP status do not satisfy that exact-grant check;
- the hazard module is independent from the sculk-growth start/stop switch.

The [sculk.hazards.*] sections in UI-World.toml provide per-rule settings.

## Nether portal breaking

The portal feature can break a Nether portal naturally when a matching player or entity enters it. The obsidian frame remains.

Settings in [nether_portal] within **UI-World.toml** include:

- master enable;
- scan interval;
- target mode (players or entities);
- world mode (all, whitelist or blacklist);
- allowed world/dimension identifiers;
- portal delay gamerule enforcement and its interval.

Commands:

- **/ui netherportal status**
- **/ui netherportal on**
- **/ui netherportal off**
- **/ui netherportal mode player**
- **/ui netherportal mode entity**

The status action is documented as public; changing state or mode is permission-controlled.

## Meteors

The Meteor module periodically spawns a meteor near a random player in configured worlds. A meteor is represented by a falling block-display sphere, creates an impact/explosion and leaves a configured ore-centered deposit/crater.

The [meteor] section in **UI-World.toml** controls interval, worlds, display blocks, core ores, explosion power and maximum simultaneous meteors. The documented command **/ui meteor spawn [count]** can force a spawn and has a dedicated permission.

## Additional world and utility features

Other documented features include:

- void protection;
- wireless redstone;
- block friction and collapse mechanics;
- dragon-egg mechanics;
- magnet attraction;
- unbreakable-block breaker;
- death logger and death bell;
- glass breaking;
- boosted cobweb;
- shield slowness;
- terracotta speed;
- entity locator;
- meteor showers;
- world clock/timeline behavior.

These features are modular. Check **/ui modules** and the relevant TOML file before assuming a feature is enabled.
