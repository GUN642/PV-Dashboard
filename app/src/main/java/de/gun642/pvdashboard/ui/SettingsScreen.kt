package de.gun642.pvdashboard.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import de.gun642.pvdashboard.BuildConfig
import de.gun642.pvdashboard.MainViewModel
import de.gun642.pvdashboard.data.Accent
import de.gun642.pvdashboard.data.AppSettings
import de.gun642.pvdashboard.data.DataSource
import de.gun642.pvdashboard.stats.PvgisReference
import de.gun642.pvdashboard.data.ThemeMode
import de.gun642.pvdashboard.data.Updater
import de.gun642.pvdashboard.ui.theme.VoidTheme
import de.gun642.pvdashboard.ui.theme.headingStyle

private val themeSwatches = mapOf(
    ThemeMode.BLACK to Color(0xFF000000),
    ThemeMode.GRAPHITE to Color(0xFF1C1C1E),
    ThemeMode.STEEL to Color(0xFF12161B),
    ThemeMode.PAPER to Color(0xFFEFEEEA),
    ThemeMode.WHITE to Color(0xFFFFFFFF),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(vm: MainViewModel, s: AppSettings, onBack: () -> Unit) {
    val c = VoidTheme.colors
    Column(Modifier.fillMaxSize().background(c.background)) {
        ScreenHeader("Einstellungen", onBack)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 40.dp)) {

            // ---------- Heimnetz ----------
            Group("Speicher im Heimnetz")
            Hint("Live-Werte direkt vom SENEC.Home – schnell und ohne Cloud, aber nur im Heim-WLAN. Die IP-Adresse steht im Router (z. B. FRITZ!Box → Heimnetz).")
            Spacer(Modifier.height(10.dp))
            var host by rememberSaveable { mutableStateOf(s.host) }
            VoidTextField(host, { host = it; vm.updateSettings { copy(host = it.trim()) } }, "IP-Adresse des Speichers", placeholder = "192.168.178.50", keyboardType = KeyboardType.Uri)
            Toggle("HTTPS verwenden", "Neuere Firmware: an. Nur bei sehr alter Firmware aus.", s.useHttps) { v -> vm.updateSettings { copy(useHttps = v) } }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NumberField(s.intervalSeconds.toDouble(), { v -> vm.updateSettings { copy(intervalSeconds = v.toInt().coerceIn(2, 300)) } }, "Aktualisierung", "s", Modifier.weight(1f))
                NumberField((s.wallboxIndex + 1).toDouble(), { v -> vm.updateSettings { copy(wallboxIndex = (v.toInt() - 1).coerceIn(0, 3)) } }, "Wallbox Nr.", "", Modifier.weight(1f))
            }

            // ---------- SENEC-Konto ----------
            Group("SENEC-Konto · unterwegs & Statistik")
            Hint("Mit deinem mein-senec.de-Konto zeigt die App die Werte auch unterwegs und lädt alle Statistiken. Das Passwort wird verschlüsselt nur auf diesem Gerät gespeichert.")
            Spacer(Modifier.height(10.dp))
            var email by rememberSaveable { mutableStateOf(s.senecEmail) }
            var password by rememberSaveable { mutableStateOf(s.senecPassword) }
            VoidTextField(email, { email = it; vm.updateSettings { copy(senecEmail = it.trim()) } }, "E-Mail", keyboardType = KeyboardType.Email)
            Spacer(Modifier.height(8.dp))
            VoidTextField(
                password, { password = it; vm.updateSettings { copy(senecPassword = it) } }, "Passwort",
                keyboardType = KeyboardType.Password, visualTransformation = PasswordVisualTransformation(),
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("Anmelden & testen", selected = true, onClick = { vm.testLogin() })
                if (s.hasCloud) Pill("Abmelden", selected = false, onClick = { password = ""; vm.logout() })
                if (vm.loginRunning) CircularProgressIndicator(Modifier.size(20.dp), color = c.accent, strokeWidth = 2.dp)
            }
            vm.loginStatus?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = if (it.startsWith("Fehler")) c.accent else c.textMuted, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(14.dp))
            Label("Live-Datenquelle")
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DataSource.entries.forEach { d -> Pill(d.label, s.dataSource == d, { vm.updateSettings { copy(dataSource = d) } }) }
            }
            Spacer(Modifier.height(6.dp))
            Hint("Automatisch: im Heimnetz direkt vom Speicher, unterwegs über die SENEC-Cloud.")

            // ---------- Tarif ----------
            Group("Stromtarif")
            var provider by rememberSaveable { mutableStateOf(s.provider) }
            VoidTextField(provider, { provider = it; vm.updateSettings { copy(provider = it) } }, "Stromanbieter", placeholder = "z. B. Stadtwerke")
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NumberField(s.pricePerKwhCent, { v -> vm.updateSettings { copy(pricePerKwhCent = v) } }, "Arbeitspreis", "ct/kWh", Modifier.weight(1f))
                NumberField(s.baseFeePerMonth, { v -> vm.updateSettings { copy(baseFeePerMonth = v) } }, "Grundgebühr", "€/Monat", Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            NumberField(s.feedInCent, { v -> vm.updateSettings { copy(feedInCent = v) } }, "Einspeisevergütung", "ct/kWh")

            // ---------- Standort & Anlage ----------
            Group("Standort & Anlage")
            if (s.locationName.isNotBlank()) {
                Text(s.locationName, color = c.text, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(8.dp))
            }
            var query by rememberSaveable { mutableStateOf("") }
            Row(verticalAlignment = Alignment.CenterVertically) {
                VoidTextField(query, { query = it }, "Ort oder PLZ suchen", Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                if (vm.placeSearching) CircularProgressIndicator(Modifier.size(22.dp), color = c.accent, strokeWidth = 2.dp)
                else Pill("Suchen", selected = true, onClick = { vm.searchPlace(query) })
            }
            vm.placeResults.forEach { place ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { vm.choosePlace(place); query = "" }.padding(vertical = 10.dp),
                ) {
                    Column {
                        Text(place.name, color = c.text, style = MaterialTheme.typography.bodyLarge)
                        Label(place.detail)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NumberField(s.peakPowerKwp, { v -> vm.updateSettings { copy(peakPowerKwp = v) } }, "Anlagenleistung", "kWp", Modifier.weight(1f))
                NumberField(s.tiltDegrees.toDouble(), { v -> vm.updateSettings { copy(tiltDegrees = v.toInt().coerceIn(0, 90)) } }, "Dachneigung", "°", Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NumberField(s.azimuthDegrees.toDouble(), { v -> vm.updateSettings { copy(azimuthDegrees = v.toInt().coerceIn(0, 359)) } }, "Azimut", "°", Modifier.weight(1f))
                NumberField(s.systemLossPercent, { v -> vm.updateSettings { copy(systemLossPercent = v.coerceIn(0.0, 50.0)) } }, "Systemverluste", "%", Modifier.weight(1f))
            }
            Spacer(Modifier.height(6.dp))
            Hint("Azimut wie bei PVGIS: 90 = Ost, 180 = Süd, 270 = West. Systemverluste: PVGIS-Standard 14 %.")

            // ---------- PVGIS ----------
            Group("PVGIS-Referenz")
            Hint("Langjähriger Mittelwert der EU (PVGIS) für deine Anlage. Die Statistik vergleicht damit Ist und Soll.")
            Spacer(Modifier.height(10.dp))
            if (s.hasPvgis) {
                Text(s.pvgisInfo.ifBlank { "Referenz hinterlegt" }, color = c.text, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(10.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill(if (s.hasPvgis) "Neu berechnen" else "Von PVGIS laden", selected = true, onClick = { vm.fetchPvgis() })
                if (s.hasPvgis) Pill("Entfernen", selected = false, onClick = { vm.clearPvgis() })
                if (vm.pvgisLoading) CircularProgressIndicator(Modifier.size(20.dp), color = c.accent, strokeWidth = 2.dp)
            }
            Spacer(Modifier.height(12.dp))
            // Monatswerte ansehen oder von Hand eintragen (z. B. aus dem PVGIS-PDF)
            var monthlyText by remember(s.pvgisMonthly) { mutableStateOf(if (s.hasPvgis) PvgisReference.formatMonthly(s.pvgisMonthly) else "") }
            VoidTextField(monthlyText, { monthlyText = it }, "Monatswerte Jan–Dez (kWh)", placeholder = "360,5 577,6 970,8 …")
            Spacer(Modifier.height(6.dp))
            Hint("12 Werte mit Leerzeichen getrennt, z. B. die Spalte E_m aus dem PVGIS-Bericht.")
            val parsed = PvgisReference.parseMonthly(monthlyText)
            if (parsed != null && parsed != s.pvgisMonthly) {
                Spacer(Modifier.height(8.dp))
                Pill("Werte übernehmen", selected = true, onClick = { vm.setPvgisManual(parsed) })
            }

            // ---------- Design ----------
            Group("Hintergrund")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ThemeMode.entries.forEach { t -> ThemeTile(t, t == s.theme) { vm.updateSettings { copy(theme = t) } } }
            }
            Group("Akzentfarbe")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Accent.entries.forEach { a ->
                    val color = if (a == Accent.MONO) c.text else Color(a.argb)
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier.size(44.dp).clip(CircleShape)
                                .border(2.dp, if (a == s.accent) c.text else Color.Transparent, CircleShape)
                                .padding(5.dp).clip(CircleShape).background(color)
                                .clickable { vm.updateSettings { copy(accent = a) } },
                        )
                        Spacer(Modifier.height(4.dp))
                        Label(a.label, color = if (a == s.accent) c.text else c.textMuted)
                    }
                }
            }
            Group("Design")
            Toggle("Dot-Matrix-Überschriften", "Pixelschrift im Nothing-Stil", s.dotHeadings) { v -> vm.updateSettings { copy(dotHeadings = v) } }
            Toggle("Punkteraster", "Dezentes Punktmuster im Hintergrund", s.dotGrid) { v -> vm.updateSettings { copy(dotGrid = v) } }

            // ---------- Updates ----------
            Group("Updates")
            UpdateSection(vm, s)

            Group("Über")
            Text("VOID PV Dashboard ${BuildConfig.VERSION_NAME}", color = c.text, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(4.dp))
            Text(
                "Design nach VOID Files. Schriften: Doto, Space Mono & Space Grotesk (SIL Open Font License). Wetter: Open-Meteo.com (CC BY 4.0). Referenzertrag: PVGIS © Europäische Union. " +
                    "SENEC-Zugriff nach dem Vorbild der Home-Assistant-Integration von marq24.",
                color = c.textMuted, style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, color = VoidTheme.colors.textMuted, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun UpdateSection(vm: MainViewModel, s: AppSettings) {
    val c = VoidTheme.colors
    LaunchedEffect(Unit) { if (vm.releases.isEmpty()) vm.checkForUpdates(manual = false) }
    val update = vm.availableUpdate
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Installiert: ${BuildConfig.VERSION_NAME}", color = c.text, style = MaterialTheme.typography.bodyLarge)
            vm.updateStatus?.let {
                Text(it, color = if (update != null) c.accent else c.textMuted, style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (vm.checkingUpdate) CircularProgressIndicator(Modifier.size(22.dp), color = c.accent, strokeWidth = 2.dp)
    }
    Spacer(Modifier.height(12.dp))
    val progress = vm.downloadProgress
    if (progress != null) {
        val (done, total) = progress
        val f = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
        DotBar(f, Modifier.fillMaxWidth().height(10.dp), dots = 32)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Label("${done / 1024 / 1024} / ${total / 1024 / 1024} MB", Modifier.weight(1f))
            TextButton(onClick = { vm.cancelDownload() }) { Text("ABBRECHEN", style = MaterialTheme.typography.labelLarge, color = c.accent) }
        }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (update != null) Pill("Auf ${update.version} aktualisieren", selected = true, onClick = { vm.downloadUpdate(update) })
            Pill("Nach Updates suchen", selected = update == null, onClick = { vm.checkForUpdates(manual = true) })
        }
    }
    Spacer(Modifier.height(8.dp))
    Toggle("Beim Start prüfen", "Beim Öffnen der App nach neuen Versionen suchen", s.autoUpdateCheck) { v ->
        vm.updateSettings { copy(autoUpdateCheck = v) }
    }

    Group("Changelog")
    if (vm.releases.isEmpty()) {
        Text(
            if (vm.checkingUpdate) "Wird geladen …" else "Changelog nicht verfügbar – Internetverbindung prüfen.",
            color = c.textMuted, style = MaterialTheme.typography.bodyMedium,
        )
    }
    var expanded by remember { mutableStateOf(false) }
    val shown = if (expanded) vm.releases else vm.releases.take(5)
    shown.forEach { r ->
        val installed = r.version == BuildConfig.VERSION_NAME
        val newer = Updater.isNewer(r.version, BuildConfig.VERSION_NAME)
        Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(r.version, style = headingStyle(22), color = c.text)
                Spacer(Modifier.width(10.dp))
                Label(r.publishedAt.take(10).split('-').reversed().joinToString("."), Modifier.weight(1f))
                when {
                    installed -> Label("Installiert", color = c.text)
                    newer -> Label("Neu", color = c.accent)
                }
            }
            if (r.notes.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(r.notes, color = c.textMuted, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
    if (vm.releases.size > 5) {
        TextButton(onClick = { expanded = !expanded }) {
            Text(if (expanded) "WENIGER" else "ALLE ${vm.releases.size} VERSIONEN", style = MaterialTheme.typography.labelLarge, color = c.accent)
        }
    }
}

@Composable
private fun ThemeTile(mode: ThemeMode, selected: Boolean, onClick: () -> Unit) {
    val c = VoidTheme.colors
    val swatch = themeSwatches[mode]
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(64.dp)) {
        Box(
            Modifier.size(56.dp).clip(RoundedCornerShape(18.dp))
                .border(if (selected) 2.dp else 1.dp, if (selected) c.accent else c.divider, RoundedCornerShape(18.dp))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            if (swatch != null) {
                Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(swatch).border(1.dp, c.divider, RoundedCornerShape(12.dp)))
            } else {
                Row(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp))) {
                    Box(Modifier.weight(1f).height(40.dp).background(Color.Black))
                    Box(Modifier.weight(1f).height(40.dp).background(if (mode == ThemeMode.DYNAMIC) Color(0xFFB4C8FF) else Color(0xFFEFEEEA)))
                }
            }
            if (selected) Box(Modifier.size(8.dp).clip(CircleShape).background(c.accent))
        }
        Spacer(Modifier.height(4.dp))
        Label(mode.label, color = if (selected) c.text else c.textMuted)
    }
}
