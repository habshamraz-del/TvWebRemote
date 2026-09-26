package com.example.tvweb

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Blocks requests to known ad and tracker domains.
 *
 * Uses the Steven Black hosts list (MIT license, github.com/StevenBlack/hosts).
 * A copy ships inside the app (assets/adhosts.txt) so blocking works right away;
 * the app also downloads a fresh copy in the background once a week.
 */
object AdBlocker {
    private const val LIST_URL = "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts"
    private const val CACHE_FILE = "adhosts-latest.txt"
    private const val REFRESH_MS = 7L * 24 * 60 * 60 * 1000

    @Volatile
    private var blocked: Set<String> = emptySet()

    @Volatile
    private var started = false

    fun init(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        thread(name = "adblock-load") {
            val cache = File(app.filesDir, CACHE_FILE)
            blocked = if (cache.exists()) {
                parse(cache.readLines())
            } else {
                app.assets.open("adhosts.txt").bufferedReader().use { parse(it.readLines()) }
            }
            if (!cache.exists() || System.currentTimeMillis() - cache.lastModified() > REFRESH_MS) {
                download(cache)
            }
        }
    }

    private fun download(cache: File) {
        try {
            val conn = URL(LIST_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            if (conn.responseCode != 200) return
            val lines = conn.inputStream.bufferedReader().use { it.readLines() }
            val fresh = parse(lines)
            if (fresh.size < 1000) return // looks broken; keep what we have
            val tmp = File(cache.parentFile, "$CACHE_FILE.tmp")
            tmp.writeText(fresh.joinToString("\n"))
            tmp.renameTo(cache)
            blocked = fresh
        } catch (e: Exception) {
            // No internet or GitHub unreachable: keep using the current list.
        }
    }

    /** Accepts both "0.0.0.0 domain" hosts-file lines and plain "domain" lines. */
    private fun parse(lines: List<String>): Set<String> {
        val out = HashSet<String>(lines.size * 2)
        for (raw in lines) {
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) continue
            val parts = line.split(Regex("\\s+"))
            val domain = when {
                parts.size >= 2 && (parts[0] == "0.0.0.0" || parts[0] == "127.0.0.1") -> parts[1]
                parts.size == 1 -> parts[0]
                else -> continue
            }.lowercase()
            if (domain.contains('.') && domain != "0.0.0.0" && domain != "localhost") out.add(domain)
        }
        return out
    }

    /** True if the host, or any parent domain of it, is on the list. */
    fun isBlocked(host: String): Boolean {
        val set = blocked
        if (set.isEmpty()) return false
        var h = host.lowercase()
        while (true) {
            if (h in set) return true
            val dot = h.indexOf('.')
            if (dot < 0) return false
            h = h.substring(dot + 1)
            if (!h.contains('.')) return false
        }
    }
}

/**
 * Remembers each website's switches: "Block ads" and "Block pop-ups" (on unless turned off)
 * and "Mouse pointer" (off unless turned on).
 */
object SiteSettings {
    const val ADS = "ads"
    const val POPUPS = "popups"
    const val MOUSE = "mouse"

    private fun defaultFor(setting: String) = setting != MOUSE

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences("site_settings", Context.MODE_PRIVATE)

    fun siteKey(host: String?): String = (host ?: "").lowercase().removePrefix("www.")

    fun isOn(context: Context, host: String?, setting: String): Boolean =
        prefs(context).getBoolean("$setting:${siteKey(host)}", defaultFor(setting))

    fun set(context: Context, host: String?, setting: String, on: Boolean) {
        prefs(context).edit().putBoolean("$setting:${siteKey(host)}", on).apply()
    }
}
