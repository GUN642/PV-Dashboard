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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.gun642.pvdashboard.DashboardState
import de.gun642.pvdashboard.senec.SenecSnapshot
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    state: DashboardState,
    onOpenSettings: () -> Unit,
    onOpenRaw: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("PV Dashboard") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Einstellungen")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatusLine(state)
            state.error?.let { ErrorCard(it) }
            state.snapshot?.let { snapshot ->
                PowerTiles(snapshot)
                BatteryCard(snapshot)
                RatiosCard(snapshot)
                ChartCard(state.history)
                TextButton(onClick = onOpenRaw, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text("Rohdaten anzeigen")
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun StatusLine(state: DashboardState) {
    val text = when {
        state.snapshot != null -> "Live · aktualisiert ${formatTime(state.snapshot.timestamp)}"
        state.loading -> "Verbinde mit ${state.settings.host} …"
        else -> "Keine Daten"
    }
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ErrorCard(message: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Text(
            message,
            modifier = Modifier.padding(16.dp),
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

@Composable
private fun PowerTiles(s: SenecSnapshot) {
    val grid = s.gridW
    val battery = s.batteryW
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PowerTile("PV-Erzeugung", formatPower(s.pvW), EnergyColors.pv, Modifier.weight(1f))
            PowerTile("Hausverbrauch", formatPower(s.houseW), EnergyColors.house, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PowerTile(
                label = when {
                    grid == null -> "Netz"
                    grid > 0 -> "Netzbezug"
                    else -> "Einspeisung"
                },
                value = formatPower(grid?.let { abs(it) }),
                color = if ((grid ?: 0.0) > 0) EnergyColors.gridImport else EnergyColors.gridExport,
                modifier = Modifier.weight(1f),
            )
            PowerTile(
                label = when {
                    battery == null -> "Speicher"
                    battery > 5 -> "Speicher lädt"
                    battery < -5 -> "Speicher entlädt"
                    else -> "Speicher ruht"
                },
                value = formatPower(battery?.let { abs(it) }),
                color = EnergyColors.battery,
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PowerTile(
                label = "Wallbox",
                value = formatPower(s.wallboxW),
                color = EnergyColors.wallbox,
                modifier = Modifier.weight(1f),
                detail = when (s.wallboxCarConnected) {
                    true -> "Auto verbunden"
                    false -> "kein Auto"
                    null -> null
                },
            )
            PowerTile(
                label = "Akku-Temperatur",
                value = s.batteryTemp?.let { String.format(java.util.Locale.GERMANY, "%.1f °C", it) } ?: "–",
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun PowerTile(
    label: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier,
    detail: String? = null,
) {
    Card(modifier = modifier) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(
                    Modifier
                        .size(10.dp)
                        .background(color, CircleShape)
                )
                Spacer(Modifier.size(8.dp))
                Text(label, style = MaterialTheme.typography.labelLarge)
            }
            Spacer(Modifier.height(6.dp))
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            if (detail != null) {
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun BatteryCard(s: SenecSnapshot) {
    val soc = s.batterySoc ?: return
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Ladestand Speicher", style = MaterialTheme.typography.labelLarge)
                Text(formatPercent(soc / 100), fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { (soc / 100).toFloat().coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp),
                color = EnergyColors.battery,
            )
        }
    }
}

@Composable
private fun RatiosCard(s: SenecSnapshot) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp)) {
            Ratio("Autarkie", formatPercent(s.autarky), Modifier.weight(1f))
            Ratio("Eigenverbrauch", formatPercent(s.selfConsumption), Modifier.weight(1f))
        }
    }
}

@Composable
private fun Ratio(label: String, value: String, modifier: Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
    }
}
