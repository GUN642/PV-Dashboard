package de.gun642.pvdashboard.senec

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/** Liest Live-Werte lokal im Heimnetz über `lala.cgi` (SENEC.Home V2.x/V3). */
class SenecClient(host: String, useHttps: Boolean, private val connectTimeoutMs: Int = 5_000) {

    private val url = URL("${if (useHttps) "https" else "http"}://${normalizeHost(host)}/lala.cgi")

    suspend fun fetch(wallboxIndex: Int): SenecSnapshot = withContext(Dispatchers.IO) {
        val body = post(REQUEST.toString())
        @Suppress("UNCHECKED_CAST")
        val decoded = LalaDecoder.decode(toKotlin(JSONObject(body))) as Map<String, Any?>
        SenecSnapshot.from(decoded, wallboxIndex, System.currentTimeMillis())
    }

    private fun post(json: String): String {
        val connection = url.openConnection() as HttpURLConnection
        try {
            if (connection is HttpsURLConnection) {
                // Der Speicher nutzt ein selbstsigniertes Zertifikat. Nur für diese Verbindung.
                connection.sslSocketFactory = trustAllSocketFactory
                connection.setHostnameVerifier { _, _ -> true }
            }
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = 10_000
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(json.toByteArray()) }
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw IOException("Speicher antwortet mit HTTP $code")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        /** Abgefragte Variablen. Wallbox-Werte sind Listen mit einem Eintrag je Wallbox. */
        private val REQUEST: JSONObject
            get() = JSONObject().apply {
                put("ENERGY", fields(
                    "STAT_STATE", "GUI_INVERTER_POWER", "GUI_HOUSE_POW", "GUI_GRID_POW",
                    "GUI_BAT_DATA_POWER", "GUI_BAT_DATA_FUEL_CHARGE",
                    "GUI_BAT_DATA_VOLTAGE", "GUI_BAT_DATA_CURRENT",
                ))
                put("PV1", fields("MPP_POWER", "POWER_RATIO"))
                put("TEMPMEASURE", fields("BATTERY_TEMP", "CASE_TEMP", "MCU_TEMP"))
                put("WALLBOX", fields(
                    "APPARENT_CHARGING_POWER", "L1_CHARGING_CURRENT", "L2_CHARGING_CURRENT",
                    "L3_CHARGING_CURRENT", "EV_CONNECTED", "STATE",
                ))
            }

        private fun fields(vararg names: String) = JSONObject().apply { names.forEach { put(it, "") } }

        /** Erlaubt Eingaben wie "https://192.168.178.50/" und macht daraus "192.168.178.50". */
        fun normalizeHost(input: String): String =
            input.trim()
                .removePrefix("https://").removePrefix("http://")
                .substringBefore('/')

        fun toKotlin(value: Any?): Any? = when (value) {
            is JSONObject -> value.keys().asSequence().associateWith { toKotlin(value.opt(it)) }
            is JSONArray -> (0 until value.length()).map { toKotlin(value.opt(it)) }
            JSONObject.NULL -> null
            else -> value
        }

        private val trustAllSocketFactory: SSLSocketFactory by lazy {
            val trustAll = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            SSLContext.getInstance("TLS").apply {
                init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
            }.socketFactory
        }
    }
}
