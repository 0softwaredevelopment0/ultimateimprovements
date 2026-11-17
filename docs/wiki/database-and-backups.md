# Database, persistence and backups

## Shared SQLite database

The UI family uses one shared SQLite database:

**plugins/UltimateImprovements/database.db**

All addons access the shared database layer in UI-Core. Do not expect a separate database file for every addon.

The database manager configures SQLite with:

- WAL journaling (journal_mode=WAL);
- synchronous=NORMAL;
- temporary storage in memory;
- foreign keys enabled;
- a 5,000 ms busy timeout;
- a configured negative cache size (SQLite interprets this as a kibibyte budget).

WAL mode can create companion files while the database is open:

- database.db-wal
- database.db-shm

These are part of SQLite's active database state and should not be casually deleted while the server is running.

## What is stored

The shared database is used for durable state across multiple feature areas, including:

- authentication users, sessions and lockouts;
- player homes and notes;
- player radiation and settings;
- punishment, warning and moderation records;
- whitelist/blacklist and reports;
- cable/energy network state;
- reactor and structure state;
- updater state;
- vanish and other player/module state.

The exact table set evolves with the project. Use the database migrations/schema in the source for the precise schema of a given release.

## Persistent versus transient state

Not every value shown by a machine is written to SQLite.

Examples documented by the project:

| State | Persistence |
|---|---|
| Player homes and notes | Stored persistently |
| Authentication sessions/lockouts | Stored persistently |
| Punishment and report records | Stored persistently |
| Energy battery charge and selected structure state | Stored persistently |
| Reactor anchor and thermal state | Stored persistently |
| Compact nuclear reactor local output buffer | RAM-only; lost on restart |
| Some generator/local machine buffers | RAM-only; check the feature docs |
| In-memory sample history for TPS/MSPT/RAM/ping placeholders | Rebuilt after startup; not a historical metrics database |

Do not assume that a visible buffer or counter survives a restart. Consult the feature documentation and code before designing automation around persistence.

## Safe backup procedure

### Recommended full maintenance backup

1. Announce maintenance and stop new player activity.
2. Stop the Minecraft server cleanly.
3. Wait for the Java process to exit.
4. Back up the entire plugins/UltimateImprovements/ folder.
5. Back up the server's world folders and player data separately.
6. Save the exact set of 21 UI JARs used with that data backup.
7. Store the backup off-host and verify that it can be read.

Backing up the full shared folder captures the database, TOML configs, logs and loose addon files. Backing up only database.db may omit recent WAL transactions if the server is still open.

### Online backups

When a live backup is required, use SQLite's online backup API or a documented SQLite-aware backup tool. Do not simply copy the main database file while ignoring an active WAL file. The server's own database lifecycle should remain in control of the connection.

## Restore

1. Stop the server.
2. Preserve a copy of the current broken/target data folder for later inspection.
3. Restore the complete backup that matches the intended JAR family.
4. Ensure the server OS user can read/write the restored files.
5. Start the server and inspect the console for database/schema migration errors.
6. Verify a few persistent records in-game (for example, a home, punishment record or stored machine state).

Avoid mixing a newer database with older plugin binaries unless the release notes explicitly support that rollback. Schema migrations may not be reversible.

## Config and player-data backups

The config manager creates numbered backups of broken TOML files when parsing fails, and the offline inventory editor creates per-player data backups before saving offline inventory changes. These are recovery aids, not substitutes for regular off-host backups.

The plugin's shared folder is separate from world player-data files. Include both in a complete server backup.
