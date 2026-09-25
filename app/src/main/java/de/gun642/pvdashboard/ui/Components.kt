package de.gun642.pvdashboard.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.gun642.pvdashboard.ui.theme.VoidTheme
import de.gun642.pvdashboard.ui.theme.headingStyle
import java.util.Locale

// ---------- VOID-Grundbausteine ----------

/** Nothing-Punktraster als Hintergrund. */
@Composable
fun DotGrid(modifier: Modifier = Modifier, spacing: Dp = 18.dp, radius: Dp = 1.2.dp) {
    val color = VoidTheme.colors.dot
    Canvas(modifier) {
        val step = spacing.toPx()
        val r = radius.toPx()
        var y = step / 2
        while (y < size.height) {
            var x = step / 2
            while (x < size.width) {
                drawCircle(color, r, Offset(x, y))
                x += step
            }
            y += step
        }
    }
}

/** Segmentierte Punkt-Leiste (Ladestand, Sonnenstunden …). */
@Composable
fun DotBar(fraction: Float, modifier: Modifier = Modifier, dots: Int = 28, color: Color = VoidTheme.colors.accent) {
    val c = VoidTheme.colors
    Canvas(modifier) {
        val gap = size.width / dots
        val r = (gap * 0.32f).coerceAtMost(size.height / 2)
        val filled = (fraction.coerceIn(0f, 1f) * dots + 0.5f).toInt()
        for (i in 0 until dots) {
            drawCircle(
                if (i < filled) color else c.textMuted.copy(alpha = 0.28f),
                r, Offset(gap * i + gap / 2, size.height / 2),
            )
        }
    }
}

/** Kleine Mono-Beschriftung in Großbuchstaben. */
@Composable
fun Label(text: String, modifier: Modifier = Modifier, color: Color = VoidTheme.colors.textMuted) {
    Text(text.uppercase(), modifier = modifier, style = MaterialTheme.typography.labelSmall, color = color)
}

/** Pillen-Schalter. */
@Composable
fun Pill(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = VoidTheme.colors
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) c.text else Color.Transparent)
            .border(1.dp, if (selected) c.text else c.divider, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text.uppercase(),
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold, fontSize = 11.sp),
            color = if (selected) c.background else c.text,
        )
    }
}

/** Farbiger Punkt als Legende / Kennzeichnung. */
@Composable
fun Dot(color: Color, size: Dp = 8.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color))
}

/** Abgerundete Fläche wie die Kacheln in VOID Files. */
@Composable
fun Tile(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val c = VoidTheme.colors
    Column(
        modifier
            .clip(RoundedCornerShape(24.dp))
            .background(c.surface)
            .border(1.dp, c.divider, RoundedCornerShape(24.dp))
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(18.dp),
        content = content,
    )
}

/** Große Überschrift eines Tabs (Dot-Matrix). */
@Composable
fun TabHeader(title: String, action: @Composable () -> Unit = {}) {
    val c = VoidTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 16.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title.uppercase(), style = headingStyle(40), color = c.text, modifier = Modifier.weight(1f))
        action()
    }
}

@Composable
fun ScreenHeader(title: String, onBack: () -> Unit, action: @Composable () -> Unit = {}) {
    val c = VoidTheme.colors
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück", tint = c.text) }
            Spacer(Modifier.weight(1f))
            action()
        }
        Text(title.uppercase(), style = headingStyle(40), color = c.text, modifier = Modifier.padding(horizontal = 20.dp))
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
fun Group(title: String) {
    Spacer(Modifier.height(26.dp))
    Label(title)
    Spacer(Modifier.height(12.dp))
}

@Composable
fun Toggle(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = VoidTheme.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { onChange(!checked) }.padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = c.text, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, color = c.textMuted, style = MaterialTheme.typography.bodyMedium)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = c.onAccent, checkedTrackColor = c.accent,
                uncheckedThumbColor = c.textMuted, uncheckedTrackColor = c.surfaceHigh, uncheckedBorderColor = c.divider,
            ),
        )
    }
}

@Composable
fun VoidTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    suffix: String? = null,
) {
    val c = VoidTheme.colors
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it, color = c.textMuted) } },
        suffix = suffix?.let { { Text(it, color = c.textMuted) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        visualTransformation = visualTransformation,
        shape = RoundedCornerShape(16.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = c.accent, unfocusedBorderColor = c.divider,
            focusedLabelColor = c.accent, unfocusedLabelColor = c.textMuted,
            cursorColor = c.accent, focusedTextColor = c.text, unfocusedTextColor = c.text,
        ),
        modifier = modifier.fillMaxWidth(),
    )
}

