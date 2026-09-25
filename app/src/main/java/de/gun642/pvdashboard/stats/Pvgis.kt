package de.gun642.pvdashboard.stats

import de.gun642.pvdashboard.data.Http
import de.gun642.pvdashboard.data.HttpException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale

/**
 * Langjähriger Mittelwert des PV-Ertrags laut PVGIS (EU-Kommission) als Soll-Wert.
 * [monthly] enthält den mittleren Ertrag je Monat (Januar bis Dezember) in kWh.
 */
class PvgisReference(val monthly: List<Double>) {
    init {
        require(monthly.size == 12) { "PVGIS braucht 12 Monatswerte" }
    }

    val yearly: Double get() = monthly.sum()

    fun month(month: Int): Double = monthly[month - 1]

    fun dailyAverage(date: LocalDate): Double = month(date.monthValue) / date.lengthOfMonth()

    /**
     * Soll-Ertrag von [from] (inklusive) bis [to] (exklusiv), höchstens bis [now].
     * Der laufende Tag zählt nur mit dem Anteil der Sonnenstunden, die schon vorbei sind.
     */
    fun expected(from: LocalDate, to: LocalDate, now: LocalDateTime = LocalDateTime.now()): Double {
        var sum = 0.0
        var day = from
        val today = now.toLocalDate()
        while (day.isBefore(to) && !day.isAfter(today)) {
            val share = if (day == today) daylightShare(now) else 1.0
            sum += dailyAverage(day) * share
            day = day.plusDays(1)
        }
        return sum
    }

    /** Soll-Wert je Balken im Diagramm (Tag → keine, Monat → je Tag, Jahr → je Monat, Gesamt → je Jahr). */
    fun bucketTargets(period: Period, buckets: List<StatsBucket>): List<Double>? = when (period.type) {
        PeriodType.DAY -> null
        PeriodType.MONTH -> buckets.map { dailyAverage(period.start.withDayOfMonth(it.index)) }
        PeriodType.YEAR -> buckets.map { month(it.index) }
        PeriodType.TOTAL -> buckets.map { yearly }
    }

    /** Soll-Wert für die Kopfzeile: beim Tag der volle Tagesdurchschnitt, sonst bis jetzt. */
    fun target(period: Period, dataStart: LocalDate?, now: LocalDateTime = LocalDateTime.now()): Double = when (period.type) {
        PeriodType.DAY -> dailyAverage(period.start)
        PeriodType.TOTAL -> expected(dataStart ?: period.start, period.end, now)
        else -> expected(period.start, period.end, now)
    }

    companion object {
        /** Grobe Verteilung des Tagesertrags: zwischen 7 und 19 Uhr linear. */
        fun daylightShare(now: LocalDateTime): Double =
            ((now.hour + now.minute / 60.0 - 7) / 12).coerceIn(0.0, 1.0)

        /** Liest Monatswerte aus Text, z. B. "360,5 577,6 …" oder "360.5;577.6;…". */
        fun parseMonthly(text: String): List<Double>? {
            val values = text.trim().split(Regex("[\\s;|]+"))
                .filter { it.isNotBlank() }
                .map { it.replace(',', '.').toDoubleOrNull() ?: return null }
            return values.takeIf { it.size == 12 }
        }

        fun formatMonthly(values: List<Double>): String =
            values.joinToString(" ") { String.format(Locale.GERMANY, "%.1f", it) }

        /**
         * Berechnet die Referenz über die PVGIS-API.
         * [azimuthFromSouth]: 0 = Süd, -90 = Ost, 90 = West.
         */
        suspend fun fetch(
            latitude: Double,
            longitude: Double,
            kwp: Double,
            lossPercent: Double,
            tilt: Int,
            azimuthFromSouth: Int,
        ): List<Double> = withContext(Dispatchers.IO) {
            val base = String.format(
                Locale.US,
                "https://re.jrc.ec.europa.eu/api/v5_3/PVcalc?lat=%.6f&lon=%.6f&peakpower=%.3f&loss=%.1f&angle=%d&aspect=%d&outputformat=json",
                latitude, longitude, kwp, lossPercent, tilt, azimuthFromSouth,
            )
            val http = Http()
            // Wie im PVGIS-6-Bericht mit SARAH3-Strahlungsdaten; falls nicht verfügbar, Standarddatenbank.
            var res = http.request("$base&raddatabase=PVGIS-SARAH3", readTimeoutMs = 60_000)
            if (res.code != 200) res = http.request(base, readTimeoutMs = 60_000)
            if (res.code != 200) throw HttpException(res.code, "PVGIS antwortet mit HTTP ${res.code}")
            parse(res.body)
        }

        fun parse(body: String): List<Double> {
            val fixed = JSONObject(body).getJSONObject("outputs").getJSONObject("monthly").getJSONArray("fixed")
            val byMonth = (0 until fixed.length()).associate { i ->
                val m = fixed.getJSONObject(i)
                m.getInt("month") to m.getDouble("E_m")
            }
            return (1..12).map { byMonth[it] ?: 0.0 }
        }    }
}
