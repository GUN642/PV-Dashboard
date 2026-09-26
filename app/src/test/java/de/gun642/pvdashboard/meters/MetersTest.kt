package de.gun642.pvdashboard.meters

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class MetersTest {
    // Beispieldaten im Format des Exports der bisherigen Zähler-App (Werte ausgedacht)
    private val csv = "﻿Datum;Zählerstand;an den Anbieter gemeldet;Kommentar\n" +
        "01.01.2025;100;1;Start\n" +
        "01.02.2025;131;0;\n" +
        "01.03.2025;159,5;1;\n" +
        "\n"

    @Test
    fun parsesExportFormat() {
        val readings = MeterCsv.parse(csv)
        assertEquals(3, readings.size)
        assertEquals(LocalDate.of(2025, 1, 1), readings[0].date)
        assertEquals(100.0, readings[0].value, 1e-9)
        assertTrue(readings[0].reported)
        assertEquals("Start", readings[0].comment)
        assertEquals(159.5, readings[2].value, 1e-9)
        assertFalse(readings[1].reported)
    }

    @Test
    fun parsesNumbers() {
        assertEquals(1234.5, MeterCsv.parseNumber("1.234,5")!!, 1e-9)
        assertEquals(283.2, MeterCsv.parseNumber("283.2")!!, 1e-9)
        assertEquals(15152.0, MeterCsv.parseNumber("15152")!!, 1e-9)
    }

    @Test
    fun formatRoundTrips() {
        val readings = MeterCsv.parse(csv)
        assertEquals(readings, MeterCsv.parse(MeterCsv.format(readings)))
        assertEquals("159,5", MeterCsv.formatValue(159.5))
        assertEquals("100", MeterCsv.formatValue(100.0))
    }

    @Test
    fun distributesConsumptionOverMonths() {
        val monthly = Consumption.monthly(MeterCsv.parse(csv))
        // 31 kWh im Januar (31 Tage), 28,5 im Februar
        assertEquals(31.0, monthly.getValue(YearMonth.of(2025, 1)), 1e-9)
        assertEquals(28.5, monthly.getValue(YearMonth.of(2025, 2)), 1e-9)
        assertEquals(59.5, Consumption.yearly(MeterCsv.parse(csv)).getValue(2025), 1e-9)
    }

    @Test
    fun splitsIntervalAcrossMonthBoundary() {
        // 20 Einheiten vom 21.01. bis 10.02. = 20 Tage: 11 im Januar, 9 im Februar
        val readings = listOf(MeterReading(LocalDate.of(2025, 1, 21), 0.0), MeterReading(LocalDate.of(2025, 2, 10), 20.0))
        val monthly = Consumption.monthly(readings)
        assertEquals(11.0, monthly.getValue(YearMonth.of(2025, 1)), 1e-9)
        assertEquals(9.0, monthly.getValue(YearMonth.of(2025, 2)), 1e-9)
    }

    @Test
    fun skipsMeterReplacement() {
        val readings = listOf(
            MeterReading(LocalDate.of(2025, 1, 1), 500.0),
            MeterReading(LocalDate.of(2025, 1, 11), 510.0),
            MeterReading(LocalDate.of(2025, 1, 21), 3.0), // neuer Zähler
            MeterReading(LocalDate.of(2025, 1, 31), 13.0),
        )
        assertEquals(20.0, Consumption.monthly(readings).getValue(YearMonth.of(2025, 1)), 1e-9)
    }

    @Test
    fun projectsYear() {
        val readings = listOf(MeterReading(LocalDate.of(2025, 1, 1), 0.0), MeterReading(LocalDate.of(2025, 1, 11), 10.0))
        assertEquals(1.0, Consumption.dailyAverage(readings)!!, 1e-9)
        assertEquals(365.0, Consumption.yearlyProjection(readings)!!, 1e-9)
    }

    @Test
    fun mergeReplacesSameDate() {
        val a = listOf(MeterReading(LocalDate.of(2025, 1, 1), 1.0), MeterReading(LocalDate.of(2025, 2, 1), 2.0))
        val b = listOf(MeterReading(LocalDate.of(2025, 2, 1), 3.0), MeterReading(LocalDate.of(2025, 3, 1), 4.0))
        assertEquals(listOf(1.0, 3.0, 4.0), MeterCsv.merge(a, b).map { it.value })
    }

    @Test
    fun waterCost() {
        val t = WaterTariff("Stadtwerke", pricePerM3 = 2.0, wastewaterPerM3 = 3.0, baseFeePerMonth = 5.0)
        assertEquals(10 * 5.0 + 12 * 5.0, t.cost(10.0, 12.0), 1e-9)
    }

    @Test
    fun jsonRoundTrip() {
        val data = mapOf(MeterType.POWER to MeterCsv.parse(csv), MeterType.WATER to emptyList())
        assertEquals(data, MeterStore.fromJson(MeterStore.toJson(data)))
    }
}

class LeakCheckTest {
    private fun r(day: Int, value: Double) = MeterReading(LocalDate.of(2025, 1, 1).plusDays(day.toLong()), value)

    @Test
    fun warnsOnClearIncrease() {
        // 100 Tage mit 0,2 m³/Tag, danach 10 Tage mit 0,5 m³/Tag
        val readings = listOf(r(0, 0.0), r(50, 10.0), r(100, 20.0), r(110, 25.0))
        val w = Consumption.unusualIncrease(readings, thresholdPercent = 50, minExcessPerDay = 0.05)!!
        assertEquals(0.5, w.recentPerDay, 1e-9)
        assertEquals(0.2, w.averagePerDay, 1e-9)
        assertEquals(150.0, w.percentAbove, 1e-6)
    }

    @Test
    fun noWarningForNormalUsage() {
        val readings = listOf(r(0, 0.0), r(50, 10.0), r(100, 20.0), r(110, 22.5))
        assertEquals(null, Consumption.unusualIncrease(readings, thresholdPercent = 50))
    }

    @Test
    fun noWarningWithTooLittleHistory() {
        assertEquals(null, Consumption.unusualIncrease(listOf(r(0, 0.0), r(10, 50.0)), thresholdPercent = 50))
    }
}
