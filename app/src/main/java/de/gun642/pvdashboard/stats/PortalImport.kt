package de.gun642.pvdashboard.stats

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Import der Wochendateien aus dem alten SENEC-Portal (mein-senec.de): ein Leistungsverlauf in kW im
 * 5-Minuten-Takt, Semikolon-getrennt, Dezimalkomma, z. B.
 * `Uhrzeit;Netzbezug [kW];Netzeinspeisung [kW];Stromverbrauch [kW];Akkubeladung [kW];Akkuentnahme [kW];Stromerzeugung [kW];…`
 *
 * Die Leistung wird über die Zeit zu Energie (kWh) aufsummiert und zu Monaten zusammengefasst.
 */
object PortalImport {
    private val timeFormat = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss")

    /** Größere Abstände zwischen zwei Messwerten gelten als Lücke und werden nicht hochgerechnet. */
    const val MAX_GAP_HOURS = 0.5

    /** Werte je Messpunkt in dieser Reihenfolge. */
    private const val PV = 0
    private const val CONSUMPTION = 1
    private const val GRID_IMPORT = 2
    private const val GRID_EXPORT = 3
    private const val BATTERY_CHARGE = 4
    private const val BATTERY_DISCHARGE = 5
    private const val VALUES = 6

    private class Sample(val time: LocalDateTime, val kw: DoubleArray)

    /** Ergebnis einer Datei: Summen je Monat, erster und letzter Messpunkt, Tage mit Werten. */
    private class FileData(
        val first: Sample,
        val last: Sample,
        val kwh: MutableMap<YearMonth, DoubleArray>,
        val days: MutableSet<LocalDate>,
    )

    /** Erkennt eine Portal-Datei an der Kopfzeile. */
    fun isPortalFile(text: String): Boolean {
        val header = text.lineSequence().firstOrNull()?.trim()?.trimStart('﻿')?.lowercase(Locale.GERMANY) ?: return false
        return header.startsWith("uhrzeit") && "netzbezug" in header && "stromerzeugung" in header
    }

    data class Result(
        val months: List<HistoryMonth>,
        /** Ausgewertete Dateien (ohne Duplikate und überlappende) */
        val files: Int,
        /** Nicht auswertbare, doppelte oder überlappende Dateien */
        val skipped: Int,
        /** Monate mit fehlenden Tagen: Monat → Tage mit Werten */
        val incomplete: Map<YearMonth, Int>,
    )

    /**
     * Fasst mehrere Dateien zusammen. [texts] in beliebiger Reihenfolge; Messwerte ab [cutoff]
     * (Beginn der aktuellen Anlage in der Cloud) werden ignoriert, damit nichts doppelt zählt.
     */
    fun combine(texts: Sequence<String>, cutoff: LocalDateTime? = null): Result {
        val files = mutableListOf<FileData>()
        var skipped = 0
        texts.forEach { text ->
            val data = parseFile(text, cutoff)
            if (data == null) skipped++ else files += data
        }
        files.sortBy { it.first.time }

        val total = mutableMapOf<YearMonth, DoubleArray>()
        val days = mutableSetOf<LocalDate>()
        var previous: FileData? = null
        var used = 0
        for (f in files) {
            val p = previous
            if (p != null) {
                // Überlappende oder doppelt heruntergeladene Datei: auslassen
                if (!f.first.time.isAfter(p.last.time)) {
                    skipped++
                    continue
                }
                add(total, p.last, f.first)
            }
            f.kwh.forEach { (month, values) ->
                val acc = total.getOrPut(month) { DoubleArray(VALUES) }
                for (i in 0 until VALUES) acc[i] += values[i]
            }
            days += f.days
            previous = f
            used++
        }

        val months = total.map { (month, v) ->
            HistoryMonth(
                month,
                EnergyTotals(
                    pv = v[PV], consumption = v[CONSUMPTION], gridImport = v[GRID_IMPORT], gridExport = v[GRID_EXPORT],
                    batteryCharge = v[BATTERY_CHARGE], batteryDischarge = v[BATTERY_DISCHARGE],
                ),
            )
        }.sortedBy { it.month }

        // Vollständigkeit: Tage mit Messwerten je Monat gegen Monatslänge (der letzte Monat vor dem Stichtag kann kürzer sein)
        val perMonth = days.groupingBy { YearMonth.from(it) }.eachCount()
        val incomplete = perMonth.filter { (month, n) ->
            val expected = if (cutoff != null && YearMonth.from(cutoff) == month) cutoff.dayOfMonth - 1 else month.lengthOfMonth()
            n < expected - 1
        }
        return Result(months, used, skipped, incomplete.toSortedMap())
    }

    private fun parseFile(text: String, cutoff: LocalDateTime?): FileData? {
        val lines = text.lineSequence().map { it.trim().trimStart('﻿') }.filter { it.isNotEmpty() }.iterator()
        if (!lines.hasNext()) return null
        val header = lines.next().split(';').map { it.trim().lowercase(Locale.GERMANY) }
        fun col(prefix: String) = header.indexOfFirst { it.startsWith(prefix) }
        val columns = intArrayOf(
            col("stromerzeugung"), col("stromverbrauch"), col("netzbezug"), col("netzeinspeisung"),
            col("akkubeladung"), col("akkuentnahme"),
        )
        if (col("uhrzeit") != 0 || columns[PV] < 0 || columns[CONSUMPTION] < 0 || columns[GRID_IMPORT] < 0) return null

        val kwh = mutableMapOf<YearMonth, DoubleArray>()
        val days = mutableSetOf<LocalDate>()
        var first: Sample? = null
        var last: Sample? = null
        while (lines.hasNext()) {
            val cells = lines.next().split(';')
            val time = runCatching { LocalDateTime.parse(cells[0].trim(), timeFormat) }.getOrNull() ?: continue
            if (cutoff != null && !time.isBefore(cutoff)) continue
            val kw = DoubleArray(VALUES) { i ->
                columns[i].takeIf { it >= 0 && it < cells.size }?.let { HistoryCsv.number(cells[it]) } ?: 0.0
            }
            val sample = Sample(time, kw)
            val prev = last
            // Nur zeitlich aufsteigende Werte (Zeitumstellung im Herbst wiederholt eine Stunde)
            if (prev != null && !time.isAfter(prev.time)) continue
            if (prev != null) add(kwh, prev, sample)
            if (first == null) first = sample
            last = sample
            days += time.toLocalDate()
        }
        return if (first != null && last != null) FileData(first, last, kwh, days) else null
    }

    /** Energie zwischen zwei Messpunkten (Trapez), dem Monat des früheren Punktes zugerechnet. */
    private fun add(into: MutableMap<YearMonth, DoubleArray>, a: Sample, b: Sample) {
        val hours = Duration.between(a.time, b.time).seconds / 3600.0
        if (hours <= 0 || hours > MAX_GAP_HOURS) return
        val acc = into.getOrPut(YearMonth.from(a.time)) { DoubleArray(VALUES) }
        for (i in 0 until VALUES) acc[i] += (a.kw[i] + b.kw[i]) / 2 * hours
    }
}
