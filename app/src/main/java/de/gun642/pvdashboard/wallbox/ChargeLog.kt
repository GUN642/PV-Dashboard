package de.gun642.pvdashboard.wallbox

import de.gun642.pvdashboard.senec.cloud.MeasurementPoint
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** Ein Ladevorgang, erkannt aus aufeinanderfolgenden Intervallen mit Wallbox-Leistung. */
data class ChargeSession(
    val start: LocalDateTime,
    val end: LocalDateTime,
    /** Geladene Energie in kWh */
    val kwh: Double,
    /** Davon aus eigener Erzeugung (PV direkt oder über den Akku) in kWh */
    val solarKwh: Double,
) {
    val gridKwh: Double get() = (kwh - solarKwh).coerceAtLeast(0.0)
    val solarShare: Double get() = if (kwh > 0) solarKwh / kwh else 0.0

    /** Netzstrom zum Arbeitspreis */
    fun gridCost(priceCent: Double) = gridKwh * priceCent / 100

    /** Eigener Strom hätte sonst die Einspeisevergütung gebracht */
    fun lostFeedIn(feedInCent: Double) = solarKwh * feedInCent / 100

    fun cost(priceCent: Double, feedInCent: Double) = gridCost(priceCent) + lostFeedIn(feedInCent)
}

data class ChargeMonth(val sessions: List<ChargeSession>) {
    val kwh: Double get() = sessions.sumOf { it.kwh }
    val solarKwh: Double get() = sessions.sumOf { it.solarKwh }
    val gridKwh: Double get() = sessions.sumOf { it.gridKwh }
    val solarShare: Double get() = if (kwh > 0) solarKwh / kwh else 0.0
    fun cost(priceCent: Double, feedInCent: Double) = sessions.sumOf { it.cost(priceCent, feedInCent) }
}

object ChargeLog {
    /** Unter dieser Energie je Intervall (kWh) gilt die Wallbox als aus (Standby, Messrauschen). */
    const val MIN_KWH = 0.05

    /** Kürzere Pausen (z. B. Ladeunterbrechung bei Wolken) trennen keinen Ladevorgang. */
    const val MAX_GAP_INTERVALS = 1

    /**
     * Fasst Messintervalle zu Ladevorgängen zusammen.
     *
     * Anteil eigener Strom je Intervall wie bei der Autarkie: Netzbezug im Verhältnis zum gesamten
     * Verbrauch (aus der Energiebilanz: PV + Netzbezug + Akku-Entladung − Einspeisung − Akku-Ladung).
     */
    fun sessions(points: List<MeasurementPoint>, zone: ZoneId = ZoneId.systemDefault()): List<ChargeSession> {
        val sorted = points.sortedBy { it.start }
        val result = mutableListOf<ChargeSession>()
        var start: Instant? = null
        var end: Instant? = null
        var kwh = 0.0
        var solar = 0.0
        var gap = 0

        fun close() {
            val s = start ?: return
            val e = end ?: return
            if (kwh >= MIN_KWH * 2) {
                result += ChargeSession(LocalDateTime.ofInstant(s, zone), LocalDateTime.ofInstant(e, zone), kwh, solar.coerceAtMost(kwh))
            }
            start = null
            end = null
            kwh = 0.0
            solar = 0.0
            gap = 0
        }

        for (p in sorted) {
            val wallbox = p.values["WALLBOX_CONSUMPTION"] ?: 0.0
            if (wallbox < MIN_KWH) {
                if (start != null && ++gap > MAX_GAP_INTERVALS) close()
                continue
            }
            gap = 0
            if (start == null) start = p.start
            end = p.start.plusSeconds(p.durationSeconds.coerceAtLeast(0))
            kwh += wallbox
            solar += wallbox * ownShare(p.values)
        }
        close()
        return result
    }

    /** Anteil des Verbrauchs im Intervall, der nicht aus dem Netz kam (0..1). */
    fun ownShare(v: Map<String, Double>): Double {
        val pv = v["POWER_GENERATION"] ?: 0.0
        val import = v["GRID_IMPORT"] ?: 0.0
        val export = v["GRID_EXPORT"] ?: 0.0
        val charge = v["BATTERY_IMPORT"] ?: 0.0
        val discharge = v["BATTERY_EXPORT"] ?: 0.0
        val demand = pv + import + discharge - export - charge
        if (demand <= 0) return 0.0
        return (1 - import / demand).coerceIn(0.0, 1.0)
    }
}
