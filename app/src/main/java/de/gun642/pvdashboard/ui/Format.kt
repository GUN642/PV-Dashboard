package de.gun642.pvdashboard.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

fun formatPower(watts: Double?): String = when {
    watts == null -> "–"
    abs(watts) >= 1000 -> String.format(Locale.GERMANY, "%.2f kW", watts / 1000)
    else -> String.format(Locale.GERMANY, "%.0f W", watts)
}

fun formatPercent(fraction: Double?): String =
    if (fraction == null) "–" else String.format(Locale.GERMANY, "%.0f %%", fraction * 100)

fun formatTime(millis: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.GERMANY).format(Date(millis))
