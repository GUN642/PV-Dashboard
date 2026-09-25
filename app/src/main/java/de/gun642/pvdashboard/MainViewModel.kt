package de.gun642.pvdashboard

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.gun642.pvdashboard.data.AppSettings
import de.gun642.pvdashboard.data.DataSource
import de.gun642.pvdashboard.data.Release
import de.gun642.pvdashboard.data.SettingsRepository
import de.gun642.pvdashboard.data.Updater
import de.gun642.pvdashboard.senec.SenecClient
import de.gun642.pvdashboard.senec.SenecSnapshot
import de.gun642.pvdashboard.senec.cloud.SenecCloud
import de.gun642.pvdashboard.stats.EnergyTotals
import de.gun642.pvdashboard.stats.Period
import de.gun642.pvdashboard.stats.PeriodType
import de.gun642.pvdashboard.stats.PvgisReference
import de.gun642.pvdashboard.stats.StatsRepository
import de.gun642.pvdashboard.stats.StatsResult
import de.gun642.pvdashboard.stats.Tariff
import de.gun642.pvdashboard.weather.Forecast
import de.gun642.pvdashboard.weather.OpenMeteo
import de.gun642.pvdashboard.weather.Place
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

enum class Tab(val label: String) { LIVE("Live"), STATS("Statistik"), WEATHER("Wetter") }

enum class Screen { MAIN, SETTINGS, RAW }

sealed interface UiEvent {
    data class Message(val text: String) : UiEvent
    data class Install(val apk: File) : UiEvent
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val repository = SettingsRepository(app)
    val settings: StateFlow<AppSettings> = repository.settings

    private val cloud = SenecCloud(app) {
        val s = settings.value
        if (s.hasCloud) s.senecEmail to s.senecPassword else null
    }
    private val stats = StatsRepository(cloud)

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<UiEvent> = _events

    // ---------- Navigation ----------
    var tab by mutableStateOf(Tab.LIVE)
    var screen by mutableStateOf(if (repository.settings.value.let { it.hasLocal || it.hasCloud }) Screen.MAIN else Screen.SETTINGS)
    /** Text für die Rohdaten-Ansicht */
    var rawTitle by mutableStateOf("")
    var rawText by mutableStateOf("")

    fun showRaw(title: String, text: String) {
        rawTitle = title
        rawText = text
        screen = Screen.RAW
    }

    fun back(): Boolean = when (screen) {
        Screen.RAW, Screen.SETTINGS -> { screen = Screen.MAIN; true }
        Screen.MAIN -> if (tab != Tab.LIVE) { tab = Tab.LIVE; true } else false
    }

    fun updateSettings(transform: AppSettings.() -> AppSettings) {
        val before = settings.value
        repository.update(transform)
        val after = settings.value
        if (before.senecEmail != after.senecEmail || before.senecPassword != after.senecPassword) {
            cloud.clear()
            loginStatus = null
            statsResult = null
        }
        if (before.host != after.host || before.useHttps != after.useHttps || before.dataSource != after.dataSource) {
            skipLocalUntil = 0
        }
        if (before.latitude != after.latitude || before.longitude != after.longitude || before.peakPowerKwp != after.peakPowerKwp ||
            before.tiltDegrees != after.tiltDegrees || before.azimuthDegrees != after.azimuthDegrees ||
            before.systemLossPercent != after.systemLossPercent
        ) {
            forecast = null
        }
    }

    // ---------- Live ----------
    var snapshot by mutableStateOf<SenecSnapshot?>(null)
        private set
    var history by mutableStateOf<List<SenecSnapshot>>(emptyList())
        private set
    var liveError by mutableStateOf<String?>(null)
        private set
    var liveLoading by mutableStateOf(false)
        private set
    var today by mutableStateOf<EnergyTotals?>(null)
        private set

    private var pollJob: Job? = null
    private var skipLocalUntil = 0L
    private var todayFetchedAt = 0L

