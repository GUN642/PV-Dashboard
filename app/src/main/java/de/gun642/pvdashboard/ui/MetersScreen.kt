package de.gun642.pvdashboard.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.gun642.pvdashboard.MainViewModel
import de.gun642.pvdashboard.data.AppSettings
import de.gun642.pvdashboard.meters.Consumption
import de.gun642.pvdashboard.meters.MeterCsv
import de.gun642.pvdashboard.meters.MeterReading
import de.gun642.pvdashboard.meters.MeterType
import de.gun642.pvdashboard.ui.theme.EnergyColors
import de.gun642.pvdashboard.ui.theme.VoidTheme
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val dateFormat = DateTimeFormatter.ofPattern("dd.MM.yyyy")
private val de = Locale.GERMANY

@Composable
private fun meterColor(type: MeterType): Color = when (type) {
    MeterType.POWER -> EnergyColors.pv
    MeterType.WATER -> EnergyColors.battery
}

private fun formatAmount(v: Double, type: MeterType): String = when (type) {
    MeterType.POWER -> String.format(de, if (v >= 100) "%.0f" else "%.1f", v)
    MeterType.WATER -> String.format(de, if (v >= 100) "%.1f" else "%.2f", v)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MetersScreen(vm: MainViewModel, settings: AppSettings, onSettings: () -> Unit) {
    val c = VoidTheme.colors
    val type = vm.meterType
    val readings = vm.readings(type)
    var showAdd by remember { mutableStateOf(false) }
    var deleteReading by remember { mutableStateOf<MeterReading?>(null) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importMeterCsv(type, uri)
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        TabHeader("Zähler") {
            IconButton(onClick = { showAdd = true }) { Icon(Icons.Filled.Add, "Zählerstand eintragen", tint = c.text) }
        }
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MeterType.entries.forEach { t -> Pill(t.label, type == t, { vm.meterType = t }) }
            }

            if (readings.isEmpty()) {
                Tile(Modifier.fillMaxWidth()) {
                    Label("Noch keine Zählerstände")
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Trage oben mit + deinen aktuellen Zählerstand ein oder importiere den CSV-Export deiner bisherigen App.",
                        color = c.text, style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else {
                CurrentTile(vm, type, readings, settings)
                ChartTile(vm, type, readings, settings, onSettings)
                ReadingsTile(type, readings) { deleteReading = it }
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("+ Zählerstand", selected = true, onClick = { showAdd = true })
                Pill("CSV importieren", selected = false, onClick = { importer.launch(arrayOf("*/*")) })
                if (readings.isNotEmpty()) Pill("CSV exportieren", selected = false, onClick = { vm.exportMeterCsv(type) })
            }
            Text(
                "Import im Format der bisherigen App: Datum;Zählerstand;an den Anbieter gemeldet;Kommentar",
                color = c.textMuted, style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showAdd) {
        AddReadingDialog(type, readings.lastOrNull(), onDismiss = { showAdd = false }) {
            showAdd = false
            vm.addReading(type, it)
        }
    }
    deleteReading?.let { r ->
        AlertDialog(
            onDismissRequest = { deleteReading = null },
            containerColor = c.surface,
            title = { Text("Zählerstand löschen", color = c.text) },
            text = { Text("${r.date.format(dateFormat)}: ${MeterCsv.formatValue(r.value)} ${type.unit}", color = c.textMuted) },
            confirmButton = {
                TextButton(onClick = { deleteReading = null; vm.deleteReading(type, r) }) {
                    Text("LÖSCHEN", style = MaterialTheme.typography.labelLarge, color = c.accent)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteReading = null }) {
                    Text("ABBRECHEN", style = MaterialTheme.typography.labelLarge, color = c.textMuted)
                }
            },
        )
    }
}

/** Kosten für eine Menge über [months] Monate. null = kein Tarif hinterlegt. */
private fun cost(vm: MainViewModel, type: MeterType, settings: AppSettings, amount: Double, months: Double): Double? = when (type) {
    MeterType.POWER -> if (settings.pricePerKwhCent > 0) amount * settings.pricePerKwhCent / 100 + settings.baseFeePerMonth * months else null
    MeterType.WATER -> vm.waterTariff.takeIf { it.configured }?.cost(amount, months)
}

@Composable
private fun CurrentTile(vm: MainViewModel, type: MeterType, readings: List<MeterReading>, settings: AppSettings) {
    val c = VoidTheme.colors
    val last = readings.last()
    Tile(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dot(meterColor(type))
            Spacer(Modifier.width(8.dp))
            Label("Zählerstand ${type.label}", Modifier.weight(1f))
            Label(last.date.format(dateFormat) + if (last.reported) " · gemeldet" else "")
        }
        BigValue(MeterCsv.formatValue(last.value), type.unit, size = 52)
        Spacer(Modifier.height(8.dp))
        Consumption.dailyAverage(readings, days = 90)?.let {
            ValueRow("Ø pro Tag (90 Tage)", "${formatAmount(it, type)} ${type.unit}")
        }
        Consumption.yearlyProjection(readings)?.let { year ->
            ValueRow("Hochrechnung pro Jahr", "${formatAmount(year, type)} ${type.unit}", emphasize = true)
            cost(vm, type, settings, year, 12.0)?.let { ValueRow("Kosten pro Jahr (hochgerechnet)", formatEuro(it), emphasize = true) }
        }
        val days = java.time.temporal.ChronoUnit.DAYS.between(last.date, LocalDate.now())
        if (days > 30) {
            Spacer(Modifier.height(6.dp))
            Label("Letzte Ablesung vor $days Tagen", color = c.accent)
        }
    }
}

@Composable
private fun ChartTile(vm: MainViewModel, type: MeterType, readings: List<MeterReading>, settings: AppSettings, onSettings: () -> Unit) {
    val c = VoidTheme.colors
    val monthly = remember(readings) { Consumption.monthly(readings) }
    val color = meterColor(type)
    Tile(Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("Monat", !vm.meterYearly, { vm.meterYearly = false })
            Pill("Jahr", vm.meterYearly, { vm.meterYearly = true })
        }
        Spacer(Modifier.height(10.dp))

        val total: Double
        val months: Double
        if (!vm.meterYearly) {
            val year = vm.meterYear
            val years = monthly.keys.map { it.year }.toSortedSet()
            Row(verticalAlignment = Alignment.CenterVertically) {
                val hasPrev = years.any { it < year }
                val hasNext = years.any { it > year }
                IconButton(onClick = { vm.meterYear = year - 1 }, enabled = hasPrev) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Vorjahr", tint = if (hasPrev) c.text else c.divider)
                }
                Text(year.toString(), style = MaterialTheme.typography.titleLarge, color = c.text, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                IconButton(onClick = { vm.meterYear = year + 1 }, enabled = hasNext) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Folgejahr", tint = if (hasNext) c.text else c.divider)
                }
            }
            val values = (1..12).map { monthly[YearMonth.of(year, it)] ?: 0.0 }
            val previous = (1..12).map { monthly[YearMonth.of(year - 1, it)] ?: 0.0 }
            total = values.sum()
            months = values.count { it > 0 }.toDouble()
            BigValue(formatAmount(total, type), type.unit, size = 44)
            Spacer(Modifier.height(10.dp))
            BarChart(
                labels = (1..12).map { java.time.Month.of(it).getDisplayName(TextStyle.SHORT, de).take(3) },
                series = listOf(color to values),
                targets = previous.takeIf { p -> p.any { it > 0 } },
            )
            Spacer(Modifier.height(8.dp))
            Legend(listOf(year.toString() to color) + if (previous.any { it > 0 }) listOf("${year - 1}" to c.textMuted) else emptyList())
        } else {
            val yearly = Consumption.yearly(readings)
            total = yearly.values.sum()
            months = monthly.values.count { it > 0 }.toDouble()
            Label("Alle Jahre")
            BigValue(formatAmount(total, type), type.unit, size = 44)
            Spacer(Modifier.height(10.dp))
            BarChart(labels = yearly.keys.map { it.toString() }, series = listOf(color to yearly.values.toList()))
            Spacer(Modifier.height(8.dp))
            yearly.forEach { (y, v) ->
                val yearMonths = monthly.filterKeys { it.year == y }.values.count { it > 0 }.toDouble()
                val euro = cost(vm, type, settings, v, yearMonths)
                ValueRow("$y", "${formatAmount(v, type)} ${type.unit}" + (euro?.let { " · ${formatEuro(it)}" } ?: ""))
            }
        }

        // Kosten
        Hairline()
        val euro = cost(vm, type, settings, total, months)
        if (euro != null) {
            ValueRow("Kosten", formatEuro(euro), emphasize = true)
            val detail = when (type) {
                MeterType.POWER -> String.format(de, "%.2f ct/kWh · %.2f €/Monat Grundgebühr", settings.pricePerKwhCent, settings.baseFeePerMonth)
                MeterType.WATER -> vm.waterTariff.let {
                    String.format(de, "%.2f + %.2f €/m³ Abwasser · %.2f €/Monat", it.pricePerM3, it.wastewaterPerM3, it.baseFeePerMonth)
                }
            }
            Label(detail + String.format(de, " · %.0f Monate", months))
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (type == MeterType.POWER) "Für Kosten den Stromtarif in den Einstellungen eintragen." else "Für Kosten den Wassertarif in den Einstellungen eintragen.",
                    color = c.textMuted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onSettings) { Text("TARIF", style = MaterialTheme.typography.labelLarge, color = c.accent) }
            }
        }
    }
}

