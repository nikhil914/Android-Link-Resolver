package com.nik.linkresolver.data

import android.content.Context
import androidx.core.content.edit
import com.nik.linkresolver.engine.DomainStore
import com.nik.linkresolver.engine.SiteTuning
import com.nik.linkresolver.engine.TuningRule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Result of the last health check for one site. */
data class SiteHealth(val site: String, val checkedAt: Long, val ok: Boolean, val message: String, val testUrl: String)

/** Persists learned/added shortener domains, per-site wait tuning and health-check results. */
class SiteStore(context: Context) {
    private val prefs = context.getSharedPreferences("sites", Context.MODE_PRIVATE)

    private val _domains = MutableStateFlow(readDomains())
    /** User-added and learned shortener domains (built-in ones are not included). */
    val domainsFlow: StateFlow<Set<String>> = _domains

    val domains: DomainStore = object : DomainStore {
        override fun hosts(): Set<String> = _domains.value
        override fun learn(host: String) = addDomain(host)
    }

    private fun readDomains(): Set<String> = prefs.getStringSet("domains", emptySet()).orEmpty().toSet()

    @Synchronized
    fun addDomain(raw: String) {
        val host = normalizeHost(raw) ?: return
        val next = _domains.value + host
        if (next == _domains.value) return
        prefs.edit { putStringSet("domains", next) }
        _domains.value = next
    }

    @Synchronized
    fun removeDomain(host: String) {
        val next = _domains.value - host
        prefs.edit { putStringSet("domains", next) }
        _domains.value = next
    }

    // ---- wait tuning

    val tuning: SiteTuning = TuningRule(
        read = { site -> prefs.getLong("tune_extra_$site", 0) to prefs.getInt("tune_clean_$site", 0) },
        write = { site, extra, clean -> prefs.edit { putLong("tune_extra_$site", extra); putInt("tune_clean_$site", clean) } },
    )

    fun tunedSites(): Map<String, Long> = prefs.all.filterKeys { it.startsWith("tune_extra_") }
        .mapNotNull { (k, v) -> (v as? Long)?.takeIf { it > 0 }?.let { k.removePrefix("tune_extra_") to it } }.toMap()

    fun resetTuning() = prefs.edit { prefs.all.keys.filter { it.startsWith("tune_") }.forEach { remove(it) } }

    // ---- health

    private val _health = MutableStateFlow(readHealth())
    val health: StateFlow<List<SiteHealth>> = _health

    private fun readHealth(): List<SiteHealth> = prefs.getStringSet("health", emptySet()).orEmpty().mapNotNull { line ->
        val p = line.split('\t')
        if (p.size < 5) null else SiteHealth(p[0], p[1].toLongOrNull() ?: 0, p[2] == "1", p[3], p[4])
    }.sortedBy { it.site }

    @Synchronized
    fun putHealth(h: SiteHealth) {
        val list = _health.value.filter { it.site != h.site } + h
        prefs.edit {
            putStringSet("health", list.map { listOf(it.site, it.checkedAt, if (it.ok) "1" else "0", it.message.replace('\t', ' ').replace('\n', ' '), it.testUrl).joinToString("\t") }.toSet())
        }
        _health.value = list.sortedBy { it.site }
    }

    companion object {
        /** "https://www.Foo.io/abc" or "foo.io" → "foo.io"; null if it isn't a host name. */
        fun normalizeHost(raw: String): String? {
            val h = raw.trim().lowercase().removePrefix("https://").removePrefix("http://").substringBefore('/')
                .substringBefore('?').substringBefore(':').removePrefix("www.")
            return h.takeIf { it.contains('.') && it.all { c -> c.isLetterOrDigit() || c == '.' || c == '-' } }
        }
    }
}
