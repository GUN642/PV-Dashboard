package de.gun642.pvdashboard.stats

import de.gun642.pvdashboard.data.HttpException
import de.gun642.pvdashboard.senec.cloud.MeasurementPoint
import de.gun642.pvdashboard.senec.cloud.MeasurementSeries
import de.gun642.pvdashboard.senec.cloud.SenecCloud
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.util.Locale

/** Lädt Statistiken aus der SENEC-Cloud und fasst sie zu Balken zusammen. */
class StatsRepository(private val cloud: SenecCloud, private val history: () -> List<HistoryMonth> = { emptyList() }) {

    private val cache = mutableMapOf<Period, Pair<Long, StatsResult>>()

    /** Verwirft geladene Zeiträume (z. B. nach Import der Werte einer früheren Anlage). */
    fun invalidate() = cache.clear()

    suspend fun load(period: Period, force: Boolean = false): StatsResult {
        val now = System.currentTimeMillis()
        val cached = cache[period]
        // Abgeschlossene Zeiträume ändern sich nicht mehr, laufende werden nach 5 Minuten neu geladen.
        val finished = period.type != PeriodType.TOTAL && !period.end.isAfter(LocalDate.now())
        if (!force && cached != null && (finished || now - cached.first < 5 * 60_000)) return cached.second

        val zone = ZoneId.systemDefault()
        val dataStart = runCatching { cloud.dataStart() }.getOrNull()?.atZone(zone)?.toLocalDate()
        val result = if (period.type == PeriodType.TOTAL) loadTotal(period, zone, dataStart) else loadRange(period, zone, dataStart)
            .let { r -> if (r.period.type == PeriodType.YEAR) withHistory(r, dataStart) else r }
        cache[period] = now to result
        return result
    }

    private suspend fun loadRange(period: Period, zone: ZoneId, dataStart: LocalDate?): StatsResult {
        val (from, to) = period.instants(zone)
        // Tag: 5-Minuten-Werte zu Viertelstunden zusammenfassen; falls nicht angeboten, Stundenwerte
        val resolutions = when (period.type) {
            PeriodType.DAY -> listOf("FIVE_MINUTES" to 15, "HOUR" to 60)
            PeriodType.MONTH -> listOf("DAY" to 60)
            else -> listOf("MONTH" to 60)
        }
        var lastError: Exception? = null
        for ((resolution, minutes) in resolutions) {
            try {
                val series = cloud.measurements(resolution, from, to)
                val buckets = bucketize(period, series.points, zone, minutes)
                return StatsResult(
                    bucketMinutes = if (period.type == PeriodType.DAY) minutes else 0,
                    period = period,
                    totals = buckets.fold(EnergyTotals()) { acc, b -> acc + b.totals },
                    buckets = buckets,
                    billingMonths = period.billingMonths(dataStart),
                    rawJson = series.rawJson,
                    dataStart = dataStart,
                )
            } catch (e: HttpException) {
                // Nicht jede Auflösung wird vom Server angeboten – dann die nächste probieren.
                lastError = e
                if (e.code !in 400..499 || e.code == 401) throw e
            }
        }
        throw lastError ?: IllegalStateException("Keine Daten")
    }

    /** Jahr: Monate der früheren Anlage dazurechnen und den Beginn der Aufzeichnung entsprechend vorziehen. */
    private fun withHistory(r: StatsResult, dataStart: LocalDate?): StatsResult {
        val hist = history()
        if (hist.none { it.month.year == r.period.start.year }) return r
        val buckets = overlayYear(r.period.start.year, r.buckets, hist, dataStart)
        return r.copy(
            buckets = buckets,
            totals = buckets.fold(EnergyTotals()) { acc, b -> acc + b.totals },
            dataStart = earliest(dataStart, hist),
            billingMonths = r.period.billingMonths(earliest(dataStart, hist)),
        )
    }

    /** Gesamtzeitraum: Jahr für Jahr in Monatsauflösung laden (wie die SENEC-App). */
    private suspend fun loadTotal(period: Period, zone: ZoneId, dataStart: LocalDate?): StatsResult {
        val currentYear = LocalDate.now().year
        val hist = history()
        val firstYear = minOf(dataStart?.year ?: (currentYear - 4), hist.minOfOrNull { it.month.year } ?: Int.MAX_VALUE)
            .coerceIn(2000, currentYear)
        val buckets = mutableListOf<StatsBucket>()
        val raw = StringBuilder()
        for (year in firstYear..currentYear) {
            val yearPeriod = Period(PeriodType.YEAR, LocalDate.of(year, 1, 1))
            val (from, to) = yearPeriod.instants(zone)
            // Vor Beginn der Aufzeichnung gibt es in der Cloud nichts abzufragen
            val series = if (dataStart != null && year < dataStart.year) MeasurementSeries(emptyList(), "")
            else cloud.measurements("MONTH", from, to)
            val cloudTotal = series.points.fold(EnergyTotals()) { acc, p -> acc + EnergyTotals.fromMeasurements(p.values) }
            val monthly = overlayYear(year, listOf(StatsBucket(1, "", cloudTotal)), hist, dataStart, wholeYear = true)
            val total = monthly.first().totals
            // Jahre vor der ersten Aufzeichnung ohne Werte überspringen.
            if (buckets.isNotEmpty() || total.pv > 0 || total.consumption > 0) {
                buckets += StatsBucket(year, year.toString(), total)
            }
            raw.append("// ").append(year).append('\n').append(series.rawJson).append("\n\n")
        }
        val start = earliest(dataStart ?: buckets.firstOrNull()?.let { LocalDate.of(it.index, 1, 1) }, hist)
        return StatsResult(
            period = period,
            totals = buckets.fold(EnergyTotals()) { acc, b -> acc + b.totals },
            buckets = buckets,
            billingMonths = period.billingMonths(start),
            rawJson = raw.toString(),
            dataStart = start,
        )
    }

