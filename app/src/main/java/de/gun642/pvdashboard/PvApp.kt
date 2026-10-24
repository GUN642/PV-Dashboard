package de.gun642.pvdashboard

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import java.time.ZoneId
import java.util.TimeZone

/** Feste Zeitzone der App: die Anlage steht in Deutschland, also gelten deutsche Uhrzeiten – auch im Urlaub. */
object AppTime {
    val ZONE: ZoneId = ZoneId.of("Europe/Berlin")

    /**
     * Setzt die Standard-Zeitzone des App-Prozesses. Damit rechnen alle Datums- und Zeitangaben
     * (Statistik, Ladelog, Widgets, Erinnerungen, Uhrzeiten) in deutscher Zeit, unabhängig von der Handy-Zeitzone.
     */
    fun apply() {
        val tz = TimeZone.getTimeZone(ZONE)
        if (TimeZone.getDefault().id != tz.id) TimeZone.setDefault(tz)
    }
}

class PvApp : Application() {
    override fun onCreate() {
        AppTime.apply()
        super.onCreate()
        // Android setzt die Zeitzone bei einem Wechsel (z. B. nach der Landung im Ausland) auf die des Handys
        // zurück – dann sofort wieder auf Deutschland stellen.
        val filter = IntentFilter(Intent.ACTION_TIMEZONE_CHANGED)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = AppTime.apply()
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(receiver, filter)
    }
}
