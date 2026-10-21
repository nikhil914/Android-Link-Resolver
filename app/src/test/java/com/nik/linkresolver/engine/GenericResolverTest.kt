package com.nik.linkresolver.engine

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GenericResolverTest {
    private val clock = FakeClock()
    private lateinit var site: FakeLinkShortX
    private val domains = MemoryDomainStore()

    @After fun tearDown() = site.stop()

    private fun registry() = ResolverRegistry(listOf(LinkShortXResolver()), LinkShortXResolver.generic(), domains)

    private fun resolve(tuning: SiteTuning = SiteTuning.NONE, debug: InMemoryDebugRecorder = InMemoryDebugRecorder(clock)) = runBlocking {
        ResolverRunner(registry(), clock, clock, tuning = tuning).run(site.entryUrl, site.client(), debug) {}
    }

    @Test
    fun unknownDomainWithUnknownBlogIsResolvedAndLearned() {
        // New shortener whose first redirect does NOT carry the entry link: the blog host must be found by probing.
        site = FakeLinkShortX(clock, entryHost = "newshort.io", blogHost = "other-blog.net").start()
        site.override = { r ->
            if (r.path == "/AbC123") {
                MockResponse().setResponseCode(302).addHeader("Location", site.url("other-blog.net", "/q7m4vk29.php?id=xyz"))
                    .addHeader("Set-Cookie", "AppSession=s1; Path=/")
            } else null
        }
        val debug = InMemoryDebugRecorder(clock)
        val result = resolve(debug = debug)
        assertEquals(debug.asText(), site.finalUrl, (result as ResolveResult.Success).finalUrl)
        assertTrue(domains.contains("newshort.io"))
        assertTrue(debug.asText().contains("checking whether it is part of the flow"))
        assertTrue(site.seen.none { it.host.contains("telegram") })
    }

    @Test
    fun learnedDomainIsSupportedInText() {
        site = FakeLinkShortX(clock).start()
        assertTrue(QueueWorker.extractLinks("https://newshort.io/a", registry()).isEmpty())
        domains.learn("newshort.io")
        assertEquals(listOf("https://newshort.io/a"), QueueWorker.extractLinks("x https://newshort.io/a y", registry()))
        assertNotNull(registry().find("https://www.newshort.io/b".toHttpUrl()))
        assertNull(registry().find("https://example.com/b".toHttpUrl()))
        assertNotNull(registry().findAny("https://example.com/b".toHttpUrl()))
    }

    @Test
    fun plainRedirectOnUnknownDomainReturnsTarget() {
        site = FakeLinkShortX(clock, entryHost = "tiny.example", blogHost = "dest.example").start()
        site.override = { r ->
            when {
                r.path == "/AbC123" -> MockResponse().setResponseCode(301).addHeader("Location", site.url("dest.example", "/file/42"))
                r.path == "/file/42" -> site.html("<html><head><title>My file</title></head><body>${"x".repeat(5000)}</body></html>")
                else -> null
            }
        }
        val result = resolve() as ResolveResult.Success
        assertEquals(site.url("dest.example", "/file/42"), result.finalUrl)
    }

    @Test
    fun continuePageIsSubmittedThenGetLink() {
        site = FakeLinkShortX(clock, entryHost = "adfly-like.com", steps = 1).start()
        site.override = { r ->
            when {
                r.path == "/AbC123" && r.method == "GET" -> site.html(
                    """<html><head><title>Continue</title></head><body>
                    <form method="post" id="link-view" action="/AbC123"><input type="hidden" name="_csrfToken" value="csrf1">
                    <input type="hidden" name="f_n" value="slc"><button type="submit">Click here to continue</button></form></body></html>""",
                )
                r.path == "/AbC123" && r.method == "POST" -> MockResponse().setResponseCode(302).addHeader("Location", site.url("adfly-like.com", "/AbC123?done=1"))
                else -> null
            }
        }
        val result = resolve()
        assertEquals(site.finalUrl, (result as ResolveResult.Success).finalUrl)
        val post = site.seen.single { it.method == "POST" && it.path == "/AbC123" }
        assertTrue(post.body.contains("f_n=slc"))
    }

    @Test
    fun captchaIsReported() {
        site = FakeLinkShortX(clock, entryHost = "captcha-short.com").start()
        site.override = { r ->
            if (r.path == "/AbC123") site.html(
                """<html><head><title>Verify</title></head><body><form method="post" id="link-view" action="/AbC123">
                <div class="g-recaptcha" data-sitekey="abc"></div><input type="hidden" name="_csrfToken" value="c"></form></body></html>""",
            ) else null
        }
        assertEquals(ErrorKind.CAPTCHA, (resolve() as ResolveResult.Failure).kind)
        assertFalse(domains.contains("captcha-short.com"))
    }

    @Test
    fun nonShortlinkPageFailsClearly() {
        site = FakeLinkShortX(clock, entryHost = "blog.example").start()
        site.override = { site.html("<html><head><title>Just a blog</title></head><body>${"text ".repeat(2000)}</body></html>") }
        assertEquals(ErrorKind.NOT_A_SHORTLINK, (resolve() as ResolveResult.Failure).kind)
    }

    @Test
    fun rejectionsRaiseSiteTuningAndCleanRunsEaseIt() {
        site = FakeLinkShortX(clock).start()
        val store = mutableMapOf<String, Pair<Long, Int>>()
        val tuning = TuningRule({ store[it] ?: (0L to 0) }, { s, e, c -> store[s] = e to c }, cleanRunsToEase = 2)
        val r1 = runBlocking {
            ResolverRunner(registry(), clock, clock, tuning = tuning).run(site.entryUrl, site.client(), InMemoryDebugRecorder(clock)) {}
        }
        assertTrue(r1 is ResolveResult.Success)
        assertEquals(0L, tuning.extraWaitMs("linkshortx.in"))

        tuning.onRejected("linkshortx.in")
        tuning.onRejected("linkshortx.in")
        assertEquals(1_000L, tuning.extraWaitMs("linkshortx.in"))
        tuning.onCleanRun("linkshortx.in")
        assertEquals(1_000L, tuning.extraWaitMs("linkshortx.in"))
        tuning.onCleanRun("linkshortx.in")
        assertEquals(750L, tuning.extraWaitMs("linkshortx.in"))
    }

    @Test
    fun tunedWaitIsAppliedToSteps() {
        site = FakeLinkShortX(clock, minFirstStepMs = 11_000, minStepMs = 11_000).start()
        val store = mutableMapOf("linkshortx.in" to (2_000L to 0))
        val tuning = TuningRule({ store[it] ?: (0L to 0) }, { s, e, c -> store[s] = e to c })
        val result = runBlocking {
            ResolverRunner(registry(), clock, clock, tuning = tuning).run(site.entryUrl, site.client(), InMemoryDebugRecorder(clock)) {}
        }
        assertTrue(result is ResolveResult.Success)
        assertEquals(0, site.rejections) // 9.2 + 2.0 s ≥ 11 s on every step
    }
}
