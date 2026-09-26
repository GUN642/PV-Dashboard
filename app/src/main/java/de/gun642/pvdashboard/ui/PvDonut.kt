package de.gun642.pvdashboard.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import de.gun642.pvdashboard.ui.theme.EnergyColors
import de.gun642.pvdashboard.ui.theme.VoidTheme
import de.gun642.pvdashboard.ui.theme.valueStyle
import de.gun642.pvdashboard.widget.PvShares

/** Ein Anteil der PV-Erzeugung mit Farbe und Bezeichnung. */
private data class Slice(val label: String, val fraction: Float, val color: Color)

@Composable
private fun slices(shares: PvShares?): List<Slice> {
    val house by animateShare(shares?.house)
    val battery by animateShare(shares?.battery)
    val wallbox by animateShare(shares?.wallbox)
    val export by animateShare(shares?.export)
    return listOf(
        Slice("Haus", house, EnergyColors.house),
        Slice("Akku", battery, EnergyColors.battery),
        Slice("Wallbox", wallbox, EnergyColors.wallbox),
        Slice("Einspeisung", export, EnergyColors.gridExport),
    )
}

@Composable
private fun animateShare(v: Double?) = animateFloatAsState((v ?: 0.0).toFloat(), tween(600), label = "share")

/**
 * Ringdiagramm: wohin die PV-Erzeugung gerade fließt. In der Mitte der größte Anteil.
 * Ohne nennenswerte PV (Nacht) ein leerer Ring mit „Keine PV“.
 */
@Composable
fun PvDonut(shares: PvShares?, modifier: Modifier = Modifier) {
    val c = VoidTheme.colors
    val parts = slices(shares)
    val top = parts.maxBy { it.fraction }
    val houseColor = EnergyColors.house
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = size.minDimension * 0.12f
            val d = size.minDimension - stroke
            val topLeft = Offset((size.width - d) / 2, (size.height - d) / 2)
            val arc = Size(d, d)
            drawArc(c.textMuted.copy(alpha = 0.18f), 0f, 360f, false, topLeft, arc, style = Stroke(stroke))
            if (shares == null) return@Canvas
            val visible = parts.filter { it.fraction * 360f >= 1f }
            val gap = if (visible.size > 1) 4f else 0f
            var start = -90f
            visible.forEach { p ->
                val sweep = p.fraction * 360f
                drawArc(p.color, start + gap / 2, (sweep - gap).coerceAtLeast(0.5f), false, topLeft, arc, style = Stroke(stroke))
                start += sweep
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (shares == null) {
                Text("–", style = valueStyle(22), color = c.textMuted)
                Label("Keine PV")
            } else {
                Text(formatPercent(top.fraction.toDouble()), style = valueStyle(22), color = c.text)
                Label(top.label, color = if (top.color == houseColor) c.textMuted else top.color)
            }
        }
    }
}

/** Legende zum Ring: 2×2 mit Farbpunkt, Bezeichnung und Anteil. */
@Composable
fun PvShareLegend(shares: PvShares) {
    val c = VoidTheme.colors
    val parts = slices(shares)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        parts.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                row.forEach { p ->
                    val active = p.fraction >= 0.005f
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Dot(if (active) p.color else c.textMuted.copy(alpha = 0.3f), size = 7.dp)
                        Spacer(Modifier.width(8.dp))
                        Label(p.label, modifier = Modifier.weight(1f))
                        Text(
                            formatPercent(p.fraction.toDouble()),
                            style = MaterialTheme.typography.labelLarge,
                            color = if (active) c.text else c.textMuted,
                        )
                    }
                }
            }
        }
    }
}