@Composable
private fun ReadingsTile(type: MeterType, readings: List<MeterReading>, onDelete: (MeterReading) -> Unit) {
    val c = VoidTheme.colors
    var expanded by rememberSaveable { mutableStateOf(false) }
    val sorted = readings.sortedByDescending { it.date }
    val shown = if (expanded) sorted else sorted.take(6)
    Tile(Modifier.fillMaxWidth()) {
        Label("Zählerstände (${readings.size})")
        Spacer(Modifier.height(4.dp))
        shown.forEach { r ->
            val previous = sorted.firstOrNull { it.date < r.date }
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(r.date.format(dateFormat), color = c.text, style = MaterialTheme.typography.bodyLarge)
                        if (r.reported) {
                            Spacer(Modifier.width(8.dp))
                            Label("gemeldet", color = EnergyColors.gridExport)
                        }
                    }
                    val delta = previous?.let { r.value - it.value }
                    Label(
                        listOfNotNull(
                            delta?.let { "+" + formatAmount(it, type) + " " + type.unit },
                            r.comment.takeIf { it.isNotBlank() },
                        ).joinToString(" · ").ifBlank { "Erste Ablesung" }
                    )
                }
                Text("${MeterCsv.formatValue(r.value)} ${type.unit}", color = c.text, style = MaterialTheme.typography.bodyLarge)
                IconButton(onClick = { onDelete(r) }) { Icon(Icons.Filled.Delete, "Löschen", tint = c.textMuted, modifier = Modifier.size(20.dp)) }
            }
        }
        if (readings.size > 6) {
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "WENIGER" else "ALLE ${readings.size} ANZEIGEN", style = MaterialTheme.typography.labelLarge, color = c.accent)
            }
        }
    }
}

