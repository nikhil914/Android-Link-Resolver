package com.nik.linkresolver.engine

import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

/**
 * Picks a resolver by host. Built-in resolvers first; then the [generic] AdLinkFly resolver for domains in [domains]
 * (added by the user or learned). [findAny] also tries the generic resolver on any other host — used for a single
 * link the user explicitly hands over, never for links picked out of text.
 */
class ResolverRegistry(
    private val resolvers: List<Resolver>,
    private val generic: Resolver? = null,
    val domains: DomainStore = DomainStore.NONE,
) {
    /** A resolver for a known shortener domain, or null. */
    fun find(url: HttpUrl): Resolver? =
        resolvers.firstOrNull { it.canHandle(url) } ?: generic?.takeIf { domains.contains(url.host) }

    /** Like [find], but falls back to trying the generic resolver on any host. */
    fun findAny(url: HttpUrl): Resolver? = find(url) ?: generic

    fun isGeneric(r: Resolver) = r === generic

    /** Domains handled by the built-in resolvers (for display). */
    fun builtInHosts(): Set<String> = resolvers.flatMap { (it as? LinkShortXResolver)?.entryHosts.orEmpty() }.toSet()

    companion object {
        fun default(domains: DomainStore = DomainStore.NONE) =
            ResolverRegistry(listOf(LinkShortXResolver()), LinkShortXResolver.generic(), domains)
    }
}

/** Runs one resolve end to end: fresh HTTP session, timing, error mapping. */
class ResolverRunner(
    private val registry: ResolverRegistry,
    private val clock: Clock = Clock.SYSTEM,
    private val sleeper: Sleeper = Sleeper.REAL,
    private val maxHops: Int = 40,
    private val tuning: SiteTuning = SiteTuning.NONE,
) {
    suspend fun run(
        rawUrl: String,
        client: OkHttpClient,
        debug: InMemoryDebugRecorder,
        onProgress: (ResolveEvent) -> Unit,
    ): ResolveResult {
        val start = clock.nowMs()
        var http: FlowHttp? = null
        fun fail(kind: ErrorKind, message: String): ResolveResult.Failure {
            debug.log("FAILED [$kind]: $message")
            return ResolveResult.Failure(kind, message, debug.hops, clock.nowMs() - start, http?.bytes ?: 0)
        }

        val url = normalize(rawUrl) ?: return fail(ErrorKind.UNSUPPORTED_URL, "Not a valid link: $rawUrl")
        val resolver = registry.findAny(url) ?: return fail(ErrorKind.UNSUPPORTED_URL, "${ErrorKind.UNSUPPORTED_URL.userMessage()} (${url.host})")
        val known = registry.find(url) != null
        debug.log("resolving $url with ${resolver.name}" + if (!known) " (unknown domain, trying anyway)" else "")

        val flow = FlowHttp(client, clock, debug, maxHops).also { http = it }
        val ctx = ResolveContext(flow, clock, sleeper, debug, onProgress, tuning)
        return try {
            val final = resolver.resolve(url, ctx)
            val elapsed = clock.nowMs() - start
            if (!known && registry.isGeneric(resolver)) {
                registry.domains.learn(url.host)
                debug.log("learned new shortener domain ${url.host}")
            }
            debug.log("done in ${elapsed / 1000}s → $final")
            ResolveResult.Success(final, debug.hops, elapsed, flow.bytes)
        } catch (e: CancellationException) {
            debug.log("cancelled")
            throw e
        } catch (e: ResolveException) {
            fail(e.kind, e.message ?: e.kind.userMessage())
        } catch (e: Exception) {
            fail(ErrorKind.UNRECOGNISED_PAGE, "Unexpected error: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    companion object {
        private val URL_IN_TEXT = Regex("""https?://[^\s<>"'`]+""", RegexOption.IGNORE_CASE)

        /** First http(s) URL found in [text] (for shared text), with trailing punctuation trimmed. */
        fun extractUrl(text: String): String? = extractAllUrls(text).firstOrNull()

        /** Every http(s) URL in [text], in order. */
        fun extractAllUrls(text: String): List<String> =
            URL_IN_TEXT.findAll(text).map { it.value.trimEnd('.', ',', ')', ']', '}', '!', '?', ';', ':', '*', '_') }.toList()

        /** Accepts a bare "linkshortx.in/abc" too. */
        fun normalize(raw: String): HttpUrl? {
            val t = raw.trim()
            extractUrl(t)?.toHttpUrlOrNull()?.let { return it }
            return if (t.isNotEmpty() && !t.contains(' ')) "https://$t".toHttpUrlOrNull() else null
        }
    }
}
