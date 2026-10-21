package com.nik.linkresolver.engine

import kotlinx.coroutines.runBlocking
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.net.URLEncoder
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * A site that keeps state per session (cookies), like the real one. If two resolves shared a cookie jar, their
 * sessions would overwrite each other and they would fail or get the wrong link.
 */
private class SessionSite(private val clock: FakeClock, private val steps: Int = 3) : Dispatcher() {
    val server = MockWebServer()
    private val nextSid = AtomicInteger(1)
    private class Session(val code: String) {
        val servedAt = ConcurrentHashMap<Int, Long>()
        @Volatile var getLinkAt = 0L
        @Volatile var armed = false
    }
    private val sessions = ConcurrentHashMap<String, Session>()
    val mismatches = AtomicInteger()
    val inFlight = AtomicInteger()
    val maxInFlight = AtomicInteger()

    fun url(host: String, path: String) = "http://$host:${server.port}$path"

    fun start() = apply { server.dispatcher = this; server.start() }

    fun client(): OkHttpClient = OkHttpClient.Builder().dns(object : Dns {
        override fun lookup(hostname: String): List<InetAddress> =
            if (hostname == "linkshortx.in" || hostname == "hindisink.com") listOf(InetAddress.getByName("127.0.0.1"))
            else throw UnknownHostException(hostname)
    }).build()

    private fun cookie(r: RecordedRequest, name: String) =
        r.getHeader("Cookie")?.split(";")?.map { it.trim() }?.firstOrNull { it.startsWith("$name=") }?.substringAfter("=")

    private fun html(s: String) = MockResponse().setResponseCode(200).addHeader("Content-Type", "text/html").setBody(s)

    override fun dispatch(request: RecordedRequest): MockResponse {
        val host = request.getHeader("Host")!!.substringBefore(':')
        val path = request.path!!
        val body = request.body.readUtf8()
        val codeRe = Regex("^/([A-Za-z0-9]+)$")
        return when {
            host == "linkshortx.in" && codeRe.matches(path) -> {
                val code = codeRe.find(path)!!.groupValues[1]
                val sid = "s${nextSid.getAndIncrement()}"
                sessions[sid] = Session(code)
                val now = inFlight.incrementAndGet()
                maxInFlight.accumulateAndGet(now) { a, b -> maxOf(a, b) }
                val entry = url("linkshortx.in", path)
                MockResponse().setResponseCode(307)
                    .addHeader("Set-Cookie", "AppSession=$sid; Path=/")
                    .addHeader("Location", url("hindisink.com", "/q7m4vk29.php?id=" + URLEncoder.encode(entry, "UTF-8") + "&sid=$sid"))
            }
            host == "hindisink.com" && path.startsWith("/q7m4vk29.php") -> {
                val sid = Regex("sid=(s\\d+)").find(path)!!.groupValues[1]
                html("<html><head><title></title></head><body><script>window.location.href = \"${url("hindisink.com", "/")}\";</script></body></html>")
                    .addHeader("Set-Cookie", "bsid=$sid; Path=/")
            }
            host == "hindisink.com" && path == "/" -> stepPage(cookie(request, "bsid")!!, 1)
            host == "hindisink.com" && path == "/go" -> {
                val (sid, step) = Regex("token=(s\\d+)-(\\d+)").find(body)!!.destructured
                if (cookie(request, "bsid") != sid) { mismatches.incrementAndGet(); return html("<title>Article</title>") }
                val s = sessions.getValue(sid)
                if (clock.now - s.servedAt.getValue(step.toInt()) < 9_000) return html("<title>Article</title>")
                if (step.toInt() < steps) stepPage(sid, step.toInt() + 1)
                else MockResponse().setResponseCode(302).addHeader("Location", url("linkshortx.in", "/${s.code}?done=1"))
            }
            host == "linkshortx.in" && path.endsWith("?done=1") -> {
                val sid = cookie(request, "AppSession")!!
                val s = sessions.getValue(sid)
                if (!path.startsWith("/${s.code}?")) mismatches.incrementAndGet()
                s.getLinkAt = clock.now
                html("""<html><head><title>Get Link</title><script>var app_vars = {"counter_value":5};</script>
                    <script>var armUrl = '/links/arm';</script></head><body>
                    <form id="go-link" method="post" action="/links/go"><input type="hidden" name="_csrfToken" value="c-$sid">
                    <input type="hidden" name="data" value="${s.code}"></form>
                    <a href="#" class="get-link disabled">wait</a></body></html>""")
            }
            host == "linkshortx.in" && path == "/links/arm" -> {
                val sid = cookie(request, "AppSession")!!
                if (body.contains("c-$sid")) sessions.getValue(sid).armed = true else mismatches.incrementAndGet()
                MockResponse().setResponseCode(200)
            }
            host == "linkshortx.in" && path == "/links/go" -> {
                val sid = cookie(request, "AppSession")!!
                val s = sessions.getValue(sid)
                val ok = s.armed && body.contains("_csrfToken=c-$sid") && body.contains("data=${s.code}") && clock.now - s.getLinkAt >= 5_000
                if (!ok) {
                    mismatches.incrementAndGet()
                    MockResponse().setBody("""{"status":"error","message":"Bad Request.","url":""}""")
                } else {
                    inFlight.decrementAndGet()
                    MockResponse().setBody("""{"status":"success","url":"https://t.me/ch_${s.code}"}""")
                }
            }
            else -> MockResponse().setResponseCode(404)
        }
    }

