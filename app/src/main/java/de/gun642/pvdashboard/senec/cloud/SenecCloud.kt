package de.gun642.pvdashboard.senec.cloud

import android.content.Context
import android.util.Base64
import de.gun642.pvdashboard.data.Http
import de.gun642.pvdashboard.data.HttpException
import de.gun642.pvdashboard.senec.LiveSource
import de.gun642.pvdashboard.senec.SenecClient
import de.gun642.pvdashboard.senec.SenecSnapshot
import de.gun642.pvdashboard.stats.EnergyTotals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.CookieManager
import java.net.CookiePolicy
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant

class SenecCloudException(message: String) : IOException(message)

data class CloudDashboard(val snapshot: SenecSnapshot, val today: EnergyTotals?)

data class MeasurementPoint(val start: Instant, val durationSeconds: Long, val values: Map<String, Double>)

data class MeasurementSeries(val points: List<MeasurementPoint>, val rawJson: String)

/**
 * Zugriff auf die Daten der SENEC-App (funktioniert von überall, auch unterwegs).
 *
 * Anmeldung per OpenID Connect (Keycloak auf sso.senec.com) mit PKCE wie in der offiziellen App.
 * Die Tokens werden im App-Speicher abgelegt und automatisch erneuert.
 */
class SenecCloud(context: Context, private val credentials: () -> Pair<String, String>?) {

    private val prefs = context.getSharedPreferences("senec_cloud", Context.MODE_PRIVATE)
    private val http = Http()
    private val tokenLock = Mutex()

    // ---------- Öffentliche API ----------

