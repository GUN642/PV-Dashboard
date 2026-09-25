package de.gun642.pvdashboard.widget

import kotlin.math.min

/**
 * Wohin fließt die PV-Erzeugung gerade? Anteile in 0..1, zusammen 1.
 *
 * - Einspeisung = negativer Netzwert
 * - Speicher = Ladeleistung des Akkus
 * - Wallbox = Ladeleistung, höchstens so viel wie nach Einspeisung und Akku von der PV übrig ist
 * - Haus = der Rest
 */
data class PvShares(val house: Double, val battery: Double, val wallbox: Double, val export: Double) {
    companion object {
        /** Unter dieser Leistung (W) gibt es keine sinnvolle Aufteilung (Nacht, Dämmerung). */
        const val MIN_PV_W = 50.0

        fun compute(pvW: Double?, gridW: Double?, batteryW: Double?, wallboxW: Double?): PvShares? {
            val pv = pvW ?: return null
            if (pv < MIN_PV_W) return null
            val export = (-(gridW ?: 0.0)).coerceIn(0.0, pv)
            val battery = (batteryW ?: 0.0).coerceIn(0.0, pv - export)
            val self = pv - export - battery
            val wallbox = min((wallboxW ?: 0.0).coerceAtLeast(0.0), self)
            val house = self - wallbox
            return PvShares(house / pv, battery / pv, wallbox / pv, export / pv)
        }

        /** Autarkie: Anteil des Hausverbrauchs, der nicht aus dem Netz kommt. */
        fun autarky(houseW: Double?, gridW: Double?): Double? {
            val house = houseW ?: return null
            if (house <= 0) return null
            val import = (gridW ?: 0.0).coerceAtLeast(0.0)
            return ((house - import) / house).coerceIn(0.0, 1.0)
        }
    }
}
