package de.gun642.pvdashboard

import android.content.Context

data class AppSettings(
    val host: String = "",
    val useHttps: Boolean = true,
    val intervalSeconds: Int = 5,
    val wallboxIndex: Int = 0,
)

class SettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load() = AppSettings(
        host = prefs.getString("host", "") ?: "",
        useHttps = prefs.getBoolean("use_https", true),
        intervalSeconds = prefs.getInt("interval_seconds", 5),
        wallboxIndex = prefs.getInt("wallbox_index", 0),
    )

    fun save(settings: AppSettings) {
        prefs.edit()
            .putString("host", settings.host.trim())
            .putBoolean("use_https", settings.useHttps)
            .putInt("interval_seconds", settings.intervalSeconds.coerceIn(2, 300))
            .putInt("wallbox_index", settings.wallboxIndex.coerceIn(0, 3))
            .apply()
    }
}
