package com.nik.linkresolver.engine

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LinkShortXResolverTest {
    private lateinit var clock: FakeClock
    private lateinit var site: FakeLinkShortX

    @Before
    fun setUp() {
        clock = FakeClock()
    }

    @After
    fun tearDown() {
        site.stop()
    }

    private fun runner(config: LinkShortXConfig = LinkShortXConfig()) =
        ResolverRunner(ResolverRegistry(listOf(LinkShortXResolver(config))), clock, clock)

    private fun resolve(
        config: LinkShortXConfig = LinkShortXConfig(),
        readTimeoutMs: Long = 5_000,
        events: MutableList<ResolveEvent> = mutableListOf(),
        debug: InMemoryDebugRecorder = InMemoryDebugRecorder(clock),
    ): ResolveResult = runBlocking {
        runner(config).run(site.entryUrl, site.client(readTimeoutMs), debug) { events += it }
    }

    @Test
    fun fullFlowReturnsFinalUrl() {
        site = FakeLinkShortX(clock, steps = 3).start()
        val events = mutableListOf<ResolveEvent>()
        val result = resolve(events = events)

        assertTrue("expected success, got $result", result is ResolveResult.Success)
        result as ResolveResult.Success
        assertEquals(site.finalUrl, result.finalUrl)
        assertEquals(0, site.rejections)

        // Waited the server timer for every step, plus the get-link counter.
        assertTrue(events.any { it is ResolveEvent.StepWaiting && it.step == 1 && it.total == "3" })
        assertTrue(events.any { it is ResolveEvent.StepWaiting && it.step == 2 && it.total == "3" })
        assertTrue(events.any { it is ResolveEvent.StepWaiting && it.step == 3 })
        assertTrue(events.any { it is ResolveEvent.GetLinkWaiting })
        assertTrue(events.contains(ResolveEvent.Finalizing))
        // ~3 × 9.2 + 5.5 s of virtual time
        assertTrue("elapsed ${result.elapsedMs}", result.elapsedMs in 32_000..34_000)
    }

    @Test
    fun finalDestinationIsNeverRequested() {
        site = FakeLinkShortX(clock).start()
        resolve()
        assertTrue(site.seen.none { it.host.contains("telegram") })
        assertTrue(site.seen.all { it.host == "linkshortx.in" || it.host == "hindisink.com" })
    }

    @Test
    fun submitButtonsAndUncheckedBoxesAreNeverSent() {
        site = FakeLinkShortX(clock).start()
        resolve()
        val stepPosts = site.seen.filter { it.method == "POST" && it.path == "/go" }
        assertEquals(3, stepPosts.size)
        for (p in stepPosts) {
            assertFalse(p.body, p.body.contains("submit="))
            assertFalse(p.body, p.body.contains("btn="))
            assertFalse(p.body, p.body.contains("unchecked="))
            assertTrue(p.body, p.body.contains("ref=AbC123"))
        }
    }

    @Test
    fun sendsBrowserHeadersCookiesAndAjaxHeaders() {
        site = FakeLinkShortX(clock).start()
        resolve()
        val first = site.seen.first()
        assertTrue(first.headers["User-Agent"]!!.contains("Mobile Safari"))
        assertTrue(first.headers.containsKey("Accept-Language"))

        val step2 = site.seen.filter { it.method == "POST" && it.path == "/go" }[1]
        assertTrue(step2.headers["Referer"]!!.startsWith("http://hindisink.com:"))
        assertTrue(step2.headers["Origin"]!!.startsWith("http://hindisink.com:"))
        assertTrue(step2.headers["Cookie"]!!.contains("link=AbC123"))

        val go = site.seen.single { it.path == "/links/go" }
        assertEquals("XMLHttpRequest", go.headers["X-Requested-With"])
        assertTrue(go.headers["Accept"]!!.startsWith("application/json"))
        assertTrue(go.headers["Cookie"]!!.contains("AppSession=s1"))
        assertTrue(go.body.contains("ad_form_data="))
    }

    @Test
    fun rejectedStepIsRepostedWithSameToken() {
        site = FakeLinkShortX(clock, steps = 3).start()
        // Wait too short on purpose (8 s < 9 s server minimum): the server rejects, we retry after 1.5 s.
        val result = resolve(LinkShortXConfig(stepWaitMs = 8_000, firstStepWaitMs = 9_200))

        assertTrue("expected success, got $result", result is ResolveResult.Success)
        assertEquals(site.finalUrl, (result as ResolveResult.Success).finalUrl)
        // Step 2 rejected once (8 s), accepted at 9.5 s. Step 3 then waits 8.5 s — still too short, one more retry.
        assertEquals(2, site.rejections)
        val tokens = site.seen.filter { it.path == "/go" }.map { Regex("token=t(\\d)").find(it.body)!!.groupValues[1] }
        assertEquals(listOf("1", "2", "2", "3", "3"), tokens)
        assertTrue(clock.sleeps.contains(1_500L))
    }

    @Test
    fun instantStepOneIsRejectedThenRetried() {
        // Old behaviour (post step 1 at once) against the current site: rejected until ~9 s, then accepted.
        site = FakeLinkShortX(clock, steps = 2).start()
        val result = resolve(LinkShortXConfig(firstStepWaitMs = 0))
        assertEquals(site.finalUrl, (result as ResolveResult.Success).finalUrl)
        assertEquals(6, site.rejections) // 0, 1.5, 3, 4.5, 6, 7.5 s rejected; 9 s accepted
    }

    @Test
    fun noRejectionsWithDefaultWaits() {
        site = FakeLinkShortX(clock, steps = 4).start()
        val result = resolve()
        assertTrue(result is ResolveResult.Success)
        assertEquals(0, site.rejections)
    }

    @Test
    fun givesUpAfterMaxRetries() {
        site = FakeLinkShortX(clock, minStepMs = 60_000).start()
        val result = resolve()
        assertTrue(result is ResolveResult.Failure)
        result as ResolveResult.Failure
        assertEquals(ErrorKind.UNRECOGNISED_PAGE, result.kind)
        assertEquals(7, site.rejections) // first post + 6 retries
    }

    @Test
    fun accessDeniedIsMapped() {
        site = FakeLinkShortX(clock).start()
        site.override = { r ->
            if (r.path!!.startsWith("/q7m4vk29.php")) {
                site.html("<html><head><title>Access Denied</title></head><body>We detected a proxy or VPN.</body></html>")
            } else {
                null
            }
        }
        val result = resolve() as ResolveResult.Failure
        assertEquals(ErrorKind.ACCESS_DENIED, result.kind)
        assertEquals("Site blocked this network (VPN/proxy/datacenter IP)", result.message)
    }

    @Test
    fun timeoutIsMappedToUnreachable() {
        site = FakeLinkShortX(clock).start()
        site.override = { MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE) }
        val result = resolve(readTimeoutMs = 300) as ResolveResult.Failure
        assertEquals(ErrorKind.UNREACHABLE, result.kind)
        assertTrue(result.message.startsWith("Can't reach site on this network — try mobile data"))
    }

    @Test
    fun enabledGetLinkAnchorIsUsedDirectly() {
        site = FakeLinkShortX(clock, steps = 1).start()
        site.override = { r ->
            if (r.path == "/AbC123?done=1") {
                site.html("""<html><head><title>Get Link</title></head><body>
                    <a class="btn get-link" href="https://t.me/direct_channel">Get Link</a></body></html>""")
            } else {
                null
            }
        }
        val result = resolve() as ResolveResult.Success
        assertEquals("https://t.me/direct_channel", result.finalUrl)
        assertTrue(site.seen.none { it.path == "/links/go" })
    }

    @Test
    fun redirectOutsideFlowHostsIsFinal() {
        site = FakeLinkShortX(clock).start()
        site.override = { r ->
            if (r.path!!.startsWith("/q7m4vk29.php")) {
                MockResponse().setResponseCode(302).addHeader("Location", "https://t.me/early_exit")
            } else {
                null
            }
        }
        val result = resolve() as ResolveResult.Success
        assertEquals("https://t.me/early_exit", result.finalUrl)
    }

    @Test
    fun getLinkErrorJsonIsRetriedThenFails() {
        site = FakeLinkShortX(clock).start()
        site.override = { r -> if (r.path == "/links/go") site.json("""{"status":"error","message":"Bad Request."}""") else null }
        val debug = InMemoryDebugRecorder(clock)
        val result = resolve(debug = debug) as ResolveResult.Failure
        assertEquals(ErrorKind.GET_LINK_FAILED, result.kind)
        assertEquals(4, site.seen.count { it.path == "/links/go" })
        assertTrue(debug.asText().contains("raw response"))
    }

    @Test
    fun unsupportedHostFailsWithoutNetwork() = runBlocking {
        site = FakeLinkShortX(clock).start()
        val result = runner().run("https://example.com/x", site.client(), InMemoryDebugRecorder(clock)) {}
        assertEquals(ErrorKind.UNSUPPORTED_URL, (result as ResolveResult.Failure).kind)
        assertTrue(site.seen.isEmpty())
    }

    @Test
    fun debugRecorderKeepsPagesAndHops() {
        site = FakeLinkShortX(clock).start()
        val debug = InMemoryDebugRecorder(clock)
        resolve(debug = debug)
        assertTrue(debug.hops.any { it.title.startsWith("Step 2 of 3") })
        assertTrue(debug.pages.any { it.body.contains("id=\"fwd\"") })
        assertEquals(site.seen.size, debug.hops.size)
    }
}
