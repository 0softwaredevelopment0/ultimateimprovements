package com.ultimateimprovements.chat;

import com.ultimateimprovements.core.Main;
import com.ultimateimprovements.util.ConsoleLogger;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.filter.AbstractFilter;
import org.apache.logging.log4j.message.Message;

import java.util.regex.Pattern;

/**
 * Log4J filter — keeps private-message commands out of the server console.
 * <p>
 * Vanilla writes every player command to the console as
 * {@code <player> issued server command: /msg <target> <text>}. For direct
 * messages that leaks the whole conversation into the log, so this filter
 * denies those lines when {@code chat.hide_private_messages} is on (default).
 * <p>
 * Covered commands: {@code /msg}, {@code /tell}, {@code /w}, {@code /whisper},
 * {@code /m}, {@code /t}, {@code /pm}, {@code /dm}, {@code /reply}, {@code /r}
 * (including the {@code minecraft:} prefix) and
 * {@code /ui chatchnl private <player>}.
 * <p>
 * The flag is re-read on every event, so {@code /ui reload} applies instantly.
 */
public final class PrivateMessageLogFilter extends AbstractFilter {

    /** Config key in UI-Chat.toml. */
    public static final String CONFIG_KEY = "chat.hide_private_messages";

    private static final Pattern PRIVATE_COMMAND = Pattern.compile(
            "issued server command: /"
                    + "(?:(?:minecraft:)?(?:msg|tell|w|whisper|m|t|pm|dm|reply|r)"
                    + "|(?:ui|ultimateimprovements)(?::\\S+)?\\s+chatchnl\\s+private)"
                    + "(?:\\s|$)",
            Pattern.CASE_INSENSITIVE);

    private static PrivateMessageLogFilter registered;

    private PrivateMessageLogFilter() {
    }

    /** True when the feature is enabled in the config (default: on). */
    static boolean isEnabled() {
        Main main = Main.getInstance();
        return main == null || main.getConfig().getBoolean(CONFIG_KEY, true);
    }

    /** True when the log line is a private-message command. */
    static boolean isPrivateCommand(String line) {
        return line != null && PRIVATE_COMMAND.matcher(line).find();
    }

    @Override
    public Result filter(LogEvent event) {
        if (event == null || !isEnabled()) return Result.NEUTRAL;

        Message msg = event.getMessage();
        if (msg == null) return Result.NEUTRAL;

        return isPrivateCommand(msg.getFormattedMessage()) ? Result.DENY : Result.NEUTRAL;
    }

    /**
     * Registers the filter in Log4J's root logger. Idempotent: a second call
     * (for example after {@code /ui reload}) keeps the existing instance.
     */
    public static void register() {
        if (registered != null) return;

        LoggerContext ctx = LoggerContext.getContext(false);
        if (ctx == null || ctx.getConfiguration() == null) return;

        registered = new PrivateMessageLogFilter();
        ctx.getConfiguration().getRootLogger().addFilter(registered);
        ctx.updateLoggers();

        ConsoleLogger.info("[Chat] PrivateMessageLogFilter registered — private messages hidden from console");
    }

    /** Removes the filter from Log4J's root logger. */
    public static void unregister() {
        if (registered == null) return;

        LoggerContext ctx = LoggerContext.getContext(false);
        if (ctx != null && ctx.getConfiguration() != null) {
            ctx.getConfiguration().getRootLogger().removeFilter(registered);
            ctx.updateLoggers();
        }
        registered = null;
    }
}
