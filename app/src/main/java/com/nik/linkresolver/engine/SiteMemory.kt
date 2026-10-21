package com.nik.linkresolver.engine

/**
 * Shortener domains the user added or the app learned (a link on that domain resolved with the generic resolver).
 * They count as supported: picked out of pasted text and Telegram posts, and resolved without asking.
 */
interface DomainStore {
    fun hosts(): Set<String>
    fun learn(host: String)

    fun contains(host: String): Boolean {
        val h = host.lowercase().removePrefix("www.")
        return hosts().any { h == it || h.endsWith(".$it") }
    }

    companion object {
        val NONE = object : DomainStore {
            override fun hosts(): Set<String> = emptySet()
            override fun learn(host: String) = Unit
        }
    }
}

/** In-memory [DomainStore] (tests, or before persistence is wired). */
class MemoryDomainStore(initial: Set<String> = emptySet()) : DomainStore {
    private val set = initial.map { it.lowercase() }.toMutableSet()
    @Synchronized override fun hosts(): Set<String> = set.toSet()
    @Synchronized override fun learn(host: String) { set += host.lowercase().removePrefix("www.") }
}

/**
 * Remembers per site how much longer than the default the server wants between a step page and its post.
 * Rejections push the extra wait up; runs without rejections slowly bring it back down.
 */
interface SiteTuning {
    fun extraWaitMs(site: String): Long
    fun onRejected(site: String)
    fun onCleanRun(site: String)

    companion object {
        val NONE = object : SiteTuning {
            override fun extraWaitMs(site: String) = 0L
            override fun onRejected(site: String) = Unit
            override fun onCleanRun(site: String) = Unit
        }
    }
}

/**
 * The tuning rule, storage-agnostic: +[stepUpMs] per rejection (capped at [maxExtraMs]); after [cleanRunsToEase]
 * clean runs in a row, −[stepDownMs] (floor 0). [read]/[write] persist (extraMs, cleanRuns) per site.
 */
class TuningRule(
    private val read: (site: String) -> Pair<Long, Int>,
    private val write: (site: String, extraMs: Long, cleanRuns: Int) -> Unit,
    private val stepUpMs: Long = 500,
    private val stepDownMs: Long = 250,
    private val maxExtraMs: Long = 10_000,
    private val cleanRunsToEase: Int = 5,
) : SiteTuning {
    @Synchronized override fun extraWaitMs(site: String) = read(site).first

    @Synchronized override fun onRejected(site: String) {
        val (extra, _) = read(site)
        write(site, (extra + stepUpMs).coerceAtMost(maxExtraMs), 0)
    }

    @Synchronized override fun onCleanRun(site: String) {
        val (extra, clean) = read(site)
        if (extra == 0L) return
        val n = clean + 1
        if (n >= cleanRunsToEase) write(site, (extra - stepDownMs).coerceAtLeast(0), 0) else write(site, extra, n)
    }
}
