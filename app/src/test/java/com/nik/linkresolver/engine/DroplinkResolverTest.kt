package com.nik.linkresolver.engine

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLEncoder

/**
 * droplink.co/<code> → tech8s.net/safe2.php?link=<code> → JS to google.com/url?url=<article> → article (no step form)
 * → droplink.co/<code> again with the article as Referer → Get Link page → JSON url.
 */
class DroplinkResolverTest {
    private val clock = FakeClock()
    private lateinit var site: FakeLinkShortX

    @After fun tearDown() = site.stop()

    private fun droplink(returnWaitMs: Long = 0) = LinkShortXResolver(
        LinkShortXConfig(
            entryHosts = setOf("droplink.co"),
            flowHosts = setOf("tech8s.net"),
            returnToEntry = true,
            returnWaitMs = returnWaitMs,
        ),
        "Droplink",
    )

    private fun start(articleLinksBack: Boolean = false): FakeLinkShortX {
        site = FakeLinkShortX(clock, entryHost = "droplink.co", blogHost = "tech8s.net").start()
        val article = site.url("tech8s.net", "/mindfulness-based-stress-reduction/")
        site.override = { r ->
            val referer = r.getHeader("Referer").orEmpty()
            when {
                r.path == "/AbC123" && referer.contains("/mindfulness-based-stress-reduction/") ->
                    MockResponse().setResponseCode(302).addHeader("Location", site.url("droplink.co", "/AbC123?done=1"))
                r.path == "/AbC123" ->
                    MockResponse().setResponseCode(302).addHeader("Location", site.url("tech8s.net", "/safe2.php?link=AbC123"))
                r.path == "/safe2.php?link=AbC123" -> site.html(
                    """<html><head></head><body><script>window.location.href = "https://www.google.com/url?sa=t&source=web&rct=j&url=$article&ved=2ahUKE&usg=AOvVaw";</script></body></html>""",
                )
                r.path == "/mindfulness-based-stress-reduction/" -> site.html(
                    "<html><head><title>Mindfulness-Based Stress Reduction</title></head><body><article>${"text ".repeat(1000)}</article>" +
                        (if (articleLinksBack) "<a href=\"${site.url("droplink.co", "/AbC123")}\">Continue</a>" else "") +
                        "</body></html>",
                )
                else -> null
            }
        }
        return site
    }

    private fun resolve(resolver: LinkShortXResolver, debug: InMemoryDebugRecorder) = runBlocking {
        ResolverRunner(ResolverRegistry(listOf(resolver)), clock, clock).run(site.entryUrl, site.client(), debug) {}
    }

    @Test
    fun goesBackToEntryWithArticleAsReferer() {
        start()
        val debug = InMemoryDebugRecorder(clock)
        val result = resolve(droplink(), debug)
        assertEquals(debug.asText(), site.finalUrl, (result as ResolveResult.Success).finalUrl)
        // Google's redirector is skipped, never requested (the test DNS would fail it).
        assertTrue(site.seen.none { it.host.contains("google") })
        assertTrue(debug.asText().contains("going back to"))
    }

    @Test
    fun followsArticleLinkBackAndWaits() {
        start(articleLinksBack = true)
        val debug = InMemoryDebugRecorder(clock)
        val result = resolve(droplink(returnWaitMs = 4_000), debug)
        assertEquals(debug.asText(), site.finalUrl, (result as ResolveResult.Success).finalUrl)
        assertTrue(debug.asText().contains("going back to ${site.url("droplink.co", "/AbC123")}"))
        assertTrue(clock.sleeps.any { it >= 1_000 } && clock.sleeps.sum() >= 9_000) // return wait + 5 s counter
    }

    @Test
    fun withoutReturnToEntryTheArticleIsUnrecognised() {
        start()
        val debug = InMemoryDebugRecorder(clock)
        val plain = LinkShortXResolver(LinkShortXConfig(entryHosts = setOf("droplink.co"), flowHosts = setOf("tech8s.net")), "Droplink")
        val result = resolve(plain, debug)
        assertEquals(debug.asText(), ErrorKind.UNRECOGNISED_PAGE, (result as ResolveResult.Failure).kind)
    }

    @Test
    fun blogHostIsLearnedFromCodeInRedirect() {
        start()
        val debug = InMemoryDebugRecorder(clock)
        val unknownBlog = LinkShortXResolver(LinkShortXConfig(entryHosts = setOf("droplink.co"), flowHosts = emptySet(), returnToEntry = true), "Droplink")
        val result = resolve(unknownBlog, debug)
        assertEquals(debug.asText(), site.finalUrl, (result as ResolveResult.Success).finalUrl)
        assertTrue(debug.asText().contains("flow host: tech8s.net"))
    }

    @Test
    fun unwrapsGoogleRedirector() {
        val f = RedirectFollower(emptySet())
        val target = "https://tech8s.net/a-post/"
        assertEquals(target, f.unwrap("https://www.google.com/url?sa=t&url=$target&ved=x".toHttpUrl()).toString())
        assertEquals(target, f.unwrap("https://www.google.com/url?q=${URLEncoder.encode(target, "UTF-8")}".toHttpUrl()).toString())
        assertEquals("https://www.google.com/search?q=x", f.unwrap("https://www.google.com/search?q=x".toHttpUrl()).toString())
    }

    @Test
    fun bundledRulesHaveDroplink() {
        var dir: java.io.File? = java.io.File("").absoluteFile
        while (dir != null && !java.io.File(dir, "rules/rules.json").exists()) dir = dir.parentFile
        val rules = SiteRules.parse(java.io.File(dir!!, "rules/rules.json").readText())
        val cfg = rules.sites.first { it.first == "Droplink" }.second
        assertTrue(cfg.returnToEntry)
        assertTrue("tech8s.net" in cfg.flowHosts)
        assertTrue(rules.registry(DomainStore.NONE).find("https://droplink.co/NJm4Wsr".toHttpUrl()) != null)
    }
}
