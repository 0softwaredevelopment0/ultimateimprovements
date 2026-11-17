# Energy network and multiblock structures

UltimateImprovements includes an energy system, several multiblock structures and machinery that consumes, stores or generates energy.

## Energy concepts

The energy system is a network of connected nodes:

- **Cables** transfer energy between structures.
- **Batteries** store energy.
- **Generators/reactors** add energy to the network.
- **Consumers** draw energy for machine operation.
- **Multimeter** and **Structure Indicator** expose diagnostic information in-game.

The energy implementation lives primarily in UI-Energy. Structure templates and multiblock validation live in UI-MBS. Their interaction is mediated by a bridge API, so UI-MBS does not need to depend directly on UI-Energy.

## Cables

The documented cable materials are:

- waxed lightning rod — straight cable;
- waxed chiseled copper — corner/junction cable.

The network tracks connections and transfers energy between connected nodes. Structure assembly and energy rebuilding may run on startup/world-load passes; diagnostic messages are normally disabled to avoid unnecessary console noise.

## Batteries

A battery is an assembled structure, not a single isolated copper block.

- Attach a one-shot item frame to a waxed copper grate.
- Connected copper grates are treated as one adaptive battery.
- Capacity is 100,000 energy per block by default (**energy.battery.max_energy**).
- Blocks in one battery cluster share one energy pool and one mode.
- A redstone pulse on any block cycles Charge → Charge & Discharge → Discharge.
- A genuine redstone power transition is used; adjacent indirect power and the charging operation itself are ignored.
- Mode and stored energy state are persisted.

An isolated grate that has not been assembled is inert.

## Generators and consumers

The suite includes:

- coal/fuel generator;
- compact nuclear reactor;
- electric furnace;
- energy workbench;
- electric lamps;
- batteries and cables.

The coal-style generator is documented as a redstone-gated generator producing 10 energy/second while burning fuel. It consumes one fuel item per 10 seconds and stores unused output in a local buffer. A full buffer pauses fuel consumption until a consumer draws energy.

### Electric lamps

An electric lamp is assembled from connected waxed copper bulbs and waxed chiseled copper junctions. A valid structure requires at least one bulb and one junction.

- Each lamp consumes 1 energy/second.
- The structure has a short local buffer (five seconds per lamp).
- Every junction must be powered by redstone; one unpowered junction turns the entire lamp structure off.
- Junctions are also energy ports. Use the documented cable connection rules when routing the grid to the fixture.
- Breaking a structure block dismantles the structure.

## Compact nuclear reactor

The compact nuclear reactor is a 3×3×3 multiblock structure using chiseled deepslate, copper, glass and reinforced deepslate.

### Assembly and operation

1. Build the complete 3×3×3 template.
2. Put the one-shot item frame on a top corner so the top barrel remains accessible.
3. Use the top-center barrel for emerald fuel and ice coolant.
4. Apply redstone power to the center reinforced-deepslate block.
5. Connect a cable below the bottom copper energy port.

Documented output is 100 energy/second. The fuel interval is 10 seconds per emerald. Generated energy can be delivered to the network or accumulated in the reactor's local output buffer.

### Buffer and load behavior

The default local output buffer is 2,000 energy (**buffer_capacity**). It is associated with the reactor's copper port and is visible through the Multimeter. It is RAM-only and does not survive a server restart.

If the network cannot accept energy, output accumulates in the local buffer. When both the network and the local buffer have no remaining capacity, the reactor stops consuming fuel and running. A consumer can draw energy from the buffer through the connected network.

Keep the buffer capacity appropriate for the installation. If no consumers are connected, the reactor can continue generating and heating until its buffer is full.

### Temperature, coolant and meltdown

- The operating temperature rises toward 512° while running.
- At the working temperature, coolant is consumed to maintain operation.
- If coolant is exhausted, the temperature can rise toward the 1,024° limit.
- At the limit the core becomes lava and a 20-second detonation countdown starts.
- Restoring reinforced deepslate in the core during the countdown cancels the blast and allows the reactor to continue in its recovery state.
- Removing the lava or replacing it with a different block cancels the immediate blast but dismantles the reactor, requiring a rebuild.

Coolant consumption depends on ice type: plain ice gives 1%, packed ice 2%, blue ice 3% per consumed block under the documented buffer conditions. The coolant buffer stops consuming ice when full.

Breaking the core can cause an explosion outside the described meltdown escape-hatch flow. Treat the structure as hazardous and keep a safe area around it.

The structure's anchor and thermal state are persisted in SQLite; the local output buffer is not.

The **[nuclear_reactor]** section is in **UI-MBS.toml**. It contains output, fuel, buffer, thermal, cooling, meltdown, audit and alert settings. The template comments and validated config rules define exact accepted values.

## Dark Fusion Reactor (DFC)

The Dark Fusion Reactor is a larger multiblock with a temperature/pressure/integrity simulation. The implementation includes:

- core and case temperatures;
- pressure and shell/case integrity;
- heating/cooling modes controlled by redstone;
- diamond/gold-block fuel;
- recipe progress that can produce ancient debris;
- time-dependent wear and degradation;
- self-destruct/meltdown states;
- radiation emission at high temperatures.

Use:

- **/ui str dfc assemble**
- **/ui str dfc stats**

The DFC's shield-failure countdown is configurable. The current guide documents a 20-second default with warning announcements at T-20, T-15, T-10 and T-5. The temperature/pressure model and alerts are configured in **UI-MBS.toml**.

## Magnet and lightning structures

The structure command family also exposes magnet and lightning assemblies:

- **/ui str magnet assemble**
- **/ui str magnet stats**
- **/ui str lightning enable**
- **/ui str lightning disable**
- **/ui str lightning stats**

The magnet attracts metallic items based on its configured structure. The lightning structure provides controlled lightning strikes. Exact templates and settings are bundled with UI-MBS and its TOML config.

## Diagnostics

- SHIFT + right-click a supported block with a **Multimeter** to read energy parameters.
- SHIFT + right-click a recognized structure with a **Structure Indicator** to see structure-specific diagnostics such as component counts, reactor temperatures, integrity and state.
- Check the console for UI-MBS/UI-Energy startup messages.
- Temporarily enable **debug.startup_scans** in UI-Core.toml when investigating structure/energy discovery; disable it again after diagnosis.

Do not use the multimeter as a substitute for the Structure Indicator: they report different layers of information.
