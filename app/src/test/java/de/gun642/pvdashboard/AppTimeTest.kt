package de.gun642.pvdashboard

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.TimeZone

class AppTimeTest {
    @Test
    fun appUsesGermanTimeRegardlessOfPhoneTimeZone() {
        val original = TimeZone.getDefault()
        try {
            // Handy steht auf New York (z. B. im Urlaub)
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
            AppTime.apply()
            assertEquals(ZoneId.of("Europe/Berlin"), ZoneId.systemDefault())
            // 10:00 UTC im Sommer = 12:00 Uhr in Deutschland
            val local = ZonedDateTime.ofInstant(Instant.parse("2026-07-01T10:00:00Z"), ZoneId.systemDefault())
            assertEquals(12, local.hour)
        } finally {
            TimeZone.setDefault(original)
        }
    }
}
