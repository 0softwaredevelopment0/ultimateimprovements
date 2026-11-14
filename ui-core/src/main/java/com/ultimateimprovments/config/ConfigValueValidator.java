package com.ultimateimprovments.config;

import com.ultimateimprovments.config.ConfigValidationMessages.Text;
import com.ultimateimprovments.core.Main;
import com.ultimateimprovments.util.ConsoleLogger;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Validates composite config values against the rules from {@link ConfigRules}.
 * <p>
 * Extracted from {@link ConfigIntegrityValidator} to reduce class size.
 * Every console text is configurable in both languages via
 * {@link ConfigValidationMessages} ({@code [messages.config_validation]} /
 * {@code [messages_en.config_validation]} in {@code UI-Core.toml}).
 */
final class ConfigValueValidator {

    private ConfigValueValidator() {}

    private enum Severity { OK, WARN, ERROR }

    private static class Result {
        static final Result OK = new Result(Severity.OK, "");
        final Severity severity;
        final String message;
        Result(Severity severity, String message) { this.severity = severity; this.message = message; }
        static Result error(String msg) { return new Result(Severity.ERROR, msg); }
        static Result warn(String msg) { return new Result(Severity.WARN, msg); }
    }

    /** Validates all config values against the rules. */
    static void validateValues(Main plugin, FileConfiguration config) {
        ConfigValidationMessages msg = new ConfigValidationMessages(config);
        int errors = 0;
        int warnings = 0;

        for (ConfigRules.Rule rule : ConfigRules.ALL) {
            if (!config.isSet(rule.key)) continue;
            Result result = checkValue(config, rule, msg);
            switch (result.severity) {
                case ERROR -> {
                    errors++;
                    ConsoleLogger.warn(msg.get(Text.ERROR_LINE, "key", rule.key, "message", result.message));
                }
                case WARN -> {
                    warnings++;
                    ConsoleLogger.warn(msg.get(Text.WARN_LINE, "key", rule.key, "message", result.message));
                }
            }
        }

        if (errors > 0 || warnings > 0) {
            String border = msg.get(Text.SUMMARY_BORDER);
            ConsoleLogger.warn("");
            ConsoleLogger.warn(border);
            ConsoleLogger.warn(msg.get(Text.SUMMARY_TITLE));
            ConsoleLogger.warn(border);
            if (errors > 0) ConsoleLogger.warn(msg.get(Text.SUMMARY_ERRORS, "errors", errors));
            if (warnings > 0) ConsoleLogger.warn(msg.get(Text.SUMMARY_WARNINGS, "warnings", warnings));
            ConsoleLogger.warn(msg.get(Text.SUMMARY_NOTE));
            ConsoleLogger.warn(border + "\n");
        } else {
            ConsoleLogger.info(msg.get(Text.ALL_PASSED));
        }
    }

    private static Result checkValue(FileConfiguration config, ConfigRules.Rule rule,
                                     ConfigValidationMessages msg) {
        Object value = config.get(rule.key);
        if (value == null) return Result.OK;

        return switch (rule.type) {
            case BOOLEAN -> checkBoolean(value, msg);
            case INT -> checkInt(value, rule, msg);
            case DOUBLE -> checkDouble(value, rule, msg);
            case STRING -> checkString(value, rule, msg);
            case STRING_LIST -> checkStringList(rule.key, value, msg);
            case INT_LIST -> checkIntList(value, msg);
            case DOUBLE_LIST -> checkDoubleList(value, msg);
        };
    }

    private static Result checkBoolean(Object value, ConfigValidationMessages msg) {
        if (value instanceof Boolean) return Result.OK;
        return Result.error(msg.get(Text.EXPECTED_BOOLEAN,
                "type", typeName(value), "value", value));
    }

    private static Result checkInt(Object value, ConfigRules.Rule rule, ConfigValidationMessages msg) {
        if (!(value instanceof Number num))
            return Result.error(msg.get(Text.EXPECTED_INT,
                    "type", typeName(value), "value", value));

        long longVal = num.longValue();
        if (value instanceof Double || value instanceof Float) {
            double dVal = num.doubleValue();
            if (dVal != Math.floor(dVal))
                return Result.warn(msg.get(Text.FLOAT_ROUNDED, "value", (long) dVal));
        }
        if (longVal < Integer.MIN_VALUE || longVal > Integer.MAX_VALUE)
            return Result.error(msg.get(Text.INT_OUT_OF_RANGE, "value", longVal));

        int intVal = (int) longVal;
        if (intVal < rule.min || intVal > rule.max)
            return Result.error(outOfRange(msg, String.valueOf(intVal), rule));
        return Result.OK;
    }

    private static Result checkDouble(Object value, ConfigRules.Rule rule, ConfigValidationMessages msg) {
        if (!(value instanceof Number num))
            return Result.error(msg.get(Text.EXPECTED_DOUBLE,
                    "type", typeName(value), "value", value));

        double dVal = num.doubleValue();
        if (Double.isInfinite(dVal)) return Result.error(msg.get(Text.INFINITE_VALUE));
        if (Double.isNaN(dVal)) return Result.error(msg.get(Text.NAN_VALUE));

        if (dVal < rule.min || dVal > rule.max)
            return Result.error(outOfRange(msg, String.valueOf(dVal), rule));
        return Result.OK;
    }

