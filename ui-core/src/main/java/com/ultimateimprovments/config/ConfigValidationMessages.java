package com.ultimateimprovments.config;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * Console texts of the config validation pass, configurable per language.
 * <p>
 * Reads {@code messages.config_validation.<key>} (active when
 * {@code messages.lang = "ru"}) or {@code messages_en.config_validation.<key>}
 * (active when {@code messages.lang = "en"}), falling back to the other
 * language and then to the built-in default. The group is owned by UI-Core, so
 * both sections live in {@code configs/UI-Core.toml}.
 * <p>
 * Placeholders are written as {@code %name%} and substituted by
 * {@link #get(Text, Object...)}.
 */
final class ConfigValidationMessages {

    /** Message group under {@code messages} / {@code messages_en}. */
    static final String GROUP = "config_validation";

    private static final String RU_SECTION = "messages";
    private static final String EN_SECTION = "messages_en";

    /**
     * Every configurable text of the validation pass, together with its
     * built-in defaults. The defaults only guard a missing/blank key — the real
     * source of defaults is the bundled {@code UI-Core.toml}.
     */
    enum Text {
        EXPECTED_BOOLEAN("expected_boolean",
                "Ожидался boolean (true/false), получен %type%: %value%",
                "Expected boolean (true/false), got %type%: %value%"),
        EXPECTED_INT("expected_int",
                "Ожидалось целое число, получен %type%: %value%",
                "Expected an integer, got %type%: %value%"),
        EXPECTED_DOUBLE("expected_double",
                "Ожидалось число с плавающей точкой, получен %type%: %value%",
                "Expected a decimal number, got %type%: %value%"),
        EXPECTED_STRING("expected_string",
                "Ожидалась строка, получен %type%: %value%",
                "Expected a string, got %type%: %value%"),
        FLOAT_ROUNDED("float_rounded",
                "Значение с плавающей точкой, будет округлено до %value%",
                "Floating-point value, it will be rounded to %value%"),
        INT_OUT_OF_RANGE("int_out_of_range",
                "Значение вне диапазона int: %value%",
                "Value is outside the int range: %value%"),
        INFINITE_VALUE("infinite_value",
                "Значение равно бесконечности (Infinity)",
                "The value is infinite (Infinity)"),
        NAN_VALUE("nan_value",
                "Значение не является числом (NaN)",
                "The value is not a number (NaN)"),
        OUT_OF_RANGE("out_of_range",
                "Значение %value% вне диапазона [%min%..%max%]%description%",
                "Value %value% is outside the range [%min%..%max%]%description%"),
        RANGE_DESCRIPTION("range_description",
                " (%description%)",
                " (%description%)"),
        EMPTY_STRING("empty_string",
                "Строка пуста",
                "The string is empty"),
        BLANK_STRING("blank_string",
                "Строка состоит только из пробелов",
                "The string consists of whitespace only"),
        CONTROL_CHARS("control_chars",
                "Строка содержит управляющие символы: %chars%",
                "The string contains control characters: %chars%"),
        STRING_TOO_LONG("string_too_long",
                "Длина (%length%) превышает максимум (%max%)",
                "Length (%length%) exceeds the maximum (%max%)"),
        REGEX_MISMATCH("regex_mismatch",
                "Строка не соответствует формату: %regex%",
                "The string does not match the pattern: %regex%"),
        EXPECTED_STRING_LIST("expected_string_list",
                "Ожидался список строк, получен %type%",
                "Expected a string list, got %type%"),
        LIST_ITEM_NOT_STRING("list_item_not_string",
                "Элемент #%index% — ожидалась строка, получен %type%",
                "Item #%index% — expected a string, got %type%"),
        LIST_ITEM_CONTROL_CHARS("list_item_control_chars",
                "Элемент #%index% содержит управляющие символы: %chars%",
                "Item #%index% contains control characters: %chars%"),
        INVALID_REGEX("invalid_regex",
                "Элемент #%index% — невалидный regex: %error%",
                "Item #%index% — invalid regex: %error%"),
        EXPECTED_INT_LIST("expected_int_list",
                "Ожидался список целых чисел, получен %type%",
                "Expected an integer list, got %type%"),
        LIST_ITEM_NOT_INT("list_item_not_int",
                "Элемент — ожидалось целое число, получен %type%",
                "Item — expected an integer, got %type%"),
        EXPECTED_DOUBLE_LIST("expected_double_list",
                "Ожидался список чисел, получен %type%",
                "Expected a number list, got %type%"),
        LIST_ITEM_NOT_NUMBER("list_item_not_number",
                "Элемент — ожидалось число, получен %type%",
                "Item — expected a number, got %type%"),
        ERROR_LINE("error_line",
                "[ConfigValidator] ⚠ [ERROR] %key%: %message%",
                "[ConfigValidator] ⚠ [ERROR] %key%: %message%"),
        WARN_LINE("warn_line",
                "[ConfigValidator] ⚠ [WARN] %key%: %message%",
                "[ConfigValidator] ⚠ [WARN] %key%: %message%"),
        SUMMARY_BORDER("summary_border",
                "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!",
                "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!"),
        SUMMARY_TITLE("summary_title",
                "!  ПРОВЕРКА ЗНАЧЕНИЙ КОНФИГА ЗАВЕРШЕНА                  !",
                "!  CONFIG VALUE VALIDATION COMPLETE                     !"),
        SUMMARY_ERRORS("summary_errors",
                "!  Найдено ошибок: %errors% — исправьте эти значения!   !",
                "!  %errors% error(s) found - fix these values!          !"),
        SUMMARY_WARNINGS("summary_warnings",
                "!  Найдено предупреждений: %warnings% — проверьте их.   !",
                "!  %warnings% warning(s) found - review recommended.    !"),
        SUMMARY_NOTE("summary_note",
                "!  Для некорректных значений будут использованы умолчания. !",
                "!  Plugin will use defaults for invalid values.         !"),
        ALL_PASSED("all_passed",
                "[ConfigValidator] ✓ Все значения конфига прошли проверку.",
                "[ConfigValidator] ✓ All config values passed validation."),
        LEGACY_MONOLITHIC("legacy_monolithic",
                "[Config] Найден устаревший монолитный config.toml в %dir% — настройки теперь хранятся в configs/UI-<Addon>.toml",
                "[Config] Legacy monolithic config.toml found in %dir% — settings now live in configs/UI-<Addon>.toml");

        private final String key;
        private final String ru;
        private final String en;

        Text(String key, String ru, String en) {
            this.key = key;
            this.ru = ru;
            this.en = en;
        }

        /** @return the config key inside the {@code config_validation} group. */
        String key() {
            return key;
        }

        /** @return the built-in Russian default (placeholders included). */
        String ru() {
            return ru;
        }

        /** @return the built-in English default (placeholders included). */
        String en() {
            return en;
        }
    }

    private final FileConfiguration config;

    ConfigValidationMessages(FileConfiguration config) {
        this.config = config;
    }

    /** Resolves a text for the active language and substitutes {@code %name%} placeholders. */
    String get(Text text, Object... placeholders) {
        boolean ru = isRu();
        String primary = ru ? RU_SECTION : EN_SECTION;
        String fallback = ru ? EN_SECTION : RU_SECTION;
        String path = primary + "." + GROUP + "." + text.key();
        String template = config == null ? null : config.getString(path, null);
        if (template == null || template.isEmpty()) {
            template = config == null ? null
                    : config.getString(fallback + "." + GROUP + "." + text.key(), null);
        }
        if (template == null || template.isEmpty()) {
            template = ru ? text.ru() : text.en();
        }
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            template = template.replace("%" + placeholders[i] + "%", String.valueOf(placeholders[i + 1]));
        }
        return template;
    }

    /** Whether the active language is Russian ({@code messages.lang}). */
    private boolean isRu() {
        return config != null && "ru".equalsIgnoreCase(config.getString("messages.lang", "en"));
    }
}
