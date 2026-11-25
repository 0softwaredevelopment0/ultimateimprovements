package com.ultimateimprovements.whitelist;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Pure countdown-warning selection shared by {@link TimedListWarnings}.
 * <p>
 * Mirrors the announcement cadence of the scheduled-restart countdown: fixed
 * thresholds ({@code 1h}, {@code 30m}, ...) followed by a per-second report
 * once only a few seconds remain. Stateful, so a countdown is announced at
 * most once per threshold/second.
 */
final class WarningCadence {

    private WarningCadence() {}

    /** Per-schedule announcement progress. */
    static final class State {
        boolean initialSent;
        final Set<Long> sentThresholds = new HashSet<>();
        long lastReportedSecond = -1L;
    }

    /**
     * Decides what (if anything) to announce this tick and updates the state.
     * <p>
     * The first tick (a fresh schedule, or a player who just came online) uses
     * the exact remaining time, so a short {@code -t 90s} schedule does not
     * announce the larger "5 minutes" threshold. Later ticks announce the
     * largest (most urgent) threshold that was crossed, then every remaining
     * second from {@code secondReportFrom} down to 1.
     *
     * @param state              mutable announcement progress for this schedule
     * @param remainingSeconds   whole seconds left before the removal
     * @param thresholds         configured thresholds in seconds, ascending
     * @param secondReportFrom   per-second report start in seconds, {@code 0} = off
     * @return seconds to announce this tick, or {@code null} to stay silent
     */
    static Long nextAnnouncement(State state, long remainingSeconds,
                                 List<Long> thresholds, long secondReportFrom) {
        if (remainingSeconds <= 0) return null;

        if (!state.initialSent) {
            state.initialSent = true;
            long maxWindow = thresholds.isEmpty()
                    ? secondReportFrom
                    : thresholds.get(thresholds.size() - 1);
            for (long threshold : thresholds) {
                if (threshold >= remainingSeconds) state.sentThresholds.add(threshold);
            }
            if (maxWindow > 0 && remainingSeconds <= maxWindow) {
                if (secondReportFrom > 0 && remainingSeconds <= secondReportFrom) {
                    state.lastReportedSecond = remainingSeconds;
                }
                return remainingSeconds;
            }
            return null;
        }

        Long threshold = dueThreshold(state, remainingSeconds, thresholds);
        if (threshold != null) return threshold;

        if (secondReportFrom <= 0 || remainingSeconds > secondReportFrom) return null;
        if (state.lastReportedSecond == remainingSeconds) return null;
        state.lastReportedSecond = remainingSeconds;
        return remainingSeconds;
    }

    /**
     * Returns the smallest not-yet-announced threshold the countdown has
     * crossed. Larger crossed thresholds are marked as sent so they never
     * fire retroactively after a skip or restart.
     */
    private static Long dueThreshold(State state, long remainingSeconds, List<Long> thresholds) {
        Long due = null;
        for (Long threshold : thresholds) {
            if (state.sentThresholds.contains(threshold)) continue;
            if (remainingSeconds <= threshold) {
                if (due == null) due = threshold;
                state.sentThresholds.add(threshold);
            }
        }
        return due;
    }
}
