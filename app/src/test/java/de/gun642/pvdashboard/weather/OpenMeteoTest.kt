package de.gun642.pvdashboard.weather

import de.gun642.pvdashboard.data.Updater
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class OpenMeteoTest {
    private val body = """
        {"hourly":{"time":["2025-06-01T11:00","2025-06-01T12:00","2025-06-02T12:00"],
                   "sunshine_duration":[3600,1800,0],
                   "global_tilted_irradiance":[800,600,100]},
         "daily":{"time":["2025-06-01","2025-06-02"],
                  "sunshine_duration":[36000,7200],
                  "daylight_duration":[57600,57600],
                  "weather_code":[1,61],
                  "temperature_2m_max":[24.5,18.0],
                  "temperature_2m_min":[12.0,null],
                  "precipitation_probability_max":[5,80]}}
    """.trimIndent()

    @Test
    fun parsesForecastWithPvEstimate() {
        val f = OpenMeteo.parse(body, kwp = 10.0, performanceRatio = 0.85)
        assertEquals(2, f.days.size)
        val day = f.days[0]
        assertEquals(LocalDate.of(2025, 6, 1), day.date)
        assertEquals(10.0, day.sunshineHours, 1e-9)
        assertEquals(16.0, day.daylightHours, 1e-9)
        // (800 + 600) Wh/m² = 1,4 kWh/m² × 10 kWp × 0,85
        assertEquals(11.9, day.pvKwh!!, 1e-9)
        assertNull(f.days[1].tempMin)
        assertEquals(60.0, f.hours[0].sunshineMinutes, 1e-9)
        assertEquals(6.8, f.hours[0].pvKw!!, 1e-9)
        assertEquals("Regen", OpenMeteo.describe(f.days[1].weatherCode))
    }

    @Test
    fun noPvEstimateWithoutPeakPower() {
        assertNull(OpenMeteo.parse(body, kwp = 0.0, performanceRatio = 0.85).days[0].pvKwh)
    }

    @Test
    fun comparesVersions() {
        assertTrue(Updater.isNewer("0.2.5", "0.1.9"))
        assertTrue(Updater.isNewer("v0.2.10", "0.2.9"))
        assertFalse(Updater.isNewer("0.2.3", "0.2.3"))
    }
}
