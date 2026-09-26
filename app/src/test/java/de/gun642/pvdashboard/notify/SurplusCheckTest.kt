package de.gun642.pvdashboard.notify

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SurplusCheckTest {
    @Test
    fun fullBatteryWithExport() {
        assertTrue(SurplusCheck.decide(soc = 100.0, gridW = -1500.0, socThreshold = 95, exportThreshold = 1000).notify)
    }

    @Test
    fun fullBatteryButTooLittleExport() {
        val d = SurplusCheck.decide(soc = 100.0, gridW = -300.0, socThreshold = 95, exportThreshold = 1000)
        assertFalse(d.notify)
        assertTrue(d.reason, d.reason.contains("Einspeisung"))
    }

    @Test
    fun zeroExportThresholdChecksBatteryOnly() {
        // Akku voll, leichter Netzbezug durch Messrauschen: trotzdem Hinweis
        assertTrue(SurplusCheck.decide(soc = 100.0, gridW = 20.0, socThreshold = 95, exportThreshold = 0).notify)
        assertFalse(SurplusCheck.decide(soc = 80.0, gridW = -3000.0, socThreshold = 95, exportThreshold = 0).notify)
    }

    @Test
    fun missingSoc() {
        assertFalse(SurplusCheck.decide(soc = null, gridW = -3000.0, socThreshold = 95, exportThreshold = 0).notify)
    }
}
