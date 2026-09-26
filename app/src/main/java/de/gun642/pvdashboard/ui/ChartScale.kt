package de.gun642.pvdashboard.ui

import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/** Achsenskala mit „runden“ Schritten (1, 2, 2,5, 5 × 10ⁿ). */
data class ChartScale(val max: Double, val step: Double) {
    val ticks: List<Double> get() = generateSequence(0.0) { it + step }.takeWhile { it <= max + step / 1000 }.toList()

    /** Beschriftung passend zur Schrittweite. */
    fun label(v: Double): String = when {
        step >= 1 && step % 1.0 == 0.0 -> String.format(Locale.GERMANY, "%.0f", v)
        step >= 0.1 -> String.format(Locale.GERMANY, "%.1f", v)
        else -> String.format(Locale.GERMANY, "%.2f", v)
    }

    companion object {
        /**
         * [minMax] verhindert, dass winzige Werte (Messrauschen) die volle Höhe füllen:
         * die Skala reicht immer mindestens bis [minMax].
         */
        fun of(maxValue: Double, minMax: Double, ticks: Int = 4): ChartScale {
            val target = maxOf(maxValue, minMax, 1e-9)
            val raw = target / ticks
            val magnitude = 10.0.pow(floor(log10(raw)))
            val step = listOf(1.0, 2.0, 2.5, 5.0, 10.0).map { it * magnitude }.first { it >= raw - 1e-12 }
            return ChartScale(ceil(target / step - 1e-9) * step, step)
        }
    }
}

/** Werte kompakt formatieren (für Tooltips). */
fun formatChartValue(v: Double, unit: String): String {
    val text = when {
        v == 0.0 -> "0"
        kotlin.math.abs(v) < 1 -> String.format(Locale.GERMANY, "%.2f", v)
        kotlin.math.abs(v) < 100 -> String.format(Locale.GERMANY, "%.1f", v)
        else -> String.format(Locale.GERMANY, "%.0f", v)
    }
    return if (unit.isBlank()) text else "$text $unit"
}
