package de.gun642.pvdashboard.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import de.gun642.pvdashboard.stats.EnergySeries
import de.gun642.pvdashboard.stats.YearCompare
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
private fun seriesColor(series: EnergySeries): Color = when (series) {
    EnergySeries.CONSUMPTION -> EnergyColors.house
    EnergySeries.GRID_IMPORT -> EnergyColors.gridImport
    EnergySeries.GRID_EXPORT -> EnergyColors.gridExport
    EnergySeries.BATTERY_CHARGE -> EnergyColors.battery
    EnergySeries.BATTERY_DISCHARGE -> EnergyColors.batteryDischarge
    EnergySeries.WALLBOX -> EnergyColors.wallbox
}

@Composable
private fun StatsContent(vm: MainViewModel, r: StatsResult, onSettings: () -> Unit) {
    val c = VoidTheme.colors
    val t = r.totals
    val pvgis = vm.pvgis
    // Vorjahr statt PVGIS, sobald Vorjahresdaten vorliegen
    val previous = vm.statsCompare?.takeIf { it.period == YearCompare.previousPeriod(r.period) && YearCompare.available(it) }
    val shown = EnergySeries.entries.filter { it in vm.chartSeries }

    Tile(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dot(EnergyColors.pv)
            Spacer(Modifier.size(8.dp))
            Label("PV-Erzeugung")
        }
        val kwh = formatKwh(t.pv).split(' ')
        BigValue(kwh[0], kwh.getOrElse(1) { "kWh" }, size = 56)
        val running = r.period.end.isAfter(java.time.LocalDate.now())
        val (refLabel, refValue) = when {
            previous != null && YearCompare.complete(previous) ->
                (if (running) "Vorjahr bis jetzt " else "Vorjahr ") to YearCompare.toDate(r.period, previous.buckets)
            pvgis != null ->
                (if (r.period.type == PeriodType.DAY) "PVGIS-Tagesmittel " else "PVGIS-Soll bis jetzt ") to pvgis.target(r.period, r.dataStart)
            else -> "" to 0.0
        }
        if (refValue > 0) {
            val ratio = t.pv / refValue
            Row(verticalAlignment = Alignment.CenterVertically) {
                Label(refLabel + formatKwh(refValue), Modifier.weight(1f))
                Label(
                    String.format(Locale.GERMANY, "%.0f %%", ratio * 100),
                    color = if (ratio >= 1) EnergyColors.gridExport else c.text,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        val targets = if (previous != null) YearCompare.bucketValues(r.buckets, previous.buckets) else pvgis?.bucketTargets(r.period, r.buckets)
        val targetName = when {
            previous != null -> "Vorjahr (${previous.period.label})"
            else -> "PVGIS-Soll"
        }
        BarChart(
            targets = targets,
            labels = r.buckets.map { it.label },
            series = listOf(EnergyColors.pv to r.buckets.map { it.totals.pv }) +
                shown.map { s -> seriesColor(s) to r.buckets.map { s.value(it.totals) } },
            labelEvery = when (r.period.type) {
                PeriodType.DAY -> 3
                PeriodType.MONTH -> 5
                else -> 1
            },
            seriesNames = listOf("Erzeugung") + shown.map { it.label },
            targetName = targetName,
            unit = "kWh",
            // Mindest-Skala je Zeitraum: kleine Werte bleiben klein (kein aufgeblähtes Messrauschen)
            minScale = when (r.period.type) {
                // Tag: fest 0–10 (kWh je Stunde = mittlere Leistung in kW), bei mehr automatisch erweitert
                PeriodType.DAY -> 10.0
                PeriodType.MONTH -> 5.0
                PeriodType.YEAR -> 50.0
                PeriodType.TOTAL -> 500.0
            },
            detailLabels = r.buckets.map { b -> bucketTitle(r.period, b.index) },
            line = if (vm.showAutarky) r.buckets.map { it.totals.autarky } else null,
            lineColor = EnergyColors.autarky,
            lineName = "Autarkie",
        )
        Spacer(Modifier.height(8.dp))
        Legend(
            listOf("Erzeugung" to EnergyColors.pv) +
                shown.map { it.label to seriesColor(it) } +
                (if (vm.showAutarky) listOf("Autarkie" to EnergyColors.autarky) else emptyList()) +
                when {
                    previous != null -> listOf("Vorjahr" to c.textMuted)
                    pvgis != null && r.period.type != PeriodType.DAY -> listOf("PVGIS" to c.textMuted)
                    else -> emptyList()
                }
        )
    }

    Tile(Modifier.fillMaxWidth()) {
        Label("Energie")
        Spacer(Modifier.height(2.dp))
        Label("Antippen: im Diagramm ein-/ausblenden")
        Spacer(Modifier.height(6.dp))
        EnergySeries.entries.forEach { s ->
            ToggleRow(s.label, formatKwh(s.value(t)), seriesColor(s), s in vm.chartSeries) { vm.toggleChartSeries(s) }
        }
        Hairline()
        ToggleRow("Autarkie", formatPercent(t.autarky), EnergyColors.autarky, vm.showAutarky, emphasize = true) { vm.toggleAutarky() }
        ValueRow("Eigenverbrauch", formatPercent(t.selfConsumption), emphasize = true)
    }

    CostTile(vm.tariff, CostSummary.of(t, vm.tariff, r.billingMonths), r, onSettings)

    TextButton(
        onClick = { vm.showRaw("Statistik-Rohdaten", r.rawJson) },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("ROHDATEN", style = MaterialTheme.typography.labelLarge, color = c.accent) }
}

/** Zeile der Energie-Kachel: gefüllter Punkt = im Diagramm sichtbar, Ring = ausgeblendet. */
@Composable
private fun ToggleRow(label: String, value: String, color: Color, selected: Boolean, emphasize: Boolean = false, onClick: () -> Unit) {
    val c = VoidTheme.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(10.dp).clip(CircleShape)
                .then(if (selected) Modifier.background(color) else Modifier.border(1.5.dp, color, CircleShape)),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            label,
            color = if (selected || emphasize) c.text else c.textMuted,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            color = c.text,
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = if (emphasize) FontWeight.Bold else FontWeight.Medium),
        )
    }
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

/** Überschrift der Detailanzeige für einen Balken. */
private fun bucketTitle(period: Period, index: Int): String = when (period.type) {
    PeriodType.DAY -> String.format(Locale.GERMANY, "%02d:00–%02d:00 Uhr", index, (index + 1) % 24)
    PeriodType.MONTH -> period.start.withDayOfMonth(index)
        .format(java.time.format.DateTimeFormatter.ofPattern("EEEE, d. MMMM", Locale.GERMANY))
    PeriodType.YEAR -> java.time.YearMonth.of(period.start.year, index)
        .format(java.time.format.DateTimeFormatter.ofPattern("MMMM yyyy", Locale.GERMANY))
    PeriodType.TOTAL -> index.toString()
}
