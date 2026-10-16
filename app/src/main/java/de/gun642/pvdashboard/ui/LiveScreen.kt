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
import androidx.compose.foundation.layout.size
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
import de.gun642.pvdashboard.widget.LiveWidget
import de.gun642.pvdashboard.widget.PvShares
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.sp
import java.util.Locale
import org.json.JSONObject
import kotlin.math.abs

@Composable
fun LiveScreen(vm: MainViewModel, onSettings: () -> Unit, onWallbox: () -> Unit) {
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
                FlowTiles(s, onWallbox)
                BatteryTile(s)
                RatioTile(s)
                vm.today?.let { TodayTile(it) }
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
            s.source == LiveSource.LOCAL -> EnergyColors.positive to "Heimnetz · ${formatTime(s.timestamp)}"
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
    val shares = PvShares.compute(s.pvW, s.gridW, s.batteryW, s.wallboxW)
    Tile(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Dot(EnergyColors.pv)
                    Spacer(Modifier.width(8.dp))
                    Label("PV-Erzeugung")
                }
                val (value, unit) = powerParts(s.pvW?.coerceAtLeast(0.0))
                BigValue(value, unit, size = 60)
                s.houseW?.let { house ->
                    Label("Hausverbrauch ${formatPower(house)}")
                }
            }
            PvDonut(shares, Modifier.size(118.dp))
        }
        if (shares != null) {
            Spacer(Modifier.height(16.dp))
            PvShareLegend(shares)
        }
    }
}

@Composable
private fun FlowTiles(s: SenecSnapshot, onWallbox: () -> Unit) {
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
                onClick = onWallbox,
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
private fun FlowTile(
    label: String,
    watts: Double?,
    color: Color,
    modifier: Modifier,
    detail: String? = null,
    onClick: (() -> Unit)? = null,
) {
    Tile(modifier, onClick = onClick) {
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
        // Farben wie im Widget: rot unter 10 %, gelb, grün über 90 %
        DotBar((soc / 100).toFloat(), Modifier.fillMaxWidth().height(12.dp), dots = 24, color = Color(LiveWidget.socColor(soc)))
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

fun pretty(raw: Map<String, Any?>): String =
    try {
        JSONObject(raw).toString(2)
    } catch (e: Exception) {
        raw.toString()
    }
