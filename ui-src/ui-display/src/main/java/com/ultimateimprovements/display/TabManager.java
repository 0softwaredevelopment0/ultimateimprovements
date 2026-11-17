package com.ultimateimprovements.display;

import com.ultimateimprovements.core.Main;
import com.ultimateimprovements.core.Permissions;
import com.ultimateimprovements.database.PlayerSettingsDB;
import com.ultimateimprovements.util.MessageUtil;
import com.ultimateimprovements.util.PlaceholderResolver;
import com.ultimateimprovements.core.hooks.CoreHooks;
import net.kyori.adventure.text.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.PlayerModelPart;
import net.minecraft.world.level.GameType;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;
import java.util.stream.Collectors;

/** * Manages the custom tab list:
 * <ul>
 *   <li>Header — text above the player list (MiniMessage + placeholders)</li>
 *   <li>Footer — text below the player list (MiniMessage + placeholders)</li>
 *   <li>PlayerList name — prefix/suffix before/after the name (ping, PAPI, etc.)</li>
 *   <li>Spectator tab visibility — spectators are listed as regular (non-italic)
 *       names only for viewers with {@code ui.tab.see_spectators} that are
 *       within {@code tab.spectator_view_radius}; everyone else does not see
 *       them in the tab at all</li>
 *   <li>Sort mode — sorts names in the tab (A-Z, Z-A, LuckPerms, OP)</li>
 * </ul>
 */
public class TabManager extends BukkitRunnable implements Listener {

    private static TabManager instance;
    private static boolean listenersRegistered = false;
    private boolean enabled;
    private List<String> headerLines;
    private List<String> footerLines;
    private boolean objectiveEnabled;
    private String objectivePrefix;
    private String objectiveSuffix;
    private String objectiveFormat;
    private boolean spectatorTabEnabled;
    private int spectatorViewRadius;
    private int intervalTicks;
    private int playerListIntervalTicks;
    private int playerListTickCounter;
    private SortMode sortMode;

    public enum SortMode {
        NONE,       // no sorting
        A_Z,        // alphabetical A-Z
        Z_A,        // alphabetical Z-A
        LUCKPERMS,  // by LuckPerms primary group
        OP          // OP players first, then non-OP
    }

    /**
     * Initializes TabManager. If it was already running — clean up the previous one.
     */
    public static void init() {
        // Clean up the previous BukkitRunnable (but NOT cancelTasks — that kills ALL plugin tasks)
        if (instance != null) {
            try { instance.cancel(); } catch (Exception ignored) {}
        }

        instance = new TabManager();

        // Register the Listener ONLY once for the whole plugin lifetime
        if (!listenersRegistered) {
            Bukkit.getPluginManager().registerEvents(instance, Main.getInstance());
            listenersRegistered = true;
        }

        instance.reloadConfig();

        // Apply the spectator tab policy to already-online players on start/reload
        if (instance.spectatorTabEnabled) {
            TabManager tab = instance;
            Bukkit.getScheduler().runTaskLater(Main.getInstance(), tab::applySpectatorTabStyles, 1L);
        }

        if (instance.enabled || instance.spectatorTabEnabled) {
            // A BukkitRunnable CANNOT be reused after cancel() — always a new instance
            instance.runTaskTimer(Main.getInstance(), 20L, instance.intervalTicks);
        }
    }

    public static void shutdown() {
        if (instance != null) {
            try { instance.cancel(); } catch (Exception ignored) {}
            instance = null;
        }
    }

    /**
     * Resets the listener registration flag (called from Main.onDisable()).
     * Needed so listeners get re-registered on the next plugin start (after /reload).
     */
    public static void resetListenerState() {
        listenersRegistered = false;
    }

    /**
     * Config reload. A BukkitRunnable can't be reused after cancel(),
     * so on reload we create a new instance via init().
     */
    public static void reload() {
        // Just re-initialize — a new BukkitRunnable is created
        init();
    }

