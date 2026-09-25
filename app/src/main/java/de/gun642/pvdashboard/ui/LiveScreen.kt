package de.gun642.pvdashboard.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import de.gun642.pvdashboard.MainViewModel
import de.gun642.pvdashboard.senec.LiveSource
import de.gun642.pvdashboard.senec.SenecSnapshot
import de.gun642.pvdashboard.stats.EnergyTotals
import de.gun642.pvdashboard.ui.theme.EnergyColors
import de.gun642.pvdashboard.ui.theme.VoidTheme
import org.json.JSONObject
import kotlin.math.abs

@Composable
fun LiveScreen(vm: MainViewModel, onSettings: () -> Unit) {
    val c = VoidTheme.colors
    val s = vm.snapshot
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        TabHeader("Live") {
            IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, "Einstellungen", tint = c.text) }
        }
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            StatusLine(vm)
            vm.liveError?.let { ErrorTile(it) }
            if (s != null) {
                Hero(s)
                FlowTiles(s)
                BatteryTile(s)
                RatioTile(s)
                vm.today?.let { TodayTile(it) }
                ChartTile(vm.history)
                TextButton(
                    onClick = { vm.showRaw("Live-Rohdaten", pretty(s.raw)) },
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                ) { Text("ROHDATEN", style = MaterialTheme.typography.labelLarge, color = c.accent) }
            } else if (vm.liveError == null) {
                Tile(Modifier.fillMaxWidth()) {
                    Label("Verbinde …")
                    Spacer(Modifier.height(80.dp))
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun StatusLine(vm: MainViewModel) {
    val c = VoidTheme.colors
    val s = vm.snapshot
    Row(verticalAlignment = Alignment.CenterVertically) {
        val (color, text) = when {
            s == null -> c.textMuted to if (vm.liveLoading) "Verbinde" else "Keine Daten"
            s.source == LiveSource.LOCAL -> EnergyColors.gridExport to "Heimnetz · ${formatTime(s.timestamp)}"
            else -> EnergyColors.battery to "SENEC-Cloud · ${formatTime(s.timestamp)}"
        }
        Dot(color)
        Spacer(Modifier.width(8.dp))
        Label(text)
    }
}

@Composable
private fun ErrorTile(message: String) {
    Tile(Modifier.fillMaxWidth()) {
        Label("Hinweis", color = VoidTheme.colors.accent)
        Spacer(Modifier.height(6.dp))
        Text(message, color = VoidTheme.colors.text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Hero(s: SenecSnapshot) {
    Tile(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dot(EnergyColors.pv)
            Spacer(Modifier.width(8.dp))
            Label("PV-Erzeugung")
        }
        val (value, unit) = powerParts(s.pvW)
        BigValue(value, unit, size = 72)
        s.houseW?.let { house ->
            Label("Hausverbrauch ${formatPower(house)}")
        }
    }
}

@Composable
private fun FlowTiles(s: SenecSnapshot) {
    val grid = s.gridW
    val battery = s.batteryW
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FlowTile(
                label = when {
                    grid == null -> "Netz"
                    grid > 0 -> "Netzbezug"
                    else -> "Einspeisung"
                },
                watts = grid?.let { abs(it) },
                color = if ((grid ?: 0.0) > 0) EnergyColors.gridImport else EnergyColors.gridExport,
                modifier = Modifier.weight(1f),
            )
            FlowTile(
                label = when {
                    battery == null -> "Speicher"
                    battery > 5 -> "Speicher lädt"
                    battery < -5 -> "Speicher entlädt"
                    else -> "Speicher ruht"
                },
                watts = battery?.let { abs(it) },
                color = EnergyColors.battery,
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FlowTile(
                label = "Wallbox",
                watts = s.wallboxW,
                color = EnergyColors.wallbox,
                modifier = Modifier.weight(1f),
                detail = when (s.wallboxCarConnected) {
                    true -> "Auto verbunden"
                    false -> "Kein Auto"
                    null -> null
                },
            )
            FlowTile(
                label = "Haus",
                watts = s.houseW,
                color = EnergyColors.house,
                modifier = Modifier.weight(1f),
                detail = s.batteryTemp?.let { String.format(java.util.Locale.GERMANY, "Akku %.1f °C", it) },
            )
        }
    }
}

@Composable
private fun FlowTile(label: String, watts: Double?, color: Color, modifier: Modifier, detail: String? = null) {
    Tile(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dot(color)
            Spacer(Modifier.width(8.dp))
            Label(label)
        }
        Spacer(Modifier.height(6.dp))
        val (value, unit) = powerParts(watts)
        BigValue(value, unit, size = 34)
        if (detail != null) Label(detail)
    }
}

@Composable
private fun BatteryTile(s: SenecSnapshot) {
    val soc = s.batterySoc ?: return
    Tile(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Label("Ladestand Speicher", Modifier.weight(1f))
            Text(formatPercent(soc / 100), style = MaterialTheme.typography.titleLarge, color = VoidTheme.colors.text)
        }
        Spacer(Modifier.height(10.dp))
        DotBar((soc / 100).toFloat(), Modifier.fillMaxWidth().height(12.dp), dots = 24, color = EnergyColors.battery)
    }
}

@Composable
private fun RatioTile(s: SenecSnapshot) {
    Tile(Modifier.fillMaxWidth()) {
        Row {
            Column(Modifier.weight(1f)) {
                Label("Autarkie")
                BigValue(formatPercent(s.autarky).removeSuffix(" %"), "%", size = 34)
            }
            Column(Modifier.weight(1f)) {
                Label("Eigenverbrauch")
                BigValue(formatPercent(s.selfConsumption).removeSuffix(" %"), "%", size = 34)
            }
        }
    }
}

@Composable
private fun TodayTile(t: EnergyTotals) {
    Tile(Modifier.fillMaxWidth()) {
        Label("Heute")
        Spacer(Modifier.height(6.dp))
        ValueRow("PV-Erzeugung", formatKwh(t.pv), EnergyColors.pv)
        ValueRow("Verbrauch", formatKwh(t.consumption), EnergyColors.house)
        ValueRow("Netzbezug", formatKwh(t.gridImport), EnergyColors.gridImport)
        ValueRow("Einspeisung", formatKwh(t.gridExport), EnergyColors.gridExport)
        ValueRow("Speicher geladen / entladen", "${formatKwh(t.batteryCharge)} / ${formatKwh(t.batteryDischarge)}", EnergyColors.battery)
        if (t.wallbox > 0) ValueRow("Wallbox", formatKwh(t.wallbox), EnergyColors.wallbox)
    }
}

private class Series(val label: String, val color: Color, val value: (SenecSnapshot) -> Double?)

@Composable
private fun ChartTile(history: List<SenecSnapshot>) {
    val c = VoidTheme.colors
    val series = listOf(
        Series("PV", EnergyColors.pv) { it.pvW },
        Series("Haus", EnergyColors.house) { it.houseW },
        Series("Netz", EnergyColors.gridImport) { it.gridW },
        Series("Wallbox", EnergyColors.wallbox) { it.wallboxW },
    )
    Tile(Modifier.fillMaxWidth()) {
        Label("Verlauf · letzte 30 min")
        Spacer(Modifier.height(12.dp))
        if (history.size < 2) {
            Text("Wird aufgebaut, solange die App offen ist …", color = c.textMuted, style = MaterialTheme.typography.bodyMedium)
            return@Tile
        }
        val values = history.flatMap { s -> series.mapNotNull { it.value(s) } }
        val maxY = maxOf(values.maxOrNull() ?: 0.0, 500.0)
        val minY = minOf(values.minOrNull() ?: 0.0, 0.0)
        val start = history.first().timestamp
        val span = (history.last().timestamp - start).coerceAtLeast(1L).toFloat()
        Canvas(Modifier.fillMaxWidth().height(170.dp)) {
            fun y(v: Double) = (size.height * (1 - (v - minY) / (maxY - minY))).toFloat()
            fun x(t: Long) = size.width * (t - start) / span
            drawLine(c.divider, Offset(0f, y(0.0)), Offset(size.width, y(0.0)), strokeWidth = 2f)
            series.forEach { line ->
                val path = Path()
                var started = false
                history.forEach { s ->
                    val v = line.value(s)
                    if (v == null) started = false
                    else if (!started) { path.moveTo(x(s.timestamp), y(v)); started = true }
                    else path.lineTo(x(s.timestamp), y(v))
                }
                drawPath(path, line.color, style = Stroke(width = 4f))
            }
        }
        Spacer(Modifier.height(8.dp))
        Legend(series.map { it.label to it.color })
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth()) {
            Label(formatTime(history.first().timestamp), Modifier.weight(1f))
            Label("max ${formatPower(maxY)}")
        }
    }
}

fun pretty(raw: Map<String, Any?>): String =
    try {
        JSONObject(raw).toString(2)
    } catch (e: Exception) {
        raw.toString()
    }
