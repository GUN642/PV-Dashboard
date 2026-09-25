package de.gun642.pvdashboard

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.gun642.pvdashboard.senec.SenecClient
import de.gun642.pvdashboard.senec.SenecSnapshot
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

data class DashboardState(
    val settings: AppSettings = AppSettings(),
    val snapshot: SenecSnapshot? = null,
    /** Verlauf der laufenden Sitzung (nur im Speicher, max. [HISTORY_MILLIS]). */
    val history: List<SenecSnapshot> = emptyList(),
    val error: String? = null,
    val loading: Boolean = false,
)

private const val HISTORY_MILLIS = 30 * 60 * 1000L

class DashboardViewModel(app: Application) : AndroidViewModel(app) {
    private val repository = SettingsRepository(app)
    private val _state = MutableStateFlow(DashboardState(settings = repository.load()))
    val state: StateFlow<DashboardState> = _state.asStateFlow()

    private var pollJob: Job? = null

    /** Startet die regelmäßige Abfrage (solange die App sichtbar ist). */
    fun start() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            while (isActive) {
                refresh()
                delay(_state.value.settings.intervalSeconds * 1000L)
            }
        }
    }

    fun stop() {
        pollJob?.cancel()
        pollJob = null
    }

    fun saveSettings(settings: AppSettings) {
        repository.save(settings)
        _state.update { DashboardState(settings = repository.load()) }
        stop()
        start()
    }

    private suspend fun refresh() {
        val settings = _state.value.settings
        if (settings.host.isBlank()) {
            _state.update { it.copy(error = "Bitte in den Einstellungen die IP-Adresse des Speichers eintragen.") }
            return
        }
        _state.update { it.copy(loading = true) }
        try {
            val snapshot = SenecClient(settings.host, settings.useHttps).fetch(settings.wallboxIndex)
            _state.update { current ->
                val cutoff = snapshot.timestamp - HISTORY_MILLIS
                current.copy(
                    snapshot = snapshot,
                    history = current.history.filter { it.timestamp >= cutoff } + snapshot,
                    error = null,
                    loading = false,
                )
            }
        } catch (e: Exception) {
            _state.update { it.copy(error = describe(e, settings), loading = false) }
        }
    }

    private fun describe(e: Exception, settings: AppSettings): String = when (e) {
        is UnknownHostException, is ConnectException, is SocketTimeoutException ->
            "Speicher unter ${settings.host} nicht erreichbar. Ist das Handy im Heim-WLAN und die IP-Adresse richtig?"
        is SSLException ->
            "HTTPS-Verbindung fehlgeschlagen. Evtl. in den Einstellungen HTTPS ausschalten. (${e.message})"
        else -> "Fehler beim Abruf: ${e.javaClass.simpleName}: ${e.message}"
    }
}
