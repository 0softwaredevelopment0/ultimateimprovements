package com.ultimateimprovements.display;

import com.ultimateimprovements.core.Main;
import com.ultimateimprovements.database.PlayerSettingsDB;
import com.ultimateimprovements.util.MessageUtil;
import com.ultimateimprovements.util.PlaceholderResolver;
import com.ultimateimprovements.core.hooks.CoreHooks;
import net.kyori.adventure.text.Component;
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
 *   <li>Spectator tab style — spectators stay in the tab, but their entry is
 *       rendered as a regular (non-italic) name instead of the client's
 *       italic spectator style</li>
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
    private boolean normalizeSpectatorTab;
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

        // Fix the tab style of already-online spectators on start/reload
        if (instance.normalizeSpectatorTab) {
            Bukkit.getScheduler().runTaskLater(Main.getInstance(), () -> {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (p.getGameMode() == GameMode.SPECTATOR) {
                        sendNormalSpectatorTabEntry(p);
                    }
                }
            }, 1L);
        }

        if (instance.enabled) {
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
        // Legacy key name: the feature no longer hides spectators, it only
        // replaces their italic tab entry with a regular one.
        this.normalizeSpectatorTab = config.getBoolean("tab.hide_spectators", false);
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

    // ── Spectator tab style ──
    //
    // The vanilla client renders every tab entry whose game mode is SPECTATOR
    // in italics. Removing the player from the tab list (what this module used
    // to do) made spectators invisible there, which is not what the feature is
    // about. Instead the server tells the other clients that the spectator is
    // in SURVIVAL mode: the entry stays in the tab and looks like any regular
    // player. The spectator's own client is never touched, so their camera,
    // no-clip and entity spectating keep working.

    private static final GameType TAB_GAME_MODE = GameType.SURVIVAL;

    /**
     * Sends the normal (non-italic) tab entry of a spectator to every other player.
     */
    private static void sendNormalSpectatorTabEntry(Player spectator) {
        ClientboundPlayerInfoUpdatePacket packet = buildGameModePacket(spectator, TAB_GAME_MODE);
        if (packet == null) return;
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.getUniqueId().equals(spectator.getUniqueId())) continue;
            sendPacket(online, packet);
        }
    }

    /**
     * Restores the real game mode in the tab entry after the player leaves spectator mode.
     */
    private static void restoreRealTabEntry(Player player, GameMode mode) {
        ClientboundPlayerInfoUpdatePacket packet = buildGameModePacket(player, toGameType(mode));
        if (packet == null) return;
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.getUniqueId().equals(player.getUniqueId())) continue;
            sendPacket(online, packet);
        }
    }

    /**
     * Sends one spectator's normal tab entry to a single viewer (used on join).
     */
    private static void sendNormalSpectatorTabEntryTo(Player viewer, Player spectator) {
        if (viewer.getUniqueId().equals(spectator.getUniqueId())) return;
        ClientboundPlayerInfoUpdatePacket packet = buildGameModePacket(spectator, TAB_GAME_MODE);
        if (packet == null) return;
        sendPacket(viewer, packet);
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

    private static void sendPacket(Player viewer, ClientboundPlayerInfoUpdatePacket packet) {
        try {
            ((CraftPlayer) viewer).getHandle().connection.send(packet);
        } catch (Exception ignored) {}
    }

    private static GameType toGameType(GameMode mode) {
        return switch (mode) {
            case CREATIVE -> GameType.CREATIVE;
            case ADVENTURE -> GameType.ADVENTURE;
            case SPECTATOR -> GameType.SPECTATOR;
            default -> GameType.SURVIVAL;
        };
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
    // If handlers used this.normalizeSpectatorTab, they'd read stale values from
    // the old object instead of the current one.

    @EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        TabManager tab = instance;
        if (tab == null || !tab.normalizeSpectatorTab) return;

        Player player = event.getPlayer();
        GameMode newMode = event.getNewGameMode();
        GameMode oldMode = player.getGameMode();

        // Delay 1 tick so the game mode is actually applied
        Bukkit.getScheduler().runTaskLater(Main.getInstance(), () -> {
            if (!player.isOnline()) return;

            if (newMode == GameMode.SPECTATOR && oldMode != GameMode.SPECTATOR) {
                // Switched to spectator — keep the entry, drop the italic style
                sendNormalSpectatorTabEntry(player);
            } else if (oldMode == GameMode.SPECTATOR && newMode != GameMode.SPECTATOR) {
                // Left spectator — restore the real game mode (and its tab style)
                restoreRealTabEntry(player, newMode);
            }
        }, 1L);
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerJoin(PlayerJoinEvent event) {
        TabManager tab = instance;
        if (tab == null || !tab.normalizeSpectatorTab) return;

        Player player = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(Main.getInstance(), () -> {
            if (!player.isOnline()) return;

            // If the new player is a spectator — give them a normal tab entry everywhere
            if (player.getGameMode() == GameMode.SPECTATOR) {
                sendNormalSpectatorTabEntry(player);
            }

            // Fix the tab entries of the spectators this player can already see:
            // the initial player-info snapshot carries their real (italic) game mode
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (online.getGameMode() == GameMode.SPECTATOR) {
                    sendNormalSpectatorTabEntryTo(player, online);
                }
            }
        }, 1L);
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        TabManager tab = instance;
        if (tab == null || !tab.normalizeSpectatorTab) return;

        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.SPECTATOR) {
            // On world change the client may re-read the tab entry — fix it again
            Bukkit.getScheduler().runTaskLater(Main.getInstance(), () -> {
                if (player.isOnline() && player.getGameMode() == GameMode.SPECTATOR) {
                    sendNormalSpectatorTabEntry(player);
                }
            }, 2L);
        }
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        TabManager tab = instance;
        if (tab == null || !tab.normalizeSpectatorTab) return;

        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.SPECTATOR) {
            // On respawn the client may re-read the tab entry — fix it again
            Bukkit.getScheduler().runTaskLater(Main.getInstance(), () -> {
                if (player.isOnline() && player.getGameMode() == GameMode.SPECTATOR) {
                    sendNormalSpectatorTabEntry(player);
                }
            }, 2L);
        }
    }

    @Override
    public void run() {
        if (!enabled) return;

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

        // Re-apply the normal tab style of spectators — setPlayerListOrder()
        // and the player-info re-sync on dimension changes may hand the client
        // the real SPECTATOR game mode again, which turns the entry italic
        if (normalizeSpectatorTab) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p != null && p.getGameMode() == GameMode.SPECTATOR) {
                    sendNormalSpectatorTabEntry(p);
                }
            }
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