    fun start() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            while (isActive) {
                val usedCloud = refreshLive()
                val s = settings.value
                // Die Cloud liefert höchstens etwa alle 30 s neue Werte.
                val seconds = if (usedCloud) maxOf(s.intervalSeconds, 30) else s.intervalSeconds
                delay(seconds * 1000L)
            }
        }
        if (settings.value.autoUpdateCheck && releases.isEmpty()) checkForUpdates(manual = false)
    }

    fun stop() {
        pollJob?.cancel()
        pollJob = null
    }

    /** @return true, wenn die Werte aus der Cloud kamen */
    private suspend fun refreshLive(): Boolean {
        val s = settings.value
        val canLocal = s.hasLocal && s.dataSource != DataSource.CLOUD
        val canCloud = s.hasCloud && s.dataSource != DataSource.LOCAL
        if (!canLocal && !canCloud) {
            liveError = "Bitte in den Einstellungen die IP-Adresse des Speichers und/oder das SENEC-Konto eintragen."
            return false
        }
        liveLoading = true
        var localError: Exception? = null
        var usedCloud = false
        try {
            var result: SenecSnapshot? = null
            if (canLocal && (!canCloud || System.currentTimeMillis() >= skipLocalUntil)) {
                try {
                    // Mit Cloud als Ausweg kurz warten, sonst großzügiger.
                    result = SenecClient(s.host, s.useHttps, if (canCloud) 2_500 else 5_000).fetch(s.wallboxIndex)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    localError = e
                    // Unterwegs nicht bei jeder Abfrage erneut auf das Heimnetz warten.
                    skipLocalUntil = System.currentTimeMillis() + 60_000
                }
            }
            if (result == null && canCloud) {
                val dashboard = cloud.dashboard()
                result = dashboard.snapshot
                dashboard.today?.let { today = it; todayFetchedAt = System.currentTimeMillis() }
                usedCloud = true
            }
            val snap = result ?: throw localError ?: IllegalStateException("Keine Daten")
            val cutoff = snap.timestamp - 30 * 60_000
            snapshot = snap
            history = history.filter { it.timestamp >= cutoff && it.timestamp < snap.timestamp } + snap
            liveError = null
            if (!usedCloud && canCloud) refreshTodayIfDue()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            liveError = describe(e, s)
        } finally {
            liveLoading = false
        }
        return usedCloud
    }

    /** Tageswerte aus der Cloud, auch wenn die Live-Werte lokal kommen (alle 5 Minuten). */
    private suspend fun refreshTodayIfDue() {
        if (System.currentTimeMillis() - todayFetchedAt < 5 * 60_000) return
        todayFetchedAt = System.currentTimeMillis()
        runCatching { cloud.dashboard() }.onSuccess { today = it.today }
    }

    private fun describe(e: Exception, s: AppSettings): String = when (e) {
        is UnknownHostException, is ConnectException, is SocketTimeoutException ->
            if (s.hasCloud && s.dataSource != DataSource.LOCAL) "Keine Verbindung – weder zum Speicher noch zur SENEC-Cloud. Internet prüfen."
            else "Speicher unter ${s.host} nicht erreichbar. Bist du im Heim-WLAN? Für unterwegs das SENEC-Konto eintragen."
        is SSLException -> "HTTPS-Verbindung fehlgeschlagen. Evtl. in den Einstellungen HTTPS ausschalten. (${e.message})"
        else -> e.message ?: e.javaClass.simpleName
    }

    // ---------- SENEC-Konto ----------
    var loginStatus by mutableStateOf<String?>(null)
        private set
    var loginRunning by mutableStateOf(false)
        private set

    fun testLogin() {
        if (loginRunning) return
        loginRunning = true
        loginStatus = "Anmeldung läuft …"
        viewModelScope.launch {
            loginStatus = try {
                val id = cloud.login()
                statsResult = null
                "Angemeldet · Anlage $id"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "Fehler: ${e.message ?: e.javaClass.simpleName}"
            }
            loginRunning = false
        }
    }

    fun logout() {
        cloud.clear()
        updateSettings { copy(senecPassword = "") }
        loginStatus = "Abgemeldet"
    }

    // ---------- Statistik ----------
    var period by mutableStateOf(Period.today(PeriodType.DAY))
        private set
    var statsResult by mutableStateOf<StatsResult?>(null)
        private set
    var statsLoading by mutableStateOf(false)
        private set
    var statsError by mutableStateOf<String?>(null)
        private set
    private var statsJob: Job? = null

    val tariff: Tariff
        get() = settings.value.let { Tariff(it.provider, it.baseFeePerMonth, it.pricePerKwhCent, it.feedInCent) }

    fun selectPeriod(p: Period) {
        period = p
        loadStats()
    }

    fun loadStats(force: Boolean = false) {
        if (!settings.value.hasCloud) {
            statsError = "Für Statistiken bitte in den Einstellungen das SENEC-Konto eintragen."
            statsResult = null
            return
        }
        val requested = period
        statsJob?.cancel()
        statsJob = viewModelScope.launch {
            statsLoading = true
            statsError = null
            try {
                val result = stats.load(requested, force)
                if (requested == period) statsResult = result
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                statsError = "Statistik konnte nicht geladen werden: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                statsLoading = false
            }
        }
    }

    // ---------- Wetter ----------
    var forecast by mutableStateOf<Forecast?>(null)
        private set
    var weatherLoading by mutableStateOf(false)
        private set
    var weatherError by mutableStateOf<String?>(null)
        private set
    private var forecastAt = 0L

    fun loadWeather(force: Boolean = false) {
        val s = settings.value
        val lat = s.latitude
        val lon = s.longitude
        if (lat == null || lon == null) {
            weatherError = "Bitte in den Einstellungen deinen Standort wählen."
            return
        }
        if (!force && forecast != null && System.currentTimeMillis() - forecastAt < 30 * 60_000) return
        viewModelScope.launch {
            weatherLoading = true
            weatherError = null
            try {
                forecast = OpenMeteo.forecast(lat, lon, s.peakPowerKwp, s.tiltDegrees, s.azimuthFromSouth, s.performanceRatio)
                forecastAt = System.currentTimeMillis()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                weatherError = "Wetterdaten nicht verfügbar: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                weatherLoading = false
            }
        }
    }

    var placeResults by mutableStateOf<List<Place>>(emptyList())
        private set
    var placeSearching by mutableStateOf(false)
        private set

    fun searchPlace(query: String) {
        if (query.isBlank()) return
        viewModelScope.launch {
            placeSearching = true
            placeResults = try {
                OpenMeteo.search(query).also { if (it.isEmpty()) message("Kein Ort gefunden") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message("Ortssuche fehlgeschlagen: ${e.message}")
                emptyList()
            }
            placeSearching = false
        }
    }

    fun choosePlace(place: Place) {
        updateSettings { copy(locationName = "${place.name}, ${place.detail}".trimEnd(',', ' '), latitude = place.latitude, longitude = place.longitude) }
        placeResults = emptyList()
    }

    // ---------- PVGIS-Referenz ----------
    val pvgis: PvgisReference?
        get() = settings.value.pvgisMonthly.takeIf { it.size == 12 }?.let { PvgisReference(it) }

    var pvgisLoading by mutableStateOf(false)
        private set

    /** Holt die Referenzwerte von PVGIS für Standort und Anlage aus den Einstellungen. */
    fun fetchPvgis() {
        val s = settings.value
        val lat = s.latitude
        val lon = s.longitude
        if (lat == null || lon == null || s.peakPowerKwp <= 0) {
            message("Bitte zuerst Standort und Anlagenleistung eintragen")
            return
        }
        if (pvgisLoading) return
        viewModelScope.launch {
            pvgisLoading = true
            try {
                val monthly = PvgisReference.fetch(lat, lon, s.peakPowerKwp, s.systemLossPercent, s.tiltDegrees, s.azimuthFromSouth)
                val info = String.format(
                    java.util.Locale.GERMANY, "PVGIS · %.1f kWp · %d° · Azimut %d° · %.0f %% Verlust · %.0f kWh/Jahr",
                    s.peakPowerKwp, s.tiltDegrees, s.azimuthDegrees, s.systemLossPercent, monthly.sum(),
                )
                updateSettings { copy(pvgisMonthly = monthly, pvgisInfo = info) }
                message("PVGIS-Referenz geladen: ${String.format(java.util.Locale.GERMANY, "%.0f", monthly.sum())} kWh/Jahr")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message("PVGIS nicht erreichbar: ${e.message}. Werte können auch von Hand eingetragen werden.")
            } finally {
                pvgisLoading = false
            }
        }
    }

    fun setPvgisManual(monthly: List<Double>) {
        updateSettings { copy(pvgisMonthly = monthly, pvgisInfo = String.format(java.util.Locale.GERMANY, "Eigene Werte · %.0f kWh/Jahr", monthly.sum())) }
    }

    fun clearPvgis() {
        updateSettings { copy(pvgisMonthly = emptyList(), pvgisInfo = "") }
    }

    // ---------- Updates ----------
    var releases by mutableStateOf<List<Release>>(emptyList())
        private set
    var availableUpdate by mutableStateOf<Release?>(null)
        private set
    var updateStatus by mutableStateOf<String?>(null)
        private set
    var checkingUpdate by mutableStateOf(false)
        private set
    var showUpdateDialog by mutableStateOf(false)
    var downloadProgress by mutableStateOf<Pair<Long, Long>?>(null)
        private set
    private var downloadJob: Job? = null

    fun checkForUpdates(manual: Boolean) {
        if (checkingUpdate) return
        checkingUpdate = true
        if (manual) updateStatus = "Suche nach Updates …"
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { Updater.fetchReleases() } }
            checkingUpdate = false
            result.onSuccess { list ->
                releases = list
                val newest = list.firstOrNull { it.apkUrl != null }
                if (newest != null && Updater.isNewer(newest.version, BuildConfig.VERSION_NAME)) {
                    availableUpdate = newest
                    updateStatus = "Version ${newest.version} ist verfügbar"
                    if (!manual) showUpdateDialog = true
                } else {
                    availableUpdate = null
                    updateStatus = "VOID PV Dashboard ist aktuell"
                }
            }.onFailure {
                updateStatus = "Update-Prüfung fehlgeschlagen: ${it.message ?: "keine Verbindung"}"
            }
        }
    }

    fun downloadUpdate(release: Release) {
        showUpdateDialog = false
        val url = release.apkUrl ?: return message("Für diese Version gibt es keine APK")
        if (downloadJob?.isActive == true) return
        val target = File(getApplication<Application>().cacheDir, "updates/PV-Dashboard-${release.version}.apk")
        downloadJob = viewModelScope.launch {
            downloadProgress = 0L to release.apkSize
            try {
                withContext(Dispatchers.IO) {
                    Updater.download(url, target, { done, total -> downloadProgress = done to total }, { !isActive })
                }
                _events.tryEmit(UiEvent.Install(target))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message("Download fehlgeschlagen: ${e.message}")
            } finally {
                downloadProgress = null
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
    }

    fun message(text: String) {
        _events.tryEmit(UiEvent.Message(text))
    }
}
