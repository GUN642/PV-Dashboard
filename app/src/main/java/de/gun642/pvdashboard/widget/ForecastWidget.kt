package de.gun642.pvdashboard.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.widget.RemoteViews
import de.gun642.pvdashboard.MainActivity
import de.gun642.pvdashboard.R
import de.gun642.pvdashboard.data.SettingsRepository
import de.gun642.pvdashboard.notify.Notifier
import de.gun642.pvdashboard.weather.Forecast
import de.gun642.pvdashboard.weather.OpenMeteo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Date
import java.util.Locale

/** Prognose eines Tages für das Widget. */
data class ForecastDay(val date: LocalDate, val pvKwh: Double?, val sunshineHours: Double, val weatherCode: Int)

/** Zuletzt geladene Prognose (aus der App oder per ⟳). */
object ForecastCache {
    private const val PREFS = "forecast_cache"

    fun save(context: Context, forecast: Forecast) {
        val e = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear()
        e.putLong("timestamp", System.currentTimeMillis())
        forecast.days.take(3).forEachIndexed { i, d ->
            e.putString("d${i}_date", d.date.toString())
            d.pvKwh?.let { e.putFloat("d${i}_pv", it.toFloat()) }
            e.putFloat("d${i}_sun", d.sunshineHours.toFloat())
            e.putInt("d${i}_code", d.weatherCode)
        }
        e.apply()
    }

    fun timestamp(context: Context): Long = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("timestamp", 0)

    fun load(context: Context): List<ForecastDay> {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return (0 until 3).mapNotNull { i ->
            val date = p.getString("d${i}_date", null)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return@mapNotNull null
            ForecastDay(
                date,
                if (p.contains("d${i}_pv")) p.getFloat("d${i}_pv", 0f).toDouble() else null,
                p.getFloat("d${i}_sun", 0f).toDouble(),
                p.getInt("d${i}_code", 0),
            )
        }
    }
}

/**
 * Widget „PV-Prognose“: geschätzter Ertrag heute und morgen (Open-Meteo, wie im Tab Wetter).
 * Kein Hintergrund-Timer: ⟳ lädt neu, außerdem übernimmt es die Prognose, die die App lädt.
 */
class ForecastWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        render(context, manager, ids)
        // Beim Platzieren oder mit veralteter Prognose (älter als 3 h) einmal laden
        if (System.currentTimeMillis() - ForecastCache.timestamp(context) > 3 * 3600_000L) {
            context.sendBroadcast(Intent(context, ForecastWidget::class.java).setAction(ACTION_REFRESH))
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action != ACTION_REFRESH) return
        val pending = goAsync()
        updateAll(context, status = "Aktualisiere …")
        CoroutineScope(Dispatchers.IO).launch {
            var status: String? = null
            try {
                val s = SettingsRepository(context).settings.value
                val lat = s.latitude
                val lon = s.longitude
                if (lat == null || lon == null) {
                    status = "Standort fehlt"
                } else {
                    val forecast = withTimeout(25_000) {
                        OpenMeteo.forecast(lat, lon, s.peakPowerKwp, s.tiltDegrees, s.azimuthFromSouth, s.performanceRatio)
                    }
                    ForecastCache.save(context, forecast)
                }
            } catch (e: Exception) {
                status = "Keine Verbindung"
            } finally {
                updateAll(context, status)
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_REFRESH = "de.gun642.pvdashboard.widget.FORECAST_REFRESH"

        fun updateAll(context: Context, status: String? = null) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, ForecastWidget::class.java))
            if (ids.isNotEmpty()) render(context, manager, ids, status)
        }

        private fun describe(d: ForecastDay): String {
            val weather = OpenMeteo.describe(d.weatherCode).uppercase(Locale.GERMANY)
            return String.format(Locale.GERMANY, "%s · %.1f H", weather, d.sunshineHours)
        }

        private fun render(context: Context, manager: AppWidgetManager, ids: IntArray, status: String? = null) {
            val s = SettingsRepository(context).settings.value
            val v = RemoteViews(context.packageName, R.layout.widget_forecast)
            val text = if (s.widgetDark) Color.parseColor("#F2F2F2") else Color.parseColor("#111111")
            val muted = if (s.widgetDark) Color.parseColor("#8C8C8C") else Color.parseColor("#6B6B6B")
            v.setInt(R.id.forecast_bg, "setColorFilter", if (s.widgetDark) Color.BLACK else Color.WHITE)
            v.setInt(R.id.forecast_bg, "setImageAlpha", (s.widgetOpacity.coerceIn(0, 100) * 255) / 100)
            v.setInt(R.id.forecast_refresh, "setColorFilter", text)
            listOf(R.id.forecast_title, R.id.forecast_time, R.id.forecast_label_1, R.id.forecast_label_2, R.id.forecast_detail_1, R.id.forecast_detail_2)
                .forEach { v.setTextColor(it, muted) }
            listOf(R.id.forecast_value_1, R.id.forecast_value_2).forEach { v.setTextColor(it, text) }

            val today = LocalDate.now()
            val days = ForecastCache.load(context)
            val slots = listOf(
                Triple(today, R.id.forecast_value_1, R.id.forecast_detail_1),
                Triple(today.plusDays(1), R.id.forecast_value_2, R.id.forecast_detail_2),
            )
            slots.forEach { (date, valueId, detailId) ->
                val day = days.firstOrNull { it.date == date }
                v.setTextViewText(
                    valueId,
                    when {
                        day == null -> "–"
                        day.pvKwh == null -> String.format(Locale.GERMANY, "%.1f h", day.sunshineHours)
                        else -> String.format(Locale.GERMANY, "%.1f kWh", day.pvKwh)
                    },
                )
                v.setTextViewText(detailId, day?.let(::describe) ?: "")
            }
            val stamp = ForecastCache.timestamp(context).takeIf { it > 0 }?.let { SimpleDateFormat("HH:mm", Locale.GERMANY).format(Date(it)) }
            v.setTextViewText(
                R.id.forecast_time,
                status?.uppercase(Locale.GERMANY) ?: when {
                    s.latitude == null -> "STANDORT FEHLT"
                    stamp == null -> "TIPP AUF ⟳"
                    else -> stamp
                },
            )

            val open = PendingIntent.getActivity(
                context, 10,
                Intent(context, MainActivity::class.java)
                    .putExtra(Notifier.EXTRA_TAB, "WEATHER")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            v.setOnClickPendingIntent(R.id.forecast_header, open)
            v.setOnClickPendingIntent(R.id.forecast_content, open)
            val refresh = PendingIntent.getBroadcast(
                context, 11, Intent(context, ForecastWidget::class.java).setAction(ACTION_REFRESH),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            v.setOnClickPendingIntent(R.id.forecast_refresh, refresh)
            ids.forEach { manager.updateAppWidget(it, v) }
        }
    }
}
