package de.gun642.pvdashboard.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.gun642.pvdashboard.DashboardViewModel

private enum class Screen { Dashboard, Settings, Raw }

@Composable
fun App(viewModel: DashboardViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Beim ersten Start ohne IP direkt zu den Einstellungen.
    var screen by rememberSaveable {
        mutableStateOf(if (state.settings.host.isBlank()) Screen.Settings else Screen.Dashboard)
    }
    BackHandler(enabled = screen != Screen.Dashboard) { screen = Screen.Dashboard }

    when (screen) {
        Screen.Dashboard -> DashboardScreen(
            state = state,
            onOpenSettings = { screen = Screen.Settings },
            onOpenRaw = { screen = Screen.Raw },
        )
        Screen.Settings -> SettingsScreen(
            settings = state.settings,
            onSave = {
                viewModel.saveSettings(it)
                screen = Screen.Dashboard
            },
            onBack = { screen = Screen.Dashboard },
        )
        Screen.Raw -> RawDataScreen(
            snapshot = state.snapshot,
            onBack = { screen = Screen.Dashboard },
        )
    }
}
