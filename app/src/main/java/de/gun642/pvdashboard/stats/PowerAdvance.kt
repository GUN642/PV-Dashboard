package de.gun642.pvdashboard.stats

import java.time.LocalDate
import java.time.YearMonth

/** Woher ein Monatswert der Hochrechnung stammt. */
enum class MonthSource { ACTUAL, PARTIAL, PREVIOUS_YEAR, AVERAGE }

data class AdvanceMonth(val month: YearMonth, val kwh: Double, val source: MonthSource)

/**
 * Reicht der monatliche Stromabschlag? Hochrechnung des Netzbezugs für den laufenden
 * Abrechnungszeitraum (12 Monate) aus den SENEC-Monatswerten:
 * - vergangene Monate: tatsächlicher Netzbezug
 * - laufender Monat: bisheriger Bezug, Rest anteilig nach Vorjahresmonat (sonst nach bisherigem Tagesschnitt)
 * - kommende Monate: derselbe Monat im Vorjahr, sonst Durchschnitt der bekannten Monate
 */
data class PowerAdvanceCheck(
    val periodStart: YearMonth,
    val months: List<AdvanceMonth>,
    val pricePerKwhCent: Double,
    val baseFeePerMonth: Double,
    /** Abschlag je Monat in € */
    val advance: Double,
    /** Bereits gezahlte Abschläge (einer je Monat inkl. laufendem) */
    val paymentsMade: Int,
) {
    val periodEnd: YearMonth get() = periodStart.plusMonths(11)
    val expectedKwh: Double get() = months.sumOf { it.kwh }
    val expectedCost: Double get() = expectedKwh * pricePerKwhCent / 100 + baseFeePerMonth * 12
    val advanceTotal: Double get() = advance * 12

    /** Positiv = Guthaben, negativ = Nachzahlung */
    val difference: Double get() = advanceTotal - expectedCost

    /** Abschlag, der das ganze Jahr gereicht hätte */
    val neededAdvance: Double get() = expectedCost / 12

    /** Abschlag für die restlichen Monate, damit es am Ende genau aufgeht (null = keine Zahlung mehr offen) */
    val neededFromNow: Double?
        get() {
            val remaining = 12 - paymentsMade
            if (remaining <= 0) return null
            return ((expectedCost - advance * paymentsMade) / remaining).coerceAtLeast(0.0)
        }

    /** Anteil der Hochrechnung, der auf echten Messwerten beruht (0..1) */
    val measuredShare: Double
        get() = if (expectedKwh <= 0) 0.0 else months.filter { it.source == MonthSource.ACTUAL || it.source == MonthSource.PARTIAL }.sumOf { it.kwh } / expectedKwh

    companion object {
        /** Beginn des laufenden Abrechnungszeitraums, der im Monat [startMonth] (1–12) beginnt. */
        fun currentPeriodStart(startMonth: Int, today: LocalDate = LocalDate.now()): YearMonth {
            val thisYear = YearMonth.of(today.year, startMonth.coerceIn(1, 12))
            return if (thisYear.isAfter(YearMonth.from(today))) thisYear.minusYears(1) else thisYear
        }

        fun of(
            startMonth: Int,
            gridImport: Map<YearMonth, Double>,
            pricePerKwhCent: Double,
            baseFeePerMonth: Double,
            advance: Double,
            today: LocalDate = LocalDate.now(),
        ): PowerAdvanceCheck {
            val start = currentPeriodStart(startMonth, today)
            val current = YearMonth.from(today)
            // Durchschnitt aus den letzten 12 abgeschlossenen Monaten mit Werten
            val known = (1..12L).mapNotNull { gridImport[current.minusMonths(it)] }.filter { it > 0 }
            val average = if (known.isNotEmpty()) known.average() else 0.0

            val months = (0 until 12L).map { i ->
                val m = start.plusMonths(i)
                val previous = gridImport[m.minusYears(1)]?.takeIf { it > 0 }
                when {
                    m.isBefore(current) -> gridImport[m]?.let { AdvanceMonth(m, it, MonthSource.ACTUAL) }
                        ?: AdvanceMonth(m, previous ?: average, if (previous != null) MonthSource.PREVIOUS_YEAR else MonthSource.AVERAGE)
                    m == current -> {
                        val soFar = gridImport[m] ?: 0.0
                        val days = m.lengthOfMonth().toDouble()
                        val elapsed = (today.dayOfMonth - 1).toDouble()
                        val rest = when {
                            previous != null -> previous * (days - elapsed) / days
                            elapsed > 0 -> soFar / elapsed * (days - elapsed)
                            else -> average
                        }
                        AdvanceMonth(m, soFar + rest, MonthSource.PARTIAL)
                    }
                    previous != null -> AdvanceMonth(m, previous, MonthSource.PREVIOUS_YEAR)
                    else -> AdvanceMonth(m, average, MonthSource.AVERAGE)
                }
            }
            val paid = (months.indexOfFirst { it.month == current } + 1).coerceIn(0, 12)
            return PowerAdvanceCheck(start, months, pricePerKwhCent, baseFeePerMonth, advance, paid)
        }
    }
}
