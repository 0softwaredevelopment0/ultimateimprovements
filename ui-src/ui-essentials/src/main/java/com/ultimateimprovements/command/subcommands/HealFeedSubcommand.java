package com.ultimateimprovements.command.subcommands;

import com.ultimateimprovements.command.CommandErrors;

import com.ultimateimprovements.config.MessagesManager;
import com.ultimateimprovements.core.Main;
import com.ultimateimprovements.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.attribute.Attribute;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Commands /ui heal and /ui feed — restore health/hunger to a player.
 * <p>
 * Settings in config.yml → heal_feed:
 * <ul>
 *   <li>enabled — enable/disable the commands</li>
 *   <li>heal_amount — how much HP to restore (0 = all)</li>
 *   <li>feed_amount — how much saturation to restore (0 = all)</li>
 * </ul>
 */
public final class HealFeedSubcommand {

    private HealFeedSubcommand() {}

    // =========================
    // HEAL
    // =========================
    public static boolean heal(CommandSender sender, String[] args) {
        if (!isEnabled()) {
            CommandErrors.moduleDisabled(sender, "healfeed");
            return true;
        }
        if (!sender.hasPermission("ui.command.heal")) {
            CommandErrors.noPermission(sender, "ui.command.heal");
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage(MessageUtil.parse("<red>❌ Usage: </red><white>/ui heal <player></white>"));
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(MessageUtil.parse(MessagesManager.getString("misc.vanish_player_not_found",
                    "<red>❌ Player</red> <yellow>%player%</yellow> <red>not found!</red>")
                    .replace("%player%", args[1])));
            return true;
        }

        double amount = getHealAmount();
        if (amount <= 0) {
            // Full HP
            target.setHealth(target.getAttribute(Attribute.MAX_HEALTH).getAttribute().getDefaultValue());
        } else {
            double newHealth = Math.min(target.getHealth() + amount,
                    target.getAttribute(Attribute.MAX_HEALTH).getAttribute().getDefaultValue());
            target.setHealth(newHealth);
        }

        String confirm = "<green>✔</green> <white>Player</white> <yellow>" + target.getName() + "</yellow> <white>has been healed.</white>";
        sender.sendMessage(MessageUtil.parse(confirm));
        if (!sender.equals(target)) {
            target.sendMessage(MessageUtil.parse("<green>✔</green> <white>You have been healed by</white> <yellow>" + sender.getName() + "</yellow><white>.</white>"));
        }
        return true;
    }

    // =========================
    // FEED — /ui feed <hunger|saturation|all> <player|all>
    // =========================
    private static final String FEED_USAGE =
            "<red>❌ Usage: </red><white>/ui feed <hunger|saturation|all> <player|all></white>";
    private static final List<String> FEED_MODES = List.of("hunger", "saturation", "all");

    public static boolean feed(CommandSender sender, String[] args) {
        if (!isEnabled()) {
            CommandErrors.moduleDisabled(sender, "healfeed");
            return true;
        }
        if (!sender.hasPermission("ui.command.feed")) {
            CommandErrors.noPermission(sender, "ui.command.feed");
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage(MessageUtil.parse(FEED_USAGE));
            return true;
        }

        String mode;
        String targetArg;
        if (isFeedMode(args[1])) {
            if (args.length < 3) {
                sender.sendMessage(MessageUtil.parse(FEED_USAGE));
                return true;
            }
            mode = args[1].toLowerCase(java.util.Locale.ROOT);
            targetArg = args[2];
        } else if (args.length == 2) {
            // Backwards compatibility: /ui feed <player> means all scales.
            mode = "all";
            targetArg = args[1];
        } else {
            sender.sendMessage(MessageUtil.parse(FEED_USAGE));
            return true;
        }

        if (targetArg.equalsIgnoreCase("all")) {
            List<Player> targets = new java.util.ArrayList<>(Bukkit.getOnlinePlayers());
            if (targets.isEmpty()) {
                sender.sendMessage(MessageUtil.parse("<red>❌ There are no players online!</red>"));
                return true;
            }
            for (Player target : targets) {
                feedPlayer(target, mode);
                if (!sender.equals(target)) {
                    target.sendMessage(MessageUtil.parse("<green>✔</green> <white>You have been fed by</white> <yellow>"
                            + sender.getName() + "</yellow><white>.</white>"));
                }
            }
            String who = targets.size() == 1
                    ? targets.get(0).getName()
                    : Integer.toString(targets.size());
            sender.sendMessage(MessageUtil.parse("<green>✔</green> <white>" + feedDescription(mode)
                    + " for</white> <yellow>" + who + "</yellow><white>.</white>"));
            return true;
        }

        Player target = Bukkit.getPlayerExact(targetArg);
        if (target == null) {
            sender.sendMessage(MessageUtil.parse(MessagesManager.getString("misc.vanish_player_not_found",
                    "<red>❌ Player</red> <yellow>%player%</yellow> <red>not found!</red>")
                    .replace("%player%", targetArg)));
            return true;
        }

        feedPlayer(target, mode);

        String confirm = "<green>✔</green> <white>" + feedDescription(mode) + " for</white> <yellow>"
                + target.getName() + "</yellow><white>.</white>";
        sender.sendMessage(MessageUtil.parse(confirm));
        if (!sender.equals(target)) {
            target.sendMessage(MessageUtil.parse("<green>✔</green> <white>You have been fed by</white> <yellow>" + sender.getName() + "</yellow><white>.</white>"));
        }
        return true;
    }

