package de.gun642.pvdashboard.meters

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

enum class MeterType(val label: String, val unit: String) {
    POWER("Strom", "kWh"),
    WATER("Wasser", "m³"),
}

data class MeterReading(
    val date: LocalDate,
    val value: Double,
    /** an den Anbieter gemeldet */
    val reported: Boolean = false,
    val comment: String = "",
)

/** Zählerstände, gespeichert als JSON im App-Speicher. */
class MeterStore(context: Context) {
    private val file = File(context.filesDir, "meters.json")

    fun load(): Map<MeterType, List<MeterReading>> {
        if (!file.exists()) return MeterType.entries.associateWith { emptyList() }
        return runCatching { fromJson(file.readText()) }.getOrElse { MeterType.entries.associateWith { emptyList() } }
    }

    fun save(data: Map<MeterType, List<MeterReading>>) {
        val tmp = File(file.parentFile, "meters.json.tmp")
        tmp.writeText(toJson(data))
        tmp.renameTo(file)
    }

    companion object {
        fun toJson(data: Map<MeterType, List<MeterReading>>): String {
            val root = JSONObject()
            data.forEach { (type, list) ->
                root.put(type.name, JSONArray().apply {
                    list.forEach { r ->
                        put(JSONObject().put("date", r.date.toString()).put("value", r.value).put("reported", r.reported).put("comment", r.comment))
                    }
                })
            }
            return root.toString()
        }

        fun fromJson(text: String): Map<MeterType, List<MeterReading>> {
            val root = JSONObject(text)
            return MeterType.entries.associateWith { type ->
                val arr = root.optJSONArray(type.name) ?: JSONArray()
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    MeterReading(LocalDate.parse(o.getString("date")), o.getDouble("value"), o.optBoolean("reported"), o.optString("comment"))
                }.sortedBy { it.date }
            }
        }
    }
}

/**
 * CSV im Format der bisherigen Zähler-App:
 * `Datum;Zählerstand;an den Anbieter gemeldet;Kommentar` mit `13.08.2025;283,2;1;`
 */
object MeterCsv {
    private val dateFormat = DateTimeFormatter.ofPattern("dd.MM.yyyy")

    fun parse(text: String): List<MeterReading> =
        text.removePrefix("﻿").lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { line ->
                val cols = line.split(';').map { it.trim().removeSurrounding("\"") }
                val date = runCatching { LocalDate.parse(cols[0], dateFormat) }.getOrNull() ?: return@mapNotNull null
                val value = cols.getOrNull(1)?.let(::parseNumber) ?: return@mapNotNull null
                MeterReading(date, value, cols.getOrNull(2) == "1", cols.getOrNull(3).orEmpty())
            }
            .sortedBy { it.date }
            .toList()

    /** "283,2" oder "1.234,5" (deutsch) bzw. "283.2" (Punkt als Dezimaltrenner). */
    fun parseNumber(raw: String): Double? {
        val t = raw.trim()
        return if (t.contains(',')) t.replace(".", "").replace(',', '.').toDoubleOrNull() else t.toDoubleOrNull()
    }

    fun format(readings: List<MeterReading>): String = buildString {
        append("Datum;Zählerstand;an den Anbieter gemeldet;Kommentar\n")
        readings.sortedBy { it.date }.forEach { r ->
            append(r.date.format(dateFormat)).append(';')
            append(formatValue(r.value)).append(';')
            append(if (r.reported) "1" else "0").append(';')
            append(r.comment.replace(';', ',').replace('\n', ' ')).append('\n')
        }
    }

    /** Wie im Export: ohne unnötige Nachkommastellen, mit Komma. */
    fun formatValue(v: Double): String =
        if (v == Math.floor(v)) String.format(Locale.GERMANY, "%.0f", v)
        else java.math.BigDecimal(v).setScale(3, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString().replace('.', ',')

    /** Führt neue Werte ein; bei gleichem Datum gewinnt der neue Wert. */
    fun merge(existing: List<MeterReading>, incoming: List<MeterReading>): List<MeterReading> =
        (existing.associateBy { it.date } + incoming.associateBy { it.date }).values.sortedBy { it.date }
}

/** Verbrauch aus Zählerständen: zwischen zwei Ablesungen gleichmäßig auf die Tage verteilt. */
object Consumption {

