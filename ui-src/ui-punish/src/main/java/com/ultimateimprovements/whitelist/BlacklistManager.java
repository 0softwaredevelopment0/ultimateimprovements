package com.ultimateimprovements.whitelist;

import com.ultimateimprovements.core.Main;
import com.ultimateimprovements.database.DatabaseManager;
import com.ultimateimprovements.punish.PunishmentMessages;
import com.ultimateimprovements.util.MessageUtil;
import com.ultimateimprovements.util.ConsoleLogger;
import com.ultimateimprovements.util.PlayerLookup;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerLoginEvent;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

/**
 * BlacklistManager — the UltimateImprovements blacklist.
 * <p>
 * Can be enabled together with the whitelist.
 * Blacklisted players cannot join the server under any circumstances.
 * <p>
 * Commands: /ui blacklist on/off/add/list/remove
 */
public class BlacklistManager implements Listener {

    private static boolean enabled = false;

    // =========================
    // INIT
    // =========================
    public static void init(Main plugin) {
        load();
        plugin.getServer().getPluginManager().registerEvents(new BlacklistManager(), plugin);
    }

    // =========================
    // LOAD
    // =========================
    public static void load() {
        try (Connection con = DatabaseManager.getConnection();
             PreparedStatement st = con.prepareStatement(
                     "SELECT value FROM blacklist_meta WHERE key = ?")) {
            st.setString(1, "enabled");
            try (ResultSet rs = st.executeQuery()) {
                if (rs.next()) {
                    enabled = Boolean.parseBoolean(rs.getString("value"));
                }
            }

            int count = 0;
            try (PreparedStatement cnt = con.prepareStatement("SELECT COUNT(*) FROM blacklist");
                 ResultSet rs = cnt.executeQuery()) {
                if (rs.next()) count = rs.getInt(1);
            }

            ConsoleLogger.info("[Blacklist] Loaded " + count + " players, enabled=" + enabled);
        } catch (Exception e) {
            Main.getInstance().getLogger().log(Level.WARNING, "[Blacklist] Failed to load", e);
        }
    }

    // =========================
    // GETTERS
    // =========================
    public static boolean isEnabled() {
        return enabled;
    }

    public static List<String> getBlacklistNames() {
        List<String> result = new ArrayList<>();
        try (Connection con = DatabaseManager.getConnection();
             PreparedStatement st = con.prepareStatement(
                     "SELECT player_name FROM blacklist ORDER BY player_name");
             ResultSet rs = st.executeQuery()) {
            while (rs.next()) {
                result.add(rs.getString("player_name"));
            }
        } catch (SQLException e) {
            Main.getInstance().getLogger().log(Level.WARNING, "[Blacklist] Failed to list", e);
        }
        return result;
    }

    // =========================
    // ADD / REMOVE
    // =========================
    public static boolean add(String playerName) {
        if (playerName == null || playerName.isBlank()) return false;
        // Store the nickname as typed (display casing); match it case-insensitively.
        String name = playerName.trim();

        try (Connection con = DatabaseManager.getConnection();
             PreparedStatement st = con.prepareStatement(
                     "INSERT INTO blacklist (player_name) "
                             + "SELECT ? WHERE NOT EXISTS "
                             + "(SELECT 1 FROM blacklist WHERE player_name = ? COLLATE NOCASE)")) {
            st.setString(1, name);
            st.setString(2, name);
            int rows = st.executeUpdate();
            if (rows > 0) {
                ConsoleLogger.info("[Blacklist] Added: " + name);
            }

            // If the player is online — kick them. This also covers a repeated
            // add for an entry that already exists under a different casing.
            Player online = PlayerLookup.online(name);
            if (online != null && online.isOnline()) {
                online.kick(MessageUtil.parse(PunishmentMessages.buildBlacklistKickMessage(
                        online.getName(), PunishmentMessages.getDiscordUrl())));
            }
            return rows > 0;
        } catch (SQLException e) {
            Main.getInstance().getLogger().log(Level.WARNING, "[Blacklist] Failed to add: " + name, e);
            return false;
        }
    }

    public static boolean remove(String playerName) {
        if (playerName == null || playerName.isBlank()) return false;
        String name = playerName.trim();

        try (Connection con = DatabaseManager.getConnection();
             PreparedStatement st = con.prepareStatement(
                     "DELETE FROM blacklist WHERE player_name = ? COLLATE NOCASE")) {
            st.setString(1, name);
            int rows = st.executeUpdate();
            if (rows > 0) {
                ConsoleLogger.info("[Blacklist] Removed: " + name);
                return true;
            }
            return false;
        } catch (SQLException e) {
            Main.getInstance().getLogger().log(Level.WARNING, "[Blacklist] Failed to remove: " + name, e);
            return false;
        }
    }

    // =========================
    // TOGGLE
    // =========================
    public static boolean setEnabled(boolean val) {
        if (enabled == val) return false;
        enabled = val;

        try (Connection con = DatabaseManager.getConnection();
             PreparedStatement st = con.prepareStatement(
                     "INSERT OR REPLACE INTO blacklist_meta (key, value) VALUES (?, ?)")) {
            st.setString(1, "enabled");
            st.setString(2, String.valueOf(val));
            st.executeUpdate();
        } catch (SQLException e) {
            Main.getInstance().getLogger().log(Level.WARNING, "[Blacklist] Failed to save state", e);
        }
        return true;
    }

    // =========================
    // CHECK
    // =========================
    public static boolean isBlacklisted(String playerName) {
        if (playerName == null || playerName.isBlank()) return false;
        String name = playerName.trim();
        try (Connection con = DatabaseManager.getConnection();
             PreparedStatement st = con.prepareStatement(
                     "SELECT 1 FROM blacklist WHERE player_name = ? COLLATE NOCASE")) {
            st.setString(1, name);
            try (ResultSet rs = st.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            Main.getInstance().getLogger().log(Level.FINE, "[Blacklist] Check error for: " + name, e);
            return false;
        }
    }

    // =========================
    // JOIN EVENT
    // =========================
    // PlayerLoginEvent is deprecated in Paper 26.3 (async pre-login is the modern hook) -
    // migration is a threading change and needs a dedicated tested task.
    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerLogin(PlayerLoginEvent e) {
        if (!enabled) return;

        String playerName = e.getPlayer().getName();
        if (isBlacklisted(playerName)) {
            e.disallow(PlayerLoginEvent.Result.KICK_BANNED,
                    MessageUtil.parse(PunishmentMessages.buildBlacklistKickMessage(
                                playerName, PunishmentMessages.getDiscordUrl())));
        }
    }
}
