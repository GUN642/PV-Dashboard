package de.gun642.pvdashboard.stats

import java.time.LocalDateTime

/** Vergleich eines Zeitraums mit demselben Zeitraum im Vorjahr. */
object YearCompare {

    /** Derselbe Tag, Monat bzw. dasselbe Jahr ein Jahr früher; beim Gesamtzeitraum keiner. */
    fun previousPeriod(period: Period): Period? =
        if (period.type == PeriodType.TOTAL) null else period.copy(anchor = period.anchor.minusYears(1))

    /** Gibt es im Vorjahr überhaupt PV-Daten? */
    fun available(previous: StatsResult?): Boolean = previous != null && previous.totals.pv > 0

    /** Deckt die Aufzeichnung den ganzen Vorjahreszeitraum ab (sonst wäre ein Summenvergleich schief)? */
    fun complete(previous: StatsResult): Boolean =
        available(previous) && (previous.dataStart == null || !previous.dataStart.isAfter(previous.period.start))

    /** Vorjahres-PV je Balken, über den Index zugeordnet (Stunde, Tag bzw. Monat). */
    fun bucketValues(current: List<StatsBucket>, previous: List<StatsBucket>): List<Double> {
        val byIndex = previous.associate { it.index to it.totals.pv }
        return current.map { byIndex[it.index] ?: 0.0 }
    }

    /**
     * Vorjahres-PV bis zum selben Zeitpunkt: bei einem laufenden Zeitraum die bereits
     * vergangenen Balken plus den laufenden anteilig, sonst die ganze Summe.
     */
    fun toDate(period: Period, previous: List<StatsBucket>, now: LocalDateTime = LocalDateTime.now()): Double {
        val today = now.toLocalDate()
        if (!period.end.isAfter(today)) return previous.sumOf { it.totals.pv }
        if (period.start.isAfter(today)) return 0.0
        val hourOfDay = now.hour + now.minute / 60.0
        val (current, fraction) = when (period.type) {
            PeriodType.DAY -> now.hour to now.minute / 60.0
            PeriodType.MONTH -> now.dayOfMonth to hourOfDay / 24
            PeriodType.YEAR -> now.monthValue to (now.dayOfMonth - 1 + hourOfDay / 24) / today.lengthOfMonth()
            PeriodType.TOTAL -> return previous.sumOf { it.totals.pv }
        }
        return previous.sumOf { b ->
            when {
                b.index < current -> b.totals.pv
                b.index == current -> b.totals.pv * fraction
                else -> 0.0
            }
        }
    }
}

/** Reihen, die sich zusätzlich zur PV-Erzeugung als Balken einblenden lassen. */
enum class EnergySeries(val label: String, val value: (EnergyTotals) -> Double) {
    CONSUMPTION("Verbrauch", { it.consumption }),
    GRID_IMPORT("Netzbezug", { it.gridImport }),
    GRID_EXPORT("Einspeisung", { it.gridExport }),
    BATTERY_CHARGE("Speicher geladen", { it.batteryCharge }),
    BATTERY_DISCHARGE("Speicher entladen", { it.batteryDischarge }),
    WALLBOX("Wallbox", { it.wallbox }),
}
