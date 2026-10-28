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
    /** Hosts a tapped/shared link may have for this resolver to pick it up (same AdLinkFly-based network). */
    val entryHosts: Set<String> = setOf("linkshortx.in", "urlshortx.io"),
    /**
     * Known intermediate (blog) hosts. Leaving the flow hosts means we reached the destination. The entry host is
     * always in the flow, and new blog hosts are learned at runtime (see [LinkShortXResolver.resolve]).
     */
    val flowHosts: Set<String> = setOf("hindisink.com"),
    /** Never treated as part of the flow, even if a rule would learn them, and never probed. */
    val destinationHosts: Set<String> = setOf(
        "t.me", "telegram.me", "telegram.dog", "drive.google.com", "docs.google.com", "mega.nz", "youtube.com",
        "youtu.be", "play.google.com", "github.com", "mediafire.com", "terabox.com", "instagram.com", "whatsapp.com",
    ),
    /**
     * Generic mode: handle any host (AdLinkFly-style sites). The first redirect out of the entry host may then be
     * probed once to see whether it is a flow page (a blog step) rather than the destination.
     */
    val matchAnyHost: Boolean = false,
    /** Generic mode: how many unknown redirect targets may be probed before one is taken as the destination. */
    val maxProbes: Int = 2,
    /** AdLinkFly's optional "Click here to continue" pre-page before the counter page. */
    val preFormId: String = "link-view",
    /** Minimum time between receiving a step page (2+) and POSTing it. Server rejects ≤ ~8 s. */
    val stepWaitMs: Long = 9_200,
    /** Same for step 1. Was 0 (instant) until Oct 2026; the live site now enforces ~9 s on step 1 too. */
    val firstStepWaitMs: Long = 9_200,
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
    /**
     * Since Oct 2026 the Get Link page "arms" the link on load: it POSTs the #go-link form (multipart) to
     * `/links/arm` before the counter runs, and /links/go answers "Bad Request." without it.
     */
    val armUrlPattern: Regex = Regex("""armUrl\s*=\s*['"]([^'"]+)['"]"""),
    val armFallbackPath: String = "/links/arm",
    val armAttempts: Int = 3,
    val armRetryDelayMs: Long = 1_000,
    /**
     * Droplink-style sites: the blog article has no step form, and the shortener only shows its Get Link page when
     * the link is opened again with the blog as Referer. When a flow page is not recognised, go back to the entry
     * link once with that page as Referer, after waiting [returnWaitMs] since the page arrived.
     */
    val returnToEntry: Boolean = false,
    val returnWaitMs: Long = 0,
    val maxHops: Int = 40,
)

/**
 * LinkShortX: `linkshortx.in/<code>` → hindisink.com "Step 1..N of N" → linkshortx.in "Get Link" → destination.
 * Faithful port of linkbypass.py.
 */
