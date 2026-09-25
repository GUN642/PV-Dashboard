package de.gun642.pvdashboard.stats

import de.gun642.pvdashboard.senec.cloud.MeasurementPoint
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class StatsTest {
    private val berlin = ZoneId.of("Europe/Berlin")

    private fun point(iso: String, pv: Double, consumption: Double = 0.0, gridImport: Double = 0.0, gridExport: Double = 0.0) =
        MeasurementPoint(
            Instant.parse(iso), 3600,
            mapOf("POWER_GENERATION" to pv, "POWER_CONSUMPTION" to consumption, "GRID_IMPORT" to gridImport, "GRID_EXPORT" to gridExport,
                "BATTERY_LEVEL_IN_PERCENT" to 80.0),
        )

    @Test
    fun bucketsYearByLocalMonth() {
        val period = Period(PeriodType.YEAR, LocalDate.of(2025, 6, 1))
        // 30.04. 22:00 UTC = 01.05. 00:00 in Berlin (Sommerzeit)
        val buckets = StatsRepository.bucketize(period, listOf(point("2025-04-30T22:00:00Z", 453.7)), berlin)
        assertEquals(12, buckets.size)
        assertEquals(453.7, buckets[4].totals.pv, 1e-9)
        assertEquals("Mai", buckets[4].label)
    }

    @Test
    fun bucketsFiveMinuteValuesIntoHours() {
        val period = Period(PeriodType.DAY, LocalDate.of(2025, 7, 1))
        val points = listOf(
            point("2025-07-01T08:00:00Z", 0.1),
            point("2025-07-01T08:05:00Z", 0.2),
            point("2025-07-01T09:00:00Z", 0.4),
        )
        val buckets = StatsRepository.bucketize(period, points, berlin)
        assertEquals(24, buckets.size)
        assertEquals(0.3, buckets[10].totals.pv, 1e-9) // 08:00 UTC = 10 Uhr
        assertEquals(0.4, buckets[11].totals.pv, 1e-9)
    }

    @Test
    fun computesCostsAndSavings() {
        val totals = EnergyTotals(pv = 500.0, consumption = 400.0, gridImport = 100.0, gridExport = 200.0)
        val tariff = Tariff("Test", baseFeePerMonth = 12.0, pricePerKwhCent = 30.0, feedInCent = 8.0)
        val cost = CostSummary.of(totals, tariff, months = 1.0)
        assertEquals(30.0, cost.gridCost, 1e-9)
        assertEquals(16.0, cost.feedInRevenue, 1e-9)
        assertEquals(26.0, cost.netCost, 1e-9) // 30 + 12 - 16
        assertEquals(132.0, cost.costWithoutPv, 1e-9) // 400 × 0,30 + 12
        assertEquals(106.0, cost.savings, 1e-9)
        assertEquals(0.75, totals.autarky!!, 1e-9)
        assertEquals(0.6, totals.selfConsumption!!, 1e-9)
    }

    @Test
    fun billingMonthsCountsOnlyUntilToday() {
        val today = LocalDate.of(2025, 3, 15)
        assertEquals(1.0, Period(PeriodType.MONTH, LocalDate.of(2025, 2, 1)).billingMonths(null, today), 1e-9)
        assertEquals(15.0 / 31, Period(PeriodType.MONTH, today).billingMonths(null, today), 1e-9)
        assertEquals(1.0 / 28, Period(PeriodType.DAY, LocalDate.of(2025, 2, 3)).billingMonths(null, today), 1e-9)
        assertEquals(2.0 + 15.0 / 31, Period(PeriodType.YEAR, today).billingMonths(null, today), 1e-9)
    }

    @Test
    fun navigatesPeriods() {
        val p = Period(PeriodType.MONTH, LocalDate.of(2025, 1, 20))
        assertEquals(LocalDate.of(2024, 12, 1), p.previous().start)
        assertEquals(LocalDate.of(2025, 2, 1), p.end)
    }
}
