package de.gun642.pvdashboard.widget

/**
 * Welche Bereiche das Widget bei einer Größe (dp) zeigt. PV-Erzeugung und Autarkie sind immer
 * sichtbar; je mehr Höhe, desto mehr kommt dazu – nichts wird abgeschnitten.
 */
data class WidgetLayout(
    val title: Boolean,
    val shareBar: Boolean,
    val battery: Boolean,
    val shareText: Boolean,
    val grid: Boolean,
    val fourColumns: Boolean,
    /** Schriftgröße der beiden großen Werte in dp */
    val bigTextDp: Float,
) {
    companion object {
        // Höhenbedarf (dp): Ränder 22 + Kopfzeile 40 + große Werte ~50, dann je Bereich
        private const val SHARE_BAR = 114f
        private const val BATTERY = 138f
        private const val SHARE_TEXT = 170f
        private const val GRID = 210f

        val FULL = of(300f, 260f)

        fun of(widthDp: Float, heightDp: Float): WidgetLayout {
            // Innenbreite ohne Ränder, geteilt auf zwei Werte; „6,02 kW“ braucht gut 4 × Schriftgröße
            val half = (widthDp - 42f) / 2
            val big = (half / 4.4f).coerceIn(16f, 30f)
            return WidgetLayout(
                title = widthDp >= 210f,
                shareBar = heightDp >= SHARE_BAR,
                battery = heightDp >= BATTERY,
                shareText = heightDp >= SHARE_TEXT,
                grid = heightDp >= GRID,
                fourColumns = widthDp >= 270f,
                bigTextDp = big,
            )
        }
    }
}
