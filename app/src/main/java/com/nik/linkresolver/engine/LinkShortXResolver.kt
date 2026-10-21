package com.nik.linkresolver.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl

/**
 * All tunables for the LinkShortX flow in one place, so remote rules can override them later.
 * Times are in milliseconds.
 */
data class LinkShortXConfig(
    /** Hosts a tapped/shared link may have for this resolver to pick it up. */
    val entryHosts: Set<String> = setOf("linkshortx.in"),
    /** Hosts that are part of the flow; leaving them means we reached the destination. */
    val flowHosts: Set<String> = setOf("linkshortx.in", "hindisink.com"),
    /** Minimum time between receiving a step page (2+) and POSTing it. Server rejects ≤ ~8 s. */
    val stepWaitMs: Long = 9_200,
    /** Added to [stepWaitMs] for later steps each time a step is rejected. */
    val rejectExtraWaitMs: Long = 500,
    val retryDelayMs: Long = 1_500,
    val maxRetries: Int = 6,
    val stepFormId: String = "fwd",
    val getLinkFormId: String = "go-link",
    val getLinkAnchorClass: String = "get-link",
    val getLinkDefaultCounterS: Int = 5,
    val getLinkExtraMs: Long = 500,
    val getLinkAttempts: Int = 4,
    val getLinkRetryDelayMs: Long = 2_000,
    val maxHops: Int = 40,
)

/**
 * LinkShortX: `linkshortx.in/<code>` → hindisink.com "Step 1..N of N" → linkshortx.in "Get Link" → destination.
 * Faithful port of linkbypass.py.
 */
class LinkShortXResolver(private val config: LinkShortXConfig = LinkShortXConfig()) : Resolver {
    override val name = "LinkShortX"

    private val follower = RedirectFollower(config.flowHosts)
    private val stepTitle = Regex("""Step\s+(\d+)\s+of\s+(\d+)""", RegexOption.IGNORE_CASE)
    private val counterValue = Regex("""counter_value["']?\s*:\s*["']?(\d+)""")
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override fun canHandle(url: HttpUrl): Boolean {
        val host = url.host.lowercase()
        return config.entryHosts.any { host == it || host.endsWith(".$it") }
    }

    private data class Pending(val action: HttpUrl, val fields: List<Pair<String, String>>, val referer: HttpUrl)

    override suspend fun resolve(url: HttpUrl, ctx: ResolveContext): String {
        var stepWait = config.stepWaitMs
        ctx.progress(ResolveEvent.Fetching(url.toString()))
        var res = follower.follow(ctx, ctx.http.get(url))
        var pending: Pending? = null
        var retries = 0

        while (true) {
            val page = when (val r = res) {
                is FollowResult.Final -> return r.url.toString()
                is FollowResult.Landed -> r.page
            }
            val parsed = page.parsed

            if (parsed.title.contains("access denied", ignoreCase = true)) {
                throw ResolveException(ErrorKind.ACCESS_DENIED, ErrorKind.ACCESS_DENIED.userMessage())
            }

            // ---- hindisink "Step X of N" page
            val fwd = parsed.form(config.stepFormId)
            if (fwd != null && fwd.method == "post") {
                val m = stepTitle.find(parsed.title)
                val step = m?.groupValues?.get(1)?.toInt() ?: 99
                val total = m?.groupValues?.get(2) ?: "?"
                val action = page.url.resolve(fwd.action.ifEmpty { page.url.toString() }) ?: page.url
                if (step != 1) {
                    val left = stepWait - (ctx.clock.nowMs() - page.receivedAtMs)
                    ctx.log("step $step/$total: waiting ${"%.1f".format(maxOf(left, 0) / 1000.0)}s (server-side timer)")
                    ctx.countdown(left) { ResolveEvent.StepWaiting(step, total, it) }
                } else {
                    ctx.log("step 1/$total: submitting")
                }
                ctx.progress(ResolveEvent.StepSubmitting(step, total))
                pending = Pending(action, fwd.fields, page.url)
                retries = 0
                res = follower.follow(ctx, ctx.http.post(action, fwd.fields, page.url), page.url)
                continue
            }

            // ---- AdLinkFly "Get Link" page
            val goLink = parsed.form(config.getLinkFormId)
            if (goLink != null || parsed.anchors.any { config.getLinkAnchorClass in it.classes }) {
                return getLink(ctx, page, goLink)
            }

            // ---- neither: the step we posted was rejected (too fast) → re-post the same token
            val p = pending
            if (p != null && retries < config.maxRetries) {
                retries++
                stepWait += config.rejectExtraWaitMs
                ctx.log("step rejected by server (got «${parsed.title}»), retry $retries/${config.maxRetries} in ${config.retryDelayMs}ms")
                ctx.progress(ResolveEvent.Retrying(retries, config.maxRetries, "Server rejected the step"))
                ctx.delay(config.retryDelayMs)
                res = follower.follow(ctx, ctx.http.post(p.action, p.fields, p.referer), p.referer)
                continue
            }

            throw ResolveException(
                ErrorKind.UNRECOGNISED_PAGE,
                "Unrecognised page «${parsed.title}» at ${page.url}" +
                    if (p != null) " (step still rejected after ${config.maxRetries} retries)" else "",
            )
        }
    }

    private suspend fun getLink(ctx: ResolveContext, page: Page, form: HtmlForm?): String {
        // Already unlocked in the HTML?
        page.parsed.anchors.firstOrNull { a ->
            config.getLinkAnchorClass in a.classes && "disabled" !in a.classes &&
                a.href.isNotEmpty() && a.href != "#" && !a.href.startsWith("javascript", ignoreCase = true)
        }?.let { a -> page.url.resolve(a.href)?.let { return it.toString() } }

        if (form == null) throw ResolveException(ErrorKind.GET_LINK_FAILED, "Get Link page without #${config.getLinkFormId} form")

        val counter = counterValue.find(page.body)?.groupValues?.get(1)?.toIntOrNull() ?: config.getLinkDefaultCounterS
        ctx.log("get-link page: waiting ${counter}s")
        ctx.countdown(counter * 1000L + config.getLinkExtraMs) { ResolveEvent.GetLinkWaiting(it) }
        ctx.progress(ResolveEvent.Finalizing)

        val action = page.url.resolve(form.action.ifEmpty { "/links/go" }) ?: page.url
        val headers = mapOf(
            "X-Requested-With" to "XMLHttpRequest",
            "Accept" to "application/json, text/javascript, */*; q=0.01",
        )
        var lastProblem = ""
        repeat(config.getLinkAttempts) { attempt ->
            val resp = ctx.http.post(action, form.fields, page.url, headers)
            if (resp.isRedirect) resp.location?.let { return it.toString() }

            val obj = runCatching { json.parseToJsonElement(resp.body.trim()) as? JsonObject }.getOrNull()
            val url = (obj?.get("url") as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (!url.isNullOrBlank()) {
                return (page.url.resolve(url) ?: return url).toString()
            }
            val message = (obj?.get("message") as? JsonPrimitive)?.content
            lastProblem = message ?: "HTTP ${resp.code}"
            ctx.log("get-link not ready ($lastProblem), raw response: ${resp.body.take(500)}")
            if (attempt < config.getLinkAttempts - 1) {
                ctx.progress(ResolveEvent.Retrying(attempt + 1, config.getLinkAttempts - 1, "Get Link not ready"))
                ctx.delay(config.getLinkRetryDelayMs)
            }
        }
        throw ResolveException(ErrorKind.GET_LINK_FAILED, "Get Link POST never returned a URL ($lastProblem)")
    }
}
