package de.gun642.pvdashboard

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.gun642.pvdashboard.data.AppSettings
import de.gun642.pvdashboard.data.Backup
import de.gun642.pvdashboard.data.DataSource
import de.gun642.pvdashboard.data.Release
import de.gun642.pvdashboard.data.SettingsRepository
import de.gun642.pvdashboard.data.Updater
import de.gun642.pvdashboard.senec.SenecClient
import de.gun642.pvdashboard.senec.SenecSnapshot
import de.gun642.pvdashboard.contracts.Contract
import de.gun642.pvdashboard.contracts.ContractStore
import de.gun642.pvdashboard.contracts.FlowType
import de.gun642.pvdashboard.contracts.ContractCategory
import de.gun642.pvdashboard.contracts.FinanceFilter
import de.gun642.pvdashboard.contracts.FinancePdf
import de.gun642.pvdashboard.contracts.FinanceSummary
import de.gun642.pvdashboard.meters.Consumption
import de.gun642.pvdashboard.meters.MeterCsv
import de.gun642.pvdashboard.meters.UsageWarning
import de.gun642.pvdashboard.notify.BackgroundChecks
import de.gun642.pvdashboard.notify.SurplusCheck
import de.gun642.pvdashboard.meters.MeterReading
import de.gun642.pvdashboard.meters.MeterStore
import de.gun642.pvdashboard.meters.MeterType
import de.gun642.pvdashboard.meters.WaterTariff
import de.gun642.pvdashboard.senec.cloud.SenecCloud
import de.gun642.pvdashboard.senec.cloud.WallboxInfo
import de.gun642.pvdashboard.senec.cloud.WallboxMode
import de.gun642.pvdashboard.stats.EnergySeries
import de.gun642.pvdashboard.stats.HistoryCsv
import de.gun642.pvdashboard.stats.HistoryMonth
import de.gun642.pvdashboard.stats.HistoryStore
import de.gun642.pvdashboard.stats.PortalImport
import de.gun642.pvdashboard.stats.EnergyTotals
import de.gun642.pvdashboard.stats.Period
import de.gun642.pvdashboard.stats.PeriodType
import de.gun642.pvdashboard.stats.AdvanceCheck
import de.gun642.pvdashboard.stats.PvgisReference
import de.gun642.pvdashboard.stats.StatsRepository
import de.gun642.pvdashboard.stats.StatsResult
import de.gun642.pvdashboard.stats.Tariff
import de.gun642.pvdashboard.stats.YearCompare
import de.gun642.pvdashboard.wallbox.ChargeLog
import de.gun642.pvdashboard.widget.ForecastCache
import de.gun642.pvdashboard.widget.ForecastWidget
import de.gun642.pvdashboard.wallbox.ChargeMonth
import de.gun642.pvdashboard.wallbox.ChargePdf
import de.gun642.pvdashboard.weather.Forecast
import de.gun642.pvdashboard.weather.OpenMeteo
import de.gun642.pvdashboard.weather.Place
import de.gun642.pvdashboard.widget.LiveWidget
import de.gun642.pvdashboard.widget.WidgetCache
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

enum class Tab(val label: String) { LIVE("Live"), STATS("Statistik"), WALLBOX("Wallbox"), WEATHER("Wetter"), HOME("Haus") }

enum class Screen { MAIN, SETTINGS, RAW }

sealed interface UiEvent {
    data class Message(val text: String) : UiEvent
    data class Install(val apk: File) : UiEvent
    data class Share(val file: File, val mimeType: String) : UiEvent
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val repository = SettingsRepository(app)
    val settings: StateFlow<AppSettings> = repository.settings

