package de.gun642.pvdashboard.stats

import de.gun642.pvdashboard.data.HttpException
import de.gun642.pvdashboard.senec.cloud.MeasurementPoint
import de.gun642.pvdashboard.senec.cloud.SenecCloud
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.util.Locale

/** Lädt Statistiken aus der SENEC-Cloud und fasst sie zu Balken zusammen. */
class StatsRepository(private val cloud: SenecCloud) {

    private val cache = mutableMapOf<Period, Pair<Long, StatsResult>>()

    suspend fun load(period: Period, force: Boolean = false): StatsResult {
        val now = System.currentTimeMillis()
        val cached = cache[period]
        // Abgeschlossene Zeiträume ändern sich nicht mehr, laufende werden nach 5 Minuten neu geladen.
        val finished = period.type != PeriodType.TOTAL && !period.end.isAfter(LocalDate.now())
        if (!force && cached != null && (finished || now - cached.first < 5 * 60_000)) return cached.second

        val zone = ZoneId.systemDefault()
        val dataStart = runCatching { cloud.dataStart() }.getOrNull()?.atZone(zone)?.toLocalDate()
        val result = if (period.type == PeriodType.TOTAL) loadTotal(period, zone, dataStart) else loadRange(period, zone, dataStart)
        cache[period] = now to result
        return result
    }

    private suspend fun loadRange(period: Period, zone: ZoneId, dataStart: LocalDate?): StatsResult {
        val (from, to) = period.instants(zone)
        val resolutions = when (period.type) {
            PeriodType.DAY -> listOf("HOUR", "FIVE_MINUTES")
            PeriodType.MONTH -> listOf("DAY")
            else -> listOf("MONTH")
        }
        var lastError: Exception? = null
        for (resolution in resolutions) {
            try {
                val series = cloud.measurements(resolution, from, to)
                val buckets = bucketize(period, series.points, zone)
                return StatsResult(
                    period = period,
                    totals = buckets.fold(EnergyTotals()) { acc, b -> acc + b.totals },
                    buckets = buckets,
                    billingMonths = period.billingMonths(dataStart),
                    rawJson = series.rawJson,
                )
            } catch (e: HttpException) {
                // Nicht jede Auflösung wird vom Server angeboten – dann die nächste probieren.
                lastError = e
                if (e.code !in 400..499 || e.code == 401) throw e
            }
        }
        throw lastError ?: IllegalStateException("Keine Daten")
    }

    /** Gesamtzeitraum: Jahr für Jahr in Monatsauflösung laden (wie die SENEC-App). */
    private suspend fun loadTotal(period: Period, zone: ZoneId, dataStart: LocalDate?): StatsResult {
        val currentYear = LocalDate.now().year
        val firstYear = (dataStart?.year ?: (currentYear - 4)).coerceIn(2015, currentYear)
        val buckets = mutableListOf<StatsBucket>()
        val raw = StringBuilder()
        for (year in firstYear..currentYear) {
            val yearPeriod = Period(PeriodType.YEAR, LocalDate.of(year, 1, 1))
            val (from, to) = yearPeriod.instants(zone)
            val series = cloud.measurements("MONTH", from, to)
            val total = series.points.fold(EnergyTotals()) { acc, p -> acc + EnergyTotals.fromMeasurements(p.values) }
            // Jahre vor der ersten Aufzeichnung ohne Werte überspringen.
            if (buckets.isNotEmpty() || total.pv > 0 || total.consumption > 0) {
                buckets += StatsBucket(year, year.toString(), total)
            }
            raw.append("// ").append(year).append('\n').append(series.rawJson).append("\n\n")
        }
        return StatsResult(
            period = period,
            totals = buckets.fold(EnergyTotals()) { acc, b -> acc + b.totals },
            buckets = buckets,
            billingMonths = period.billingMonths(dataStart ?: buckets.firstOrNull()?.let { LocalDate.of(it.index, 1, 1) }),
            rawJson = raw.toString(),
        )
    }

    companion object {
        /** Ordnet Messpunkte den Balken des Zeitraums zu (Stunde, Tag oder Monat, lokal). */
        fun bucketize(period: Period, points: List<MeasurementPoint>, zone: ZoneId): List<StatsBucket> {
            val slots = when (period.type) {
                PeriodType.DAY -> (0..23).map { it to "%02d".format(it) }
                PeriodType.MONTH -> (1..period.start.lengthOfMonth()).map { it to it.toString() }
                PeriodType.YEAR -> (1..12).map { m ->
                    m to java.time.Month.of(m).getDisplayName(TextStyle.SHORT, Locale.GERMANY).take(3)
                }
                PeriodType.TOTAL -> emptyList()
            }
            val sums = slots.associate { it.first to EnergyTotals() }.toMutableMap()
            for (p in points) {
                val key = slotOf(period.type, p.start, zone)
                if (key in sums) sums[key] = sums.getValue(key) + EnergyTotals.fromMeasurements(p.values)
            }
            return slots.map { (index, label) -> StatsBucket(index, label, sums.getValue(index)) }
        }

        private fun slotOf(type: PeriodType, start: Instant, zone: ZoneId): Int {
            val local = ZonedDateTime.ofInstant(start, zone)
            return when (type) {
                PeriodType.DAY -> local.hour
                PeriodType.MONTH -> local.dayOfMonth
                PeriodType.YEAR -> local.monthValue
                PeriodType.TOTAL -> local.year
            }
        }
    }
}
