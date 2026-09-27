package de.gun642.pvdashboard.wallbox

import de.gun642.pvdashboard.senec.cloud.MeasurementPoint
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

class ChargeLogTest {
    private val t0 = Instant.parse("2026-09-01T08:00:00Z")

    private fun hour(i: Int, wallbox: Double, pv: Double = 0.0, import: Double = 0.0, export: Double = 0.0) =
        MeasurementPoint(
            t0.plusSeconds(3600L * i), 3600,
            mapOf("WALLBOX_CONSUMPTION" to wallbox, "POWER_GENERATION" to pv, "GRID_IMPORT" to import, "GRID_EXPORT" to export),
        )

    @Test
    fun groupsConsecutiveHoursAndSplitsOnLongGaps() {
        val points = listOf(
            hour(0, 5.0, pv = 6.0),              // voll solar
            hour(1, 0.0),                        // kurze Pause
            hour(2, 4.0, pv = 0.0, import = 5.0),// voll Netz
            hour(3, 0.0), hour(4, 0.0),          // lange Pause → neuer Vorgang
            hour(5, 2.0, pv = 1.0, import = 1.0),// halb/halb
        )
        val sessions = ChargeLog.sessions(points, ZoneOffset.UTC)
        assertEquals(2, sessions.size)
        assertEquals(9.0, sessions[0].kwh, 1e-9)
        assertEquals(5.0, sessions[0].solarKwh, 1e-9)
        assertEquals(8, sessions[0].start.hour)
        assertEquals(11, sessions[0].end.hour)
        assertEquals(0.5, sessions[1].solarShare, 1e-9)
    }

    @Test
    fun costCountsGridPriceAndLostFeedIn() {
        val time = t0.atOffset(ZoneOffset.UTC).toLocalDateTime()
        val s = ChargeSession(time, time, 10.0, 6.0)
        // 4 kWh × 30 ct + 6 kWh × 8 ct
        assertEquals(1.2 + 0.48, s.cost(30.0, 8.0), 1e-9)
    }
}
