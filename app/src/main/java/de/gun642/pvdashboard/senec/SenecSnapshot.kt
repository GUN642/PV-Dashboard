package de.gun642.pvdashboard.senec

/**
 * Ein Messpunkt in Watt / Prozent / °C.
 *
 * Vorzeichen: [gridW] > 0 = Netzbezug, < 0 = Einspeisung;
 * [batteryW] > 0 = Speicher lädt, < 0 = Speicher entlädt.
 */
data class SenecSnapshot(
    val timestamp: Long,
    val pvW: Double?,
    val houseW: Double?,
    val gridW: Double?,
    val batteryW: Double?,
    val batterySoc: Double?,
    val batteryTemp: Double?,
    val wallboxW: Double?,
    val wallboxCarConnected: Boolean?,
    val raw: Map<String, Any?>,
) {
    /** Anteil des Hausverbrauchs, der nicht aus dem Netz kommt (0..1). */
    val autarky: Double?
        get() {
            val house = houseW ?: return null
            val grid = gridW ?: return null
            if (house <= 0) return null
            return ((house - grid.coerceAtLeast(0.0)) / house).coerceIn(0.0, 1.0)
        }

    /** Anteil der PV-Erzeugung, der selbst genutzt wird (0..1). */
    val selfConsumption: Double?
        get() {
            val pv = pvW ?: return null
            val grid = gridW ?: return null
            if (pv <= 0) return null
            return ((pv - (-grid).coerceAtLeast(0.0)) / pv).coerceIn(0.0, 1.0)
        }

    companion object {
        fun from(decoded: Map<String, Any?>, wallboxIndex: Int, timestamp: Long): SenecSnapshot {
            val energy = decoded.section("ENERGY")
            val wallbox = decoded.section("WALLBOX")
            val temps = decoded.section("TEMPMEASURE")
            return SenecSnapshot(
                timestamp = timestamp,
                pvW = energy.number("GUI_INVERTER_POWER"),
                houseW = energy.number("GUI_HOUSE_POW"),
                gridW = energy.number("GUI_GRID_POW"),
                batteryW = energy.number("GUI_BAT_DATA_POWER"),
                batterySoc = energy.number("GUI_BAT_DATA_FUEL_CHARGE"),
                batteryTemp = temps.number("BATTERY_TEMP"),
                wallboxW = wallbox.number("APPARENT_CHARGING_POWER", wallboxIndex),
                wallboxCarConnected = wallbox.number("EV_CONNECTED", wallboxIndex)?.let { it != 0.0 },
                raw = decoded,
            )
        }

        @Suppress("UNCHECKED_CAST")
        private fun Map<String, Any?>.section(name: String): Map<String, Any?> =
            (this[name] as? Map<String, Any?>) ?: emptyMap()

        /** Liest einen Zahlenwert; bei Listen (z. B. je Wallbox) den Eintrag [index]. */
        private fun Map<String, Any?>.number(key: String, index: Int = 0): Double? {
            val value = when (val v = this[key]) {
                is List<*> -> v.getOrNull(index)
                else -> v
            }
            return (value as? Number)?.toDouble()
        }
    }
}
