package de.gun642.pvdashboard.weather

import de.gun642.pvdashboard.data.Http
import de.gun642.pvdashboard.data.HttpException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale

data class Place(val name: String, val detail: String, val latitude: Double, val longitude: Double)

data class DayForecast(
    val date: LocalDate,
    val sunshineHours: Double,
    val daylightHours: Double,
    val weatherCode: Int,
    val tempMax: Double?,
    val tempMin: Double?,
    val precipitationProbability: Int?,
    /** Geschätzter PV-Ertrag in kWh (nur wenn die Anlagenleistung bekannt ist). */
    val pvKwh: Double?,
)

data class HourForecast(
    val time: LocalDateTime,
    val sunshineMinutes: Double,
    /** Einstrahlung auf die geneigte Modulfläche in W/m² */
    val irradiance: Double,
    val pvKw: Double?,
)

data class Forecast(val days: List<DayForecast>, val hours: List<HourForecast>)

/** Wetterdaten von Open-Meteo.com (kostenlos, ohne Schlüssel; Lizenz CC BY 4.0). */
object OpenMeteo {
    private val http = Http()

    /** Wirkungsgrad-Faktor für die Ertragsschätzung (Wechselrichter, Temperatur, Verschattung). */
    const val PERFORMANCE_RATIO = 0.85

    suspend fun search(query: String): List<Place> = withContext(Dispatchers.IO) {
        val url = "https://geocoding-api.open-meteo.com/v1/search?name=${Http.encode(query.trim())}&count=8&language=de&format=json"
        val res = http.request(url)
        if (res.code != 200) throw HttpException(res.code, "Ortssuche fehlgeschlagen (HTTP ${res.code})")
        val results = JSONObject(res.body).optJSONArray("results") ?: return@withContext emptyList()
        (0 until results.length()).map { i ->
            val r = results.getJSONObject(i)
            Place(
                name = r.optString("name"),
                detail = listOf(r.optString("admin1"), r.optString("country")).filter { it.isNotBlank() }.joinToString(", "),
                latitude = r.getDouble("latitude"),
                longitude = r.getDouble("longitude"),
            )
        }
    }

    suspend fun forecast(latitude: Double, longitude: Double, kwp: Double, tilt: Int, azimuth: Int): Forecast =
        withContext(Dispatchers.IO) {
            val url = String.format(
                Locale.US,
                "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f" +
                    "&daily=sunshine_duration,daylight_duration,weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
                    "&hourly=sunshine_duration,global_tilted_irradiance&tilt=%d&azimuth=%d&timezone=auto&forecast_days=7",
                latitude, longitude, tilt, azimuth,
            )
            val res = http.request(url)
            if (res.code != 200) throw HttpException(res.code, "Wetterdaten nicht verfügbar (HTTP ${res.code})")
            parse(res.body, kwp)
        }

    fun parse(body: String, kwp: Double): Forecast {
        val json = JSONObject(body)
        val hourly = json.getJSONObject("hourly")
        val hourTimes = hourly.getJSONArray("time")
        val sunshine = hourly.getJSONArray("sunshine_duration")
        val gti = hourly.optJSONArray("global_tilted_irradiance")
        val hours = (0 until hourTimes.length()).map { i ->
            val irradiance = gti?.optDouble(i, 0.0)?.takeIf { !it.isNaN() } ?: 0.0
            HourForecast(
                time = LocalDateTime.parse(hourTimes.getString(i)),
                sunshineMinutes = (sunshine.optDouble(i, 0.0).takeIf { !it.isNaN() } ?: 0.0) / 60,
                irradiance = irradiance,
                pvKw = if (kwp > 0) estimateKw(irradiance, kwp) else null,
            )
        }
        val pvByDay = hours.groupBy { it.time.toLocalDate() }.mapValues { (_, list) ->
            // Stundenmittel in W/m² × 1 h = Wh/m²
            list.sumOf { it.irradiance } / 1000
        }

        val daily = json.getJSONObject("daily")
        val dates = daily.getJSONArray("time")
        fun arr(name: String) = daily.optJSONArray(name)
        val days = (0 until dates.length()).map { i ->
            val date = LocalDate.parse(dates.getString(i))
            fun num(name: String): Double? = arr(name)?.let { a -> if (a.isNull(i)) null else a.optDouble(i) }
            DayForecast(
                date = date,
                sunshineHours = (num("sunshine_duration") ?: 0.0) / 3600,
                daylightHours = (num("daylight_duration") ?: 0.0) / 3600,
                weatherCode = num("weather_code")?.toInt() ?: -1,
                tempMax = num("temperature_2m_max"),
                tempMin = num("temperature_2m_min"),
                precipitationProbability = num("precipitation_probability_max")?.toInt(),
                pvKwh = if (kwp > 0) pvByDay[date]?.let { it * kwp * PERFORMANCE_RATIO } else null,
            )
        }
        return Forecast(days, hours)
    }

    /** Leistung bei gegebener Einstrahlung: kWp × (Einstrahlung / 1000 W/m²) × Wirkungsgrad-Faktor. */
    fun estimateKw(irradiance: Double, kwp: Double) = kwp * irradiance / 1000 * PERFORMANCE_RATIO

    fun describe(code: Int): String = when (code) {
        0 -> "Klar"
        1 -> "Überwiegend klar"
        2 -> "Teilweise bewölkt"
        3 -> "Bedeckt"
        45, 48 -> "Nebel"
        51, 53, 55, 56, 57 -> "Nieselregen"
        61, 63, 65, 66, 67 -> "Regen"
        71, 73, 75, 77 -> "Schnee"
        80, 81, 82 -> "Regenschauer"
        85, 86 -> "Schneeschauer"
        95, 96, 99 -> "Gewitter"
        else -> "–"
    }
}
