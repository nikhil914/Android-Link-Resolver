package com.nik.linkresolver.engine

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

sealed class FollowResult {
    /** Left the flow hosts: this is the destination. It was NOT requested. */
    data class Final(val url: HttpUrl) : FollowResult()

    /** A real page inside the flow that the resolver must handle. */
    data class Landed(val page: Page) : FollowResult()
}

/**
 * Follows 3xx, meta-refresh and tiny JS-redirect pages while they stay inside [flowHosts].
 * Anything pointing outside is returned as [FollowResult.Final] without being requested.
 */
class RedirectFollower(
    flowHosts: Set<String>,
    private val smallPageBytes: Int = 4000,
) {
    private val flowHosts: MutableSet<String> = flowHosts.map { it.lowercase() }.toMutableSet()

    val hosts: Set<String> get() = flowHosts.toSet()

    /** Adds a host discovered during this resolve (e.g. a new blog domain). Returns true if it was new. */
    fun addFlowHost(host: String): Boolean = flowHosts.add(host.lowercase().removePrefix("www."))

    fun inFlow(url: HttpUrl): Boolean {
        val host = url.host.lowercase()
        return flowHosts.any { host == it || host.endsWith(".$it") }
    }

    /**
     * Google's click redirector (`google.com/url?...&url=<target>`), which droplink's blog uses to fake search
     * traffic: go straight to the target instead of returning Google as the destination.
     */
    fun unwrap(url: HttpUrl): HttpUrl {
        val host = url.host.lowercase()
        val google = host == "google.com" || host.startsWith("www.google.") || host.startsWith("google.")
        if (!google || url.encodedPath != "/url") return url
        val target = url.queryParameter("url") ?: url.queryParameter("q") ?: return url
        return target.toHttpUrlOrNull() ?: url
    }

    suspend fun follow(ctx: ResolveContext, start: Page, referer: HttpUrl? = null): FollowResult {
        var page = start
        var ref = referer
        while (true) {
            if (page.isRedirect) {
                val next = page.location?.let(::unwrap) ?: return FollowResult.Landed(page)
                if (!inFlow(next)) return FollowResult.Final(next)
                ctx.progress(ResolveEvent.Fetching(next.toString()))
                page = ctx.http.get(next, ref)
                continue
            }
            val parsed = page.parsed
            val target = if (page.body.length < smallPageBytes && parsed.forms.isEmpty()) {
                parsed.metaRefresh ?: PageParser.jsRedirect(page.body)
            } else {
                null
            }
            val next = target?.let { page.url.resolve(it) }?.let(::unwrap)
            if (next != null) {
                if (!inFlow(next)) return FollowResult.Final(next)
                ref = page.url
                ctx.progress(ResolveEvent.Fetching(next.toString()))
                page = ctx.http.get(next, ref)
                continue
            }
            return FollowResult.Landed(page)
        }
    }
}