    private void reloadConfig() {
        FileConfiguration config = Main.getInstance().getConfig();
        this.enabled = config.getBoolean("tab.enabled", false);
        this.headerLines = config.getStringList("tab.header");
        this.footerLines = config.getStringList("tab.footer");
        this.objectiveEnabled = config.getBoolean("tab.player_list.objective_enabled", false);
        this.objectivePrefix = config.getString("tab.player_list.objective_prefix", "");
        this.objectiveSuffix = config.getString("tab.player_list.objective_suffix", "");
        this.objectiveFormat = config.getString("tab.player_list.format", "");
        // Legacy key name: hides spectators from players that are not allowed
        // to see them; allowed viewers get a regular (non-italic) entry.
        this.spectatorTabEnabled = config.getBoolean("tab.hide_spectators", false);
        this.spectatorViewRadius = Math.max(0, config.getInt("tab.spectator_view_radius", 128));
        this.intervalTicks = Math.max(10, config.getInt("tab.update_interval_ticks", 20));
        this.playerListIntervalTicks = config.getInt("tab.player_list.update_interval_ticks", 0);
        // If 0 — updates at the same frequency as the header/footer
        if (playerListIntervalTicks <= 0) {
            playerListIntervalTicks = intervalTicks;
        } else {
            playerListIntervalTicks = Math.max(5, playerListIntervalTicks);
        }
        this.playerListTickCounter = 0;

        // Sort mode
        String sortStr = config.getString("tab.sort.mode", "none").toUpperCase().replace("-", "_");
        try {
            this.sortMode = SortMode.valueOf(sortStr);
        } catch (IllegalArgumentException e) {
            this.sortMode = SortMode.NONE;
        }
    }

    // ── Spectator tab visibility ──
    //
    // The vanilla client renders every tab entry whose game mode is SPECTATOR
    // in italics. The tab entry is also the only place where other players can
    // learn that someone is spectating.
    //
    // Policy per viewer:
    //   • the viewer has ui.tab.see_spectators (OP by default) and is within
    //     tab.spectator_view_radius blocks of the spectator → the entry is sent
    //     as SURVIVAL, so it stays in the tab and looks like a regular player;
    //   • otherwise → the entry is removed from that viewer's tab.
    // The spectator's own client is never touched, so their camera, no-clip and
    // entity spectating keep working, and vanishing still owns its own removal.

    private static final GameType TAB_GAME_MODE = GameType.SURVIVAL;

