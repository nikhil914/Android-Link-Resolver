package com.nik.linkresolver.engine

/** Collects everything needed to diagnose a failed resolve: log lines, hops and raw pages. */
interface DebugRecorder {
    fun log(message: String)
    fun hop(hop: Hop)
    fun page(method: String, url: String, status: Int, body: String)
}

data class SavedPage(val index: Int, val method: String, val url: String, val status: Int, val body: String)

class InMemoryDebugRecorder(private val clock: Clock = Clock.SYSTEM) : DebugRecorder {
    private val start = clock.nowMs()
    private val _lines = mutableListOf<String>()
    private val _hops = mutableListOf<Hop>()
    private val _pages = mutableListOf<SavedPage>()

    val lines: List<String> @Synchronized get() = _lines.toList()
    val hops: List<Hop> @Synchronized get() = _hops.toList()
    val pages: List<SavedPage> @Synchronized get() = _pages.toList()

    @Synchronized
    override fun log(message: String) {
        val t = (clock.nowMs() - start) / 1000.0
        _lines += "[%6.1fs] %s".format(t, message)
    }

    @Synchronized
    override fun hop(hop: Hop) {
        _hops += hop
        log("${hop.method} ${hop.status} ${hop.url}${if (hop.title.isNotEmpty()) "  «${hop.title}»" else ""}")
    }

    @Synchronized
    override fun page(method: String, url: String, status: Int, body: String) {
        _pages += SavedPage(_pages.size + 1, method, url, status, body)
    }

    /** Plain-text log suitable for copying. */
    fun asText(): String = lines.joinToString("\n")
}
