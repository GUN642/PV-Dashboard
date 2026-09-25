package de.gun642.pvdashboard.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.gun642.pvdashboard.MainViewModel
import de.gun642.pvdashboard.data.AppSettings
import de.gun642.pvdashboard.senec.cloud.WallboxInfo
import de.gun642.pvdashboard.senec.cloud.WallboxMode
import de.gun642.pvdashboard.ui.theme.EnergyColors
import de.gun642.pvdashboard.ui.theme.VoidTheme
import java.util.Locale

/** Höchster einstellbarer Mindestladestrom (SENEC-Wallbox: 16 A je Phase). */
private const val MAX_CURRENT = 16

@Composable
fun WallboxScreen(vm: MainViewModel, settings: AppSettings, onSettings: () -> Unit) {
    val c = VoidTheme.colors
    LaunchedEffect(settings.hasCloud) { vm.loadWallbox() }
    var confirmMode by remember { mutableStateOf<WallboxMode?>(null) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        TabHeader("Wallbox") {
            if (vm.wallboxLoading || vm.wallboxBusy) {
                CircularProgressIndicator(Modifier.padding(12.dp).size(22.dp), color = c.accent, strokeWidth = 2.dp)
            } else {
                IconButton(onClick = { vm.loadWallbox() }) { Icon(Icons.Filled.Refresh, "Aktualisieren", tint = c.text) }
            }
        }
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            vm.wallboxError?.let { msg ->
                Tile(Modifier.fillMaxWidth(), onClick = if (!settings.hasCloud) onSettings else null) {
                    Label("Hinweis", color = c.accent)
                    Spacer(Modifier.height(6.dp))
                    Text(msg, color = c.text, style = MaterialTheme.typography.bodyMedium)
                }
            }
            vm.wallbox?.let { wb ->
                StatusTile(wb)
                ModeTile(wb, enabled = !vm.wallboxBusy) { confirmMode = it }
                when (wb.mode) {
                    WallboxMode.FAST -> FastTile(vm, wb)
                    WallboxMode.SOLAR -> SolarTile(vm, wb)
                    else -> {}
                }
                Text(
                    "Änderungen laufen über die SENEC-Cloud. Danach liest die App den tatsächlichen Zustand der Wallbox zurück – " +
                        "angezeigt wird immer, was die Wallbox meldet.",
                    color = c.textMuted, style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = { vm.showRaw("Wallbox-Rohdaten", wb.rawJson) }, modifier = Modifier.fillMaxWidth()) {
                    Text("ROHDATEN", style = MaterialTheme.typography.labelLarge, color = c.accent)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    confirmMode?.let { mode ->
        AlertDialog(
            onDismissRequest = { confirmMode = null },
            containerColor = c.surface,
            title = { Text("Lademodus ändern", color = c.text) },
            text = {
                Text(
                    when (mode) {
                        WallboxMode.FAST -> "Auf Schnellladen umstellen? Das Auto lädt mit voller Leistung, auch aus dem Netz."
                        WallboxMode.SOLAR -> "Auf solaroptimiertes Laden umstellen? Das Auto lädt vor allem mit PV-Überschuss."
                        WallboxMode.COMFORT -> "Auf Komfortladen umstellen?"
                        WallboxMode.LOCKED -> "Wallbox sperren? Das Auto wird dann nicht geladen."
                        WallboxMode.UNKNOWN -> ""
                    },
                    color = c.textMuted,
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmMode = null; vm.setWallboxMode(mode) }) {
                    Text("ÄNDERN", style = MaterialTheme.typography.labelLarge, color = c.accent)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmMode = null }) {
                    Text("ABBRECHEN", style = MaterialTheme.typography.labelLarge, color = c.textMuted)
                }
            },
        )
    }
}

@Composable
private fun StatusTile(wb: WallboxInfo) {
    val c = VoidTheme.colors
    Tile(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dot(if (wb.hasError) EnergyColors.gridImport else if (wb.isCharging) EnergyColors.wallbox else c.textMuted)
            Spacer(Modifier.width(8.dp))
            Label(wb.name, Modifier.weight(1f))
            Label(wb.mode.label, color = c.text)
        }
        val (value, unit) = powerParts(wb.chargingPowerKw?.let { it * 1000 })
        BigValue(value, unit, size = 56)
        Label(wb.statusText)
        Spacer(Modifier.height(8.dp))
        ValueRow("Fahrzeug", if (wb.carConnected) "verbunden" else "nicht verbunden")
        wb.temperature?.let { ValueRow("Temperatur", String.format(Locale.GERMANY, "%.1f °C", it)) }
        if (wb.hasError) ValueRow("Fehler", "Wallbox meldet einen Fehler", emphasize = true)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModeTile(wb: WallboxInfo, enabled: Boolean, onSelect: (WallboxMode) -> Unit) {
    Tile(Modifier.fillMaxWidth()) {
        Label("Lademodus")
        Spacer(Modifier.height(10.dp))
        val modes = buildList {
            add(WallboxMode.FAST)
            if (wb.solarAvailable) add(WallboxMode.SOLAR)
            if (wb.comfortAvailable) add(WallboxMode.COMFORT)
            add(WallboxMode.LOCKED)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            modes.forEach { mode ->
                Pill(mode.label, selected = wb.mode == mode, onClick = { if (enabled && wb.mode != mode) onSelect(mode) })
            }
        }
    }
}

@Composable
private fun FastTile(vm: MainViewModel, wb: WallboxInfo) {
    if (!wb.interchargeAvailable) return
    Tile(Modifier.fillMaxWidth()) {
        Label("Schnellladen")
        Toggle(
            "Speicher lädt mit",
            "Der Hausspeicher unterstützt das Laden des Autos",
            wb.fastAllowIntercharge,
        ) { if (!vm.wallboxBusy) vm.setWallboxFastBattery(it) }
    }
}

@Composable
private fun SolarTile(vm: MainViewModel, wb: WallboxInfo) {
    val c = VoidTheme.colors
    Tile(Modifier.fillMaxWidth()) {
        Label("Solaroptimiertes Laden")
        Toggle(
            "Ladeunterbrechungen verhindern",
            "Bei zu wenig PV-Überschuss mit Mindeststrom aus Netz/Speicher weiterladen",
            wb.solarPreventInterruptions,
        ) { if (!vm.wallboxBusy) vm.setWallboxPreventInterruptions(it) }

        wb.solarMinCurrent?.let { current ->
            val min = (wb.minPossibleCurrent ?: 6.0).toInt()
            var selected by remember(current) { mutableStateOf(current.toInt()) }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Mindestladestrom", color = c.text, style = MaterialTheme.typography.bodyLarge)
                    Text("$min–$MAX_CURRENT A", color = c.textMuted, style = MaterialTheme.typography.bodyMedium)
                }
                Pill("–", selected = false, onClick = { if (selected > min) selected-- })
                Text(
                    "$selected A",
                    color = c.text,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 14.dp),
                )
                Pill("+", selected = false, onClick = { if (selected < MAX_CURRENT) selected++ })
            }
            if (selected != current.toInt()) {
                Spacer(Modifier.height(10.dp))
                Pill("$selected A übernehmen", selected = true, onClick = { if (!vm.wallboxBusy) vm.setWallboxMinCurrent(selected.toDouble()) })
            }
        }
    }
}
