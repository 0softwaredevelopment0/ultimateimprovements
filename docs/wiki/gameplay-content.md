# Gameplay content

This page covers the player-facing content implemented by the suite: custom items, enchantments, achievements and turrets. Individual mechanics may be switched off through their owning module or configuration file.

## Custom items

Most custom recipes are provided by the bundled datapack and are intended to be crafted in the plugin's **Crafter** block. The vanilla recipe book/workbench can show a recipe preview, but the actual recipe is restricted to the Crafter.

| Item | Behavior |
|---|---|
| Blazing Sword | Sets a target on fire and applies additional burn damage over time. Armor reduces the extra damage. |
| Glass Sword | Very high burst damage, but only one durability; breaks after a hit and also breaks when used to mine a block. |
| Electric Trident | Trident with extended durability that calls lightning where it hits. |
| Photon Cannon | Long-range projectile weapon. |
| Electro Shoker | Close-range projectile weapon. |
| Antimatter Flask | Explosive item with a large blast. Use carefully and test in a protected environment. |
| Multimeter | SHIFT + right-click reads energy parameters from supported energy blocks/structures. |
| Metal Detector | Scans a 33×33×33 area over multiple ticks for metal blocks, dropped metal items and entities carrying metal. |
| Ore Finder | Counts ores in the current chunk column; scan work is performed asynchronously. |
| Mob Finder | Counts entity types in the current chunk, lists spawners and can warn when hostile-mob counts reach a configured threshold. |
| Entity Locator | While held, provides proximity feedback toward the nearest entity using sound and particles. |
| Health Meter | Shows a target's health, armor, attack damage, knockback resistance, effects and equipment. |
| Portable Radar | Finds a nearby target and displays direction/distance; SHIFT + right-click cycles the target filter. |
| Lead Ingot | Radiation-related crafting material. It cannot be converted back into its source ingredients. |
| Hazmat Suit | Each armor piece reduces radiation exposure by 20%; a full set provides 80% protection. |
| Dosimeter | Displays the holder's radiation level in the action bar. |
| Concrete Bucket | Places concrete instantly. |
| Structure Indicator | SHIFT + right-click on a recognized structure to display a structural report. |
| Particle accelerator components | Includes the Particle Engine, Injector, Ring and Speed Sensor components. |
| Chunk Loader | Keeps chunks loaded and consumes an experience bottle as its fuel. |
| Portable Workbench | Opens a crafting-table interface and has 64 charges by default; charge and presentation settings are configurable. |
| Portable Ender Chest | Portable storage item. |

Some names and values in this table are intentionally high-level. Use **/ui cilist** and the bundled item/recipe definitions for exact registry IDs, recipes, lore and values in the installed release.

## Custom enchantments

The project provides 18 custom enchantments. They are registered as Paper registry entries during the UI-Datapack bootstrap phase; they are not ordinary datapack JSON enchantments.

| Enchantment | Namespace ID | Levels | Effect |
|---|---|---:|---|
| AoE | ui:aoe | 1–8 | Area damage around the hit target. |
| Attack AoE | ui:attack_aoe | 1–10 | Hits entities of the same type as the target; radius scales with level. |
| Auto Smelt | ui:autosmelt | 1–10 | Each level adds 10% smelt chance to dropped item stacks. |
| Vein Miner | ui:veinminer | 1–255 | Mines an ore vein. |
| TreeCapitator | ui:treecapitator | 1–255 | Fells a tree. |
| Flight | ui:flight | 1–255 | Provides jetpack-style flight with item-integrity cost. |
| Levitation | ui:levitation | 1 | Chestplate boost using a Levitation effect while jump is held. |
| Magnet | ui:magnet | 1–16 | Attracts nearby items; radius is 2 blocks per level (maximum 32). |
| Igniting | ui:igniting | 1–255 | Sets hit targets on fire. |
| Repairing | ui:repairing | 1–255 | Restores durability over time; higher levels restore more. |
| Lava Walker | ui:lava_walker | 1–16 | Creates a temporary obsidian-like crust on lava under the wearer. |
| Container Stealing | ui:container_stealing | 1–10 | Chance to obtain a broken container and its contents; level N = N×10%. |
| Item Stealing | ui:item_stealing | 1–10 | Chance to pull the target's held item; level N = N×10%. Permission-gated. |
| Self-Destruct | ui:self_destruct | 1–255 | Starts a 30-second countdown, then damages the owner and destroys the item. |
| Degradation | ui:degradation | 1–255 | Curse that degrades worn armor over time. |
| Blunting | ui:blunting | 1–255 | Curse that reduces the wielder's melee damage. |
| Vulnerability | ui:vulnerability | 1–255 | Curse that increases damage received by the wearer. |
| Disappearance | ui:disappearance | 1–255 | Curse that causes the held item to disappear during a timed sweep. |

Most enchantments support levels up to 255, but the strongest mechanics have their own caps as shown above. The Flight and Levitation mechanics consume item integrity while active. Flight conflicts with Repairing on the chest slot.

### Applying and configuring enchantments

Custom enchantments can be applied through supported enchanting-table slots, anvils or the command interface:

- **/ui enchant give <enchantment> <level>**
- **/ui enchant take <enchantment>**
- **/ui enchant check**

The commands act on the item in the player's hand. See **/ui help enchant** for the exact argument syntax in the installed build.

Configuration is in **UI-Enchantments.toml**: master switch, per-enchantment enabled switches, localized display names, maximum levels, anvil cost, active equipment slots and shared enchanting-table weight/cost settings. Registry entries are created at startup, so changes require a server restart. An item may retain the stored enchantment data after the enchantment is disabled, but its effect no longer runs.

The custom curses are deliberately not added to the vanilla curse tag, allowing them to be offered by the enchanting table.

## Custom achievements

The project ships a custom achievement tree under the ui: namespace. It begins at **ui:datapack/start** and has branches for server events, challenges, technology, research and reactor progression.

Achievements are granted by plugin code, while player progress is stored in vanilla advancement data.

### Timed challenges

- **/ui advancement start woodcutter**
- **/ui advancement start teleport**
- **/ui advancement stop**

Only one timed challenge can be active for a player at a time. Progress is displayed in the action bar.

### Configuration

**UI-Advancements.toml** contains:

- **advancements.enabled** — master switch;
- **advancements.<id>.enabled** — per-achievement switch;
- goal and time settings for challenges;
- selected thresholds for damage, memory pressure and server-freeze achievements.

Content registration occurs during startup. Restart after changing achievement enable flags or values that affect the registered tree. The implementation reattaches children to the nearest enabled ancestor when an intermediate achievement is disabled, preventing a broken parent chain.

## End-crystal turrets

Turrets use end crystals as ranged defensive structures.

1. Place an end crystal.
2. SHIFT + right-click the crystal to open its configuration interface.
3. Enable the turret (it is off by default).
4. Select whitelist or blacklist mode.
5. Add, remove, list or clear player/entity targets.

Equivalent command actions are available through **/ui turret**; see **/ui help turret** for the exact syntax.

Documented default behavior:

- targets are scanned within a 16 × 16 × 16 block area;
- the turret fires a beam that deals 1 damage per tick;
- armor mitigates the damage;
- a clear line of sight is required;
- materials listed in **turret.ignore_blocks** do not block the beam (glass and glass panes are included by default);
- the owner's online status does not determine whether the turret can operate.

Turret settings belong to **UI-Combat.toml**. If a material name is invalid, the implementation skips it and warns in the console.
