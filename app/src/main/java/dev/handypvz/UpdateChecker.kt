package dev.handypvz

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class AppUpdate(val version: String, val url: String)

/**
 * Checks the latest GitHub release once a day (MSK) and reports a newer version.
 * Source of truth is the canonical `v<versionName>` tag created by scripts/release.sh.
 */
object UpdateChecker {

    private const val TAG = "UpdateChecker"
    private const val LATEST_RELEASE_URL = "https://api.github.com/repos/timsoid/handyPVZ/releases/latest"

    /** Returns the newer release when one is available, otherwise null. Never throws. */
    suspend fun check(context: Context): AppUpdate? {
        if (!CodeStore.shouldCheckToday(context)) return null
        return withContext(Dispatchers.IO) { fetchLatest() }
            ?.takeIf { isNewerVersion(it.version, BuildConfig.VERSION_NAME) }
    }

    private fun fetchLatest(): AppUpdate? {
        return try {
            val connection = URL(LATEST_RELEASE_URL).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000
                connection.requestMethod = "GET"
                connection.setRequestProperty("Accept", "application/vnd.github+json")
                if (connection.responseCode !in 200..299) return null
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(body)
                val tag = json.optString("tag_name").removePrefix("v")
                val url = json.optString("html_url")
                if (tag.isBlank() || url.isBlank()) null else AppUpdate(tag, url)
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            Log.d(TAG, "update check failed: ${e.message}")
            null
        }
    }

}

/** Numeric per-dot comparison; a non-numeric part counts as 0. */
fun isNewerVersion(latest: String, current: String): Boolean {
    val a = latest.split('.')
    val b = current.split('.')
    for (i in 0 until maxOf(a.size, b.size)) {
        val x = a.getOrNull(i)?.toIntOrNull() ?: 0
        val y = b.getOrNull(i)?.toIntOrNull() ?: 0
        if (x != y) return x > y
    }
    return false
}