    /** Builds the shared "value outside [min..max]" text (with the optional rule description). */
    private static String outOfRange(ConfigValidationMessages msg, String value, ConfigRules.Rule rule) {
        String description = rule.description.isEmpty() ? ""
                : msg.get(Text.RANGE_DESCRIPTION, "description", rule.description);
        return msg.get(Text.OUT_OF_RANGE,
                "value", value,
                "min", formatNum(rule.min),
                "max", formatNum(rule.max),
                "description", description);
    }

    private static Result checkString(Object value, ConfigRules.Rule rule, ConfigValidationMessages msg) {
        if (!(value instanceof String str))
            return Result.error(msg.get(Text.EXPECTED_STRING,
                    "type", typeName(value), "value", value));
        if (rule.notEmpty && str.isEmpty()) return Result.error(msg.get(Text.EMPTY_STRING));
        if (rule.notBlank && str.trim().isEmpty()) return Result.error(msg.get(Text.BLANK_STRING));

        String badChars = findControlChars(str);
        if (!badChars.isEmpty()) return Result.warn(msg.get(Text.CONTROL_CHARS, "chars", badChars));
        if (rule.maxLength > 0 && str.length() > rule.maxLength)
            return Result.error(msg.get(Text.STRING_TOO_LONG,
                    "length", str.length(), "max", rule.maxLength));
        if (rule.regex != null && !rule.regex.isEmpty()) {
            try {
                if (!str.matches(rule.regex))
                    return Result.error(msg.get(Text.REGEX_MISMATCH, "regex", rule.regex));
            } catch (PatternSyntaxException ignored) {}
        }
        return Result.OK;
    }

    @SuppressWarnings("unchecked")
    private static Result checkStringList(String key, Object value, ConfigValidationMessages msg) {
        if (!(value instanceof List))
            return Result.warn(msg.get(Text.EXPECTED_STRING_LIST, "type", typeName(value)));
        List<?> list = (List<?>) value;
        for (int i = 0; i < list.size(); i++) {
            Object item = list.get(i);
            if (!(item instanceof String))
                return Result.warn(msg.get(Text.LIST_ITEM_NOT_STRING,
                        "index", i, "type", typeName(item)));
            String str = (String) item;
            String badChars = findControlChars(str);
            if (!badChars.isEmpty())
                return Result.warn(msg.get(Text.LIST_ITEM_CONTROL_CHARS,
                        "index", i, "chars", badChars));
            if (key.endsWith("regex_patterns")) {
                try { Pattern.compile(str); }
                catch (PatternSyntaxException e) {
                    return Result.error(msg.get(Text.INVALID_REGEX,
                            "index", i, "error", e.getMessage()));
                }
            }
        }
        return Result.OK;
    }

    @SuppressWarnings("unchecked")
    private static Result checkIntList(Object value, ConfigValidationMessages msg) {
        if (!(value instanceof List))
            return Result.warn(msg.get(Text.EXPECTED_INT_LIST, "type", typeName(value)));
        for (Object item : (List<?>) value) {
            if (!(item instanceof Number))
                return Result.warn(msg.get(Text.LIST_ITEM_NOT_INT, "type", typeName(item)));
        }
        return Result.OK;
    }

    @SuppressWarnings("unchecked")
    private static Result checkDoubleList(Object value, ConfigValidationMessages msg) {
        if (!(value instanceof List))
            return Result.warn(msg.get(Text.EXPECTED_DOUBLE_LIST, "type", typeName(value)));
        for (Object item : (List<?>) value) {
            if (!(item instanceof Number))
                return Result.warn(msg.get(Text.LIST_ITEM_NOT_NUMBER, "type", typeName(item)));
        }
        return Result.OK;
    }

    /** Collects the control characters of a string as {@code U+XXXX} (empty when clean). */
    private static String findControlChars(String str) {
        StringBuilder badChars = new StringBuilder();
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            if (c < 32 && c != '\n' && c != '\r' && c != '\t') {
                if (!badChars.isEmpty()) badChars.append(", ");
                badChars.append("U+").append(String.format("%04X", (int) c));
            }
        }
        return badChars.toString();
    }

    private static String typeName(Object value) {
        if (value == null) return "null";
        if (value instanceof Boolean) return "boolean";
        if (value instanceof Integer || value instanceof Long) return "int";
        if (value instanceof Double || value instanceof Float) return "double";
        if (value instanceof String) return "string";
        if (value instanceof List) return "list";
        if (value instanceof ConfigurationSection) return "section";
        return value.getClass().getSimpleName();
    }

    private static String formatNum(double d) {
        return d == (long) d ? String.valueOf((long) d) : String.valueOf(d);
    }
}
