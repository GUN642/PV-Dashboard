package de.gun642.pvdashboard.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class YearCompareTest {
    private fun months(vararg pv: Double) = pv.mapIndexed { i, v -> StatsBucket(i + 1, "M${i + 1}", EnergyTotals(pv = v)) }

    private fun result(period: Period, buckets: List<StatsBucket>, dataStart: LocalDate?) = StatsResult(
        period = period,
        totals = buckets.fold(EnergyTotals()) { acc, b -> acc + b.totals },
        buckets = buckets,
        billingMonths = 12.0,
        rawJson = "",
        dataStart = dataStart,
    )

    @Test
    fun previousPeriodIsOneYearEarlier() {
        val p = Period(PeriodType.MONTH, LocalDate.of(2026, 9, 26))
        assertEquals(Period(PeriodType.MONTH, LocalDate.of(2025, 9, 26)), YearCompare.previousPeriod(p))
        assertNull(YearCompare.previousPeriod(Period(PeriodType.TOTAL, LocalDate.of(2026, 1, 1))))
    }

    @Test
    fun bucketValuesMatchByIndex() {
        val current = months(1.0, 2.0, 3.0)
        val previous = months(10.0, 20.0)
        assertEquals(listOf(10.0, 20.0, 0.0), YearCompare.bucketValues(current, previous))
    }

    @Test
    fun toDateCountsPastMonthsAndCurrentMonthProRata() {
        val period = Period(PeriodType.YEAR, LocalDate.of(2026, 1, 1))
        val previous = months(100.0, 200.0, 300.0, 400.0)
        // 16. März 00:00: Januar + Februar voll, März zu 15/31
        val v = YearCompare.toDate(period, previous, LocalDateTime.of(2026, 3, 16, 0, 0))
        assertEquals(300.0 + 300.0 * 15 / 31, v, 1e-9)
    }

    @Test
    fun toDateOfFinishedPeriodIsFullSum() {
        val period = Period(PeriodType.YEAR, LocalDate.of(2025, 1, 1))
        assertEquals(600.0, YearCompare.toDate(period, months(100.0, 200.0, 300.0), LocalDateTime.of(2026, 3, 16, 0, 0)), 1e-9)
    }

    @Test
    fun comparisonNeedsDataForWholePreviousPeriod() {
        val prev = Period(PeriodType.YEAR, LocalDate.of(2025, 1, 1))
        assertTrue(YearCompare.complete(result(prev, months(100.0), LocalDate.of(2024, 5, 1))))
        assertFalse(YearCompare.complete(result(prev, months(0.0, 100.0), LocalDate.of(2025, 2, 10))))
        assertTrue(YearCompare.available(result(prev, months(0.0, 100.0), LocalDate.of(2025, 2, 10))))
        assertFalse(YearCompare.available(result(prev, months(0.0), null)))
    }
}
