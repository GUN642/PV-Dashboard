package de.gun642.pvdashboard.ui

import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.gun642.pvdashboard.senec.SenecSnapshot
import org.json.JSONObject

/** Zeigt alle dekodierten Werte – hilfreich, um fehlende Werte zu finden. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RawDataScreen(snapshot: SenecSnapshot?, onBack: () -> Unit) {
    val context = LocalContext.current
    val text = snapshot?.let { toPrettyJson(it.raw) } ?: "Noch keine Daten."

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Rohdaten") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        val send = Intent(Intent.ACTION_SEND)
                            .setType("text/plain")
                            .putExtra(Intent.EXTRA_TEXT, text)
                        context.startActivity(Intent.createChooser(send, "Rohdaten teilen"))
                    }) {
                        Icon(Icons.Filled.Share, contentDescription = "Teilen")
                    }
                },
            )
        },
    ) { padding ->
        SelectionContainer(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .horizontalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Text(text, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        }
    }
}

private fun toPrettyJson(raw: Map<String, Any?>): String =
    try {
        JSONObject(raw).toString(2)
    } catch (e: Exception) {
        raw.toString()
    }
