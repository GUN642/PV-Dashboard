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

/** Ergebnis der Überschuss-Prüfung mit Begründung (für die Anzeige in den Einstellungen). */
data class SurplusDecision(val notify: Boolean, val reason: String)

object SurplusCheck {
    private const val STATE = "notify_state"

    /**
     * Akku ≥ Schwelle und – falls eine Einspeise-Schwelle > 0 gesetzt ist – Einspeisung ≥ Schwelle.
     * Bei 0 W wird nur der Akkustand geprüft.
     */
    fun decide(soc: Double?, gridW: Double?, socThreshold: Int, exportThreshold: Int): SurplusDecision {
        if (soc == null) return SurplusDecision(false, "Kein Akkustand gemeldet")
        val export = -(gridW ?: 0.0)
        val socOk = soc >= socThreshold
        val exportOk = exportThreshold <= 0 || export >= exportThreshold
        val socText = String.format(Locale.GERMANY, "Akku %.0f %% %s %d %%", soc, if (socOk) "≥" else "<", socThreshold)
        val exportText = if (exportThreshold <= 0) "Einspeisung egal"
        else "Einspeisung ${formatPower(export.coerceAtLeast(0.0))} ${if (exportOk) "≥" else "<"} ${formatPower(exportThreshold.toDouble())}"
        return SurplusDecision(socOk && exportOk, "$socText · $exportText")
    }

    /**
     * Führt die Prüfung aus. [manual] = über „Jetzt prüfen“: ohne Zeitfenster und Tageslimit.
     * @return Text für die Anzeige
     */
    suspend fun run(context: Context, manual: Boolean): String {
        val s = SettingsRepository(context).settings.value
        val state = context.getSharedPreferences(STATE, Context.MODE_PRIVATE)
        val now = LocalTime.now()
        val today = LocalDate.now().toString()
        val result = when {
            !manual && !s.surplusNotify -> return "Hinweis ist ausgeschaltet"
            !manual && (now.hour < 9 || now.hour >= 18) -> "Außerhalb von 9–18 Uhr – nicht geprüft"
            !manual && state.getString("surplus_date", null) == today -> "Heute schon benachrichtigt"
            else -> try {
                val snapshot = LiveWidget.fetchAndCache(context).also { LiveWidget.updateAll(context) }
                val decision = decide(snapshot.batterySoc, snapshot.gridW, s.surplusSocPercent, s.surplusExportW)
                if (decision.notify) {
                    val export = -(snapshot.gridW ?: 0.0)
                    Notifier.show(
                        context, 1001, Notifier.CHANNEL_HINTS,
                        "Akku voll – Überschuss nutzen",
                        String.format(Locale.GERMANY, "Akku %.0f %%", snapshot.batterySoc ?: 0.0) +
                            (if (export > 50) ", ${formatPower(export)} werden eingespeist" else "") +
                            ". Jetzt Waschmaschine, Spülmaschine oder Wallbox nutzen.",
                        tab = "LIVE",
                    )
                    if (!manual) state.edit().putString("surplus_date", today).apply()
                    "Benachrichtigt – ${decision.reason}" +
                        if (!Notifier.permitted(context)) " (Benachrichtigungen sind für die App aber ausgeschaltet!)" else ""
                } else {
                    "Keine Nachricht – ${decision.reason}"
                }
            } catch (e: Exception) {
                "Abruf fehlgeschlagen: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        val stamp = java.time.LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd.MM. HH:mm"))
        val text = "$stamp${if (manual) " (manuell)" else ""}: $result"
        state.edit().putString("last_surplus_check", text).apply()
        return text
    }

    fun lastCheck(context: Context): String? =
        context.getSharedPreferences(STATE, Context.MODE_PRIVATE).getString("last_surplus_check", null)
}

/**
 * Prüft in großen Abständen, ob der Akku voll ist (und ggf. Strom eingespeist wird).
 * Nur tagsüber (9–18 Uhr) und höchstens einmal pro Tag eine Benachrichtigung.
 */
class SurplusWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        SurplusCheck.run(applicationContext, manual = false)
        return Result.success() // auch bei Fehlern: nächster Versuch im nächsten Intervall
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
