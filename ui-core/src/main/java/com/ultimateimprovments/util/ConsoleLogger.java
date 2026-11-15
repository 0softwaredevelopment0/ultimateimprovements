package com.ultimateimprovments.util;

import com.ultimateimprovments.config.AddonConfigManager;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;

/**
 * Colourful console logger using MiniMessage and Paper's Adventure API.
 * <p>
 * Colour scheme:
 * <ul>
 *   <li>{@link #info(String)} — <white>white</white> (informational messages)</li>
 *   <li>{@link #success(String)} — <green>green</green> (successful operations)</li>
 *   <li>{@link #warn(String)} — <yellow>yellow</yellow> (warnings)</li>
 *   <li>{@link #error(String)} — <red>red</red> (errors)</li>
 * </ul>
 * <p>
 * Uses {@link Bukkit#getConsoleSender()} with {@link MiniMessage} — prints coloured text
 * to the console (with ANSI/Virtual Terminal support).
 */
public final class ConsoleLogger {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static boolean initialized = false;

    /** Config key gating the diagnostic startup/world-load scan messages. */
    private static final String DEBUG_STARTUP_SCANS = "debug.startup_scans";

    /** Cached {@code debug.startup_scans} value, refreshed by {@link #refreshDebugFlags()}. */
    private static volatile boolean startupScanLogs = false;

    private ConsoleLogger() {}

    /**
     * Initializes the logger. Must be called in onEnable() after instance = this.
     */
    public static void init() {
        initialized = true;
    }

    /**
     * Re-reads the diagnostic switches from the config (called after the config
     * backend has loaded, and again on every {@code /ui reload}).
     */
    public static void refreshDebugFlags() {
        try {
            Object value = AddonConfigManager.get(DEBUG_STARTUP_SCANS);
            startupScanLogs = value instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(value));
        } catch (Throwable t) {
            startupScanLogs = false;
        }
    }

    /**
     * {@code [debug]} — diagnostic message printed only when the matching config
     * switch ({@code debug.startup_scans}) is enabled.
     */
    public static void debug(String message) {
        if (!initialized || !startupScanLogs) return;
        Bukkit.getConsoleSender().sendMessage(MM.deserialize(
                MessageUtil.PREFIX + "<dark_gray>[debug]</dark_gray> <gray>" + escape(message) + "</gray>"));
    }

    /** <white>White</white> — informational messages (prefixed with {@link MessageUtil#PREFIX}) */
    public static void info(String message) {
        if (!initialized) return;
        Bukkit.getConsoleSender().sendMessage(MM.deserialize(MessageUtil.PREFIX + "<white>" + escape(message) + "</white>"));
    }

    /** <green>Green</green> — successful operations (prefixed with {@link MessageUtil#PREFIX}) */
    public static void success(String message) {
        if (!initialized) return;
        Bukkit.getConsoleSender().sendMessage(MM.deserialize(MessageUtil.PREFIX + "<green>" + escape(message) + "</green>"));
    }

    /** <yellow>Yellow</yellow> — warnings (prefixed with {@link MessageUtil#PREFIX}) */
    public static void warn(String message) {
        if (!initialized) return;
        Bukkit.getConsoleSender().sendMessage(MM.deserialize(MessageUtil.PREFIX + "<yellow>" + escape(message) + "</yellow>"));
    }

    /** <red>Red</red> — errors (prefixed with {@link MessageUtil#PREFIX}) */
    public static void error(String message) {
        if (!initialized) return;
        Bukkit.getConsoleSender().sendMessage(MM.deserialize(MessageUtil.PREFIX + "<red>" + escape(message) + "</red>"));
    }

    /**
     * Logs a fully-formatted MiniMessage string — tags are interpreted and
     * rendered as colors (Paper console with ANSI/VT support). The plugin
     * prefix ({@link MessageUtil#PREFIX}) is prepended automatically.
     */
    public static void log(String miniMessage) {
        if (!initialized) return;
        Bukkit.getConsoleSender().sendMessage(MM.deserialize(MessageUtil.PREFIX + miniMessage));
    }

    /**
     * Raw MiniMessage message without escaping and WITHOUT the plugin prefix
     * (for ASCII banners, gradients, etc.).
     * Warning: tags in message will be interpreted as MiniMessage!
     */
    public static void raw(String miniMessage) {
        if (!initialized) return;
        Bukkit.getConsoleSender().sendMessage(MM.deserialize(miniMessage));
    }

    /**
     * Escapes MiniMessage-sensitive characters,
     * so the message content is not interpreted as tags.
     */
    private static String escape(String message) {
        if (message == null) return "";
        // Escape MiniMessage tags and strip legacy §-codes — MiniMessage
        // throws on § and should never receive one.
        return message.replace("<", "\\<")
                .replace(">", "\\>")
                .replaceAll("\u00A7[0-9a-fk-orx]", "")
                .replace("\u00A7", "");
    }
}
