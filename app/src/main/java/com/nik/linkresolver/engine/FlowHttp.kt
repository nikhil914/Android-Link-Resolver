package com.nik.linkresolver.engine

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A received response with its body already read. */
class Page(
    val method: String,
    val url: HttpUrl,
    val code: Int,
    val headers: Headers,
    val body: String,
    /** Clock time when the response arrived — server-side step timers are measured from here. */
    val receivedAtMs: Long,
) {
    val isRedirect: Boolean get() = code in REDIRECT_CODES && headers["Location"] != null
    val location: HttpUrl? get() = headers["Location"]?.let { url.resolve(it) }

    private var _parsed: ParsedPage? = null
    val parsed: ParsedPage get() = _parsed ?: PageParser.parse(body, url).also { _parsed = it }

    companion object {
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}

/** Per-resolve cookie store (the site remembers completed links per session, so never share it). */
class MemoryCookieJar : CookieJar {
    private val cookies = mutableListOf<Cookie>()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        for (c in cookies) {
            this.cookies.removeAll { it.name == c.name && it.domain == c.domain && it.path == c.path }
            this.cookies += c
        }
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        cookies.removeAll { it.expiresAt < now }
        return cookies.filter { it.matches(url) }
    }
}

/**
 * Browser-like HTTP for one resolve: fresh cookie jar, no automatic redirects (we follow manually so the final
 * destination is never requested), browser headers, hop limit, and debug recording.
 */
class FlowHttp(
    baseClient: OkHttpClient,
    private val clock: Clock,
    private val debug: DebugRecorder,
    private val maxHops: Int = 40,
    private val userAgent: String = DEFAULT_UA,
) {
    private val client: OkHttpClient = baseClient.newBuilder()
        .cookieJar(MemoryCookieJar())
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    var hops = 0
        private set

    suspend fun get(url: HttpUrl, referer: HttpUrl? = null, headers: Map<String, String> = emptyMap()): Page =
        execute("GET", url, null, referer, headers)

    suspend fun post(
        url: HttpUrl,
        fields: List<Pair<String, String>>,
        referer: HttpUrl? = null,
        headers: Map<String, String> = emptyMap(),
    ): Page {
        val body = FormBody.Builder().apply { fields.forEach { (k, v) -> add(k, v) } }.build()
        return execute("POST", url, body, referer, headers)
    }

    private suspend fun execute(
        method: String,
        url: HttpUrl,
        body: FormBody?,
        referer: HttpUrl?,
        extra: Map<String, String>,
    ): Page {
        hops++
        if (hops > maxHops) throw ResolveException(ErrorKind.TOO_MANY_HOPS, "Too many hops ($maxHops), giving up")

        val builder = Request.Builder().url(url)
            .header("User-Agent", userAgent)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
        if (referer != null) {
            builder.header("Referer", referer.toString())
            if (method == "POST") builder.header("Origin", origin(referer))
        }
        extra.forEach { (k, v) -> builder.header(k, v) }
        builder.method(method, body)

        val started = clock.nowMs()
        val page = try {
            client.newCall(builder.build()).await { resp ->
                Page(method, url, resp.code, resp.headers, resp.body.string(), clock.nowMs())
            }
        } catch (e: IOException) {
            debug.log("$method $url failed: ${e.javaClass.simpleName}: ${e.message}")
            throw mapIoError(e)
        }
        val title = if (page.isRedirect) "→ ${page.headers["Location"]}" else page.parsed.title
        debug.hop(Hop(method, url.toString(), page.code, title, page.receivedAtMs - started))
        if (page.body.isNotEmpty()) debug.page(method, url.toString(), page.code, page.body)
        return page
    }

    companion object {
        const val DEFAULT_UA = "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/141.0.0.0 Mobile Safari/537.36"

        fun origin(url: HttpUrl): String {
            val defaultPort = HttpUrl.defaultPort(url.scheme)
            return if (url.port == defaultPort) "${url.scheme}://${url.host}" else "${url.scheme}://${url.host}:${url.port}"
        }

        fun mapIoError(e: IOException): ResolveException = when (e) {
            is SocketTimeoutException, is ConnectException, is UnknownHostException, is NoRouteToHostException ->
                ResolveException(ErrorKind.UNREACHABLE, ErrorKind.UNREACHABLE.userMessage() + " (${e.javaClass.simpleName})", e)
            is InterruptedIOException ->
                ResolveException(ErrorKind.UNREACHABLE, ErrorKind.UNREACHABLE.userMessage() + " (timeout)", e)
            else -> ResolveException(ErrorKind.NETWORK, "Network error: ${e.message ?: e.javaClass.simpleName}", e)
        }
    }
}

/** Cancellable OkHttp call: cancelling the coroutine cancels the request. */
private suspend fun <T> Call.await(read: (Response) -> T): T = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            val result = try {
                response.use(read)
            } catch (e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
                return
            }
            if (cont.isActive) cont.resume(result)
        }
    })
}
