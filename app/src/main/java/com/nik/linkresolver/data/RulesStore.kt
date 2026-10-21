package com.nik.linkresolver.data

import android.content.Context
import com.nik.linkresolver.BuildConfig
import com.nik.linkresolver.engine.DomainStore
import com.nik.linkresolver.engine.ResolverRegistry
import com.nik.linkresolver.engine.SiteRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Site rules from `rules/rules.json` in the GitHub repo (HTTPS, not signed), checked once a day.
 * The newest usable file wins: downloaded (cached) or the copy bundled in the APK. A file that doesn't parse, is
 * older than what is in use, or needs a newer app is ignored and the current rules stay.
 */
class RulesStore(private val context: Context, private val registry: ResolverRegistry, private val learned: DomainStore) {
    data class Status(
        val version: Int,
        val source: String, // "bundled" or "downloaded"
        val updated: String,
        val checkedAt: Long,
        val message: String,
        val sites: Int,
    )

    private val prefs = context.getSharedPreferences("rules", Context.MODE_PRIVATE)
    private val cache = File(context.filesDir, "rules.json")
    private val lock = Mutex()
    private val _status = MutableStateFlow(Status(0, "built-in", "", 0, "", 0))
    val status: StateFlow<Status> = _status

    @Volatile private var current: SiteRules? = null

    /** Applies the best local rules (cache or bundled). Cheap; call at startup. */
    fun load() {
        val bundled = runCatching { SiteRules.parse(context.assets.open("rules.json").bufferedReader().readText()) }.getOrNull()
        val cached = runCatching { if (cache.exists()) SiteRules.parse(cache.readText()) else null }.getOrNull()
            ?.takeIf { it.minAppVersion <= BuildConfig.VERSION_CODE }
        val best = listOfNotNull(cached, bundled).maxByOrNull { it.version } ?: return
        apply(best, if (best === cached) "downloaded" else "bundled", prefs.getString("message", "") ?: "")
    }

    fun dueForCheck(now: Long = System.currentTimeMillis()) = now - prefs.getLong("checkedAt", 0) > 24 * 3_600_000L

    /** Downloads the rules from GitHub and applies them if newer. Returns a short message. */
    suspend fun refresh(): String = lock.withLock {
        val msg = withContext(Dispatchers.IO) {
            try {
                val req = Request.Builder().url(URL).header("Cache-Control", "no-cache").build()
                val text = client.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) return@withContext "Download failed (HTTP ${r.code})"
                    val body = r.body.string()
                    if (body.length > MAX_BYTES) return@withContext "File too large"
                    body
                }
                val rules = try {
                    SiteRules.parse(text)
                } catch (e: IllegalArgumentException) {
                    return@withContext "Ignored a bad file: ${e.message}"
                }
                val inUse = current?.version ?: 0
                when {
                    rules.minAppVersion > BuildConfig.VERSION_CODE -> "Rules v${rules.version} need a newer app — update the app"
                    rules.version < inUse -> "Up to date (v$inUse)"
                    rules.version == inUse -> "Up to date (v$inUse)"
                    else -> {
                        cache.writeText(text)
                        apply(rules, "downloaded", "")
                        "Updated to v${rules.version}"
                    }
                }
            } catch (e: Exception) {
                "Download failed: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        val now = System.currentTimeMillis()
        prefs.edit().putLong("checkedAt", now).putString("message", msg).apply()
        _status.value = _status.value.copy(checkedAt = now, message = msg)
        msg
    }

    /** Forgets downloaded rules and goes back to the bundled ones. */
    fun reset() {
        cache.delete()
        current = null
        prefs.edit().remove("message").apply()
        load()
    }

    private fun apply(rules: SiteRules, source: String, message: String) {
        registry.replaceWith(rules.registry(learned))
        current = rules
        _status.value = Status(
            rules.version, source, rules.updated, prefs.getLong("checkedAt", 0), message,
            rules.sites.size,
        )
    }

    companion object {
        const val URL = "https://raw.githubusercontent.com/nikhil914/Android-Link-Resolver/main/rules/rules.json"
        private const val MAX_BYTES = 256 * 1024
        private val client by lazy {
            OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).followRedirects(true).build()
        }
    }
}
