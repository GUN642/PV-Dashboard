package de.gun642.pvdashboard.stats

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class AdvanceCheckTest {
    @Test
    fun periodStartIsLatestStartMonthNotAfterToday() {
        assertEquals(YearMonth.of(2026, 3), AdvanceCheck.currentPeriodStart(3, LocalDate.of(2026, 9, 27)))
        assertEquals(YearMonth.of(2025, 11), AdvanceCheck.currentPeriodStart(11, LocalDate.of(2026, 9, 27)))
        assertEquals(YearMonth.of(2026, 1), AdvanceCheck.currentPeriodStart(1, LocalDate.of(2026, 1, 1)))
    }

    @Test
    fun projectsWithActualAndPreviousYearValues() {
        // Abrechnung ab Januar, heute 1. März: Jan + Feb gemessen, März bis Dez aus dem Vorjahr
        val data = mutableMapOf<YearMonth, Double>()
        (1..12).forEach { data[YearMonth.of(2025, it)] = 100.0 }
        data[YearMonth.of(2026, 1)] = 200.0
        data[YearMonth.of(2026, 2)] = 150.0
        val check = AdvanceCheck.of(1, data, pricePerUnit = 0.30, baseFeePerMonth = 10.0, advance = 40.0, today = LocalDate.of(2026, 3, 1))
        // 200 + 150 + März (0 bisher + 100 Rest) + 9 × 100
        assertEquals(1350.0, check.expectedKwh, 1e-9)
        assertEquals(1350 * 0.30 + 120, check.expectedCost, 1e-9)
        assertEquals(480 - (405.0 + 120), check.difference, 1e-9)
        assertEquals(check.expectedCost / 12, check.neededAdvance, 1e-9)
        // 3 Abschläge gezahlt (Jan–März), Rest auf 9 Monate verteilt
        assertEquals(3, check.paymentsMade)
        assertEquals((525.0 - 120) / 9, check.neededFromNow!!, 1e-9)
    }

    @Test
    fun withoutPreviousYearUsesAverageOfKnownMonths() {
        val data = mapOf(YearMonth.of(2026, 1) to 120.0, YearMonth.of(2026, 2) to 80.0)
        val check = AdvanceCheck.of(1, data, 0.30, 0.0, 30.0, today = LocalDate.of(2026, 3, 1))
        // März ohne Werte am 1.: Durchschnitt; April–Dez Durchschnitt 100
        assertEquals(120.0 + 80.0 + 10 * 100.0, check.expectedKwh, 1e-9)
        assertEquals(MonthSource.AVERAGE, check.months.last().source)
    }

    @Test
    fun currentMonthWithoutValuesIsEstimatedCompletely() {
        // Wasser: im laufenden Monat noch nicht abgelesen → ganzer Monat wie im Vorjahr
        val data = mapOf(YearMonth.of(2025, 9) to 12.0, YearMonth.of(2026, 8) to 10.0)
        val check = AdvanceCheck.of(1, data, 5.0, 0.0, 50.0, today = LocalDate.of(2026, 9, 20))
        val sep = check.months.first { it.month == YearMonth.of(2026, 9) }
        assertEquals(12.0, sep.kwh, 1e-9)
        assertEquals(MonthSource.PREVIOUS_YEAR, sep.source)
    }
}
