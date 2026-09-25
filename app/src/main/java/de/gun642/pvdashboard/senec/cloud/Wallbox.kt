package de.gun642.pvdashboard.senec.cloud

import org.json.JSONArray
import org.json.JSONObject

enum class WallboxMode(val label: String, val apiType: String?) {
    FAST("Schnell", "FAST"),
    SOLAR("Solar", "SOLAR"),
    COMFORT("Komfort", "COMFORT"),
    LOCKED("Gesperrt", null),
    UNKNOWN("Unbekannt", null),
}

/** Zustand und Einstellungen einer SENEC-Wallbox aus der App-API. */
data class WallboxInfo(
    val id: String,
    val name: String,
    /** z. B. "V123" (SENEC.Home V2/V3) oder "V4" */
    val type: String,
    val mode: WallboxMode,
    val carConnected: Boolean,
    val isCharging: Boolean,
    val hasError: Boolean,
    val statusCode: String,
    val temperature: Double?,
    val chargingPowerKw: Double?,
    val interchargeAvailable: Boolean,
    val solarAvailable: Boolean,
    /** Schnellladen: Speicher darf mitladen */
    val fastAllowIntercharge: Boolean,
    /** Solar: Ladeunterbrechungen verhindern */
    val solarPreventInterruptions: Boolean,
    val solarMinCurrent: Double?,
    val minPossibleCurrent: Double?,
    /** Alle Solar-Einstellungen, damit beim Speichern nichts verloren geht */
    val solarSettings: Map<String, Any?>,
    val rawJson: String,
) {
    /** Komfort-Laden gibt es nur bei V4/P4 (oder wenn es bereits aktiv ist). */
    val comfortAvailable: Boolean get() = !type.equals("V123", ignoreCase = true) || mode == WallboxMode.COMFORT

    val statusText: String
        get() = when (statusCode.uppercase()) {
            "WAITING_FOR_EV" -> "Wartet auf Fahrzeug"
            "READY" -> "Bereit"
            "CHARGING" -> "Lädt"
            "CHARGING_PAUSED", "PAUSED" -> "Laden pausiert"
            "FINISHED", "CHARGING_FINISHED" -> "Laden beendet"
            "ERROR" -> "Fehler"
            "LOCKED" -> "Gesperrt"
            "" -> if (isCharging) "Lädt" else if (carConnected) "Fahrzeug verbunden" else "–"
            else -> statusCode.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
        }

    companion object {
        fun parseList(body: String): List<WallboxInfo> {
            val array = JSONArray(body)
            return (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let(::parse) }
        }

        fun parse(o: JSONObject): WallboxInfo {
            val state = o.optJSONObject("state") ?: JSONObject()
            val charging = o.optJSONObject("chargingMode") ?: JSONObject()
            val fast = charging.optJSONObject("fastChargingSettings") ?: JSONObject()
            val solar = charging.optJSONObject("solarOptimizeSettings") ?: JSONObject()
            val currents = o.optJSONObject("chargingCurrents") ?: JSONObject()
            fun num(obj: JSONObject, key: String): Double? =
                if (obj.has(key) && !obj.isNull(key)) obj.optDouble(key).takeIf { !it.isNaN() } else null

            val mode = when {
                o.optBoolean("prohibitUsage", false) -> WallboxMode.LOCKED
                else -> when (charging.optString("type").uppercase()) {
                    "FAST" -> WallboxMode.FAST
                    "SOLAR" -> WallboxMode.SOLAR
                    "COMFORT" -> WallboxMode.COMFORT
                    else -> WallboxMode.UNKNOWN
                }
            }
            return WallboxInfo(
                id = o.optString("id"),
                name = o.optString("name").ifBlank { "Wallbox" },
                type = o.optString("type"),
                mode = mode,
                carConnected = state.optBoolean("electricVehicleConnected", false),
                isCharging = state.optBoolean("isCharging", false),
                hasError = state.optBoolean("hasError", false),
                statusCode = state.optString("statusCode"),
                temperature = num(state, "temperatureInCelsius"),
                chargingPowerKw = num(currents, "currentApparentChargingPowerInKw"),
                interchargeAvailable = o.optBoolean("isInterchargeAvailable", true),
                solarAvailable = o.optBoolean("isSolarChargingAvailable", true),
                fastAllowIntercharge = fast.optBoolean("allowIntercharge", false),
                solarPreventInterruptions = solar.optBoolean("preventInterruptions", false),
                solarMinCurrent = num(solar, "minChargingCurrentInA"),
                minPossibleCurrent = num(currents, "minPossibleCharging"),
                solarSettings = solar.keys().asSequence().associateWith { k -> solar.opt(k).takeIf { it != JSONObject.NULL } },
                rawJson = o.toString(2),
            )
        }

        /**
         * Neue Solar-Einstellungen: übernimmt die vorhandenen Felder und ändert nur die gewünschten
         * (so macht es auch die SENEC-App; fehlende Felder würden sonst zurückgesetzt).
         */
        fun solarSettingsBody(current: Map<String, Any?>, preventInterruptions: Boolean? = null, minCurrent: Double? = null): String {
            val body = JSONObject()
            for (key in listOf("preventInterruptions", "compatibilityMode", "minChargingCurrentInA", "useDynamicTariffs", "priceLimitInCtPerKwh")) {
                current[key]?.let { body.put(key, it) }
            }
            preventInterruptions?.let { body.put("preventInterruptions", it) }
            minCurrent?.let { body.put("minChargingCurrentInA", it) }
            return body.toString()
        }
    }
}