    /** Verbrauch je Monat. Zählerwechsel (Stand sinkt) werden übersprungen. */
    fun monthly(readings: List<MeterReading>): Map<YearMonth, Double> {
        val result = sortedMapOf<YearMonth, Double>()
        forEachDay(readings) { day, amount -> result.merge(YearMonth.from(day), amount, Double::plus) }
        return result
    }

    fun yearly(readings: List<MeterReading>): Map<Int, Double> =
        monthly(readings).entries.groupBy({ it.key.year }, { it.value }).mapValues { it.value.sum() }.toSortedMap()

    /** Durchschnitt je Tag in den letzten [days] Tagen vor der letzten Ablesung. */
    fun dailyAverage(readings: List<MeterReading>, days: Long = 365): Double? {
        val sorted = readings.sortedBy { it.date }
        val last = sorted.lastOrNull()?.date ?: return null
        val from = maxOf(last.minusDays(days), sorted.first().date)
        val span = ChronoUnit.DAYS.between(from, last)
        if (span <= 0) return null
        var sum = 0.0
        forEachDay(sorted) { day, amount -> if (!day.isBefore(from) && day.isBefore(last)) sum += amount }
        return sum / span
    }

    /**
     * Auffälliger Mehrverbrauch seit der letzten Ablesung, z. B. durch ein Leck.
     * Vergleicht den Verbrauch pro Tag im letzten Intervall mit dem Durchschnitt davor (bis zu 365 Tage).
     *
     * @param thresholdPercent Warnung ab so viel Prozent über dem Durchschnitt
     * @param minExcessPerDay Mindest-Mehrverbrauch pro Tag, damit Kleinstmengen nicht warnen
     */
    fun unusualIncrease(readings: List<MeterReading>, thresholdPercent: Int, minExcessPerDay: Double = 0.0): UsageWarning? {
        val sorted = readings.sortedBy { it.date }
        if (sorted.size < 3) return null
        val last = sorted[sorted.size - 1]
        val previous = sorted[sorted.size - 2]
        val days = ChronoUnit.DAYS.between(previous.date, last.date)
        if (days <= 0) return null
        val recent = (last.value - previous.value) / days
        if (recent < 0) return null
        val average = dailyAverage(sorted.dropLast(1)) ?: return null
        if (average <= 0) return null
        val limit = average * (1 + thresholdPercent / 100.0)
        return if (recent > limit && recent - average >= minExcessPerDay) UsageWarning(recent, average, previous.date, last.date) else null
    }

    /** Hochrechnung auf ein Jahr aus dem Verbrauch der letzten 365 Tage (bzw. der vorhandenen Daten). */
    fun yearlyProjection(readings: List<MeterReading>): Double? = dailyAverage(readings)?.let { it * 365 }

    private inline fun forEachDay(readings: List<MeterReading>, action: (LocalDate, Double) -> Unit) {
        val sorted = readings.sortedBy { it.date }
        for (i in 1 until sorted.size) {
            val a = sorted[i - 1]
            val b = sorted[i]
            val days = ChronoUnit.DAYS.between(a.date, b.date)
            val delta = b.value - a.value
            if (days <= 0 || delta < 0) continue
            val perDay = delta / days
            var day = a.date
            while (day.isBefore(b.date)) {
                action(day, perDay)
                day = day.plusDays(1)
            }
        }
    }
}

/** Deutlich höherer Verbrauch pro Tag im letzten Ableseintervall als im Durchschnitt davor. */
data class UsageWarning(val recentPerDay: Double, val averagePerDay: Double, val from: LocalDate, val to: LocalDate) {
    val percentAbove: Double get() = (recentPerDay / averagePerDay - 1) * 100
}

/** Wassertarif: Frischwasser und Abwasser je m³ plus Grundgebühr. */
data class WaterTariff(val provider: String, val pricePerM3: Double, val wastewaterPerM3: Double, val baseFeePerMonth: Double) {
    val configured: Boolean get() = pricePerM3 > 0 || wastewaterPerM3 > 0
    fun cost(m3: Double, months: Double) = m3 * (pricePerM3 + wastewaterPerM3) + baseFeePerMonth * months
}
