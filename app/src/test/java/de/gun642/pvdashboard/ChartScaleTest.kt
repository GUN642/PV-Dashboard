package de.gun642.pvdashboard

import de.gun642.pvdashboard.ui.ChartScale
import de.gun642.pvdashboard.ui.formatChartValue
import org.junit.Assert.assertEquals
import org.junit.Test

class ChartScaleTest {
    @Test
    fun noiseStaysSmall() {
        // Messrauschen von 3 Wh je Stunde: Skala reicht trotzdem bis mind. 0,5 kWh (gerundet 0,6)
        val s = ChartScale.of(0.003, minMax = 0.5)
        assertEquals(0.2, s.step, 1e-9)
        assertEquals(0.6, s.max, 1e-9)
        assertEquals(4, s.ticks.size)
        assertEquals("0,4", s.label(0.4))
    }

    @Test
    fun roundSteps() {
        val s = ChartScale.of(7.3, minMax = 0.5)
        assertEquals(2.0, s.step, 1e-9)
        assertEquals(8.0, s.max, 1e-9)
        assertEquals(listOf(0.0, 2.0, 4.0, 6.0, 8.0), s.ticks)
        assertEquals("4", s.label(4.0))

        val big = ChartScale.of(1234.0, minMax = 50.0)
        assertEquals(500.0, big.step, 1e-9)
        assertEquals(1500.0, big.max, 1e-9)
    }

    @Test
    fun formatsValues() {
        assertEquals("0 kWh", formatChartValue(0.0, "kWh"))
        assertEquals("0,05 kWh", formatChartValue(0.05, "kWh"))
        assertEquals("12,3 m³", formatChartValue(12.34, "m³"))
        assertEquals("1234 kWh", formatChartValue(1234.0, "kWh"))
    }
}