    private static boolean isFeedMode(String arg) {
        for (String mode : FEED_MODES) {
            if (mode.equalsIgnoreCase(arg)) return true;
        }
        return false;
    }

    private static String feedDescription(String mode) {
        return switch (mode) {
            case "hunger" -> "Hunger restored";
            case "saturation" -> "Saturation restored";
            default -> "Hunger and saturation restored";
        };
    }

    /** Applies the requested feed mode to one player. */
    private static void feedPlayer(Player target, String mode) {
        boolean hunger = mode.equals("hunger") || mode.equals("all");
        boolean saturation = mode.equals("saturation") || mode.equals("all");
        double amount = getFeedAmount();

        if (hunger) {
            if (amount <= 0) {
                target.setFoodLevel(20);
            } else {
                target.setFoodLevel(Math.min((int) (target.getFoodLevel() + amount), 20));
            }
        }
        if (saturation) {
            if (amount <= 0) {
                target.setSaturation(20f);
            } else {
                target.setSaturation((float) Math.min(target.getSaturation() + amount, 20f));
            }
        }
        if (hunger && saturation && amount <= 0) {
            target.setExhaustion(0);
        }
    }

    // =========================
    // CONFIG HELPERS
    // =========================
    /**
     * Returns the heal/feed config section from config.yml.
     */
    private static ConfigurationSection getConfig() {
        return Main.getInstance().getConfig().getConfigurationSection("heal_feed");
    }

    /**
     * Returns the HP amount for heal from config.yml.
     * 0 = all HP.
     */
    public static double getHealAmount() {
        var cfg = getConfig();
        if (cfg == null) return 0;
        return cfg.getDouble("heal_amount", 0);
    }

    /**
     * Returns the saturation amount for feed from config.yml.
     * 0 = all saturation.
     */
    public static double getFeedAmount() {
        var cfg = getConfig();
        if (cfg == null) return 0;
        return cfg.getDouble("feed_amount", 0);
    }

    /**
     * Checks whether the command is enabled.
     */
    public static boolean isEnabled() {
        var cfg = getConfig();
        if (cfg == null) return true;
        return cfg.getBoolean("enabled", true);
    }

    /**
     * Tab-completion for /ui heal: suggests player names.
     */
    public static List<String> tabCompleteHeal(String[] args) {
        if (args.length == 2) {
            return onlinePlayers(args[1], false);
        }
        return List.of();
    }

    /**
     * Tab-completion for /ui feed: modes first, then players plus {@code all}.
     */
    public static List<String> tabCompleteFeed(String[] args) {
        if (args.length == 2) {
            String prefix = args[1].toLowerCase();
            return FEED_MODES.stream()
                    .filter(mode -> mode.startsWith(prefix))
                    .collect(Collectors.toList());
        }
        if (args.length == 3) {
            return onlinePlayers(args[2], true);
        }
        return List.of();
    }

    private static List<String> onlinePlayers(String prefix, boolean includeAll) {
        List<String> names = Bukkit.getOnlinePlayers().stream()
                .map(Player::getName)
                .filter(name -> name.toLowerCase().startsWith(prefix.toLowerCase()))
                .collect(Collectors.toList());
        if (includeAll && "all".startsWith(prefix.toLowerCase())) {
            names.add("all");
        }
        return names;
    }
}
