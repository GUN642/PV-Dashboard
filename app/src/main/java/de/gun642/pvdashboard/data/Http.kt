package de.gun642.pvdashboard.data

import java.io.IOException
import java.net.CookieManager
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder

class HttpResponse(val code: Int, val body: String, val headers: Map<String, List<String>>) {
    fun header(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()
}

class HttpException(val code: Int, message: String) : IOException(message)

/** Schlanker HTTP-Client auf Basis von HttpURLConnection, optional mit eigenem Cookie-Speicher. */
class Http(private val cookies: CookieManager? = null) {

    fun request(
        url: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
        contentType: String? = null,
        followRedirects: Boolean = true,
        connectTimeoutMs: Int = 10_000,
        readTimeoutMs: Int = 20_000,
    ): HttpResponse {
        val uri = URI(url)
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = followRedirects
            connection.requestMethod = method
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
            cookies?.get(uri, emptyMap())?.forEach { (k, v) ->
                if (v.isNotEmpty()) connection.setRequestProperty(k, v.joinToString("; "))
            }
            if (body != null) {
                connection.doOutput = true
                contentType?.let { connection.setRequestProperty("Content-Type", it) }
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            val responseHeaders = connection.headerFields
                .filterKeys { it != null }
                .mapKeys { it.key!! }
            cookies?.put(uri, responseHeaders)
            val stream = if (code >= 400) connection.errorStream else connection.inputStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            return HttpResponse(code, text, responseHeaders)
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

        fun form(fields: Map<String, String>): String =
            fields.entries.joinToString("&") { (k, v) -> "${encode(k)}=${encode(v)}" }
    }
}
