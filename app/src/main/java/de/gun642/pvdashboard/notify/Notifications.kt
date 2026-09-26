package de.gun642.pvdashboard.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import de.gun642.pvdashboard.MainActivity
import de.gun642.pvdashboard.R
import de.gun642.pvdashboard.contracts.ContractStore
import de.gun642.pvdashboard.data.AppSettings
import de.gun642.pvdashboard.data.SettingsRepository
import de.gun642.pvdashboard.ui.formatPower
import de.gun642.pvdashboard.widget.LiveWidget
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

object Notifier {
    const val CHANNEL_HINTS = "hints"
    const val CHANNEL_REMINDERS = "reminders"
    const val EXTRA_TAB = "open_tab"

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_HINTS, "Energie-Hinweise", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Akku voll und PV-Überschuss"
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_REMINDERS, "Erinnerungen", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Kündigungsfristen von Verträgen"
            }
        )
    }

    fun permitted(context: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun show(context: Context, id: Int, channel: String, title: String, text: String, tab: String) {
        if (!permitted(context)) return
        ensureChannels(context)
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_TAB, tab)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(context, id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(0xFFD71921.toInt())
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (e: SecurityException) {
            // Berechtigung wurde zwischendurch entzogen
        }
    }
}

/** Plant die Hintergrundprüfungen passend zu den Einstellungen (oder beendet sie). */
object BackgroundChecks {
    private const val SURPLUS = "surplus_check"
    private const val REMINDERS = "contract_reminders"

    fun apply(context: Context, s: AppSettings) {
        val wm = WorkManager.getInstance(context)
        if (s.surplusNotify && (s.hasLocal || s.hasCloud)) {
            val request = PeriodicWorkRequestBuilder<SurplusWorker>(s.surplusIntervalMinutes.coerceAtLeast(15).toLong(), TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            wm.enqueueUniquePeriodicWork(SURPLUS, ExistingPeriodicWorkPolicy.UPDATE, request)
        } else {
            wm.cancelUniqueWork(SURPLUS)
        }
        if (s.contractReminders) {
            val request = PeriodicWorkRequestBuilder<ReminderWorker>(12, TimeUnit.HOURS).build()
            wm.enqueueUniquePeriodicWork(REMINDERS, ExistingPeriodicWorkPolicy.KEEP, request)
        } else {
            wm.cancelUniqueWork(REMINDERS)
        }
    }
}

/**
 * Prüft in großen Abständen, ob der Akku voll ist und Strom eingespeist wird.
 * Nur tagsüber (9–18 Uhr) und höchstens einmal pro Tag eine Benachrichtigung.
 */
class SurplusWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val s = SettingsRepository(applicationContext).settings.value
        if (!s.surplusNotify) return Result.success()
        val now = LocalTime.now()
        if (now.hour < 9 || now.hour >= 18) return Result.success()
        val state = applicationContext.getSharedPreferences("notify_state", Context.MODE_PRIVATE)
        val today = LocalDate.now().toString()
        if (state.getString("surplus_date", null) == today) return Result.success()

        val snapshot = try {
            LiveWidget.fetchAndCache(applicationContext).also { LiveWidget.updateAll(applicationContext) }
        } catch (e: Exception) {
            return Result.success() // beim nächsten Intervall erneut – keine Wiederholungsschleife
        }
        val soc = snapshot.batterySoc ?: return Result.success()
        val export = -(snapshot.gridW ?: 0.0)
        if (soc >= s.surplusSocPercent && export >= s.surplusExportW) {
            Notifier.show(
                applicationContext, 1001, Notifier.CHANNEL_HINTS,
                "Akku voll – Überschuss nutzen",
                String.format(Locale.GERMANY, "Akku %.0f %%, %s werden eingespeist. Jetzt Waschmaschine, Spülmaschine oder Wallbox nutzen.", soc, formatPower(export)),
                tab = "LIVE",
            )
            state.edit().putString("surplus_date", today).apply()
        }
        return Result.success()
    }
}

/** Erinnert an Kündigungsfristen – je Vertrag und Frist nur einmal. */
class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val s = SettingsRepository(applicationContext).settings.value
        if (!s.contractReminders) return Result.success()
        val state = applicationContext.getSharedPreferences("notify_state", Context.MODE_PRIVATE)
        val done = state.getStringSet("reminded", emptySet()).orEmpty().toMutableSet()
        val format = DateTimeFormatter.ofPattern("dd.MM.yyyy")
        ContractStore(applicationContext).load().forEach { c ->
            val deadline = c.nextDeadline() ?: return@forEach
            val key = "${c.id}:$deadline"
            if (c.reminderDue() && key !in done) {
                val days = c.daysUntilDeadline() ?: 0
                Notifier.show(
                    applicationContext, 2000 + (c.id.hashCode() and 0xFFFF), Notifier.CHANNEL_REMINDERS,
                    "Kündigungsfrist: ${c.name}",
                    "Kündigen bis ${deadline.format(format)} (in $days Tagen)" +
                        (if (c.provider.isNotBlank()) " bei ${c.provider}" else "") + ".",
                    tab = "HOME",
                )
                done += key
            }
        }
        state.edit().putStringSet("reminded", done).apply()
        return Result.success()
    }
}
