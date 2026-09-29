package de.gun642.pvdashboard.stats

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.YearMonth
import java.util.Locale

/**
 * Monatswerte einer früheren Anlage (z. B. vor einem Speichertausch), die die SENEC-App-Schnittstelle
 * nicht mehr liefert. Sie ergänzen die Statistik für Jahr, Gesamt und Vorjahresvergleich.
 */
data class HistoryMonth(val month: YearMonth, val totals: EnergyTotals)

/** Monatswerte als JSON im App-Speicher. */
class HistoryStore(context: Context) {
    private val file = File(context.filesDir, "history.json")

    fun load(): List<HistoryMonth> =
        if (!file.exists()) emptyList() else runCatching { fromJson(file.readText()) }.getOrDefault(emptyList())

    fun save(list: List<HistoryMonth>) {
        val tmp = File(file.parentFile, "history.json.tmp")
        tmp.writeText(toJson(list))
        tmp.renameTo(file)
    }

    companion object {
        fun toJson(list: List<HistoryMonth>): String = JSONArray().apply {
            list.sortedBy { it.month }.forEach { h ->
                put(
                    JSONObject().put("month", h.month.toString())
                        .put("pv", h.totals.pv).put("consumption", h.totals.consumption)
                        .put("gridImport", h.totals.gridImport).put("gridExport", h.totals.gridExport)
                        .put("batteryCharge", h.totals.batteryCharge).put("batteryDischarge", h.totals.batteryDischarge)
                        .put("wallbox", h.totals.wallbox),
                )
            }
        }.toString()

        fun fromJson(text: String): List<HistoryMonth> {
            val arr = JSONArray(text)
            return (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val month = runCatching { YearMonth.parse(o.optString("month")) }.getOrNull() ?: return@mapNotNull null
                HistoryMonth(
                    month,
                    EnergyTotals(
                        o.optDouble("pv", 0.0), o.optDouble("consumption", 0.0), o.optDouble("gridImport", 0.0),
                        o.optDouble("gridExport", 0.0), o.optDouble("batteryCharge", 0.0), o.optDouble("batteryDischarge", 0.0),
                        o.optDouble("wallbox", 0.0),
                    ),
                )
            }
        }

        /** Bei gleichem Monat gewinnt der neue Wert. */
        fun merge(existing: List<HistoryMonth>, incoming: List<HistoryMonth>): List<HistoryMonth> =
            (existing.associateBy { it.month } + incoming.associateBy { it.month }).values.sortedBy { it.month }
    }
}

/**
 * CSV: `Jahr;Monat;PV;Verbrauch;Netzbezug;Einspeisung;Speicher geladen;Speicher entladen;Wallbox`, Werte in kWh.
 * Monat als Zahl 1–12 oder deutscher Name; Trennzeichen Semikolon, Tab oder Komma (Dezimalkomma bei Semikolon).
 */
object HistoryCsv {
    const val HEADER = "Jahr;Monat;PV;Verbrauch;Netzbezug;Einspeisung;Speicher geladen;Speicher entladen;Wallbox"

    private val monthNames = listOf("jan", "feb", "mär", "mar", "apr", "mai", "jun", "jul", "aug", "sep", "okt", "nov", "dez")
    private val monthNumbers = listOf(1, 2, 3, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12)

    fun parse(text: String): List<HistoryMonth> =
        text.lineSequence().map { it.trim().trimStart('\uFEFF') }.filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { parseLine(it) }.toList()

    private fun parseLine(line: String): HistoryMonth? {
        val cells = when {
            ';' in line -> line.split(';')
            '\t' in line -> line.split('\t')
            else -> line.split(',')
        }.map { it.trim() }
        val year = cells.getOrNull(0)?.toIntOrNull()?.takeIf { it in 2000..2100 } ?: return null
        val month = parseMonth(cells.getOrNull(1) ?: return null) ?: return null
        fun v(i: Int) = cells.getOrNull(i)?.let(::number) ?: 0.0
        return HistoryMonth(YearMonth.of(year, month), EnergyTotals(v(2), v(3), v(4), v(5), v(6), v(7), v(8)))
    }

    private fun parseMonth(raw: String): Int? {
        raw.toIntOrNull()?.let { return it.takeIf { m -> m in 1..12 } }
        val key = raw.lowercase(Locale.GERMANY).take(3)
        val i = monthNames.indexOf(key)
        return if (i >= 0) monthNumbers[i] else null
    }

    /** „1.234,5“, „1234,5“ und „1234.5“ → 1234.5; leer oder ungültig → null. */
    fun number(raw: String): Double? {
        val t = raw.replace(" ", "").replace("\u00a0", "")
        if (t.isEmpty()) return null
        val normalized = when {
            ',' in t && '.' in t -> t.replace(".", "").replace(',', '.')
            ',' in t -> t.replace(',', '.')
            else -> t
        }
        return normalized.toDoubleOrNull()?.takeIf { it >= 0 }
    }

    fun format(list: List<HistoryMonth>): String = buildString {
        append(HEADER).append('\n')
        list.sortedBy { it.month }.forEach { h ->
            val t = h.totals
            append(String.format(Locale.GERMANY, "%d;%d;%.1f;%.1f;%.1f;%.1f;%.1f;%.1f;%.1f", h.month.year, h.month.monthValue,
                t.pv, t.consumption, t.gridImport, t.gridExport, t.batteryCharge, t.batteryDischarge, t.wallbox)).append('\n')
        }
    }
}