    /**
     * Re-applies the spectator tab policy for every spectator currently online.
     */
    private void applySpectatorTabStyles() {
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.getGameMode() == GameMode.SPECTATOR) {
                applySpectatorTabStyle(online);
            }
        }
    }

    /**
     * Applies the spectator tab policy of one spectator to every other viewer.
     * Idempotent: ADD/REMOVE packets are safe to repeat, which lets the periodic
     * pass repair entries re-sent by the server after dimension changes.
     */
    private void applySpectatorTabStyle(Player spectator) {
        ServerPlayer handle;
        try {
            handle = ((CraftPlayer) spectator).getHandle();
        } catch (Exception e) {
            return;
        }

        ClientboundPlayerInfoUpdatePacket normalEntry = buildGameModePacket(spectator, TAB_GAME_MODE);
        ClientboundPlayerInfoRemovePacket removeEntry =
                new ClientboundPlayerInfoRemovePacket(List.of(spectator.getUniqueId()));
        boolean vanished = CoreHooks.isVanished(spectator.getUniqueId());

        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer.getUniqueId().equals(spectator.getUniqueId())) continue;

            if (!vanished && isSpectatorVisibleInTab(viewer, spectator)) {
                try {
                    ServerPlayer viewerHandle = ((CraftPlayer) viewer).getHandle();
                    // The entry may have been removed for this viewer before;
                    // ADD_PLAYER restores it and is a no-op if it still exists.
                    sendPacket(viewer, ClientboundPlayerInfoUpdatePacket
                            .createPlayerInitializing(List.of(handle), viewerHandle));
                    if (normalEntry != null) sendPacket(viewer, normalEntry);
                } catch (Exception ignored) {}
            } else {
                sendPacket(viewer, removeEntry);
            }
        }
    }

    /**
     * Whether this viewer may see the spectator in the tab: permission plus the
     * configured radius (0 = no distance limit). Other worlds count as too far.
     */
    private boolean isSpectatorVisibleInTab(Player viewer, Player spectator) {
        if (!viewer.hasPermission(Permissions.UI_TAB_SEE_SPECTATORS)) return false;
        if (spectatorViewRadius <= 0) return true;
        if (!viewer.getWorld().equals(spectator.getWorld())) return false;

        double dx = viewer.getX() - spectator.getX();
        double dy = viewer.getY() - spectator.getY();
        double dz = viewer.getZ() - spectator.getZ();
        double radius = spectatorViewRadius;
        return dx * dx + dy * dy + dz * dz <= radius * radius;
    }

    /**
     * Re-adds the player to every viewer's tab with the real game mode after
     * they leave spectator mode. Viewers that never saw the spectator entry
     * still get the full ADD_PLAYER payload.
     */
    private void restoreRealTabEntry(Player player) {
        if (CoreHooks.isVanished(player.getUniqueId())) return; // vanish owns the entry
        try {
            ServerPlayer handle = ((CraftPlayer) player).getHandle();
            for (Player viewer : Bukkit.getOnlinePlayers()) {
                if (viewer.getUniqueId().equals(player.getUniqueId())) continue;
                ServerPlayer viewerHandle = ((CraftPlayer) viewer).getHandle();
                sendPacket(viewer, ClientboundPlayerInfoUpdatePacket
                        .createPlayerInitializing(List.of(handle), viewerHandle));
            }
        } catch (Exception ignored) {}
    }

    private static ClientboundPlayerInfoUpdatePacket buildGameModePacket(Player player, GameType gameMode) {
        try {
            ServerPlayer handle = ((CraftPlayer) player).getHandle();
            ClientboundPlayerInfoUpdatePacket.Entry entry = new ClientboundPlayerInfoUpdatePacket.Entry(
                    player.getUniqueId(),
                    handle.getGameProfile(),
                    true,
                    handle.connection.latency(),
                    gameMode,
                    handle.getTabListDisplayName(),
                    handle.isModelPartShown(PlayerModelPart.HAT),
                    handle.getTabListOrder(),
                    handle.getChatSession() == null ? null : handle.getChatSession().asData()
            );
            return new ClientboundPlayerInfoUpdatePacket(
                    EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE),
                    entry
            );
        } catch (Exception e) {
            return null;
        }
    }

    private static void sendPacket(Player viewer, Packet<?> packet) {
        try {
            ((CraftPlayer) viewer).getHandle().connection.send(packet);
        } catch (Exception ignored) {}
    }

    /**
     * Sends ClientboundPlayerInfoRemovePacket with a batch of all vanished UUIDs
     * to all online players (except the vanished themselves).
     */
    private static void batchRemoveVanishedFromTabList(List<UUID> vanishedUuids) {
        ClientboundPlayerInfoRemovePacket packet = new ClientboundPlayerInfoRemovePacket(vanishedUuids);
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (vanishedUuids.contains(online.getUniqueId())) continue;
            try {
                ((CraftPlayer) online).getHandle().connection.send(packet);
            } catch (Exception ignored) {}
        }
    }

    // ── Listeners ──
    // IMPORTANT: all event handlers use TabManager.getInstance() instead of this,
    // because after /ui reload Bukkit still holds a reference to the OLD instance
    // (listenersRegistered=true → registerEvents() isn't called again).
    // If handlers used this.spectatorTabEnabled, they'd read stale values from
    // the old object instead of the current one.

    @EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        TabManager tab = instance;
        if (tab == null || !tab.spectatorTabEnabled) return;

        Player player = event.getPlayer();
        GameMode newMode = event.getNewGameMode();
        GameMode oldMode = player.getGameMode();

        // Delay 1 tick so the game mode is actually applied
        Bukkit.getScheduler().runTaskLater(Main.getInstance(), () -> {
            if (!player.isOnline()) return;

            if (newMode == GameMode.SPECTATOR && oldMode != GameMode.SPECTATOR) {
                // Switched to spectator — hide from unauthorized viewers, show
                // the allowed ones a regular (non-italic) entry
                tab.applySpectatorTabStyle(player);
            } else if (oldMode == GameMode.SPECTATOR && newMode != GameMode.SPECTATOR) {
                // Left spectator — viewers that had no entry need it back
                tab.restoreRealTabEntry(player);
            }
        }, 1L);
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerJoin(PlayerJoinEvent event) {
        TabManager tab = instance;
        if (tab == null || !tab.spectatorTabEnabled) return;

        // The initial player-info snapshot carries the real (italic/listed)
        // spectator entries, so the whole policy has to be re-applied to the
        // new viewer and (if they are a spectator) to everyone else.
        Bukkit.getScheduler().runTaskLater(Main.getInstance(), () -> {
            if (event.getPlayer().isOnline()) {
                tab.applySpectatorTabStyles();
            }
        }, 1L);
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        TabManager tab = instance;
        if (tab == null || !tab.spectatorTabEnabled) return;

        // A dimension change re-syncs player info; also changes the distance check
        Bukkit.getScheduler().runTaskLater(Main.getInstance(), () -> {
            if (event.getPlayer().isOnline()) {
                tab.applySpectatorTabStyles();
            }
        }, 2L);
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        TabManager tab = instance;
        if (tab == null || !tab.spectatorTabEnabled) return;

        Bukkit.getScheduler().runTaskLater(Main.getInstance(), () -> {
            if (event.getPlayer().isOnline()) {
                tab.applySpectatorTabStyles();
            }
        }, 2L);
    }

    @Override
    public void run() {
        if (enabled) {
            runTab();
        }
        // The spectator policy has its own switch and needs periodic updates
        // because it depends on movement (tab.spectator_view_radius).
        if (spectatorTabEnabled) {
            applySpectatorTabStyles();
        }
    }

    /** Header/footer, player-list names, sorting and vanish upkeep. */
    private void runTab() {
        List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
        sortPlayers(players);

        // playerListTickCounter tracks REAL ticks, not run() calls
        // run() is called every intervalTicks ticks
        playerListTickCounter += intervalTicks;
        boolean updatePlayerList = (playerListTickCounter >= playerListIntervalTicks);
        if (updatePlayerList) {
            playerListTickCounter = 0;
        }

        for (Player player : players) {
            if (player == null || !player.isOnline()) continue;

            // Per-player header/footer (with player-specific placeholders)
            // Updated every tick (every intervalTicks)
            Component playerHeader = buildComponent(headerLines, player);
            Component playerFooter = buildComponent(footerLines, player);
            player.sendPlayerListHeaderAndFooter(playerHeader, playerFooter);

            // Player list name — updated on a separate interval
            if (objectiveEnabled && updatePlayerList) {
                if (!objectiveFormat.isEmpty()) {
                    // Custom format — full control: %luckperms_prefix%%player_name%...
                    String resolved = PlaceholderResolver.resolve(objectiveFormat, player);
                    player.playerListName(MessageUtil.parse(resolved));
                } else {
                    // Old logic: prefix + name + suffix
                    String prefix = PlaceholderResolver.resolve(objectivePrefix, player);
                    String suffix = PlaceholderResolver.resolve(objectiveSuffix, player);

                    Component prefixComp = prefix.isEmpty() ? Component.empty() : MessageUtil.parse(prefix);
                    Component nameComp = Component.text(player.getName());
                    Component suffixComp = suffix.isEmpty() ? Component.empty() : MessageUtil.parse(suffix);

                    player.playerListName(prefixComp.append(nameComp).append(suffixComp));
                }
            }
        }

        // Apply sorting via playerListOrder (Paper API)
        // Sorting updates every tick because players join/leave
        if (sortMode != SortMode.NONE) {
            applySortOrder(players);
        }

        // Re-hide vanished players — any tab operations may
        // make the client re-add the vanished to the tab list
        List<UUID> vanishedUuids = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p != null && CoreHooks.isVanished(p.getUniqueId())) {
                vanishedUuids.add(p.getUniqueId());
            }
        }
        if (!vanishedUuids.isEmpty()) {
            batchRemoveVanishedFromTabList(vanishedUuids);
        }
    }

    /**
     * Builds a Component from a list of MiniMessage strings + placeholders.
     */
    private Component buildComponent(List<String> lines, Player player) {
        if (lines == null || lines.isEmpty()) return Component.empty();

        String joined = String.join("\n", lines);
        String resolved = PlaceholderResolver.resolve(joined, player);
        return MessageUtil.parse(resolved);
    }

    // =========================
    // TAB SORTING
    // =========================

    /**
     * Sorts the player list according to sortMode.
     */
    private void sortPlayers(List<Player> players) {
        switch (sortMode) {
            case A_Z -> players.sort(Comparator.comparing(Player::getName));
            case Z_A -> players.sort(Comparator.comparing(Player::getName).reversed());
            case OP -> players.sort((a, b) -> {
                boolean aOp = a.isOp();
                boolean bOp = b.isOp();
                if (aOp == bOp) return a.getName().compareToIgnoreCase(b.getName());
                return aOp ? -1 : 1;
            });
            case LUCKPERMS -> {
                // Sort by LuckPerms primary group (weight), fallback to name
                players.sort((a, b) -> {
                    int aWeight = getLuckPermsWeight(a);
                    int bWeight = getLuckPermsWeight(b);
                    if (aWeight != bWeight) return Integer.compare(bWeight, aWeight); // higher weight first
                    return a.getName().compareToIgnoreCase(b.getName());
                });
            }
        }
    }

    /**
     * Applies the sort order using Paper's playerListOrder API.
     * Uses the index in the sorted list as the order value.
     */
    private void applySortOrder(List<Player> sortedPlayers) {
        for (int i = 0; i < sortedPlayers.size(); i++) {
            Player player = sortedPlayers.get(i);
            try {
                // Paper API: setPlayerListOrder(int) controls tab list position
                player.setPlayerListOrder(i);
            } catch (Exception ignored) {
                // Fallback: older Paper versions may not have this method
            }
        }
    }

    /**
     * Gets LuckPerms primary group weight via PAPI placeholder.
     * Falls back to 0 if not available.
     */
    private int getLuckPermsWeight(Player player) {
        if (!PlaceholderResolver.isPapiAvailable()) return 0;
        String weightStr = PlaceholderResolver.resolve("%luckperms_primary_group_weight%", player);
        if (weightStr == null || weightStr.isEmpty() || weightStr.equals("%luckperms_primary_group_weight%")) {
            return 0;
        }
        try {
            return Integer.parseInt(weightStr);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
