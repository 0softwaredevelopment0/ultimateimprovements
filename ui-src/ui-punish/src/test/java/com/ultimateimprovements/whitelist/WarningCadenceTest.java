package com.ultimateimprovements.whitelist;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class WarningCadenceTest {

    private static final List<Long> THRESHOLDS = List.of(15L, 30L, 45L, 60L, 300L, 900L, 1800L, 3600L);

    @Test
    @DisplayName("first tick announces the exact remaining time at a threshold")
    void firstTickExactAtThreshold() {
        WarningCadence.State state = new WarningCadence.State();

        assertEquals(300L, WarningCadence.nextAnnouncement(state, 300, THRESHOLDS, 10));
        assertNull(WarningCadence.nextAnnouncement(state, 299, THRESHOLDS, 10));
    }

    @Test
    @DisplayName("a short schedule announces its real time, not a larger threshold")
    void shortScheduleUsesExactTime() {
        WarningCadence.State state = new WarningCadence.State();

        assertEquals(90L, WarningCadence.nextAnnouncement(state, 90, THRESHOLDS, 10));
        // The 5 minute threshold is in the past and must never be announced.
        assertNull(WarningCadence.nextAnnouncement(state, 89, THRESHOLDS, 10));
        assertEquals(60L, WarningCadence.nextAnnouncement(state, 60, THRESHOLDS, 10));
    }

    @Test
    @DisplayName("a schedule far beyond the largest threshold stays silent until it crosses")
    void farScheduleStaysSilent() {
        WarningCadence.State state = new WarningCadence.State();

        assertNull(WarningCadence.nextAnnouncement(state, 7200, THRESHOLDS, 10));
        assertNull(WarningCadence.nextAnnouncement(state, 3601, THRESHOLDS, 10));
        assertEquals(3600L, WarningCadence.nextAnnouncement(state, 3600, THRESHOLDS, 10));
        assertNull(WarningCadence.nextAnnouncement(state, 3599, THRESHOLDS, 10));
        assertEquals(1800L, WarningCadence.nextAnnouncement(state, 1800, THRESHOLDS, 10));
    }

    @Test
    @DisplayName("a jumped countdown announces the most urgent threshold and skips the rest")
    void jumpedCountdownSkipsLargerThresholds() {
        WarningCadence.State state = new WarningCadence.State();
        WarningCadence.nextAnnouncement(state, 300, THRESHOLDS, 10); // exact first tick
        // Simulate a long server stall between two ticks: 4 seconds are left.
        assertEquals(15L, WarningCadence.nextAnnouncement(state, 4, THRESHOLDS, 10));
        assertEquals(3L, WarningCadence.nextAnnouncement(state, 3, THRESHOLDS, 10));
    }

    @Test
    @DisplayName("per-second report announces each remaining second exactly once")
    void perSecondReport() {
        WarningCadence.State state = new WarningCadence.State();
        WarningCadence.nextAnnouncement(state, 300, THRESHOLDS, 10); // first tick
        for (long threshold : THRESHOLDS) state.sentThresholds.add(threshold);

        assertEquals(10L, WarningCadence.nextAnnouncement(state, 10, THRESHOLDS, 10));
        assertNull(WarningCadence.nextAnnouncement(state, 10, THRESHOLDS, 10));
        assertEquals(9L, WarningCadence.nextAnnouncement(state, 9, THRESHOLDS, 10));
        assertEquals(1L, WarningCadence.nextAnnouncement(state, 1, THRESHOLDS, 10));
    }

    @Test
    @DisplayName("the per-second report can be disabled")
    void perSecondReportOff() {
        WarningCadence.State state = new WarningCadence.State();
        WarningCadence.nextAnnouncement(state, 300, THRESHOLDS, 0);
        for (long threshold : THRESHOLDS) state.sentThresholds.add(threshold);

        assertNull(WarningCadence.nextAnnouncement(state, 5, THRESHOLDS, 0));
    }

    @Test
    @DisplayName("a fresh state restarts the cadence after a schedule replacement")
    void replacementStartsFresh() {
        WarningCadence.State first = new WarningCadence.State();
        WarningCadence.nextAnnouncement(first, 300, THRESHOLDS, 10);
        assertEquals(60L, WarningCadence.nextAnnouncement(first, 60, THRESHOLDS, 10));

        WarningCadence.State replacement = new WarningCadence.State();
        assertEquals(300L, WarningCadence.nextAnnouncement(replacement, 300, THRESHOLDS, 10));
    }

    @Test
    @DisplayName("announced time uses the largest exact unit")
    void splitsUnits() {
        assertEquals(new TimedListWarnings.TimeValue(1, "unit_days"), TimedListWarnings.splitUnits(86_400));
        assertEquals(new TimedListWarnings.TimeValue(2, "unit_hours"), TimedListWarnings.splitUnits(7_200));
        assertEquals(new TimedListWarnings.TimeValue(5, "unit_minutes"), TimedListWarnings.splitUnits(300));
        assertEquals(new TimedListWarnings.TimeValue(45, "unit_seconds"), TimedListWarnings.splitUnits(45));
        assertEquals(new TimedListWarnings.TimeValue(90, "unit_seconds"), TimedListWarnings.splitUnits(90));
    }
}
