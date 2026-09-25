package de.gun642.pvdashboard.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/** Feste Farben je Energiefluss, gut lesbar in hellem und dunklem Design. */
object EnergyColors {
    val pv = Color(0xFFF5A623)
    val house = Color(0xFF3B82F6)
    val gridImport = Color(0xFFE5484D)
    val gridExport = Color(0xFF30A46C)
    val battery = Color(0xFF12A594)
    val wallbox = Color(0xFF8E4EC6)
}

@Composable
fun PvTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colors, content = content)
}
