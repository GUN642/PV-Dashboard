package de.gun642.pvdashboard.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.gun642.pvdashboard.ui.theme.VoidTheme

/** Zeigt Rohdaten (JSON) – zum Teilen bei der Fehlersuche. */
@Composable
fun RawDataScreen(title: String, text: String, onBack: () -> Unit) {
    val c = VoidTheme.colors
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().background(c.background)) {
        ScreenHeader(title, onBack) {
            IconButton(onClick = {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                context.startActivity(Intent.createChooser(send, "Rohdaten teilen"))
            }) { Icon(Icons.Filled.Share, "Teilen", tint = c.text) }
        }
        SelectionContainer(
            Modifier.fillMaxSize()
                .verticalScroll(rememberScrollState())
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            Text(text.ifBlank { "Keine Daten." }, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = c.text)
        }
    }
}