    private fun stepPage(sid: String, n: Int): MockResponse {
        sessions.getValue(sid).servedAt[n] = clock.now
        return html("""<html><head><title>Step $n of $steps · X</title></head><body>
            <form id="fwd" method="post" action="/go"><input type="hidden" name="token" value="$sid-$n"></form></body></html>""")
    }
}

private class MemoryStore(urls: List<String>) : QueueStore {
    val queue = ArrayDeque(urls.mapIndexed { i, u -> QueuedLink(i + 1L, u) })
    val results = ConcurrentHashMap<Long, ResolveResult>()
    val cancelled = ConcurrentHashMap.newKeySet<Long>()
    override suspend fun claimNext(): QueuedLink? = synchronized(queue) { queue.removeFirstOrNull() }
    override suspend fun finish(id: Long, result: ResolveResult, log: String) { results[id] = result }
    override suspend fun markCancelled(id: Long, log: String) { cancelled += id }
}

class QueueWorkerTest {
    private lateinit var clock: FakeClock
    private lateinit var site: SessionSite

    @Before fun setUp() { clock = FakeClock(); site = SessionSite(clock).start() }
    @After fun tearDown() { site.server.shutdown() }

    private fun worker(store: QueueStore, concurrency: Int) = QueueWorker(
        ResolverRunner(ResolverRegistry.default(), clock, clock), store, { concurrency }, clock,
    )

    @Test
    fun resolvesManyLinksInParallelEachInItsOwnSession() = runBlocking {
        val codes = listOf("Aa1", "Bb2", "Cc3", "Dd4", "Ee5", "Ff6", "Gg7")
        val store = MemoryStore(codes.map { site.url("linkshortx.in", "/$it") })
        val client = site.client()
        worker(store, concurrency = 3).drain { client }

        assertEquals(codes.size, store.results.size)
        store.results.forEach { (id, r) ->
            assertTrue("link $id: $r", r is ResolveResult.Success)
            assertEquals("https://t.me/ch_${codes[(id - 1).toInt()]}", (r as ResolveResult.Success).finalUrl)
        }
        assertEquals(0, site.mismatches.get())
        assertTrue("max in flight ${site.maxInFlight.get()}", site.maxInFlight.get() in 2..3)
    }

    @Test
    fun concurrencyOneRunsSequentially() = runBlocking {
        val store = MemoryStore(listOf("Aa1", "Bb2", "Cc3").map { site.url("linkshortx.in", "/$it") })
        val client = site.client()
        worker(store, concurrency = 1).drain { client }
        assertEquals(3, store.results.values.count { it is ResolveResult.Success })
        assertEquals(1, site.maxInFlight.get())
    }

    @Test
    fun extractsEverySupportedLinkOnce() {
        val text = """
            🔥 Part 1: https://linkshortx.in/RvGxHZvJ
            Part 2 👉 https://urlshortx.io/P1uCe.
            again https://linkshortx.in/RvGxHZvJ and https://example.com/x
            (https://linkshortx.in/Third)
        """.trimIndent()
        assertEquals(
            listOf("https://linkshortx.in/RvGxHZvJ", "https://urlshortx.io/P1uCe", "https://linkshortx.in/Third"),
            QueueWorker.extractLinks(text, ResolverRegistry.default()),
        )
    }
}
