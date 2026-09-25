package de.gun642.pvdashboard.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode(val label: String) {
    BLACK("Schwarz"),
    GRAPHITE("Graphit"),
    STEEL("Stahl"),
    PAPER("Papier"),
    WHITE("Weiß"),
    SYSTEM("System"),
    DYNAMIC("Material You"),
}

enum class Accent(val label: String, val argb: Long) {
    RED("Rot", 0xFFD71921),
    MONO("Mono", 0xFFFFFFFF),
    YELLOW("Gelb", 0xFFFFC400),
    ORANGE("Orange", 0xFFFF6A13),
    GREEN("Grün", 0xFF34C759),
    BLUE("Blau", 0xFF3D7BFF),
    PINK("Pink", 0xFFFF4F8B),
}

/** Woher die Live-Werte kommen. */
enum class DataSource(val label: String) {
    AUTO("Automatisch"),
    LOCAL("Nur Heimnetz"),
    CLOUD("Nur SENEC-Cloud"),
}

/** Ausrichtung der Module; Werte als Azimut wie bei Open-Meteo (0 = Süd, -90 = Ost, 90 = West). */
enum class Orientation(val label: String, val azimuth: Int) {
    EAST("Ost", -90),
    SOUTH_EAST("Südost", -45),
    SOUTH("Süd", 0),
    SOUTH_WEST("Südwest", 45),
    WEST("West", 90),
}

data class AppSettings(
    // Speicher im Heimnetz
    val host: String = "",
    val useHttps: Boolean = true,
    val intervalSeconds: Int = 5,
    val wallboxIndex: Int = 0,
    // SENEC-Konto
    val senecEmail: String = "",
    val senecPassword: String = "",
    val dataSource: DataSource = DataSource.AUTO,
    // Stromtarif
    val provider: String = "",
    val baseFeePerMonth: Double = 0.0,
    val pricePerKwhCent: Double = 0.0,
    val feedInCent: Double = 0.0,
    // Standort & Anlage
    val locationName: String = "",
    val latitude: Double? = null,
    val longitude: Double? = null,
    val peakPowerKwp: Double = 0.0,
    val tiltDegrees: Int = 30,
    val orientation: Orientation = Orientation.SOUTH,
    // Design
    val theme: ThemeMode = ThemeMode.BLACK,
    val accent: Accent = Accent.RED,
    val dotHeadings: Boolean = true,
    val dotGrid: Boolean = true,
    val autoUpdateCheck: Boolean = true,
) {
    val hasLocal: Boolean get() = host.isNotBlank()
    val hasCloud: Boolean get() = senecEmail.isNotBlank() && senecPassword.isNotBlank()
    val hasLocation: Boolean get() = latitude != null && longitude != null
    val hasTariff: Boolean get() = pricePerKwhCent > 0
}

class SettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val secrets = SecretStore(context)
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    fun update(transform: AppSettings.() -> AppSettings) {
        val next = _settings.value.transform()
        save(next)
        _settings.value = next
    }

    private inline fun <reified T : Enum<T>> enumOf(key: String, default: T): T =
        prefs.getString(key, null)?.let { v -> enumValues<T>().firstOrNull { it.name == v } } ?: default

    private fun double(key: String): Double? =
        if (prefs.contains(key)) java.lang.Double.longBitsToDouble(prefs.getLong(key, 0)) else null

    private fun load(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            host = prefs.getString("host", d.host) ?: d.host,
            useHttps = prefs.getBoolean("use_https", d.useHttps),
            intervalSeconds = prefs.getInt("interval_seconds", d.intervalSeconds),
            wallboxIndex = prefs.getInt("wallbox_index", d.wallboxIndex),
            senecEmail = prefs.getString("senec_email", d.senecEmail) ?: d.senecEmail,
            senecPassword = prefs.getString("senec_password", null)?.let { secrets.decrypt(it) } ?: d.senecPassword,
            dataSource = enumOf("data_source", d.dataSource),
            provider = prefs.getString("provider", d.provider) ?: d.provider,
            baseFeePerMonth = double("base_fee") ?: d.baseFeePerMonth,
            pricePerKwhCent = double("price_kwh") ?: d.pricePerKwhCent,
            feedInCent = double("feed_in") ?: d.feedInCent,
            locationName = prefs.getString("location_name", d.locationName) ?: d.locationName,
            latitude = double("latitude"),
            longitude = double("longitude"),
            peakPowerKwp = double("kwp") ?: d.peakPowerKwp,
            tiltDegrees = prefs.getInt("tilt", d.tiltDegrees),
            orientation = enumOf("orientation", d.orientation),
            theme = enumOf("theme", d.theme),
            accent = enumOf("accent", d.accent),
            dotHeadings = prefs.getBoolean("dot_headings", d.dotHeadings),
            dotGrid = prefs.getBoolean("dot_grid", d.dotGrid),
            autoUpdateCheck = prefs.getBoolean("auto_update", d.autoUpdateCheck),
        )
    }

    private fun save(s: AppSettings) {
        val e = prefs.edit()
        fun putDouble(key: String, value: Double?) {
            if (value == null) e.remove(key) else e.putLong(key, java.lang.Double.doubleToRawLongBits(value))
        }
        e.putString("host", s.host.trim())
        e.putBoolean("use_https", s.useHttps)
        e.putInt("interval_seconds", s.intervalSeconds.coerceIn(2, 300))
        e.putInt("wallbox_index", s.wallboxIndex.coerceIn(0, 3))
        e.putString("senec_email", s.senecEmail.trim())
        if (s.senecPassword.isEmpty()) e.remove("senec_password") else e.putString("senec_password", secrets.encrypt(s.senecPassword))
        e.putString("data_source", s.dataSource.name)
        e.putString("provider", s.provider)
        putDouble("base_fee", s.baseFeePerMonth)
        putDouble("price_kwh", s.pricePerKwhCent)
        putDouble("feed_in", s.feedInCent)
        e.putString("location_name", s.locationName)
        putDouble("latitude", s.latitude)
        putDouble("longitude", s.longitude)
        putDouble("kwp", s.peakPowerKwp)
        e.putInt("tilt", s.tiltDegrees.coerceIn(0, 90))
        e.putString("orientation", s.orientation.name)
        e.putString("theme", s.theme.name)
        e.putString("accent", s.accent.name)
        e.putBoolean("dot_headings", s.dotHeadings)
        e.putBoolean("dot_grid", s.dotGrid)
        e.putBoolean("auto_update", s.autoUpdateCheck)
        e.apply()
    }
}
