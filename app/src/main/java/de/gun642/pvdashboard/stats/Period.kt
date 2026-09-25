package de.gun642.pvdashboard.stats

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

enum class PeriodType(val label: String) { DAY("Tag"), MONTH("Monat"), YEAR("Jahr"), TOTAL("Gesamt") }

/** Ein Auswertungszeitraum, verankert an einem Datum. */
data class Period(val type: PeriodType, val anchor: LocalDate) {

    val start: LocalDate
        get() = when (type) {
            PeriodType.DAY -> anchor
            PeriodType.MONTH -> anchor.withDayOfMonth(1)
            PeriodType.YEAR, PeriodType.TOTAL -> anchor.withDayOfYear(1)
        }

    /** Exklusives Ende. */
    val end: LocalDate
        get() = when (type) {
            PeriodType.DAY -> start.plusDays(1)
            PeriodType.MONTH -> start.plusMonths(1)
            PeriodType.YEAR -> start.plusYears(1)
            PeriodType.TOTAL -> LocalDate.now().plusDays(1)
        }

    fun instants(zone: ZoneId): Pair<Instant, Instant> =
        start.atStartOfDay(zone).toInstant() to end.atStartOfDay(zone).toInstant()

    fun previous() = when (type) {
        PeriodType.DAY -> copy(anchor = anchor.minusDays(1))
        PeriodType.MONTH -> copy(anchor = anchor.minusMonths(1))
        PeriodType.YEAR -> copy(anchor = anchor.minusYears(1))
        PeriodType.TOTAL -> this
    }

    fun next() = when (type) {
        PeriodType.DAY -> copy(anchor = anchor.plusDays(1))
        PeriodType.MONTH -> copy(anchor = anchor.plusMonths(1))
        PeriodType.YEAR -> copy(anchor = anchor.plusYears(1))
        PeriodType.TOTAL -> this
    }

    fun hasNext(today: LocalDate = LocalDate.now()) = type != PeriodType.TOTAL && next().start <= today

    val label: String
        get() = when (type) {
            PeriodType.DAY -> {
                val today = LocalDate.now()
                when (anchor) {
                    today -> "Heute"
                    today.minusDays(1) -> "Gestern"
                    else -> "%s, %02d.%02d.%d".format(
                        anchor.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.GERMANY), anchor.dayOfMonth, anchor.monthValue, anchor.year,
                    )
                }
            }
            PeriodType.MONTH -> "${anchor.month.getDisplayName(TextStyle.FULL_STANDALONE, Locale.GERMANY)} ${anchor.year}"
            PeriodType.YEAR -> anchor.year.toString()
            PeriodType.TOTAL -> "Seit Inbetriebnahme"
        }

    /** Anzahl Monate (anteilig) für die Grundgebühr. Laufende Zeiträume zählen nur bis heute. */
    fun billingMonths(dataStart: LocalDate?, today: LocalDate = LocalDate.now()): Double {
        val from = if (type == PeriodType.TOTAL) (dataStart ?: start) else start
        val to = minOf(end, today.plusDays(1))
        if (!to.isAfter(from)) return 0.0
        var months = 0.0
        var cursor = from
        while (cursor.isBefore(to)) {
            val ym = YearMonth.from(cursor)
            val monthEnd = ym.plusMonths(1).atDay(1)
            val sliceEnd = minOf(monthEnd, to)
            months += (sliceEnd.toEpochDay() - cursor.toEpochDay()).toDouble() / ym.lengthOfMonth()
            cursor = sliceEnd
        }
        return months
    }

    companion object {
        fun today(type: PeriodType = PeriodType.DAY) = Period(type, LocalDate.now())
    }
}

/** Ein Balken im Diagramm, z. B. eine Stunde, ein Tag oder ein Monat. */
data class StatsBucket(val index: Int, val label: String, val totals: EnergyTotals)

data class StatsResult(
    val period: Period,
    val totals: EnergyTotals,
    val buckets: List<StatsBucket>,
    val billingMonths: Double,
    val rawJson: String,
    /** Beginn der Aufzeichnung (für Soll-Werte im Gesamtzeitraum) */
    val dataStart: LocalDate? = null,
)
