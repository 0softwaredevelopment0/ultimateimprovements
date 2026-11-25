package com.ultimateimprovements.command.subcommands;

import com.ultimateimprovements.command.CommandErrors;
import com.ultimateimprovements.command.SubCommand;
import com.ultimateimprovements.core.Main;
import com.ultimateimprovements.core.Permissions;
import com.ultimateimprovements.core.SubCommandInfo;
import com.ultimateimprovements.util.ConsoleLogger;
import com.ultimateimprovements.util.MessageUtil;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code /ui uninstall} — removes every {@code UI-*.jar} of the plugin family
 * from the plugins directory (and staged {@code UI-*.jar} files from
 * {@code UltimateImprovements/update/}).
 *
 * <p>The command is registered unconditionally. It is disabled by default
 * ({@code uninstall.enabled = false} in UI-Other.toml) and then answers with
 * error {@code 018} ("the command belongs to a disabled module").
 *
 * <p>Access control (all must pass):
 * <ol>
 *   <li>{@code ui.command.uninstall} — registered with
 *       {@code PermissionDefault.FALSE}, so neither players nor OPs get it
 *       out of the box (a permissions plugin must grant it explicitly);</li>
 *   <li>the nickname list from UI-Other.toml — {@code uninstall.mode}
 *       ({@code whitelist} by default, or {@code blacklist}) and
 *       {@code uninstall.players} (case-insensitive). The console is exempt
 *       from the nickname list;</li>
 *   <li>sudo mode — {@code ui uninstall} is listed in
 *       {@code sudo.dangerous_commands} (UI-Guard.toml), so players need an
 *       active sudo session (error {@code 003} without {@code ui.sudo}).</li>
 * </ol>
 *
 * <p>JAR deletion is best effort: on Windows the JARs of running plugins are
 * locked by the JVM, so the command reports them and the server has to be
 * stopped to remove them. On Linux the files are unlinked immediately; the
 * family keeps running until the next server restart.
 */
@SubCommandInfo(name = "uninstall")
public final class UninstallSubcommand implements SubCommand {

    private static final String PERMISSION = Permissions.CMD_UNINSTALL;

    @Override
    public String getName() {
        return "uninstall";
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (!sender.hasPermission(PERMISSION)) {
            CommandErrors.noPermission(sender, PERMISSION);
            return true;
        }

        Main main = Main.getInstance();
        if (main == null || !main.getConfig().getBoolean("uninstall.enabled", false)) {
            CommandErrors.moduleDisabled(sender, "uninstall");
            return true;
        }

        if (sender instanceof Player player && !isAllowed(player.getName())) {
            CommandErrors.noPermission(sender, PERMISSION);
            return true;
        }

        if (args.length > 1) {
            CommandErrors.syntaxError(sender);
            return true;
        }

        File pluginsDir = main.getDataFolder().getParentFile();
        File updateDir = new File(main.getDataFolder(), "update");

        List<String> removed = new ArrayList<>();
        List<String> failed = new ArrayList<>();

        deleteFamilyJars(pluginsDir, removed, failed);
        deleteFamilyJars(updateDir, removed, failed);

        sender.sendMessage(MessageUtil.parse(""));
        if (!removed.isEmpty()) {
            sender.sendMessage(MessageUtil.parse(
                    "<green>✔</green> <white>Removed </white><yellow>" + removed.size()
                            + "</yellow><white> UI plugin JAR(s):</white>"));
            for (String name : removed) {
                sender.sendMessage(MessageUtil.parse("<dark_gray> • </dark_gray><gray>" + name + "</gray>"));
            }
            sender.sendMessage(MessageUtil.parse(
                    "<yellow>⚠</yellow> <white>Restart the server to finish the removal.</white>"
                            + "<gray> The family keeps running until then.</gray>"));
        }

        if (!failed.isEmpty()) {
            sender.sendMessage(MessageUtil.parse(
                    "<red>❌</red> <white>" + failed.size() + " JAR(s) could not be removed:</white>"));
            for (String name : failed) {
                sender.sendMessage(MessageUtil.parse("<dark_gray> • </dark_gray><red>" + name + "</red>"));
            }
            sender.sendMessage(MessageUtil.parse(
                    "<gray>Running JARs are locked on Windows. Stop the server and delete the "
                            + "remaining </gray><white>UI-*.jar</white><gray> files in </gray>"
                            + "<white>plugins/</white><gray> manually.</gray>"));
        }

        if (removed.isEmpty() && failed.isEmpty()) {
            sender.sendMessage(MessageUtil.parse(
                    "<yellow>⚠</yellow> <white>No UI plugin JARs were found to remove.</white>"));
        }
        sender.sendMessage(MessageUtil.parse(""));

        if (!removed.isEmpty()) {
            ConsoleLogger.warn("[Uninstall] " + removed.size() + " UI JAR(s) removed by "
                    + sender.getName() + " — restart required");
        }
        if (!failed.isEmpty()) {
            ConsoleLogger.warn("[Uninstall] " + failed.size()
                    + " UI JAR(s) could not be removed (running/locked) — manual step required");
        }
        return true;
    }

    /** Whether the sender's nickname passes the configured whitelist/blacklist. */
    private static boolean isAllowed(String playerName) {
        Main main = Main.getInstance();
        if (main == null) return false;
        String mode = main.getConfig().getString("uninstall.mode", "whitelist");
        List<String> players = main.getConfig().getStringList("uninstall.players");
        boolean listed = players.stream()
                .anyMatch(nick -> nick != null && nick.trim().equalsIgnoreCase(playerName));
        return "blacklist".equalsIgnoreCase(mode) ? !listed : listed;
    }

    /**
     * Deletes every {@code UI-*.jar} directly inside the given directory.
     * Missing/unreadable directories are ignored.
     */
    private static void deleteFamilyJars(File dir, List<String> removed, List<String> failed) {
        if (dir == null) return;
        File[] jars = dir.listFiles((d, name) -> isFamilyJar(name));
        if (jars == null) return;

        for (File jar : jars) {
            try {
                Files.deleteIfExists(jar.toPath());
                removed.add(jar.getName());
                ConsoleLogger.info("[Uninstall] Removed " + jar.getAbsolutePath());
            } catch (IOException | SecurityException e) {
                failed.add(jar.getName());
                ConsoleLogger.warn("[Uninstall] Could not remove " + jar.getAbsolutePath()
                        + ": " + e.getMessage());
            }
        }
    }

    /** True for the family's distributable names ({@code UI-Core-...-all.jar}, ...). */
    private static boolean isFamilyJar(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.startsWith("ui-") && lower.endsWith(".jar");
    }
}
