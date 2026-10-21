package com.nik.linkresolver.engine

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class SiteRulesTest {
    private fun bundled(): String {
        // The repo's rules/rules.json is what the APK bundles; it must always parse.
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "rules/rules.json").exists()) dir = dir.parentFile
        return File(dir!!, "rules/rules.json").readText()
    }

    @Test
    fun bundledRulesParseAndMatchBuiltInDefaults() {
        val r = SiteRules.parse(bundled())
        assertTrue(r.version >= 1)
        val (name, cfg) = r.sites.single()
        assertEquals("LinkShortX", name)
        assertEquals(setOf("linkshortx.in", "urlshortx.io"), cfg.entryHosts)
        val d = LinkShortXConfig()
        assertEquals(d.stepWaitMs, cfg.stepWaitMs)
        assertEquals(d.armFallbackPath, cfg.armFallbackPath)
        assertEquals(d.armUrlPattern.pattern, cfg.armUrlPattern.pattern)
        assertTrue(r.generic.matchAnyHost)
    }

    @Test
    fun bundledAssetMatchesRepoRules() {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "rules/rules.json").exists()) dir = dir.parentFile
        assertEquals(bundled(), File(dir!!, "app/src/main/assets/rules.json").readText())
    }

    @Test
    fun overridesAndExtraDomainsApply() {
        val r = SiteRules.parse(
            """{"version":5,"minAppVersion":1,"sites":[{"hosts":["newshort.io"],"stepWaitMs":12000,"stepFormId":"next"}],
               "domains":["other.link"]}""",
        )
        val cfg = r.sites.single().second
        assertEquals(12_000L, cfg.stepWaitMs)
        assertEquals("next", cfg.stepFormId)
        assertEquals(LinkShortXConfig().firstStepWaitMs, cfg.firstStepWaitMs) // missing key keeps default
        val reg = r.registry(DomainStore.NONE)
        assertNotNull(reg.find("https://newshort.io/a".toHttpUrl()))
        assertNotNull(reg.find("https://other.link/a".toHttpUrl()))
        assertNull(reg.find("https://linkshortx.in/a".toHttpUrl())) // not in these rules
        assertTrue("other.link" in reg.builtInHosts())
    }

    @Test
    fun replaceWithSwapsResolversInPlace() {
        val reg = ResolverRegistry.default()
        assertNull(reg.find("https://newshort.io/a".toHttpUrl()))
        reg.replaceWith(SiteRules.parse("""{"version":2,"sites":[{"hosts":["newshort.io"]}]}""").registry(DomainStore.NONE))
        assertNotNull(reg.find("https://newshort.io/a".toHttpUrl()))
    }

    @Test
    fun badFilesAreRejected() {
        val bad = listOf(
            "not json",
            """{"sites":[{"hosts":["a.io"]}]}""", // no version
            """{"version":1,"sites":[]}""",
            """{"version":1,"sites":[{"hosts":["not a host"]}]}""",
            """{"version":1,"sites":[{"hosts":["a.io"],"stepWaitMs":999999}]}""",
            """{"version":1,"sites":[{"hosts":["a.io"],"armFallbackPath":"../x"}]}""",
            """{"version":1,"sites":[{"hosts":["a.io"],"stepFormId":"bad id!"}]}""",
            """{"version":1,"sites":[{"hosts":["a.io"],"armUrlPattern":"(unclosed"}]}""",
        )
        for (b in bad) {
            try {
                SiteRules.parse(b)
                fail("accepted: $b")
            } catch (e: IllegalArgumentException) {
                // expected
            }
        }
    }
}
