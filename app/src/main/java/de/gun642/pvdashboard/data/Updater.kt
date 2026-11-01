package de.gun642.pvdashboard.data

import org.json.JSONArray
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class Release(
    val version: String,
    val title: String,
    val notes: String,
    val publishedAt: String,
    val apkUrl: String?,
    val apkSize: Long,
    /** Release-Seite auf GitHub (Fallback, falls keine APK angehängt ist) */
    val pageUrl: String = "",
)

/**
 * Prüft die GitHub-Releases dieser App. Heruntergeladen und installiert wird über den Browser –
 * die App selbst installiert nichts (keine Berechtigung „Apps installieren“).
 */
object Updater {
    private const val REPO = "GUN642/PV-Dashboard"
    private const val API = "https://api.github.com/repos/$REPO/releases?per_page=30"

    fun parts(version: String): List<Int> =
        version.trim().removePrefix("v").split('.', '-').mapNotNull { it.toIntOrNull() }

    fun isNewer(candidate: String, current: String): Boolean {
        val a = parts(candidate)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun open(url: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "PV-Dashboard")
        return conn
    }

    /** Alle veröffentlichten Versionen (ohne Vorabversionen), neueste zuerst. */
    fun fetchReleases(): List<Release> {
        val conn = open(API)
        try {
            if (conn.responseCode != 200) throw IOException("GitHub antwortet mit ${conn.responseCode}")
            val json = JSONArray(conn.inputStream.bufferedReader().use { it.readText() })
            val out = ArrayList<Release>()
            for (i in 0 until json.length()) {
                val r = json.getJSONObject(i)
                if (r.optBoolean("draft") || r.optBoolean("prerelease")) continue
                val tag = r.optString("tag_name")
                if (parts(tag).isEmpty()) continue
                val assets = r.optJSONArray("assets") ?: JSONArray()
                var apkUrl: String? = null
                var apkSize = 0L
                for (j in 0 until assets.length()) {
                    val a = assets.getJSONObject(j)
                    if (a.optString("name").endsWith(".apk")) {
                        apkUrl = a.optString("browser_download_url")
                        apkSize = a.optLong("size")
                        break
                    }
                }
                out += Release(
                    version = tag.removePrefix("v"),
                    title = r.optString("name").ifBlank { tag },
                    notes = cleanNotes(r.optString("body")),
                    publishedAt = r.optString("published_at"),
                    apkUrl = apkUrl,
                    apkSize = apkSize,
                    pageUrl = r.optString("html_url"),
                )
            }
            return out.sortedWith { x, y -> if (isNewer(x.version, y.version)) -1 else if (isNewer(y.version, x.version)) 1 else 0 }
        } finally {
            conn.disconnect()
        }
    }

    private fun cleanNotes(body: String): String = body.lines()
        .filterNot {
            it.startsWith("Co-Authored-By", ignoreCase = true) || it.startsWith("Claude-Session") ||
                it.startsWith("**Installation") || it.startsWith("Beim ersten Mal") || it.startsWith("Branch:")
        }
        .joinToString("\n").trim()
}
