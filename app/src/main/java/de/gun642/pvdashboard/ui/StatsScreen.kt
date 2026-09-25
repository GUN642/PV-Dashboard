package de.gun642.pvdashboard.ui

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.gun642.pvdashboard.MainViewModel
import de.gun642.pvdashboard.data.AppSettings
import de.gun642.pvdashboard.stats.CostSummary
import de.gun642.pvdashboard.stats.Period
import de.gun642.pvdashboard.stats.PeriodType
import de.gun642.pvdashboard.stats.StatsResult
import de.gun642.pvdashboard.stats.Tariff
import de.gun642.pvdashboard.ui.theme.EnergyColors
import de.gun642.pvdashboard.ui.theme.VoidTheme
import de.gun642.pvdashboard.ui.theme.valueStyle
import java.util.Locale

@Composable
fun StatsScreen(vm: MainViewModel, settings: AppSettings, onSettings: () -> Unit) {
    val c = VoidTheme.colors
    val period = vm.period
    LaunchedEffect(settings.hasCloud) { if (vm.statsResult == null) vm.loadStats() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        TabHeader("Statistik") {
            if (vm.statsLoading) {
                CircularProgressIndicator(Modifier.padding(12.dp).size(22.dp), color = c.accent, strokeWidth = 2.dp)
            } else {
                IconButton(onClick = { vm.loadStats(force = true) }) { Icon(Icons.Filled.Refresh, "Aktualisieren", tint = c.text) }
            }
        }
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PeriodType.entries.forEach { type ->
                    Pill(type.label, selected = period.type == type, onClick = { vm.selectPeriod(Period.today(type)) })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.selectPeriod(period.previous()) }, enabled = period.type != PeriodType.TOTAL) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Zurück", tint = if (period.type != PeriodType.TOTAL) c.text else c.divider)
                }
                Text(
                    period.label,
                    style = MaterialTheme.typography.titleLarge,
                    color = c.text,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { vm.selectPeriod(period.next()) }, enabled = period.hasNext()) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Weiter", tint = if (period.hasNext()) c.text else c.divider)
                }
            }

            vm.statsError?.let { msg ->
                Tile(Modifier.fillMaxWidth(), onClick = if (!settings.hasCloud) onSettings else null) {
                    Label("Hinweis", color = c.accent)
                    Spacer(Modifier.height(6.dp))
                    Text(msg, color = c.text, style = MaterialTheme.typography.bodyMedium)
                }
            }

            val result = vm.statsResult
            if (result != null && result.period == period) {
                StatsContent(vm, result, onSettings)
            } else if (vm.statsLoading) {
                Tile(Modifier.fillMaxWidth()) {
                    Label("Lade Daten …")
                    Spacer(Modifier.height(120.dp))
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun StatsContent(vm: MainViewModel, r: StatsResult, onSettings: () -> Unit) {
    val c = VoidTheme.colors
    val t = r.totals

    Tile(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dot(EnergyColors.pv)
            Spacer(Modifier.size(8.dp))
            Label("PV-Erzeugung")
        }
        val kwh = formatKwh(t.pv).split(' ')
        BigValue(kwh[0], kwh.getOrElse(1) { "kWh" }, size = 56)
        val pvgis = vm.pvgis
        if (pvgis != null) {
            val target = pvgis.target(r.period, r.dataStart)
            if (target > 0) {
                val ratio = t.pv / target
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Label(
                        (if (r.period.type == PeriodType.DAY) "PVGIS-Tagesmittel " else "PVGIS-Soll bis jetzt ") + formatKwh(target),
                        Modifier.weight(1f),
                    )
                    Label(
                        String.format(Locale.GERMANY, "%.0f %%", ratio * 100),
                        color = if (ratio >= 1) EnergyColors.gridExport else c.text,
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        BarChart(
            targets = pvgis?.bucketTargets(r.period, r.buckets),
            labels = r.buckets.map { it.label },
            series = listOf(
                EnergyColors.pv to r.buckets.map { it.totals.pv },
                EnergyColors.house to r.buckets.map { it.totals.consumption },
            ),
            labelEvery = when (r.period.type) {
                PeriodType.DAY -> 3
                PeriodType.MONTH -> 5
                else -> 1
            },
        )
        Spacer(Modifier.height(8.dp))
        Legend(
            listOf("Erzeugung" to EnergyColors.pv, "Verbrauch" to EnergyColors.house) +
                if (pvgis != null && r.period.type != PeriodType.DAY) listOf("PVGIS" to c.textMuted) else emptyList()
        )
    }

    Tile(Modifier.fillMaxWidth()) {
        Label("Energie")
        Spacer(Modifier.height(6.dp))
        ValueRow("Verbrauch", formatKwh(t.consumption), EnergyColors.house)
        ValueRow("Netzbezug", formatKwh(t.gridImport), EnergyColors.gridImport)
        ValueRow("Einspeisung", formatKwh(t.gridExport), EnergyColors.gridExport)
        ValueRow("Speicher geladen", formatKwh(t.batteryCharge), EnergyColors.battery)
        ValueRow("Speicher entladen", formatKwh(t.batteryDischarge), EnergyColors.battery)
        ValueRow("Wallbox", formatKwh(t.wallbox), EnergyColors.wallbox)
        Hairline()
        ValueRow("Autarkie", formatPercent(t.autarky), emphasize = true)
        ValueRow("Eigenverbrauch", formatPercent(t.selfConsumption), emphasize = true)
    }

    CostTile(vm.tariff, CostSummary.of(t, vm.tariff, r.billingMonths), r, onSettings)

    TextButton(
        onClick = { vm.showRaw("Statistik-Rohdaten", r.rawJson) },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("ROHDATEN", style = MaterialTheme.typography.labelLarge, color = c.accent) }
}

@Composable
private fun CostTile(tariff: Tariff, cost: CostSummary, r: StatsResult, onSettings: () -> Unit) {
    val c = VoidTheme.colors
    Tile(Modifier.fillMaxWidth(), onClick = if (tariff.pricePerKwhCent <= 0) onSettings else null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Label("Kosten", Modifier.weight(1f))
            if (tariff.provider.isNotBlank()) Label(tariff.provider)
        }
        Spacer(Modifier.height(6.dp))
        if (tariff.pricePerKwhCent <= 0) {
            Text(
                "Trage in den Einstellungen deinen Stromtarif ein (Arbeitspreis, Grundgebühr, Einspeisevergütung), um Kosten und Ersparnis zu sehen.",
                color = c.text, style = MaterialTheme.typography.bodyMedium,
            )
            return@Tile
        }
        val de = Locale.GERMANY
        Text(formatEuro(cost.savings), style = valueStyle(40), color = EnergyColors.gridExport)
        Label("Ersparnis durch PV & Speicher")
        Spacer(Modifier.height(10.dp))
        ValueRow("Netzbezug × ${String.format(de, "%.2f", tariff.pricePerKwhCent)} ct", formatEuro(cost.gridCost))
        ValueRow(
            "Grundgebühr (${String.format(de, "%.1f", r.billingMonths)} Mon.)",
            formatEuro(cost.baseFee),
        )
        if (tariff.feedInCent > 0) {
            ValueRow("Einspeisevergütung × ${String.format(de, "%.2f", tariff.feedInCent)} ct", "– ${formatEuro(cost.feedInRevenue)}")
        }
        Hairline()
        ValueRow("Stromkosten netto", formatEuro(cost.netCost), emphasize = true)
        ValueRow("Ohne PV-Anlage", formatEuro(cost.costWithoutPv))
    }
}