class LinkShortXResolver(
    private val config: LinkShortXConfig = LinkShortXConfig(),
    override val name: String = "LinkShortX",
) : Resolver {
    val entryHosts: Set<String> get() = config.entryHosts

    private val stepTitle = Regex("""Step\s+(\d+)\s+of\s+(\d+)""", RegexOption.IGNORE_CASE)
    private val counterValue = Regex("""counter_value["']?\s*:\s*["']?(\d+)""")
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override fun canHandle(url: HttpUrl): Boolean {
        if (config.matchAnyHost) return true
        val host = url.host.lowercase()
        return config.entryHosts.any { host == it || host.endsWith(".$it") }
    }

    private data class Pending(val step: Int, val action: HttpUrl, val fields: List<Pair<String, String>>, val referer: HttpUrl)

    override suspend fun resolve(url: HttpUrl, ctx: ResolveContext): String {
        val site = url.host.lowercase().removePrefix("www.")
        val tuned = ctx.tuning.extraWaitMs(site)
        if (tuned > 0) ctx.log("site tuning: +${tuned}ms on step waits for $site")
        var stepWait = config.stepWaitMs + tuned
        var firstStepWait = config.firstStepWaitMs + tuned
        var rejections = 0
        var sawFlowPage = false
        var probes = 0
        var probed: HttpUrl? = null
        var returned = false
        val follower = RedirectFollower(config.flowHosts + config.entryHosts + url.host)
        ctx.progress(ResolveEvent.Fetching(url.toString()))
        val first = ctx.http.get(url)
        learnBlogHost(ctx, follower, url, first)
        var res = follower.follow(ctx, first)
        var pending: Pending? = null
        var retries = 0

        while (true) {
            val page = when (val r = res) {
                is FollowResult.Final -> {
                    // Generic mode, nothing recognised yet: the "destination" may really be an unknown blog step.
                    // Look at it once (at most maxProbes times); if it isn't a flow page it is the destination.
                    if (config.matchAnyHost && !sawFlowPage && probes < config.maxProbes && !isDestination(r.url)) {
                        probes++
                        probed = r.url
                        ctx.log("unknown host ${r.url.host}: checking whether it is part of the flow")
                        learn(ctx, follower, r.url.host)
                        res = follower.follow(ctx, ctx.http.get(r.url))
                        continue
                    }
                    if (rejections == 0) ctx.tuning.onCleanRun(site)
                    return r.url.toString()
                }
                is FollowResult.Landed -> r.page
            }
            val parsed = page.parsed

            if (parsed.title.contains("access denied", ignoreCase = true)) {
                throw ResolveException(ErrorKind.ACCESS_DENIED, ErrorKind.ACCESS_DENIED.userMessage())
            }

            // ---- AdLinkFly "Click here to continue" pre-page (optionally with a captcha)
            val pre = parsed.form(config.preFormId)
            if (pre != null && pre.method == "post" && parsed.form(config.getLinkFormId) == null) {
                sawFlowPage = true
                if (hasCaptcha(page)) throw ResolveException(ErrorKind.CAPTCHA, ErrorKind.CAPTCHA.userMessage())
                val action = page.url.resolve(pre.action.ifEmpty { page.url.toString() }) ?: page.url
                ctx.log("continue page: submitting #${config.preFormId}")
                ctx.progress(ResolveEvent.StepSubmitting(1, "?"))
                res = follower.follow(ctx, ctx.http.post(action, pre.fields, page.url), page.url)
                continue
            }

            // ---- hindisink "Step X of N" page
            val fwd = parsed.form(config.stepFormId)
            if (fwd != null && fwd.method == "post") {
                sawFlowPage = true
                // A step page's own host and its form target are part of the flow by definition.
                learn(ctx, follower, page.url.host)
                val m = stepTitle.find(parsed.title)
                val step = m?.groupValues?.get(1)?.toInt() ?: 99
                val total = m?.groupValues?.get(2) ?: "?"
                val action = page.url.resolve(fwd.action.ifEmpty { page.url.toString() }) ?: page.url
                learn(ctx, follower, action.host)
                val wait = if (step == 1) firstStepWait else stepWait
                if (wait > 0) {
                    val left = wait - (ctx.clock.nowMs() - page.receivedAtMs)
                    ctx.log("step $step/$total: waiting ${"%.1f".format(maxOf(left, 0) / 1000.0)}s (server-side timer)")
                    ctx.countdown(left) { ResolveEvent.StepWaiting(step, total, it) }
                } else {
                    ctx.log("step $step/$total: submitting")
                }
                ctx.progress(ResolveEvent.StepSubmitting(step, total))
                pending = Pending(step, action, fwd.fields, page.url)
                retries = 0
                res = follower.follow(ctx, ctx.http.post(action, fwd.fields, page.url), page.url)
                continue
            }

            // ---- AdLinkFly "Get Link" page
            val goLink = parsed.form(config.getLinkFormId)
            if (goLink != null || parsed.anchors.any { config.getLinkAnchorClass in it.classes }) {
                val final = getLink(ctx, page, goLink)
                if (rejections == 0) ctx.tuning.onCleanRun(site)
                return final
            }

            // ---- neither: the step we posted was rejected (too fast) → re-post the same token
            val p = pending
            if (p != null && retries < config.maxRetries) {
                retries++
                // Only lengthen the wait for the kind of step that was rejected.
                if (p.step == 1) firstStepWait += config.rejectExtraWaitMs else stepWait += config.rejectExtraWaitMs
                rejections++
                ctx.tuning.onRejected(site)
                ctx.log("step rejected by server (got «${parsed.title}»), retry $retries/${config.maxRetries} in ${config.retryDelayMs}ms")
                ctx.progress(ResolveEvent.Retrying(retries, config.maxRetries, "Server rejected the step"))
                ctx.delay(config.retryDelayMs)
                res = follower.follow(ctx, ctx.http.post(p.action, p.fields, p.referer), p.referer)
                continue
            }

            // ---- Droplink-style blog article: open the entry link again with the article as Referer
            if (config.returnToEntry && !returned && !sameHost(page.url, url)) {
                returned = true
                sawFlowPage = true
                val back = entryLinkOn(page, url) ?: url
                val left = config.returnWaitMs - (ctx.clock.nowMs() - page.receivedAtMs)
                ctx.log("blog page «${parsed.title}» at ${page.url.host}: going back to $back with it as referer")
                if (left > 0) ctx.countdown(left) { ResolveEvent.StepWaiting(1, "1", it) }
                ctx.progress(ResolveEvent.Fetching(back.toString()))
                res = follower.follow(ctx, ctx.http.get(back, page.url), page.url)
                continue
            }

            // Generic mode: a probed page that is no flow page is the destination itself.
            val pr = probed
            if (pr != null && !sawFlowPage) {
                ctx.log("${pr.host} is not part of the flow: taking it as the destination")
                return pr.toString()
            }
            if (config.matchAnyHost && !sawFlowPage) {
                if (hasCaptcha(page)) throw ResolveException(ErrorKind.CAPTCHA, ErrorKind.CAPTCHA.userMessage())
                throw ResolveException(ErrorKind.NOT_A_SHORTLINK, "${ErrorKind.NOT_A_SHORTLINK.userMessage()} («${parsed.title}» at ${page.url.host})")
            }
            throw ResolveException(
                ErrorKind.UNRECOGNISED_PAGE,
                "Unrecognised page «${parsed.title}» at ${page.url}" +
                    if (p != null) " (step still rejected after ${config.maxRetries} retries)"
                    else if (returned) " (going back to the link with the blog as referer did not unlock it)"
                    else "",
            )
        }
    }

    /**
     * The entry host 3xx-redirects to its blog with the original link in the query
     * (`hindisink.com/q7m4vk29.php?id=https://linkshortx.in/<code>#...`, or just the code as in
     * `tech8s.net/safe2.php?link=<code>`). That proves the target is the next flow host, not the destination, so new
     * blog domains work without an app update.
     */
    private fun learnBlogHost(ctx: ResolveContext, follower: RedirectFollower, entry: HttpUrl, first: Page) {
        val next = first.location ?: return
        if (!first.isRedirect || follower.inFlow(next)) return
        val code = entry.pathSegments.lastOrNull { it.isNotEmpty() }?.takeIf { it.length >= 4 }
        val carriesEntry = (0 until next.querySize).any { i ->
            val v = next.queryParameterValue(i) ?: return@any false
            v.contains(entry.host, ignoreCase = true) || v == code
        }
        if (carriesEntry) learn(ctx, follower, next.host)
    }

    /** Replays the page's on-load "arm" XHR (`xhr.send(new FormData(#go-link))` to /links/arm), if it has one. */
    private suspend fun arm(ctx: ResolveContext, page: Page, form: HtmlForm) {
        val inline = page.parsed.scripts.filter { it.src.isEmpty() }.joinToString("\n") { it.text }
        val path = config.armUrlPattern.find(inline)?.groupValues?.get(1)
            ?: config.armFallbackPath.takeIf { inline.contains(it) }
            ?: return
        val url = page.url.resolve(path) ?: return
        ctx.log("arming link: POST $url")
        repeat(config.armAttempts) { attempt ->
            val resp = ctx.http.postMultipart(url, form.fields, page.url, mapOf("X-Requested-With" to "XMLHttpRequest", "Accept" to "*/*"))
            if (resp.code == 200) {
                if (resp.body.isNotBlank()) ctx.log("arm response: ${resp.body.take(300)}")
                return
            }
            ctx.log("arm got HTTP ${resp.code}, attempt ${attempt + 1}/${config.armAttempts}")
            if (attempt < config.armAttempts - 1) ctx.delay(config.armRetryDelayMs)
        }
    }

    /** Detailed description of the Get Link page in the debug log, so a pasted log is enough to diagnose it. */
    private fun logGetLinkPage(ctx: ResolveContext, page: Page, form: HtmlForm) {
        val p = page.parsed
        ctx.log("get-link form: action=${form.action} method=${form.method}")
        ctx.log("get-link fields: " + form.fields.joinToString { (k, v) -> if (v.isEmpty()) "$k=<EMPTY>" else "$k(${v.length})" })
        val body = page.body
        val markers = listOf("g-recaptcha", "recaptcha", "cf-turnstile", "turnstile", "h-captcha", "hcaptcha", "captcha")
            .filter { body.contains(it, ignoreCase = true) }
        if (markers.isNotEmpty()) ctx.log("get-link captcha markers: ${markers.joinToString()}")
        ctx.log("get-link page: ${body.length} bytes, ids=${p.ids.take(40).joinToString()}")
        p.scripts.filter { it.src.isNotEmpty() }.forEach { ctx.log("script src: ${it.src}") }
        val keywords = listOf("go-link", "links/go", "ajax", "serialize", "cookie", "counter", "get-link", "submit")
        p.scripts.filter { it.src.isEmpty() && it.text.isNotBlank() }.forEachIndexed { i, sc ->
            val t = sc.text.replace(Regex("\\s+"), " ").trim()
            val hits = keywords.filter { t.contains(it, ignoreCase = true) }
            if (hits.isNotEmpty() || t.length < 300) {
                ctx.log("inline script #$i (${t.length} chars, ${hits.joinToString()}): ${t.take(1500)}")
            }
        }
        // Inputs outside the form or added for JS (e.g. tokens) also matter.
        val formHtml = Regex("<form[^>]*id=[\"']?${config.getLinkFormId}[\"']?[\\s\\S]*?</form>", RegexOption.IGNORE_CASE).find(body)?.value
        if (formHtml != null) ctx.log("get-link form html: ${formHtml.replace(Regex("\\s+"), " ").take(2500)}")
    }

    private fun sameHost(a: HttpUrl, b: HttpUrl) =
        a.host.lowercase().removePrefix("www.") == b.host.lowercase().removePrefix("www.")

    /** A link on [page] back to the entry link (it may carry extra query parameters the site wants). */
    private fun entryLinkOn(page: Page, entry: HttpUrl): HttpUrl? =
        page.parsed.anchors.asSequence()
            .mapNotNull { page.url.resolve(it.href) }
            .firstOrNull { sameHost(it, entry) && it.encodedPath == entry.encodedPath }

    private fun isDestination(url: HttpUrl): Boolean {
        val h = url.host.lowercase()
        return config.destinationHosts.any { h == it || h.endsWith(".$it") }
    }

    private fun hasCaptcha(page: Page): Boolean {
        val b = page.body
        return listOf("class=\"g-recaptcha", "class=\"h-captcha", "class=\"cf-turnstile", "data-sitekey=").any { b.contains(it, ignoreCase = true) }
    }

    private fun learn(ctx: ResolveContext, follower: RedirectFollower, host: String) {
        val h = host.lowercase()
        if (config.destinationHosts.any { h == it || h.endsWith(".$it") }) return
        if (follower.addFlowHost(h)) ctx.log("flow host: $h")
    }

    private suspend fun getLink(ctx: ResolveContext, page: Page, form: HtmlForm?): String {
        // Already unlocked in the HTML?
        page.parsed.anchors.firstOrNull { a ->
            config.getLinkAnchorClass in a.classes && "disabled" !in a.classes &&
                a.href.isNotEmpty() && a.href != "#" && !a.href.startsWith("javascript", ignoreCase = true)
        }?.let { a -> page.url.resolve(a.href)?.let { return it.toString() } }

        if (form == null) throw ResolveException(ErrorKind.GET_LINK_FAILED, "Get Link page without #${config.getLinkFormId} form")
        logGetLinkPage(ctx, page, form)
        arm(ctx, page, form)

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

    companion object {
        /** AdLinkFly-style resolver for any host (unknown shortener domains). */
        fun generic() = LinkShortXResolver(
            LinkShortXConfig(entryHosts = emptySet(), flowHosts = setOf("hindisink.com"), matchAnyHost = true),
            name = "AdLinkFly (generic)",
        )
    }
}