/** Zahleneingabe mit deutschem Komma; übernimmt den Wert, sobald er gültig ist. */
@Composable
fun NumberField(value: Double, onValue: (Double) -> Unit, label: String, suffix: String, modifier: Modifier = Modifier) {
    var text by rememberSaveable { mutableStateOf(if (value == 0.0) "" else formatPlain(value)) }
    VoidTextField(
        value = text,
        onValueChange = { input ->
            text = input.filter { it.isDigit() || it == ',' || it == '.' }.take(10)
            val parsed = text.replace(',', '.').toDoubleOrNull()
            if (parsed != null) onValue(parsed) else if (text.isEmpty()) onValue(0.0)
        },
        label = label,
        keyboardType = KeyboardType.Decimal,
        suffix = suffix,
        modifier = modifier,
    )
}

// ---------- Werte-Darstellung ----------

/** Große Zahl in Dot-Matrix mit Einheit. */
@Composable
fun BigValue(value: String, unit: String, color: Color = VoidTheme.colors.text, size: Int = 64) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(value, style = headingStyle(size), color = color)
        Spacer(Modifier.width(8.dp))
        Text(unit, style = MaterialTheme.typography.labelLarge, color = VoidTheme.colors.textMuted, modifier = Modifier.padding(bottom = 10.dp))
    }
}

/** Zeile "Bezeichnung ...... Wert" mit optionalem Farbpunkt. */
@Composable
fun ValueRow(label: String, value: String, dot: Color? = null, emphasize: Boolean = false) {
    val c = VoidTheme.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (dot != null) {
            Dot(dot)
            Spacer(Modifier.width(10.dp))
        }
        Text(label, color = if (emphasize) c.text else c.textMuted, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(
            value,
            color = c.text,
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = if (emphasize) FontWeight.Bold else FontWeight.Medium),
        )
    }
}

@Composable
fun Hairline() {
    Box(Modifier.fillMaxWidth().padding(vertical = 6.dp).height(1.dp).background(VoidTheme.colors.divider))
}

@Composable
fun Legend(items: List<Pair<String, Color>>) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        items.forEach { (label, color) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Dot(color, 7.dp)
                Spacer(Modifier.width(5.dp))
                Label(label)
            }
        }
    }
}

/** Säulendiagramm mit bis zu zwei Reihen nebeneinander je Balken. */
@Composable
fun BarChart(
    labels: List<String>,
    series: List<Pair<Color, List<Double>>>,
    modifier: Modifier = Modifier,
    labelEvery: Int = 1,
) {
    val c = VoidTheme.colors
    val max = series.flatMap { it.second }.maxOrNull()?.takeIf { it > 0 } ?: 1.0
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(160.dp)) {
            val n = labels.size.coerceAtLeast(1)
            val slot = size.width / n
            val groupWidth = slot * 0.72f
            val barWidth = groupWidth / series.size.coerceAtLeast(1)
            val radius = CornerRadius(barWidth / 2.5f, barWidth / 2.5f)
            // Grundlinie
            drawRect(c.divider, Offset(0f, size.height - 1f), Size(size.width, 1f))
            series.forEachIndexed { s, (color, values) ->
                values.forEachIndexed { i, v ->
                    if (v <= 0) return@forEachIndexed
                    val h = (v / max * (size.height - 2)).toFloat().coerceAtLeast(2f)
                    val x = slot * i + (slot - groupWidth) / 2 + barWidth * s
                    drawRoundRect(color, Offset(x, size.height - h), Size(barWidth * 0.85f, h), radius)
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth()) {
            labels.forEachIndexed { i, l ->
                Text(
                    if (i % labelEvery == 0) l else "",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                    color = c.textMuted,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

// ---------- Formatierung ----------

private val de = Locale.GERMANY

fun formatPlain(v: Double): String =
    if (v == Math.floor(v)) String.format(de, "%.0f", v) else String.format(de, "%s", v).replace('.', ',')

/** Leistung: bis 999 W in W, darüber in kW. Liefert Wert und Einheit getrennt. */
fun powerParts(watts: Double?): Pair<String, String> = when {
    watts == null -> "–" to "W"
    kotlin.math.abs(watts) >= 1000 -> String.format(de, "%.2f", watts / 1000) to "kW"
    else -> String.format(de, "%.0f", watts) to "W"
}

fun formatPower(watts: Double?): String = powerParts(watts).let { "${it.first} ${it.second}" }

fun formatKwh(kwh: Double?): String = when {
    kwh == null -> "–"
    kwh >= 1000 -> String.format(de, "%.2f MWh", kwh / 1000)
    kwh >= 100 -> String.format(de, "%.0f kWh", kwh)
    else -> String.format(de, "%.1f kWh", kwh)
}

fun formatEuro(euro: Double): String = String.format(de, "%.2f €", euro)

fun formatPercent(fraction: Double?): String =
    if (fraction == null) "–" else String.format(de, "%.0f %%", fraction * 100)

fun formatHours(hours: Double): String = String.format(de, "%.1f h", hours)

fun formatTime(millis: Long): String =
    java.text.SimpleDateFormat("HH:mm:ss", de).format(java.util.Date(millis))
