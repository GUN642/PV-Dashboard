package de.gun642.pvdashboard.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import de.gun642.pvdashboard.MainViewModel
import de.gun642.pvdashboard.data.AppSettings
import de.gun642.pvdashboard.stats.PvgisReference
import de.gun642.pvdashboard.weather.DayForecast
import de.gun642.pvdashboard.weather.Forecast
import de.gun642.pvdashboard.weather.OpenMeteo
import de.gun642.pvdashboard.ui.theme.EnergyColors
import de.gun642.pvdashboard.ui.theme.VoidTheme
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.TextStyle
import java.util.Locale

@Composable
fun WeatherScreen(vm: MainViewModel, settings: AppSettings, onSettings: () -> Unit) {
    val c = VoidTheme.colors
    LaunchedEffect(settings.latitude, settings.longitude, settings.peakPowerKwp) { vm.loadWeather() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        TabHeader("Wetter") {
            if (vm.weatherLoading) {
                CircularProgressIndicator(Modifier.padding(12.dp).size(22.dp), color = c.accent, strokeWidth = 2.dp)
            } else {
                IconButton(onClick = { vm.loadWeather(force = true) }) { Icon(Icons.Filled.Refresh, "Aktualisieren", tint = c.text) }
            }
        }
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (settings.locationName.isNotBlank()) Label(settings.locationName)
            vm.weatherError?.let { msg ->
                Tile(Modifier.fillMaxWidth(), onClick = if (!settings.hasLocation) onSettings else null) {
                    Label("Hinweis", color = c.accent)
                    Spacer(Modifier.height(6.dp))
                    Text(msg, color = c.text, style = MaterialTheme.typography.bodyMedium)
                }
            }
            vm.forecast?.let { f ->
                TodayTile(f, settings.peakPowerKwp > 0, vm.pvgis)
                Label("7 Tage")
                f.days.forEach { DayRow(it) }
                if (settings.peakPowerKwp <= 0) {
                    Tile(Modifier.fillMaxWidth(), onClick = onSettings) {
                        Text(
                            "Tipp: Trage in den Einstellungen die Leistung deiner Anlage (kWp) ein, dann schätzt die App den PV-Ertrag.",
                            color = c.textMuted, style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                Attribution()
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun TodayTile(f: Forecast, showPv: Boolean, pvgis: PvgisReference?) {
    val c = VoidTheme.colors
    val today = f.days.firstOrNull { it.date == LocalDate.now() } ?: f.days.firstOrNull() ?: return
    Tile(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dot(EnergyColors.pv)
            Spacer(Modifier.width(8.dp))
            Label("Sonnenstunden heute", Modifier.weight(1f))
            Label(OpenMeteo.describe(today.weatherCode))
        }
        BigValue(String.format(Locale.GERMANY, "%.1f", today.sunshineHours), "h", size = 60)
        Label(
            buildString {
                append("von ").append(formatHours(today.daylightHours)).append(" Tageslicht")
                today.tempMax?.let { append(" · ").append(String.format(Locale.GERMANY, "%.0f°", it)) }
                today.tempMin?.let { append(" / ").append(String.format(Locale.GERMANY, "%.0f°", it)) }
            }
        )
        if (showPv && today.pvKwh != null) {
            Spacer(Modifier.height(12.dp))
            Label("Erwarteter PV-Ertrag")
            BigValue(String.format(Locale.GERMANY, "%.1f", today.pvKwh), "kWh", color = EnergyColors.pv, size = 40)
            pvgis?.let {
                val avg = it.dailyAverage(today.date)
                Label(String.format(Locale.GERMANY, "PVGIS-Mittel: %.1f kWh/Tag · %.0f %%", avg, today.pvKwh / avg * 100))
            }
        }
        // Stündliche Sonnenscheindauer von 5 bis 21 Uhr
        val hours = f.hours.filter { it.time.toLocalDate() == today.date && it.time.hour in 5..21 }
        if (hours.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Label(if (showPv) "Erwartete Leistung je Stunde" else "Sonnenminuten je Stunde")
            Spacer(Modifier.height(8.dp))
            BarChart(
                labels = hours.map { "%02d".format(it.time.hour) },
                series = listOf(
                    EnergyColors.pv to hours.map { if (showPv) it.pvKw ?: 0.0 else it.sunshineMinutes },
                ),
                labelEvery = 4,
                seriesNames = listOf(if (showPv) "Erwartete Leistung" else "Sonnenschein"),
                unit = if (showPv) "kW" else "min",
                minScale = if (showPv) 10.0 else 60.0,
                detailLabels = hours.map { String.format(Locale.GERMANY, "%02d:00 Uhr", it.time.hour) },
            )
            val now = LocalDateTime.now()
            hours.firstOrNull { it.time.hour == now.hour }?.let {
                Spacer(Modifier.height(6.dp))
                Label(
                    if (showPv) "Jetzt ca. ${formatPower((it.pvKw ?: 0.0) * 1000)}"
                    else "Jetzt ${it.sunshineMinutes.toInt()} Sonnenminuten",
                    color = c.text,
                )
            }
        }
    }
}

@Composable
private fun DayRow(d: DayForecast) {
    val c = VoidTheme.colors
    val today = LocalDate.now()
    val name = when (d.date) {
        today -> "Heute"
        today.plusDays(1) -> "Morgen"
        else -> d.date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.GERMANY)
    }
    Tile(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(name, color = c.text, style = MaterialTheme.typography.titleMedium)
                Label(
                    buildString {
                        append(OpenMeteo.describe(d.weatherCode))
                        if (d.tempMax != null && d.tempMin != null) {
                            append(" · ").append(String.format(Locale.GERMANY, "%.0f° / %.0f°", d.tempMax, d.tempMin))
                        }
                        d.precipitationProbability?.takeIf { it > 0 }?.let { append(" · ").append(it).append(" % Regen") }
                    }
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(formatHours(d.sunshineHours), color = c.text, style = MaterialTheme.typography.titleMedium)
                d.pvKwh?.let { Label(String.format(Locale.GERMANY, "≈ %.1f kWh", it), color = EnergyColors.pv) }
            }
        }
        Spacer(Modifier.height(10.dp))
        val fraction = if (d.daylightHours > 0) d.sunshineHours / d.daylightHours else 0.0
        DotBar(fraction.toFloat(), Modifier.fillMaxWidth().height(10.dp), dots = 28, color = EnergyColors.pv)
    }
}

@Composable
private fun Attribution() {
    val uri = LocalUriHandler.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        androidx.compose.material3.TextButton(onClick = { uri.openUri("https://open-meteo.com/") }) {
            Label("Wetterdaten: Open-Meteo.com (CC BY 4.0)")
        }
    }
}
