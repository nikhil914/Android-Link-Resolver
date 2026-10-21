package com.nik.linkresolver.engine

import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.Collections
import java.util.concurrent.TimeUnit

/** Virtual time: sleeping just moves the clock, so 9 s waits take 0 s of real time. */
class FakeClock(var now: Long = 1_000_000L) : Clock, Sleeper {
    val sleeps = Collections.synchronizedList(mutableListOf<Long>())
    override fun nowMs(): Long = now
    override suspend fun sleep(ms: Long) {
        sleeps += ms
        now += ms
    }
}

data class Seen(val method: String, val host: String, val path: String, val body: String, val headers: Map<String, String>)

/**
 * A local imitation of the real flow, served from one MockWebServer for both hosts:
 * linkshortx.in → 307 → hindisink.com JS redirect → Step 1..N (server-enforced 9 s minimum for steps 2+)
 * → linkshortx.in Get Link page → JSON {"url": ...}.
 */
class FakeLinkShortX(
    private val clock: FakeClock,
    private val steps: Int = 3,
    private val minStepMs: Long = 9_000,
    /** The live site enforces ~9 s on step 1 too (since Oct 2026). */
    private val minFirstStepMs: Long = 9_000,
    private val counterS: Int = 5,
    val finalUrl: String = "https://telegram.me/somechannel?start=abc123",
    val entryHost: String = "linkshortx.in",
    val blogHost: String = "hindisink.com",
    /** Real page since Oct 2026: /links/go refuses unless /links/arm was called with the form first. */
    private val requireArm: Boolean = true,
) : Dispatcher() {
    val server = MockWebServer()
    val seen: MutableList<Seen> = Collections.synchronizedList(mutableListOf())
    private val servedAt = mutableMapOf<Int, Long>()
    private var getLinkServedAt = 0L
    var armed = false
        private set
    var rejections = 0
        private set

    /** Override to customise a response (return null to use the default flow). */
    var override: ((RecordedRequest) -> MockResponse?)? = null

    val port get() = server.port
    fun url(host: String, path: String) = "http://$host:$port$path"
    val entryUrl get() = url(entryHost, "/AbC123")

    fun start(): FakeLinkShortX {
        server.dispatcher = this
        server.start()
        return this
    }

    /** OkHttp client whose DNS sends the flow hosts to the mock server and fails for everything else. */
    fun client(readTimeoutMs: Long = 5_000): OkHttpClient = OkHttpClient.Builder()
        .dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> =
                if (hostname == entryHost || hostname == blogHost) listOf(InetAddress.getByName("127.0.0.1"))
                else throw UnknownHostException("test dns: $hostname must never be requested")
        })
        .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
        .connectTimeout(2, TimeUnit.SECONDS)
        .build()

    override fun dispatch(request: RecordedRequest): MockResponse {
        val host = request.getHeader("Host")!!.substringBefore(':')
        val path = request.path!!
        val body = request.body.readUtf8()
        seen += Seen(request.method!!, host, path, body, request.headers.toMap())
        override?.invoke(request)?.let { return it }

        return when {
            host == entryHost && path == "/AbC123" ->
                MockResponse().setResponseCode(307)
                    .addHeader("Location", url(blogHost, "/q7m4vk29.php?id=" + java.net.URLEncoder.encode(entryUrl + "#hsfn1.abc", "UTF-8") + "&t=1"))
                    .addHeader("Set-Cookie", "AppSession=s1; Path=/")
                    .addHeader("Set-Cookie", "refAbC123=1; Path=/")

            host == blogHost && path.startsWith("/q7m4vk29.php") ->
                html(
                    """<html><head><title></title></head><body><script>
                    setTimeout(function(){ window.location.href = "${url(blogHost, "")}"; });
                    </script></body></html>""",
                ).addHeader("Set-Cookie", "link=AbC123; Path=/")

            host == blogHost && path == "/" && request.method == "GET" -> stepPage(1)

            host == blogHost && path == "/go" && request.method == "POST" -> {
                val token = Regex("token=t(\\d+)").find(body)?.groupValues?.get(1)?.toInt()
                    ?: return article("Bad token")
                val min = if (token == 1) minFirstStepMs else minStepMs
                if (clock.now - servedAt.getValue(token) < min) {
                    rejections++
                    return article("Ten Tips For Healthy Living")
                }
                if (token < steps) stepPage(token + 1)
                else MockResponse().setResponseCode(302).addHeader("Location", url(entryHost, "/AbC123?done=1"))
            }

            host == entryHost && path == "/AbC123?done=1" -> {
                getLinkServedAt = clock.now
                html(getLinkPage())
            }

            host == entryHost && path == "/links/arm" && request.method == "POST" -> {
                val ajax = request.getHeader("X-Requested-With") == "XMLHttpRequest"
                val multipart = request.getHeader("Content-Type")?.startsWith("multipart/form-data") == true
                if (ajax && multipart && body.contains("csrf1") && body.contains("name=\"f5defcd30b9fc9b89\"")) armed = true
                MockResponse().setResponseCode(200).setBody("")
            }

            host == entryHost && path == "/links/go" && request.method == "POST" -> {
                val ajax = request.getHeader("X-Requested-With") == "XMLHttpRequest"
                if (!ajax || clock.now - getLinkServedAt < counterS * 1000L || !body.contains("_csrfToken=csrf1") ||
                    (requireArm && !armed)
                ) {
                    json("""{"status":"error","message":"Bad Request.","url":""}""")
                } else {
                    json("""{"status":"success","message":"Go without Earn because anonymous user","url":"$finalUrl"}""")
                }
            }

            else -> MockResponse().setResponseCode(404).setBody("not found")
        }
    }

    private fun stepPage(n: Int): MockResponse {
        servedAt[n] = clock.now
        return html(
            """<!doctype html><html><head><title>Step $n of $steps · Ten Tips For Healthy Living</title></head>
            <body><article>${"Lorem ipsum dolor sit amet. ".repeat(50)}</article>
            <div id="go"><span id="tp-time">10</span></div><div id="pDone" style="display:none"></div>
            <form id="fwd" method="post" action="/go">
              <input type="hidden" name="token" value="t$n">
              <input type="hidden" name="ref" value="AbC123">
              <input type="checkbox" name="unchecked" value="1">
              <button type="submit" name="btn">Continue</button>
              <input type="submit" name="submit" value="Verify">
            </form></body></html>""",
        )
    }

    private fun getLinkPage() = """<!doctype html><html><head><title>LinkShortX - Get Link</title>
        <script>var app_vars = {"base_url":"/","enable_captcha":"yes","captcha_shortlink":"no","counter_value":$counterS,"counter_start":"load"};</script>
        ${if (requireArm) """<script>(function () { var armUrl = '/links/arm'; function arm(attempt) { var f = document.getElementById('go-link');
          var xhr = new XMLHttpRequest(); xhr.open('POST', armUrl, true); xhr.setRequestHeader('X-Requested-With', 'XMLHttpRequest');
          xhr.send(new FormData(f)); } arm(0); })();</script>""" else ""}
        </head><body>
        <form id="go-link" method="post" action="/links/go" accept-charset="utf-8">
          <div style="display:none;"><input type="hidden" name="_method" value="POST"><input type="hidden" name="_csrfToken" value="csrf1"></div>
          <input type="hidden" name="f5defcd30b9fc9b89" value="ZGF0YQ==">
          <div style="display:none;"><input type="hidden" name="_Token[fields]" value="f1"><input type="hidden" name="_Token[unlocked]" value="adcopy_challenge"></div>
        </form>
        <a href="#" class="btn btn-success btn-lg get-link disabled">Please wait...</a>
        </body></html>"""

    fun article(title: String) = html("<html><head><title>$title</title></head><body><article>${"text ".repeat(1000)}</article></body></html>")

    fun html(s: String) = MockResponse().setResponseCode(200).addHeader("Content-Type", "text/html; charset=UTF-8").setBody(s.trimIndent())
    fun json(s: String) = MockResponse().setResponseCode(200).addHeader("Content-Type", "application/json; charset=UTF-8").setBody(s)

    fun stop() = server.shutdown()
}
