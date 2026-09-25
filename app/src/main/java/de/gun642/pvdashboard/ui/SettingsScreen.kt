package de.gun642.pvdashboard.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import de.gun642.pvdashboard.AppSettings
import de.gun642.pvdashboard.BuildConfig

private const val RELEASES_URL = "https://github.com/GUN642/PV-Dashboard/releases"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onSave: (AppSettings) -> Unit,
    onBack: () -> Unit,
) {
    var host by rememberSaveable { mutableStateOf(settings.host) }
    var useHttps by rememberSaveable { mutableStateOf(settings.useHttps) }
    var interval by rememberSaveable { mutableStateOf(settings.intervalSeconds.toString()) }
    var wallbox by rememberSaveable { mutableStateOf((settings.wallboxIndex + 1).toString()) }
    val uriHandler = LocalUriHandler.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Einstellungen") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("SENEC.Home im Heimnetz", style = MaterialTheme.typography.titleMedium)
            Text(
                "Die App liest die Live-Werte direkt vom Speicher. Das Handy muss dafür im Heim-WLAN sein. " +
                    "Die IP-Adresse des Speichers findest du in deinem Router (z. B. FRITZ!Box → Heimnetz), " +
                    "oft 192.168.178.x.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = host,
                onValueChange = { host = it },
                label = { Text("IP-Adresse des Speichers") },
                placeholder = { Text("192.168.178.50") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("HTTPS verwenden")
                    Text(
                        "Neuere Firmware: an. Nur bei sehr alter Firmware ausschalten.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = useHttps, onCheckedChange = { useHttps = it })
            }
            OutlinedTextField(
                value = interval,
                onValueChange = { interval = it.filter(Char::isDigit).take(3) },
                label = { Text("Aktualisierung alle … Sekunden") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = wallbox,
                onValueChange = { wallbox = it.filter(Char::isDigit).take(1) },
                label = { Text("Wallbox Nr. (1–4)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    onSave(
                        AppSettings(
                            host = host,
                            useHttps = useHttps,
                            intervalSeconds = interval.toIntOrNull() ?: 5,
                            wallboxIndex = (wallbox.toIntOrNull() ?: 1) - 1,
                        )
                    )
                },
                enabled = host.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Speichern")
            }
            OutlinedButton(onClick = { uriHandler.openUri(RELEASES_URL) }, modifier = Modifier.fillMaxWidth()) {
                Text("Nach neuer Version suchen")
            }
            Text(
                "Version ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
