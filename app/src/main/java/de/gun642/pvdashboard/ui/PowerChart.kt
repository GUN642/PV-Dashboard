package de.gun642.pvdashboard.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import de.gun642.pvdashboard.senec.SenecSnapshot

private class Series(val label: String, val color: Color, val value: (SenecSnapshot) -> Double?)

private val chartSeries = listOf(
    Series("PV", EnergyColors.pv) { it.pvW },
    Series("Haus", EnergyColors.house) { it.houseW },
    Series("Netz", EnergyColors.gridImport) { it.gridW },
    Series("Wallbox", EnergyColors.wallbox) { it.wallboxW },
)

/** Linienverlauf der letzten Minuten (seit die App geöffnet ist). */
@Composable
fun ChartCard(history: List<SenecSnapshot>) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Verlauf (letzte 30 min, solange die App offen ist)", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(12.dp))
            if (history.size < 2) {
                Text(
                    "Wird aufgebaut …",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                PowerChart(history)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    chartSeries.forEach { LegendItem(it.label, it.color) }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatTime(history.first().timestamp), style = MaterialTheme.typography.bodySmall)
                    Text(formatTime(history.last().timestamp), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun PowerChart(history: List<SenecSnapshot>) {
    val axisColor = MaterialTheme.colorScheme.outlineVariant
    val values = history.flatMap { s -> chartSeries.mapNotNull { it.value(s) } }
    val maxY = maxOf(values.maxOrNull() ?: 0.0, 500.0)
    val minY = minOf(values.minOrNull() ?: 0.0, 0.0)
    val start = history.first().timestamp
    val span = (history.last().timestamp - start).coerceAtLeast(1L).toFloat()

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(180.dp)
    ) {
        fun y(v: Double) = (size.height * (1 - (v - minY) / (maxY - minY))).toFloat()
        fun x(t: Long) = size.width * (t - start) / span

        // Nulllinie
        drawLine(axisColor, Offset(0f, y(0.0)), Offset(size.width, y(0.0)), strokeWidth = 2f)

        chartSeries.forEach { series ->
            val path = Path()
            var started = false
            history.forEach { s ->
                val v = series.value(s)
                if (v == null) {
                    started = false
                } else if (!started) {
                    path.moveTo(x(s.timestamp), y(v))
                    started = true
                } else {
                    path.lineTo(x(s.timestamp), y(v))
                }
            }
            drawPath(path, series.color, style = Stroke(width = 4f))
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("max ${formatPower(maxY)}", style = MaterialTheme.typography.bodySmall)
        if (minY < 0) Text("min ${formatPower(minY)}", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun LegendItem(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Spacer(
            Modifier
                .size(8.dp)
                .background(color, CircleShape)
        )
        Spacer(Modifier.size(4.dp))
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}
