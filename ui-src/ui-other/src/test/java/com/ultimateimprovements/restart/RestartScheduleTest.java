package com.ultimateimprovements.restart;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the pure scheduled-restart helpers ({@link RestartSchedule}).
 * <p>
 * Проверяет парсинг времени, переход через полночь, дедупликацию/сортировку,
 * ключ слота и форматирование оставшегося времени.
 */
class RestartScheduleTest {

    @Test
    @DisplayName("parseTime accepts H:mm and HH:mm")
    void parseTimeValid() {
        assertEquals(LocalTime.of(4, 0), RestartSchedule.parseTime("4:00"));
        assertEquals(LocalTime.of(4, 5), RestartSchedule.parseTime("04:05"));
        assertEquals(LocalTime.of(0, 0), RestartSchedule.parseTime("00:00"));
        assertEquals(LocalTime.of(23, 59), RestartSchedule.parseTime(" 23:59 "));
    }

    @Test
    @DisplayName("parseTime rejects malformed values")
    void parseTimeInvalid() {
        assertNull(RestartSchedule.parseTime(null));
        assertNull(RestartSchedule.parseTime(""));
        assertNull(RestartSchedule.parseTime("24:00"));
        assertNull(RestartSchedule.parseTime("12:60"));
        assertNull(RestartSchedule.parseTime("4"));
        assertNull(RestartSchedule.parseTime("04:00:00"));
        assertNull(RestartSchedule.parseTime("four"));
    }

    @Test
    @DisplayName("parseTimes sorts, deduplicates and reports invalid entries")
    void parseTimesList() {
        List<String> invalid = new ArrayList<>();
        List<LocalTime> parsed = RestartSchedule.parseTimes(
                List.of("18:00", "4:00", "04:00", "oops", "23:59"),
                invalid::add);

        assertEquals(List.of(LocalTime.of(4, 0), LocalTime.of(18, 0), LocalTime.of(23, 59)), parsed);
        assertEquals(List.of("oops"), invalid);
    }

    @Test
    @DisplayName("parseInterval accepts s/m/h/d with an explicit unit")
    void parseIntervalValid() {
        assertEquals(Duration.ofSeconds(30), RestartSchedule.parseInterval("30s"));
        assertEquals(Duration.ofMinutes(15), RestartSchedule.parseInterval("15m"));
        assertEquals(Duration.ofHours(48), RestartSchedule.parseInterval(" 48h "));
        assertEquals(Duration.ofDays(7), RestartSchedule.parseInterval("7D"));
    }

    @Test
    @DisplayName("parseInterval rejects missing units, zero and malformed values")
    void parseIntervalInvalid() {
        assertNull(RestartSchedule.parseInterval(null));
        assertNull(RestartSchedule.parseInterval(""));
        assertNull(RestartSchedule.parseInterval("48"));
        assertNull(RestartSchedule.parseInterval("0h"));
        assertNull(RestartSchedule.parseInterval("-5h"));
        assertNull(RestartSchedule.parseInterval("1.5h"));
        assertNull(RestartSchedule.parseInterval("48x"));
        assertNull(RestartSchedule.parseInterval("h"));
    }

    @Test
    @DisplayName("parseTimes handles null/empty input")
    void parseTimesEmpty() {
        assertTrue(RestartSchedule.parseTimes(null, null).isEmpty());
        assertTrue(RestartSchedule.parseTimes(List.of(), null).isEmpty());
    }

    @Test
    @DisplayName("nextAfter returns today's slot when it is still ahead")
    void nextAfterToday() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 17, 2, 30);
        LocalDateTime next = RestartSchedule.nextAfter(
                List.of(LocalTime.of(4, 0), LocalTime.of(18, 0)), now);

        assertEquals(LocalDateTime.of(2026, 11, 17, 4, 0), next);
    }

    @Test
    @DisplayName("nextAfter rolls over to tomorrow when the slot has passed")
    void nextAfterRollover() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 17, 4, 0, 1);
        LocalDateTime next = RestartSchedule.nextAfter(List.of(LocalTime.of(4, 0)), now);

        assertEquals(LocalDateTime.of(2026, 11, 18, 4, 0), next);
    }

    @Test
    @DisplayName("nextAfter uses the earliest of several times across midnight")
    void nextAfterEarliest() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 17, 23, 30);
        LocalDateTime next = RestartSchedule.nextAfter(
                List.of(LocalTime.of(4, 0), LocalTime.of(23, 45)), now);

        assertEquals(LocalDateTime.of(2026, 11, 17, 23, 45), next);
    }

    @Test
    @DisplayName("nextAfter returns null without configured times")
    void nextAfterNoTimes() {
        assertNull(RestartSchedule.nextAfter(List.of(), LocalDateTime.now()));
        assertNull(RestartSchedule.nextAfter(null, LocalDateTime.now()));
    }

    @Test
    @DisplayName("slotKey uses minute precision")
    void slotKey() {
        assertEquals("2026-11-18 04:00",
                RestartSchedule.slotKey(LocalDateTime.of(2026, 11, 18, 4, 0, 37)));
        assertNull(RestartSchedule.slotKey(null));
    }

    @Test
    @DisplayName("formatDuration is compact and language-neutral")
    void formatDuration() {
        assertEquals("0s", RestartSchedule.formatDuration(Duration.ZERO));
        assertEquals("45s", RestartSchedule.formatDuration(Duration.ofSeconds(45)));
        assertEquals("2m 5s", RestartSchedule.formatDuration(Duration.ofSeconds(125)));
        assertEquals("1h 1m 1s", RestartSchedule.formatDuration(Duration.ofSeconds(3661)));
        assertEquals("1d 0h 0m 0s", RestartSchedule.formatDuration(Duration.ofHours(24)));
        assertEquals("0s", RestartSchedule.formatDuration(Duration.ofSeconds(-5)));
        assertEquals("0s", RestartSchedule.formatDuration(null));
    }
}