    companion object {
        const val SOC_KEY = "BATTERY_LEVEL_IN_PERCENT"

        /** Früherer Beginn: Aufzeichnung der Cloud oder erster Monat der früheren Anlage. */
        fun earliest(dataStart: LocalDate?, history: List<HistoryMonth>): LocalDate? =
            listOfNotNull(dataStart, history.minOfOrNull { it.month }?.atDay(1)).minOrNull()

        /**
         * Rechnet die Monate der früheren Anlage zu den Cloud-Werten. Sie gelten bis einschließlich des Monats,
         * in dem die aktuelle Aufzeichnung beginnt (dort ergänzen sie den Teil davor); danach zählt nur die Cloud.
         * Ohne bekannten Beginn füllen sie nur Monate ohne Cloud-Werte.
         * Mit [wholeYear] steht [buckets] für das ganze Jahr in einem Wert (Gesamtansicht).
         */
        fun overlayYear(
            year: Int, buckets: List<StatsBucket>, history: List<HistoryMonth>, dataStart: LocalDate?, wholeYear: Boolean = false,
        ): List<StatsBucket> {
            val limit = dataStart?.let(java.time.YearMonth::from)
            fun applies(h: HistoryMonth, cloud: EnergyTotals) =
                if (limit != null) !h.month.isAfter(limit) else cloud.pv == 0.0 && cloud.consumption == 0.0
            val months = history.filter { it.month.year == year }
            if (wholeYear) {
                val cloud = buckets.first().totals
                val extra = months.filter { limit != null || cloud.pv == 0.0 }.filter { applies(it, cloud) }
                    .fold(EnergyTotals()) { acc, h -> acc + h.totals }
                return listOf(buckets.first().copy(totals = cloud + extra))
            }
            val byMonth = months.associateBy { it.month.monthValue }
            return buckets.map { b ->
                val h = byMonth[b.index] ?: return@map b
                if (applies(h, b.totals)) b.copy(totals = b.totals + h.totals) else b
            }
        }

        /**
         * Ordnet Messpunkte den Balken des Zeitraums zu (Tag: je [dayMinutes] Minuten, sonst Tag bzw. Monat, lokal).
         * Beim Tag ist der Index die Nummer des Zeitabschnitts (bei 15 Minuten 0–95), die Beschriftung die Stunde.
         */
        fun bucketize(period: Period, points: List<MeasurementPoint>, zone: ZoneId, dayMinutes: Int = 60): List<StatsBucket> {
            val perHour = 60 / dayMinutes
            val slots = when (period.type) {
                PeriodType.DAY -> (0 until 24 * perHour).map { it to "%02d".format(it / perHour) }
                PeriodType.MONTH -> (1..period.start.lengthOfMonth()).map { it to it.toString() }
                PeriodType.YEAR -> (1..12).map { m ->
                    m to java.time.Month.of(m).getDisplayName(TextStyle.SHORT, Locale.GERMANY).take(3)
                }
                PeriodType.TOTAL -> emptyList()
            }
            val sums = slots.associate { it.first to EnergyTotals() }.toMutableMap()
            // Ladestand ist ein Momentanwert: Mittelwert statt Summe
            val soc = mutableMapOf<Int, MutableList<Double>>()
            for (p in points) {
                val key = slotOf(period.type, p.start, zone, dayMinutes)
                if (key !in sums) continue
                sums[key] = sums.getValue(key) + EnergyTotals.fromMeasurements(p.values)
                p.values[SOC_KEY]?.let { soc.getOrPut(key) { mutableListOf() } += it }
            }
            return slots.map { (index, label) -> StatsBucket(index, label, sums.getValue(index), soc[index]?.average()) }
        }

        private fun slotOf(type: PeriodType, start: Instant, zone: ZoneId, dayMinutes: Int): Int {
            val local = ZonedDateTime.ofInstant(start, zone)
            return when (type) {
                PeriodType.DAY -> (local.hour * 60 + local.minute) / dayMinutes
                PeriodType.MONTH -> local.dayOfMonth
                PeriodType.YEAR -> local.monthValue
                PeriodType.TOTAL -> local.year
            }
        }
    }
}
