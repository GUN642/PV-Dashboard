package de.gun642.pvdashboard.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.widget.RemoteViews
import androidx.core.content.res.ResourcesCompat
import de.gun642.pvdashboard.R
import de.gun642.pvdashboard.data.SettingsRepository
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

/**
 * 1×1-Widget: Akku-Ladestand als Punkte-Ring (Farben wie im großen Widget) mit Prozent in der Mitte.
 * Nutzt dieselben zwischengespeicherten Werte wie das Live-Widget; Tipp aktualisiert beide.
 */
class BatteryWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        render(context, manager, ids)
    }

    companion object {
        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, BatteryWidget::class.java))
            if (ids.isNotEmpty()) render(context, manager, ids)
        }

        private fun render(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val s = SettingsRepository(context).settings.value
            val d = WidgetCache.load(context)
            val v = RemoteViews(context.packageName, R.layout.widget_battery)
            v.setInt(R.id.battery_bg, "setColorFilter", if (s.widgetDark) Color.BLACK else Color.WHITE)
            v.setInt(R.id.battery_bg, "setImageAlpha", (s.widgetOpacity.coerceIn(0, 100) * 255) / 100)
            v.setImageViewBitmap(R.id.battery_ring, ring(context, d?.batterySoc, d?.batteryW, s.widgetDark))
            // Tipp: neue Werte holen (übernimmt der Empfänger des Live-Widgets)
            val refresh = PendingIntent.getBroadcast(
                context, 2,
                Intent(context, LiveWidget::class.java).setAction(LiveWidget.ACTION_REFRESH),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            v.setOnClickPendingIntent(R.id.battery_root, refresh)
            ids.forEach { manager.updateAppWidget(it, v) }
        }

        /** Punkte-Ring: gefüllte Punkte = Ladestand, dazu Prozent und Lade-/Entladepfeil. */
        private fun ring(context: Context, soc: Double?, batteryW: Double?, dark: Boolean): Bitmap {
            val size = 300
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val text = if (dark) Color.parseColor("#F2F2F2") else Color.parseColor("#111111")
            val muted = if (dark) Color.parseColor("#8C8C8C") else Color.parseColor("#6B6B6B")
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)

            val dots = 30
            val radius = size * 0.40f
            val filled = soc?.let { ((it / 100) * dots + 0.5).toInt().coerceIn(0, dots) } ?: 0
            val color = soc?.let { LiveWidget.socColor(it) } ?: muted
            for (i in 0 until dots) {
                val a = Math.toRadians(-90.0 + i * 360.0 / dots)
                paint.color = if (i < filled) color else muted
                paint.alpha = if (i < filled) 255 else 70
                canvas.drawCircle(size / 2f + (radius * cos(a)).toFloat(), size / 2f + (radius * sin(a)).toFloat(), size * 0.028f, paint)
            }

            val grotesk = ResourcesCompat.getFont(context, R.font.space_grotesk) ?: Typeface.SANS_SERIF
            val mono = ResourcesCompat.getFont(context, R.font.space_mono) ?: Typeface.MONOSPACE
            paint.alpha = 255
            paint.textAlign = Paint.Align.CENTER
            paint.color = text
            paint.typeface = Typeface.create(grotesk, Typeface.BOLD)
            paint.textSize = size * 0.22f
            val value = soc?.let { String.format(Locale.GERMANY, "%.0f", it) } ?: "–"
            canvas.drawText(value, size / 2f, size / 2f + size * 0.06f, paint)
            paint.typeface = mono
            paint.textSize = size * 0.075f
            paint.color = muted
            val arrow = when {
                batteryW == null -> ""
                batteryW > 5 -> " ↑"
                batteryW < -5 -> " ↓"
                else -> ""
            }
            canvas.drawText("% AKKU$arrow", size / 2f, size / 2f + size * 0.17f, paint)
            return bmp
        }
    }
}
