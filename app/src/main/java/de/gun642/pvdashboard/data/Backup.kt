package de.gun642.pvdashboard.data

import android.content.Context
import de.gun642.pvdashboard.contracts.ContractStore
import de.gun642.pvdashboard.meters.MeterStore
import org.json.JSONObject
import java.time.LocalDateTime

data class RestoreSummary(val settings: Int, val readings: Int, val contracts: Int, val created: String)

/**
 * Sicherungsdatei mit Einstellungen (ohne Passwort), Zählerständen und Verträgen.
 *
 * Aufbau:
 * ```
 * { "app": "VOID Home Dashboard", "version": 1, "created": "...",
 *   "settings": { "key": {"t": "s|b|i|l|f", "v": ...} },
 *   "meters": { ... }, "contracts": [ ... ] }
 * ```
 */
object Backup {
    private const val FORMAT_VERSION = 1

    fun create(context: Context): String {
        val prefs = context.getSharedPreferences(SettingsRepository.PREFS, Context.MODE_PRIVATE)
        return JSONObject()
            .put("app", "VOID Home Dashboard")
            .put("version", FORMAT_VERSION)
            .put("created", LocalDateTime.now().withNano(0).toString())
            .put("settings", encodePrefs(prefs.all))
            .put("meters", JSONObject(MeterStore.toJson(MeterStore(context).load())))
            .put("contracts", org.json.JSONArray(ContractStore.toJson(ContractStore(context).load())))
            .toString(2)
    }

    /** Ersetzt Einstellungen, Zählerstände und Verträge durch die Sicherung. Das Passwort bleibt unverändert. */
    fun restore(context: Context, text: String): RestoreSummary {
        val root = JSONObject(text)
        require(root.optString("app") == "VOID Home Dashboard") { "Keine Sicherung von VOID Home Dashboard" }
        require(root.optInt("version") <= FORMAT_VERSION) { "Sicherung stammt aus einer neueren App-Version – bitte erst die App aktualisieren" }

        val settings = decodePrefs(root.optJSONObject("settings") ?: JSONObject())
        val meters = MeterStore.fromJson((root.optJSONObject("meters") ?: JSONObject()).toString())
        val contracts = ContractStore.fromJson((root.optJSONArray("contracts") ?: org.json.JSONArray()).toString())

        val editor = context.getSharedPreferences(SettingsRepository.PREFS, Context.MODE_PRIVATE).edit().clear()
        settings.forEach { (key, value) ->
            when (value) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
            }
        }
        editor.commit()
        MeterStore(context).save(meters)
        ContractStore(context).save(contracts)
        return RestoreSummary(settings.size, meters.values.sumOf { it.size }, contracts.size, root.optString("created"))
    }

    /** Einstellungen typgenau als JSON (das Passwort liegt in einer eigenen Datei und ist nicht enthalten). */
    fun encodePrefs(all: Map<String, *>): JSONObject {
        val out = JSONObject()
        all.forEach { (key, value) ->
            if (key.contains("password", ignoreCase = true)) return@forEach
            val (type, v) = when (value) {
                is String -> "s" to value
                is Boolean -> "b" to value
                is Int -> "i" to value
                is Long -> "l" to value.toString() // als Text, damit JSON keine Genauigkeit verliert
                is Float -> "f" to value.toDouble()
                else -> return@forEach
            }
            out.put(key, JSONObject().put("t", type).put("v", v))
        }
        return out
    }

    fun decodePrefs(json: JSONObject): Map<String, Any> =
        json.keys().asSequence().mapNotNull { key ->
            if (key.contains("password", ignoreCase = true)) return@mapNotNull null
            val entry = json.optJSONObject(key) ?: return@mapNotNull null
            val value: Any = when (entry.optString("t")) {
                "s" -> entry.optString("v")
                "b" -> entry.optBoolean("v")
                "i" -> entry.optInt("v")
                "l" -> entry.optString("v").toLongOrNull() ?: return@mapNotNull null
                "f" -> entry.optDouble("v").toFloat()
                else -> return@mapNotNull null
            }
            key to value
        }.toMap()
}