    /** Meldet neu an (z. B. nach Änderung der Zugangsdaten) und liefert die Anlagen-ID. */
    suspend fun login(): String = withContext(Dispatchers.IO) {
        tokenLock.withLock {
            clear()
            val (email, password) = credentials() ?: throw SenecCloudException("Keine SENEC-Zugangsdaten hinterlegt.")
            performLogin(email, password)
        }
        systemId()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    suspend fun dashboard(): CloudDashboard = withContext(Dispatchers.IO) {
        val json = JSONObject(appGet("$MEASURE/measurements/api/v1/systems/${systemId()}/dashboard"))
        val now = json.optJSONObject("currently") ?: JSONObject()
        val today = json.optJSONObject("today")
        fun d(o: JSONObject, key: String): Double? = if (o.has(key) && !o.isNull(key)) o.optDouble(key) else null

        val timestamp = runCatching { Instant.parse(json.optString("timestamp")).toEpochMilli() }
            .getOrElse { System.currentTimeMillis() }
        @Suppress("UNCHECKED_CAST")
        val raw = SenecClient.toKotlin(json) as Map<String, Any?>
        val snapshot = SenecSnapshot(
            timestamp = timestamp,
            pvW = d(now, "powerGenerationInW"),
            houseW = d(now, "powerConsumptionInW"),
            gridW = diff(d(now, "gridDrawInW"), d(now, "gridFeedInInW")),
            batteryW = diff(d(now, "batteryChargeInW"), d(now, "batteryDischargeInW")),
            batterySoc = d(now, "batteryLevelInPercent"),
            batteryTemp = null,
            wallboxW = d(now, "wallboxInW"),
            wallboxCarConnected = if (json.has("electricVehicleConnected")) json.optBoolean("electricVehicleConnected") else null,
            raw = raw,
            source = LiveSource.CLOUD,
        )
        val todayTotals = today?.let {
            EnergyTotals(
                pv = (d(it, "powerGenerationInWh") ?: 0.0) / 1000,
                consumption = (d(it, "powerConsumptionInWh") ?: 0.0) / 1000,
                gridImport = (d(it, "gridDrawInWh") ?: 0.0) / 1000,
                gridExport = (d(it, "gridFeedInInWh") ?: 0.0) / 1000,
                batteryCharge = (d(it, "batteryChargeInWh") ?: 0.0) / 1000,
                batteryDischarge = (d(it, "batteryDischargeInWh") ?: 0.0) / 1000,
                wallbox = (d(it, "wallboxInWh") ?: 0.0) / 1000,
            )
        }
        CloudDashboard(snapshot, todayTotals)
    }

    /**
     * Energiewerte (kWh) je Intervall. [resolution] z. B. FIVE_MINUTES, HOUR, DAY, MONTH.
     */
    suspend fun measurements(resolution: String, from: Instant, to: Instant): MeasurementSeries =
        withContext(Dispatchers.IO) {
            val wallboxes = wallboxIds()
            val url = buildString {
                append("$MEASURE/measurements/api/v1/systems/${systemId()}/measurements")
                append("?resolution=").append(resolution)
                append("&from=").append(Http.encode(from.toString()))
                append("&to=").append(Http.encode(to.toString()))
                if (wallboxes.isNotEmpty()) append("&wallboxIds=").append(Http.encode(wallboxes.joinToString(",")))
            }
            val body = appGet(url)
            MeasurementSeries(parseMeasurements(body), body)
        }

    /** Beginn der Datenaufzeichnung der Anlage. */
    suspend fun dataStart(): Instant? = withContext(Dispatchers.IO) {
        if (prefs.contains("data_start")) return@withContext Instant.ofEpochMilli(prefs.getLong("data_start", 0))
        val json = JSONObject(appGet("$MEASURE/measurements/api/v1/systems/${systemId()}/data-availability/timespan?timezone=UTC"))
        if (!json.has("periodStartDateInMilliseconds")) return@withContext null
        val start = json.getLong("periodStartDateInMilliseconds")
        prefs.edit().putLong("data_start", start).apply()
        Instant.ofEpochMilli(start)
    }

    // ---------- Anlage & Wallbox ----------

    private suspend fun systemId(): String {
        prefs.getString("system_id", null)?.let { return it }
        val systems = JSONArray(appGet("$SYSTEMS/systems/api/v1"))
        if (systems.length() == 0) throw SenecCloudException("Im SENEC-Konto wurde keine Anlage gefunden.")
        val system = systems.getJSONObject(0)
        val id = system.opt("id")?.toString() ?: throw SenecCloudException("Anlagen-ID fehlt in der Antwort.")
        prefs.edit().putString("system_id", id).apply()
        return id
    }

    private suspend fun wallboxIds(): List<String> {
        if (prefs.contains("wallbox_ids")) {
            return prefs.getString("wallbox_ids", "").orEmpty().split(',').filter { it.isNotBlank() }
        }
        val ids = runCatching {
            val body = JSONObject().put("systemIds", JSONArray().put(systemId())).toString()
            val result = JSONArray(appRequest("$WALLBOX/wallbox/api/v1/systems/wallboxes/search", "POST", body))
            (0 until result.length()).mapNotNull { result.optJSONObject(it)?.optString("id")?.takeIf { id -> id.isNotBlank() } }
        }.getOrDefault(emptyList())
        prefs.edit().putString("wallbox_ids", ids.joinToString(",")).apply()
        return ids
    }

    // ---------- HTTP mit Token ----------

    private suspend fun appGet(url: String): String = appRequest(url, "GET", null)

    private suspend fun appRequest(url: String, method: String, jsonBody: String?): String {
        suspend fun send(token: String) = http.request(
            url,
            method = method,
            headers = APP_HEADERS + ("Authorization" to "Bearer $token") + ("Accept" to "application/json"),
            body = jsonBody,
            contentType = if (jsonBody != null) "application/json" else null,
        )
        var res = send(accessToken())
        if (res.code == 401) {
            prefs.edit().remove("access_token").apply()
            res = send(accessToken())
        }
        if (res.code !in 200..299) throw HttpException(res.code, "SENEC-Server antwortet mit HTTP ${res.code}")
        return res.body
    }

    private suspend fun accessToken(): String = tokenLock.withLock {
        val now = System.currentTimeMillis() / 1000
        prefs.getString("access_token", null)?.let { token ->
            if (prefs.getLong("expires_at", 0) > now + 30) return@withLock token
        }
        val refresh = prefs.getString("refresh_token", null)
        if (refresh != null && prefs.getLong("refresh_expires_at", 0) > now + 30) {
            val refreshed = runCatching {
                tokenRequest(mapOf("grant_type" to "refresh_token", "refresh_token" to refresh, "client_id" to CLIENT_ID))
            }
            if (refreshed.isSuccess) return@withLock prefs.getString("access_token", null)!!
        }
        val (email, password) = credentials() ?: throw SenecCloudException("Keine SENEC-Zugangsdaten hinterlegt.")
        performLogin(email, password)
        prefs.getString("access_token", null) ?: throw SenecCloudException("Anmeldung fehlgeschlagen.")
    }

    // ---------- Anmeldung ----------

    private fun performLogin(email: String, password: String) {
        val web = Http(CookieManager(null, CookiePolicy.ACCEPT_ALL))
        val verifier = randomString(64)
        val challenge = base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        val authUrl = "$SSO/auth?redirect_uri=${Http.encode(REDIRECT_URI)}&client_id=$CLIENT_ID" +
            "&response_type=code&prompt=login&state=${randomString(22)}&nonce=${randomString(22)}" +
            "&scope=${Http.encode(SCOPE)}&code_challenge=$challenge&code_challenge_method=S256"

        val page = web.request(authUrl, headers = WEB_HEADERS)
        if (page.code != 200) throw SenecCloudException("SENEC-Anmeldeseite nicht erreichbar (HTTP ${page.code}).")

        var html = page.body
        var passwordSent = false
        var code: String? = null
        for (step in 0 until 4) {
            val form = LoginForm.parse(html)
                ?: throw SenecCloudException(LoginForm.error(html) ?: "Unerwartete Antwort der SENEC-Anmeldeseite.")
            if (form.hasOtp) throw SenecCloudException("Zwei-Faktor-Anmeldung ist im SENEC-Konto aktiv – das wird noch nicht unterstützt.")
            if (passwordSent && form.hasPassword) {
                throw SenecCloudException(LoginForm.error(html) ?: "E-Mail-Adresse oder Passwort falsch.")
            }
            val fields = buildMap {
                if (form.hasUsername) put("username", email)
                if (form.hasPassword) put("password", password)
            }
            if (fields.isEmpty()) throw SenecCloudException(LoginForm.error(html) ?: "Unerwartetes Anmeldeformular.")
            passwordSent = passwordSent || form.hasPassword

            val res = web.request(
                form.action,
                method = "POST",
                headers = WEB_HEADERS,
                body = Http.form(fields),
                contentType = "application/x-www-form-urlencoded",
                followRedirects = false,
            )
            when (res.code) {
                in 300..399 -> {
                    val location = res.header("Location") ?: throw SenecCloudException("Anmeldung fehlgeschlagen (keine Weiterleitung).")
                    if (!location.startsWith(REDIRECT_URI)) {
                        throw SenecCloudException("SENEC verlangt eine zusätzliche Bestätigung. Bitte einmal in der SENEC-App oder auf mein-senec.de anmelden.")
                    }
                    code = LoginForm.codeFromRedirect(location)
                    break
                }
                200 -> html = res.body
                else -> throw SenecCloudException("Anmeldung fehlgeschlagen (HTTP ${res.code}).")
            }
        }
        val authCode = code ?: throw SenecCloudException("Anmeldung fehlgeschlagen.")
        tokenRequest(
            mapOf(
                "code" to authCode,
                "grant_type" to "authorization_code",
                "redirect_uri" to REDIRECT_URI,
                "code_verifier" to verifier,
                "client_id" to CLIENT_ID,
            )
        )
        // Neue Anmeldung: Anlagen-Infos neu ermitteln.
        prefs.edit().remove("system_id").remove("wallbox_ids").remove("data_start").apply()
    }

    private fun tokenRequest(fields: Map<String, String>) {
        val res = http.request(
            "$SSO/token",
            method = "POST",
            headers = mapOf("Accept" to "application/json", "User-Agent" to SSO_USER_AGENT),
            body = Http.form(fields),
            contentType = "application/x-www-form-urlencoded",
        )
        if (res.code !in 200..299) throw SenecCloudException("Token-Abruf fehlgeschlagen (HTTP ${res.code}).")
        val json = JSONObject(res.body)
        val access = json.optString("access_token").takeIf { it.isNotBlank() }
            ?: throw SenecCloudException("Kein Zugriffstoken erhalten.")
        val now = System.currentTimeMillis() / 1000
        val refreshExpiresIn = json.optLong("refresh_expires_in", 0)
        prefs.edit()
            .putString("access_token", access)
            .putLong("expires_at", now + json.optLong("expires_in", 300))
            .putString("refresh_token", json.optString("refresh_token"))
            // 0 bedeutet bei Keycloak: läuft nicht ab (Offline-Token).
            .putLong("refresh_expires_at", if (refreshExpiresIn <= 0) Long.MAX_VALUE else now + refreshExpiresIn)
            .apply()
    }

    companion object {
        private const val SSO = "https://sso.senec.com/realms/senec/protocol/openid-connect"
        private const val CLIENT_ID = "endcustomer-app-frontend"
        private const val REDIRECT_URI = "senec-app-auth://keycloak.prod"
        private const val SCOPE = "roles profile meinsenec"
        private const val SYSTEMS = "https://senec-app-systems-proxy.prod.senec.dev"
        private const val MEASURE = "https://senec-app-measurements-proxy.prod.senec.dev"
        private const val WALLBOX = "https://senec-app-wallbox-proxy.prod.senec.dev"

        private const val WEB_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Mobile Safari/537.36"
        private const val SSO_USER_AGENT = "Dalvik/2.1.0 (Linux; U; Android 14; Pixel 8 Build/AP2A.240805.005)"
        private val WEB_HEADERS = mapOf(
            "User-Agent" to WEB_USER_AGENT,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        )
        private val APP_HEADERS = mapOf(
            "User-Agent" to "SENEC.App/4.8.1 (com.senecapp; build:1613; Android SDK 34; Model:Pixel 8) okhttp/4.12.0",
            "x-device-type" to "mobile",
        )

        private val random = SecureRandom()
        private const val ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

        private fun randomString(length: Int) = buildString { repeat(length) { append(ALPHABET[random.nextInt(ALPHABET.length)]) } }

        private fun base64Url(bytes: ByteArray) =
            Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

        private fun diff(positive: Double?, negative: Double?): Double? =
            if (positive == null && negative == null) null else (positive ?: 0.0) - (negative ?: 0.0)

        /** Zerlegt die Antwort des measurements-Endpunkts. Werte werden nach Namen zugeordnet. */
        fun parseMeasurements(body: String): List<MeasurementPoint> {
            val json = JSONObject(body)
            val names = json.optJSONArray("measurements") ?: return emptyList()
            val series = json.optJSONArray("timeSeries") ?: json.optJSONArray("timeseries") ?: return emptyList()
            return (0 until series.length()).mapNotNull { i ->
                val entry = series.optJSONObject(i) ?: return@mapNotNull null
                val start = runCatching { Instant.parse(entry.optString("date")) }.getOrNull() ?: return@mapNotNull null
                val m = entry.optJSONObject("measurements") ?: return@mapNotNull null
                val values = m.optJSONArray("values") ?: return@mapNotNull null
                val map = (0 until minOf(names.length(), values.length())).associate { j ->
                    names.optString(j) to values.optDouble(j, 0.0)
                }
                MeasurementPoint(start, m.optLong("durationInSeconds", 0), map)
            }
        }
    }
}