    private val cloud = SenecCloud(app) {
        val s = settings.value
        if (s.hasCloud) s.senecEmail to s.senecPassword else null
    }
    private val stats = StatsRepository(cloud) { legacyData }

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
        if (before.surplusNotify != after.surplusNotify || before.surplusIntervalMinutes != after.surplusIntervalMinutes ||
            before.contractReminders != after.contractReminders || before.hasLocal != after.hasLocal || before.hasCloud != after.hasCloud
        ) {
            BackgroundChecks.apply(getApplication<Application>(), after)
        }
        if (before.widgetDark != after.widgetDark || before.widgetOpacity != after.widgetOpacity) {
            LiveWidget.updateAll(getApplication<Application>())
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
            shareWithWidget(snap)
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

    private var widgetUpdatedAt = 0L

    /** Gibt die ohnehin abgerufenen Werte ans Widget weiter (höchstens alle 30 s neu zeichnen). */
    private fun shareWithWidget(snap: SenecSnapshot) {
        val app = getApplication<Application>()
        WidgetCache.save(app, snap)
        if (System.currentTimeMillis() - widgetUpdatedAt > 30_000) {
            widgetUpdatedAt = System.currentTimeMillis()
            LiveWidget.updateAll(app)
        }
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

    var diagnosing by mutableStateOf(false)
        private set

    /** Zeigt, welche Anlagen und Jahre die SENEC-Cloud für das Konto liefert (zur Fehlersuche bei fehlenden Vorjahren). */
    fun diagnoseCloud() {
        if (diagnosing) return
        diagnosing = true
        viewModelScope.launch {
            try {
                val year = java.time.LocalDate.now().year
                showRaw("SENEC-Anlagen prüfen", cloud.diagnose((year - 5)..year))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message("Prüfung fehlgeschlagen: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                diagnosing = false
            }
        }
    }

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

    // ---------- Wallbox ----------
    var wallbox by mutableStateOf<WallboxInfo?>(null)
        private set
    var wallboxLoading by mutableStateOf(false)
        private set
    /** Läuft gerade eine Änderung? (Bedienelemente sperren) */
    var wallboxBusy by mutableStateOf(false)
        private set
    var wallboxError by mutableStateOf<String?>(null)
        private set

    fun loadWallbox() {
        if (!settings.value.hasCloud) {
            wallboxError = "Für die Wallbox-Steuerung bitte in den Einstellungen das SENEC-Konto eintragen."
            return
        }
        if (wallboxLoading) return
        viewModelScope.launch {
            wallboxLoading = true
            try {
                wallbox = readWallbox()
                wallboxError = if (wallbox == null) "Im SENEC-Konto wurde keine Wallbox gefunden." else null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                wallboxError = "Wallbox nicht erreichbar: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                wallboxLoading = false
            }
        }
    }

    private suspend fun readWallbox(): WallboxInfo? {
        val list = cloud.wallboxes()
        return list.getOrNull(settings.value.wallboxIndex) ?: list.firstOrNull()
    }

    fun setWallboxMode(mode: WallboxMode) = changeWallbox("Lademodus „${mode.label}“", { it.mode == mode }) { wb ->
        when {
            mode == WallboxMode.LOCKED -> cloud.setWallboxLocked(wb.id, true)
            else -> {
                if (wb.mode == WallboxMode.LOCKED) cloud.setWallboxLocked(wb.id, false)
                mode.apiType?.let { cloud.setWallboxMode(wb.id, it) }
            }
        }
    }

    fun setWallboxFastBattery(enabled: Boolean) =
        changeWallbox(if (enabled) "Speicher lädt mit" else "Speicher lädt nicht mit", { it.fastAllowIntercharge == enabled }) { wb ->
            cloud.setWallboxFastSettings(wb.id, enabled)
        }

    fun setWallboxPreventInterruptions(enabled: Boolean) =
        changeWallbox(if (enabled) "Ladeunterbrechungen verhindern" else "Ladeunterbrechungen zulassen", { it.solarPreventInterruptions == enabled }) { wb ->
            cloud.setWallboxSolarSettings(wb.id, WallboxInfo.solarSettingsBody(wb.solarSettings, preventInterruptions = enabled))
        }

    fun setWallboxMinCurrent(ampere: Double) =
        changeWallbox("Mindestladestrom ${ampere.toInt()} A", { it.solarMinCurrent == ampere }) { wb ->
            cloud.setWallboxSolarSettings(wb.id, WallboxInfo.solarSettingsBody(wb.solarSettings, minCurrent = ampere))
        }

    /**
     * Führt eine Änderung aus und liest danach den tatsächlichen Zustand der Wallbox zurück.
     * Nur wenn die Wallbox den neuen Wert meldet, gilt die Änderung als übernommen.
     */
    private fun changeWallbox(what: String, applied: (WallboxInfo) -> Boolean, action: suspend (WallboxInfo) -> Unit) {
        val current = wallbox ?: return
        if (wallboxBusy) return
        viewModelScope.launch {
            wallboxBusy = true
            try {
                action(current)
                // Die Wallbox braucht einen Moment, bis der neue Zustand gemeldet wird.
                var result: WallboxInfo? = null
                for (attempt in 0 until 3) {
                    delay(2_000)
                    result = readWallbox()
                    if (result != null && applied(result)) break
                }
                wallbox = result
                message(if (result != null && applied(result)) "Übernommen: $what" else "Achtung: Die Wallbox meldet die Änderung noch nicht – bitte Anzeige prüfen.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message("Änderung fehlgeschlagen: ${e.message ?: e.javaClass.simpleName}")
                runCatching { wallbox = readWallbox() }
            } finally {
                wallboxBusy = false
            }
        }
    }

    // ---------- Zähler (Strom & Wasser) ----------
    private val meterStore = MeterStore(app)
    var meterData by mutableStateOf(meterStore.load())
        private set
    var meterType by mutableStateOf(MeterType.POWER)
    /** false = Monate eines Jahres, true = Jahresübersicht */
    var meterYearly by mutableStateOf(false)
    var meterYear by mutableStateOf(java.time.LocalDate.now().year)

    val waterTariff: WaterTariff
        get() = settings.value.let { WaterTariff(it.waterProvider, it.waterPricePerM3, it.wastewaterPerM3, it.waterBaseFeePerMonth) }

    fun readings(type: MeterType): List<MeterReading> = meterData[type].orEmpty()

    private fun updateReadings(type: MeterType, readings: List<MeterReading>) {
        meterData = meterData + (type to readings.sortedBy { it.date })
        val snapshot = meterData
        viewModelScope.launch(Dispatchers.IO) { meterStore.save(snapshot) }
    }

    /** Hinweis nach dem Eintragen, z. B. „Wasserverbrauch deutlich über dem Durchschnitt“. */
    var usageAlert by mutableStateOf<UsageWarning?>(null)

    fun addReading(type: MeterType, reading: MeterReading) {
        updateReadings(type, MeterCsv.merge(readings(type), listOf(reading)))
        message("${type.label}: Zählerstand ${MeterCsv.formatValue(reading.value)} ${type.unit} gespeichert")
        if (type == MeterType.WATER) {
            usageAlert = waterWarning()?.takeIf { it.to == reading.date }
        }
    }

    /** Auffällig hoher Wasserverbrauch seit der letzten Ablesung (Leck?). */
    fun waterWarning(): UsageWarning? =
        Consumption.unusualIncrease(readings(MeterType.WATER), settings.value.leakWarnPercent, minExcessPerDay = 0.03)

    // ---------- Überschuss-Hinweis ----------
    var lastSurplusCheck by mutableStateOf(SurplusCheck.lastCheck(app))
        private set
    var surplusChecking by mutableStateOf(false)
        private set

    /** „Jetzt prüfen“: sofort abfragen und Ergebnis mit Begründung anzeigen. */
    fun runSurplusCheckNow() {
        if (surplusChecking) return
        viewModelScope.launch {
            surplusChecking = true
            val text = withContext(Dispatchers.IO) { SurplusCheck.run(getApplication<Application>(), manual = true) }
            lastSurplusCheck = text
            surplusChecking = false
            message(text.substringAfter(": "))
        }
    }

    /** Ist die App von der Akku-Optimierung ausgenommen? (sonst verschiebt Android Hintergrundprüfungen stark) */
    fun ignoresBatteryOptimization(): Boolean {
        val pm = getApplication<Application>().getSystemService(android.os.PowerManager::class.java)
        return pm?.isIgnoringBatteryOptimizations(getApplication<Application>().packageName) ?: true
    }

    fun refreshSurplusStatus() {
        lastSurplusCheck = SurplusCheck.lastCheck(getApplication<Application>())
    }

    // ---------- Datensicherung ----------
    private val backupInfo = app.getSharedPreferences("backup_info", android.content.Context.MODE_PRIVATE)
    var lastBackup by mutableStateOf(backupInfo.getString("last_backup", null))
        private set

    /** Vorschlag für den Dateinamen der Sicherung. */
    fun backupFileName(): String = "VOID-Home-Backup-${java.time.LocalDate.now()}.json"

    fun exportBackup(uri: android.net.Uri) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val json = Backup.create(getApplication<Application>())
                    getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.use { it.write(json.toByteArray()) }
                        ?: throw java.io.IOException("Datei nicht beschreibbar")
                }
                val stamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))
                backupInfo.edit().putString("last_backup", stamp).apply()
                lastBackup = stamp
                message("Sicherung gespeichert")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message("Sichern fehlgeschlagen: ${e.message}")
            }
        }
    }

    fun restoreBackup(uri: android.net.Uri) {
        viewModelScope.launch {
            try {
                val summary = withContext(Dispatchers.IO) {
                    val text = getApplication<Application>().contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                        ?: throw java.io.IOException("Datei nicht lesbar")
                    Backup.restore(getApplication<Application>(), text)
                }
                // Alles neu einlesen
                repository.reload()
                meterData = meterStore.load()
                contracts = contractStore.load()
                legacyData = historyStore.load()
                stats.invalidate()
                statsResult = null
                statsCompare = null
                forecast = null
                BackgroundChecks.apply(getApplication<Application>(), settings.value)
                LiveWidget.updateAll(getApplication<Application>())
                message("Wiederhergestellt: ${summary.readings} Zählerstände, ${summary.contracts} Verträge, Einstellungen. Auf einem neuen Handy das SENEC-Passwort neu eingeben.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message("Wiederherstellen fehlgeschlagen: ${e.message}")
            }
        }
    }

    // ---------- Verträge ----------
    private val contractStore = ContractStore(app)
    var contracts by mutableStateOf(contractStore.load())
        private set
    /** Im Haus-Tab: Verträge statt Zähler anzeigen */
    var showContracts by mutableStateOf(false)

    fun saveContract(contract: Contract) {
        contracts = contracts.filterNot { it.id == contract.id } + contract
        persistContracts()
        message("„${contract.name}“ gespeichert")
    }

    fun deleteContract(contract: Contract) {
        contracts = contracts.filterNot { it.id == contract.id }
        persistContracts()
    }

    // ---------- Finanzen (Einnahmen & Ausgaben) ----------
    var financeYear by mutableStateOf(java.time.LocalDate.now().year)
    var financeFilter by mutableStateOf(FinanceFilter.ALL)

    /** Übersicht für einen Monat (Standard) oder das ganze Jahr */
    var financeMonthView by mutableStateOf(true)
    var financeMonth by mutableStateOf(java.time.LocalDate.now().monthValue)

    fun shiftFinanceMonth(delta: Int) {
        val ym = java.time.YearMonth.of(financeYear, financeMonth).plusMonths(delta.toLong())
        financeYear = ym.year
        financeMonth = ym.monthValue
    }

    // ---------- Frühere Anlage (Vorjahre aus CSV) ----------
    private val historyStore = HistoryStore(app)

    /** Monatswerte der früheren Anlage; ergänzen Jahr, Gesamt und Vorjahresvergleich. */
    var legacyData by mutableStateOf(historyStore.load())
        private set

    private fun applyHistory(list: List<HistoryMonth>) {
        legacyData = list
        stats.invalidate()
        statsResult = null
        statsCompare = null
        loadStats(force = true)
    }

    var historyImporting by mutableStateOf(false)
        private set

    /**
     * Liest beliebig viele Dateien auf einmal: Wochendateien aus dem alten SENEC-Portal (Leistungsverlauf,
     * wird zu Monatswerten aufsummiert) und/oder eigene Monats-CSV. Messwerte ab Beginn der aktuellen
     * Anlage in der Cloud werden ignoriert, damit im Wechselmonat nichts doppelt zählt.
     */
    fun importHistoryFiles(uris: List<android.net.Uri>) {
        if (uris.isEmpty() || historyImporting) return
        historyImporting = true
        viewModelScope.launch {
            try {
                val zone = java.time.ZoneId.systemDefault()
                val cutoff = runCatching { cloud.dataStart() }.getOrNull()?.let { java.time.LocalDateTime.ofInstant(it, zone) }
                val resolver = getApplication<Application>().contentResolver
                val (months, report, problems) = withContext(Dispatchers.IO) {
                    val own = mutableListOf<HistoryMonth>()
                    var unreadable = 0
                    val portalTexts = uris.asSequence().mapNotNull { uri ->
                        val text = runCatching { resolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()
                        if (text == null) { unreadable++; null }
                        else if (PortalImport.isPortalFile(text)) text
                        else { own += HistoryCsv.parse(text); null }
                    }
                    val portal = PortalImport.combine(portalTexts, cutoff)
                    val merged = HistoryStore.merge(own, portal.months)
                    val fmt = java.time.format.DateTimeFormatter.ofPattern("MM/yyyy")
                    val lines = buildList {
                        add("Dateien: ${uris.size} ausgewählt, ${portal.files} Portal-Wochendateien ausgewertet" +
                            (if (portal.skipped + unreadable > 0) ", ${portal.skipped + unreadable} übersprungen (Duplikat, überlappend oder nicht lesbar)" else ""))
                        add("Monate: ${merged.size}" + (if (merged.isNotEmpty()) " (${merged.first().month.format(fmt)} – ${merged.last().month.format(fmt)})" else ""))
                        if (cutoff != null) add("Messwerte ab ${cutoff.toLocalDate()} (Beginn der aktuellen Anlage) wurden ausgelassen.")
                        else add("Beginn der aktuellen Anlage nicht abrufbar – es wurde nichts ausgelassen. Bei Überschneidung mit der Cloud kann der Wechselmonat doppelt zählen.")
                        if (portal.incomplete.isNotEmpty()) {
                            add("")
                            add("Monate mit fehlenden Tagen (Tage mit Daten):")
                            portal.incomplete.forEach { (m, n) -> add("  ${m.format(fmt)}: $n von ${m.lengthOfMonth()}") }
                            add("Fehlende Wochendateien nachladen und erneut importieren – dabei alle Dateien des Monats mit auswählen.")
                        }
                    }
                    Triple(merged, lines.joinToString("\n"), portal.incomplete.isNotEmpty() || portal.skipped + unreadable > 0)
                }
                if (months.isEmpty()) {
                    message("Keine auswertbaren Daten gefunden")
                    return@launch
                }
                val merged = HistoryStore.merge(legacyData, months)
                withContext(Dispatchers.IO) { historyStore.save(merged) }
                applyHistory(merged)
                if (problems) showRaw("Import der früheren Anlage", report) else message("${months.size} Monate importiert")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message("Import fehlgeschlagen: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                historyImporting = false
            }
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { historyStore.save(emptyList()) }
            applyHistory(emptyList())
            message("Werte der früheren Anlage gelöscht")
        }
    }

    /** Vorhandene Werte (bzw. nur die Kopfzeile) als CSV teilen – zum Bearbeiten oder als Vorlage. */
    fun shareHistoryCsv() {
        viewModelScope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    val dir = File(getApplication<Application>().cacheDir, "exports").apply { mkdirs() }
                    File(dir, "Fruehere-Anlage.csv").also { it.writeText(HistoryCsv.format(legacyData)) }
                }
                _events.tryEmit(UiEvent.Share(file, "text/csv"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message("Export fehlgeschlagen: ${e.message}")
            }
        }
    }

    // ---------- Ladelog der Wallbox ----------
    var chargeMonth by mutableStateOf(java.time.YearMonth.now())
        private set
    var chargeLog by mutableStateOf<ChargeMonth?>(null)
        private set
    var chargeLoading by mutableStateOf(false)
        private set
    var chargeError by mutableStateOf<String?>(null)
        private set
    private val chargeCache = mutableMapOf<java.time.YearMonth, ChargeMonth>()
    private var chargeJob: Job? = null

    fun shiftChargeMonth(delta: Int) {
        val next = chargeMonth.plusMonths(delta.toLong())
        if (next.isAfter(java.time.YearMonth.now())) return
        chargeMonth = next
        loadChargeLog()
    }

    /** Ladevorgänge eines Monats aus den stündlichen Messwerten der SENEC-Cloud. */
    fun loadChargeLog(force: Boolean = false) {
        if (!settings.value.hasCloud) return
        val month = chargeMonth
        val finished = month.isBefore(java.time.YearMonth.now())
        if (!force && finished) chargeCache[month]?.let { chargeLog = it; chargeError = null; return }
        chargeJob?.cancel()
        chargeJob = viewModelScope.launch {
            chargeLoading = true
            chargeError = null
            chargeLog = chargeCache[month]
            try {
                val zone = java.time.ZoneId.systemDefault()
                val from = month.atDay(1).atStartOfDay(zone).toInstant()
                val to = minOf(month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant(), java.time.Instant.now())
                val points = try {
                    cloud.measurements("HOUR", from, to).points
                } catch (e: de.gun642.pvdashboard.data.HttpException) {
                    // Falls ein ganzer Monat in Stundenauflösung abgelehnt wird: Tag für Tag
                    if (e.code !in 400..499 || e.code == 401) throw e
                    val days = mutableListOf<de.gun642.pvdashboard.senec.cloud.MeasurementPoint>()
                    var day = month.atDay(1)
                    while (day.atStartOfDay(zone).toInstant().isBefore(to)) {
                        days += cloud.measurements("HOUR", day.atStartOfDay(zone).toInstant(), day.plusDays(1).atStartOfDay(zone).toInstant()).points
                        day = day.plusDays(1)
                    }
                    days
                }
                val result = ChargeMonth(ChargeLog.sessions(points, zone).sortedByDescending { it.start })
                chargeCache[month] = result
                if (chargeMonth == month) chargeLog = result
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                chargeError = "Ladevorgänge konnten nicht geladen werden: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                chargeLoading = false
            }
        }
    }

    fun chargePdfName(month: java.time.YearMonth = chargeMonth) = "Ladebericht-$month.pdf"

    private fun writeChargePdf(out: java.io.OutputStream, month: java.time.YearMonth, log: ChargeMonth) {
        val s = settings.value
        ChargePdf.write(getApplication<Application>(), out, month, log, s.pricePerKwhCent, s.feedInCent)
    }

    fun exportChargePdf(uri: android.net.Uri) {
        val month = chargeMonth
        val log = chargeLog ?: return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.use { writeChargePdf(it, month, log) }
                        ?: throw java.io.IOException("Datei nicht beschreibbar")
                }
                message("PDF gespeichert")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message("PDF-Export fehlgeschlagen: ${e.message}")
            }
        }
    }

    fun shareChargePdf() {
        val month = chargeMonth
        val log = chargeLog ?: return
        viewModelScope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    val dir = File(getApplication<Application>().cacheDir, "exports").apply { mkdirs() }
                    File(dir, chargePdfName(month)).also { f -> f.outputStream().use { writeChargePdf(it, month, log) } }
                }
                _events.tryEmit(UiEvent.Share(file, "application/pdf"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message("PDF-Export fehlgeschlagen: ${e.message}")
            }
        }
    }

    // ---------- Stromabschlag ----------
    var advanceCheck by mutableStateOf<AdvanceCheck?>(null)
        private set
    var advanceLoading by mutableStateOf(false)
        private set
    var advanceError by mutableStateOf<String?>(null)
        private set

    /** Wasser-Abschlag aus den Einstellungen, sonst aus den Finanzposten der Kategorie „Wasser“. */
    val waterAdvance: Double
        get() = settings.value.waterAdvance.takeIf { it > 0 }
            ?: contracts.filter { it.flow == FlowType.EXPENSE && it.category == ContractCategory.WATER }.sumOf { it.monthlyCost }

    /**
     * Wasser-Abschlag hochrechnen – aus den Zählerständen, ohne Netzabruf. Monate, die noch nicht
     * vollständig abgelesen sind, werden geschätzt (Vorjahresmonat bzw. Durchschnitt).
     */
    fun waterAdvanceCheck(): AdvanceCheck? {
        val s = settings.value
        val readings = readings(MeterType.WATER)
        val price = s.waterPricePerM3 + s.wastewaterPerM3
        if (waterAdvance <= 0 || price <= 0 || readings.size < 2) return null
        val lastReading = readings.maxOf { it.date }
        val usage = de.gun642.pvdashboard.meters.Consumption.monthly(readings)
            .filterKeys { !it.atEndOfMonth().isAfter(lastReading.minusDays(1)) }
        return AdvanceCheck.of(s.waterBillingStartMonth, usage, price, s.waterBaseFeePerMonth, waterAdvance)
    }

    /** Abschlag aus den Einstellungen, sonst aus den Finanzposten der Kategorie „Strom“. */
    val powerAdvance: Double
        get() = settings.value.powerAdvance.takeIf { it > 0 }
            ?: contracts.filter { it.flow == FlowType.EXPENSE && it.category == ContractCategory.POWER }.sumOf { it.monthlyCost }

    /** Netzbezug je Monat aus der SENEC-Cloud laden und den Abschlag hochrechnen. */
    fun loadAdvanceCheck() {
        val s = settings.value
        if (!s.hasCloud || !s.hasTariff || powerAdvance <= 0 || advanceLoading) return
        viewModelScope.launch {
            advanceLoading = true
            advanceError = null
            try {
                val today = java.time.LocalDate.now()
                val start = AdvanceCheck.currentPeriodStart(s.billingStartMonth, today)
                val imports = mutableMapOf<java.time.YearMonth, Double>()
                for (year in (start.year - 1)..today.year) {
                    val r = try {
                        stats.load(Period(PeriodType.YEAR, java.time.LocalDate.of(year, 1, 1)))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Vorjahr vor Inbetriebnahme fehlt – dann eben ohne
                        if (year == today.year) throw e else continue
                    }
                    r.buckets.forEach { b -> imports[java.time.YearMonth.of(year, b.index)] = b.totals.gridImport }
                }
                // Monate nach heute sind noch leer und zählen nicht als gemessen
                val current = java.time.YearMonth.from(today)
                imports.keys.removeAll { it.isAfter(current) }
                advanceCheck = AdvanceCheck.of(s.billingStartMonth, imports, s.pricePerKwhCent / 100, s.baseFeePerMonth, powerAdvance, today)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                advanceError = "Netzbezug konnte nicht geladen werden: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                advanceLoading = false
            }
        }
    }

    fun financeSummary(year: Int = financeYear): FinanceSummary = FinanceSummary.of(contracts, year)

    fun financePdfName(year: Int = financeYear) = "Einnahmen-Ausgaben-$year.pdf"

    private fun writeFinancePdf(out: java.io.OutputStream, year: Int) =
        FinancePdf.write(getApplication<Application>(), out, financeSummary(year), contracts)

    /** PDF an einem selbst gewählten Ort speichern. */
    fun exportFinancePdf(uri: android.net.Uri) {
        val year = financeYear
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.use { writeFinancePdf(it, year) }
                        ?: throw java.io.IOException("Datei nicht beschreibbar")
                }
                message("PDF gespeichert")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message("PDF-Export fehlgeschlagen: ${e.message}")
            }
        }
    }

    /** PDF erzeugen und über das Teilen-Menü weitergeben (Mail, Messenger, Drive …). */
    fun shareFinancePdf() {
        val year = financeYear
        viewModelScope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    val dir = File(getApplication<Application>().cacheDir, "exports").apply { mkdirs() }
                    File(dir, financePdfName(year)).also { f -> f.outputStream().use { writeFinancePdf(it, year) } }
                }
                _events.tryEmit(UiEvent.Share(file, "application/pdf"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message("PDF-Export fehlgeschlagen: ${e.message}")
            }
        }
    }

    private fun persistContracts() {
        val snapshot = contracts
        viewModelScope.launch(Dispatchers.IO) { contractStore.save(snapshot) }
    }

    fun deleteReading(type: MeterType, reading: MeterReading) {
        updateReadings(type, readings(type) - reading)
    }

    fun importMeterCsv(type: MeterType, uri: android.net.Uri) {
        viewModelScope.launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                } ?: throw java.io.IOException("Datei nicht lesbar")
                val imported = MeterCsv.parse(text)
                if (imported.isEmpty()) {
                    message("Keine Zählerstände in der Datei gefunden")
                    return@launch
                }
                updateReadings(type, MeterCsv.merge(readings(type), imported))
                message("${type.label}: ${imported.size} Zählerstände importiert")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message("Import fehlgeschlagen: ${e.message}")
            }
        }
    }

    fun exportMeterCsv(type: MeterType) {
        viewModelScope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    val dir = File(getApplication<Application>().cacheDir, "exports").apply { mkdirs() }
                    val stamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
                    File(dir, "Export_${type.label}_$stamp.csv").apply { writeText(MeterCsv.format(readings(type))) }
                }
                _events.tryEmit(UiEvent.Share(file, "text/csv"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message("Export fehlgeschlagen: ${e.message}")
            }
        }
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

    /** Derselbe Zeitraum im Vorjahr zum Vergleich (falls vorhanden). */
    var statsCompare by mutableStateOf<StatsResult?>(null)
        private set

    /** Zusätzlich zur PV-Erzeugung im Diagramm gezeigte Reihen. */
    var chartSeries by mutableStateOf(setOf(EnergySeries.GRID_IMPORT))
        private set
    var showAutarky by mutableStateOf(true)
        private set

    fun toggleChartSeries(series: EnergySeries) {
        chartSeries = if (series in chartSeries) chartSeries - series else chartSeries + series
    }

    fun toggleAutarky() { showAutarky = !showAutarky }

    /** Akku-Ladestand als Linie im Statistik-Diagramm */
    var showSoc by mutableStateOf(true)
        private set

    fun toggleSoc() { showSoc = !showSoc }

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
            // Vorjahr nachladen; fehlt es (vor Inbetriebnahme), bleibt es beim PVGIS-Vergleich.
            val previous = YearCompare.previousPeriod(requested) ?: return@launch
            if (statsCompare?.period == previous) return@launch
            try {
                val result = stats.load(previous)
                if (requested == period) statsCompare = result
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Kein Vorjahresvergleich möglich
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
                // Widget „PV-Prognose“ gleich mit aktualisieren
                forecast?.let { f ->
                    val app = getApplication<Application>()
                    withContext(Dispatchers.IO) { ForecastCache.save(app, f) }
                    ForecastWidget.updateAll(app)
                }
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
                    updateStatus = "VOID Home Dashboard ist aktuell"
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
        val target = File(getApplication<Application>().cacheDir, "updates/VOID-Home-Dashboard-${release.version}.apk")
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
