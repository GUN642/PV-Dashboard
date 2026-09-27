package de.gun642.pvdashboard.notify

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class MeterReminderTest {
    // Sonntag, 27.09.2026
    private val sunday = LocalDateTime.of(2026, 9, 27, 10, 0)

    @Test
    fun remindsOnChosenDayAfterNine() {
        assertTrue(MeterReminder.due(sunday, 7, null, listOf(LocalDate.of(2026, 9, 1))))
        assertFalse(MeterReminder.due(sunday.withHour(8), 7, null, emptyList()))
        assertFalse(MeterReminder.due(sunday, 1, null, emptyList()))
    }

    @Test
    fun onlyOncePerDayAndNotWhenJustRead() {
        assertFalse(MeterReminder.due(sunday, 7, sunday.toLocalDate(), emptyList()))
        assertFalse(MeterReminder.due(sunday, 7, null, listOf(LocalDate.of(2026, 9, 26), LocalDate.of(2026, 9, 25))))
        // Wasser frisch, Strom alt: trotzdem erinnern
        assertTrue(MeterReminder.due(sunday, 7, null, listOf(LocalDate.of(2026, 9, 26), LocalDate.of(2026, 8, 1))))
    }
}
