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
import de.gun642.pvdashboard.data.AppSettings
import de.gun642.pvdashboard.data.DataSource
import de.gun642.pvdashboard.data.SettingsRepository
import de.gun642.pvdashboard.senec.LiveSource
import de.gun642.pvdashboard.senec.SenecClient
import de.gun642.pvdashboard.senec.SenecSnapshot
import de.gun642.pvdashboard.senec.cloud.SenecCloud
import de.gun642.pvdashboard.ui.formatPercent
import de.gun642.pvdashboard.ui.formatPower
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/** Letzte Live-Werte für das Widget (überlebt das Beenden der App). */
data class WidgetData(
    val timestamp: Long,
    val pvW: Double?,
    val houseW: Double?,
    val gridW: Double?,
    val batteryW: Double?,
    val batterySoc: Double?,
    val wallboxW: Double?,
    val cloud: Boolean,
)

object WidgetCache {
    private const val PREFS = "widget_cache"

    fun save(context: Context, s: SenecSnapshot) {
        val e = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        fun put(key: String, v: Double?) {
            if (v == null) e.remove(key) else e.putFloat(key, v.toFloat())
        }
        e.putLong("timestamp", s.timestamp)
        put("pv", s.pvW)
        put("house", s.houseW)
        put("grid", s.gridW)
        put("battery", s.batteryW)
        put("soc", s.batterySoc)
        put("wallbox", s.wallboxW)
        e.putBoolean("cloud", s.source == LiveSource.CLOUD)
        e.apply()
    }

    fun load(context: Context): WidgetData? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!p.contains("timestamp")) return null
        fun get(key: String): Double? = if (p.contains(key)) p.getFloat(key, 0f).toDouble() else null
        return WidgetData(
            timestamp = p.getLong("timestamp", 0),
            pvW = get("pv"),
            houseW = get("house"),
            gridW = get("grid"),
            batteryW = get("battery"),
            batterySoc = get("soc"),
            wallboxW = get("wallbox"),
            cloud = p.getBoolean("cloud", false),
        )
    }
}

/**
 * Homescreen-Widget mit Live-Werten.
 *
 * Akkuschonend: Es gibt keinen Hintergrund-Timer. Neue Werte holt das Widget nur beim Tipp auf ⟳;
 * außerdem übernimmt es die Werte, die die geöffnete App ohnehin abruft.
 */
class LiveWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        render(context, manager, ids)
        // Beim ersten Platzieren ohne gespeicherte Werte einmal abrufen.
        if (WidgetCache.load(context) == null) context.sendBroadcast(refreshIntent(context))
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action != ACTION_REFRESH) return
        val pending = goAsync()
        updateAll(context, status = "Aktualisiere …")
        CoroutineScope(Dispatchers.IO).launch {
            var status: String? = null
            try {
                withTimeout(25_000) { fetchAndCache(context) }
            } catch (e: Exception) {
                status = "Keine Verbindung"
            } finally {
                updateAll(context, status)
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_REFRESH = "de.gun642.pvdashboard.widget.REFRESH"

        /** Zeichnet alle Widgets mit den zuletzt gespeicherten Werten neu. */
        fun updateAll(context: Context, status: String? = null) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, LiveWidget::class.java))
            if (ids.isNotEmpty()) render(context, manager, ids, status)
        }

        private fun refreshIntent(context: Context) =
            Intent(context, LiveWidget::class.java).setAction(ACTION_REFRESH)

        /** Wie die App: im Heimnetz direkt vom Speicher, sonst über die SENEC-Cloud. */
        private suspend fun fetchAndCache(context: Context) {
            val settings = SettingsRepository(context).settings.value
            val snapshot = fetch(context, settings)
            WidgetCache.save(context, snapshot)
        }

        private suspend fun fetch(context: Context, s: AppSettings): SenecSnapshot {
            val canLocal = s.hasLocal && s.dataSource != DataSource.CLOUD
            val canCloud = s.hasCloud && s.dataSource != DataSource.LOCAL
            var error: Exception? = null
            if (canLocal) {
                try {
                    return SenecClient(s.host, s.useHttps, if (canCloud) 2_500 else 5_000).fetch(s.wallboxIndex)
                } catch (e: Exception) {
                    error = e
                }
            }
            if (canCloud) {
                val cloud = SenecCloud(context) { s.senecEmail to s.senecPassword }
                return cloud.dashboard().snapshot
            }
            throw error ?: IllegalStateException("Keine Datenquelle eingerichtet")
        }

        private fun render(context: Context, manager: AppWidgetManager, ids: IntArray, status: String? = null) {
            val settings = SettingsRepository(context).settings.value
            val data = WidgetCache.load(context)
            val views = build(context, settings, data, status)
            ids.forEach { manager.updateAppWidget(it, views) }
        }

        private fun build(context: Context, s: AppSettings, d: WidgetData?, status: String?): RemoteViews {
            val v = RemoteViews(context.packageName, R.layout.widget_live)

            // Hintergrund schwarz oder weiß mit einstellbarer Deckkraft
            val background = if (s.widgetDark) Color.BLACK else Color.WHITE
            val text = if (s.widgetDark) Color.parseColor("#F2F2F2") else Color.parseColor("#111111")
            val muted = if (s.widgetDark) Color.parseColor("#8C8C8C") else Color.parseColor("#6B6B6B")
            v.setInt(R.id.widget_bg, "setColorFilter", background)
            v.setInt(R.id.widget_bg, "setImageAlpha", (s.widgetOpacity.coerceIn(0, 100) * 255) / 100)
            v.setInt(R.id.widget_refresh, "setColorFilter", text)

            listOf(R.id.widget_title, R.id.widget_time, R.id.widget_pv_label, R.id.widget_label_1, R.id.widget_label_2, R.id.widget_label_3, R.id.widget_label_4)
                .forEach { v.setTextColor(it, muted) }
            listOf(R.id.widget_pv, R.id.widget_value_1, R.id.widget_value_2, R.id.widget_value_3, R.id.widget_value_4)
                .forEach { v.setTextColor(it, text) }

            // Werte
            val grid = d?.gridW
            val battery = d?.batteryW
            v.setTextViewText(R.id.widget_pv, formatPower(d?.pvW))
            v.setTextViewText(R.id.widget_label_1, "HAUS")
            v.setTextViewText(R.id.widget_value_1, formatPower(d?.houseW))
            v.setTextViewText(R.id.widget_label_2, if (grid != null && grid < 0) "EINSPEIS." else "NETZ")
            v.setTextViewText(R.id.widget_value_2, formatPower(grid?.let { abs(it) }))
            v.setTextViewText(
                R.id.widget_label_3,
                when {
                    battery == null -> "AKKU"
                    battery > 5 -> "AKKU ↑"
                    battery < -5 -> "AKKU ↓"
                    else -> "AKKU"
                },
            )
            v.setTextViewText(R.id.widget_value_3, formatPercent(d?.batterySoc?.let { it / 100 }))
            v.setTextViewText(R.id.widget_label_4, "WALLBOX")
            v.setTextViewText(R.id.widget_value_4, formatPower(d?.wallboxW))

            val time = d?.let { SimpleDateFormat("HH:mm", Locale.GERMANY).format(Date(it.timestamp)) + if (it.cloud) " · CLOUD" else "" }
            v.setTextViewText(R.id.widget_time, status?.uppercase(Locale.GERMANY) ?: time ?: "TIPP AUF ⟳")

            // Tipp auf Kopfzeile oder Werte öffnet die App, ⟳ aktualisiert.
            val open = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            v.setOnClickPendingIntent(R.id.widget_header, open)
            v.setOnClickPendingIntent(R.id.widget_content, open)
            val refresh = PendingIntent.getBroadcast(
                context, 1, refreshIntent(context),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            v.setOnClickPendingIntent(R.id.widget_refresh, refresh)
            return v
        }
    }
}