@Composable
private fun AddReadingDialog(type: MeterType, last: MeterReading?, onDismiss: () -> Unit, onSave: (MeterReading) -> Unit) {
    val c = VoidTheme.colors
    var dateText by rememberSaveable { mutableStateOf(LocalDate.now().format(dateFormat)) }
    var valueText by rememberSaveable { mutableStateOf("") }
    var reported by rememberSaveable { mutableStateOf(false) }
    var comment by rememberSaveable { mutableStateOf("") }

    val date = runCatching { LocalDate.parse(dateText.trim(), dateFormat) }.getOrNull()
    val value = MeterCsv.parseNumber(valueText)
    val warning = when {
        date == null -> "Datum im Format TT.MM.JJJJ"
        date.isAfter(LocalDate.now()) -> "Datum liegt in der Zukunft"
        value == null -> null
        last != null && !date.isBefore(last.date) && value < last.value -> "Kleiner als der letzte Stand (${MeterCsv.formatValue(last.value)}) – Zählerwechsel?"
        else -> null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        title = { Text("Zählerstand ${type.label}", color = c.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                last?.let { Label("Zuletzt: ${MeterCsv.formatValue(it.value)} ${type.unit} am ${it.date.format(dateFormat)}") }
                VoidTextField(dateText, { dateText = it }, "Datum", keyboardType = KeyboardType.Number)
                VoidTextField(valueText, { valueText = it.filter { ch -> ch.isDigit() || ch == ',' || ch == '.' } }, "Zählerstand", keyboardType = KeyboardType.Decimal, suffix = type.unit)
                Toggle("An den Anbieter gemeldet", null, reported) { reported = it }
                VoidTextField(comment, { comment = it }, "Kommentar (optional)")
                warning?.let { Text(it, color = c.accent, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = date != null && value != null && !date.isAfter(LocalDate.now()),
                onClick = { if (date != null && value != null) onSave(MeterReading(date, value, reported, comment.trim())) },
            ) { Text("SPEICHERN", style = MaterialTheme.typography.labelLarge, color = c.accent) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("ABBRECHEN", style = MaterialTheme.typography.labelLarge, color = c.textMuted) }
        },
    )
}
