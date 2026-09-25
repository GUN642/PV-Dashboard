package de.gun642.pvdashboard.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.gun642.pvdashboard.MainViewModel
import de.gun642.pvdashboard.Screen
import de.gun642.pvdashboard.Tab
import de.gun642.pvdashboard.UiEvent
import de.gun642.pvdashboard.ui.theme.PvTheme
import de.gun642.pvdashboard.ui.theme.VoidTheme
import kotlinx.coroutines.launch

@Composable
fun App(vm: MainViewModel, onInstall: (java.io.File) -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    PvTheme(settings.theme, settings.accent, settings.dotHeadings, settings.dotGrid) {
        val c = VoidTheme.colors
        val context = LocalContext.current
        val snackbar = remember { SnackbarHostState() }

        LaunchedEffect(Unit) {
            vm.events.collect { e ->
                when (e) {
                    is UiEvent.Message -> {
                        snackbar.currentSnackbarData?.dismiss()
                        launch { snackbar.showSnackbar(e.text) }
                    }
                    is UiEvent.Install -> onInstall(e.apk)
                }
            }
        }

        BackHandler {
            if (!vm.back()) (context as? Activity)?.moveTaskToBack(true)
        }

        Box(Modifier.fillMaxSize().background(c.background)) {
            if (VoidTheme.dotGrid) DotGrid(Modifier.fillMaxSize())
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Box(Modifier.weight(1f)) {
                    when (vm.screen) {
                        Screen.SETTINGS -> SettingsScreen(vm, settings) { vm.screen = Screen.MAIN }
                        Screen.RAW -> RawDataScreen(vm.rawTitle, vm.rawText) { vm.screen = Screen.MAIN }
                        Screen.MAIN -> {
                            val openSettings = { vm.screen = Screen.SETTINGS }
                            when (vm.tab) {
                                Tab.LIVE -> LiveScreen(vm, openSettings) { vm.tab = Tab.WALLBOX }
                                Tab.STATS -> StatsScreen(vm, settings, openSettings)
                                Tab.WALLBOX -> WallboxScreen(vm, settings, openSettings)
                                Tab.WEATHER -> WeatherScreen(vm, settings, openSettings)
                            }
                        }
                    }
                }
                if (vm.screen == Screen.MAIN) TabBar(vm.tab) { vm.tab = it }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(12.dp)) { data ->
                Snackbar(data, containerColor = c.surfaceHigh, contentColor = c.text, shape = RoundedCornerShape(16.dp))
            }
        }

        val update = vm.availableUpdate
        if (vm.showUpdateDialog && update != null) {
            AlertDialog(
                onDismissRequest = { vm.showUpdateDialog = false },
                containerColor = c.surface,
                title = { Text("Update ${update.version}", color = c.text) },
                text = { Text(update.notes.ifBlank { "Eine neue Version ist verfügbar." }, color = c.textMuted) },
                confirmButton = {
                    TextButton(onClick = { vm.downloadUpdate(update) }) {
                        Text("INSTALLIEREN", style = MaterialTheme.typography.labelLarge, color = c.accent)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { vm.showUpdateDialog = false }) {
                        Text("SPÄTER", style = MaterialTheme.typography.labelLarge, color = c.textMuted)
                    }
                },
            )
        }
    }
}

/** Untere Navigation im Nothing-Stil: Mono-Beschriftung mit Punkt für den aktiven Tab. */
@Composable
private fun TabBar(current: Tab, onSelect: (Tab) -> Unit) {
    val c = VoidTheme.colors
    Column(Modifier.fillMaxWidth().background(c.background)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.divider))
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp)) {
            Tab.entries.forEach { tab ->
                val selected = tab == current
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).clickable { onSelect(tab) }.padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(if (selected) c.accent else Color.Transparent))
                    Spacer(Modifier.height(4.dp))
                    Text(
                        tab.label.uppercase(),
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal),
                        color = if (selected) c.text else c.textMuted,
                    )
                }
            }
        }
    }
}
