package com.ultimateimprovements.mechanics.features.world;

import java.util.Locale;

/**
 * ChallengeText — small formatting helpers shared by the timed advancement
 * challenges, so their messages always show the configured goal and duration
 * instead of the values that used to be compile-time constants.
 */
final class ChallengeText {

    private ChallengeText() {}

    /** "1 minute", "2 hours", "90 seconds" — used in the challenge start message. */
    static String duration(int seconds) {
        if (seconds >= 3600 && seconds % 3600 == 0) {
            int hours = seconds / 3600;
            return hours + (hours == 1 ? " hour" : " hours");
        }
        if (seconds >= 60 && seconds % 60 == 0) {
            int minutes = seconds / 60;
            return minutes + (minutes == 1 ? " minute" : " minutes");
        }
        return seconds + (seconds == 1 ? " second" : " seconds");
    }

    /** "7 200" — grouped goal for readable chat output. */
    static String goal(int value) {
        return String.format(Locale.ROOT, "%,d", value).replace(',', ' ');
    }
}
