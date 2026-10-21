package com.nik.linkresolver.engine

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageParserTest {
    @Test
    fun collectsOnlyFieldsABrowserWouldSubmit() {
        val p = PageParser.parse(
            """<html><head><title>  Step 2
                of 4 · Hello </title></head><body>
            <form id="fwd" method="POST" action="/next">
              <input type="hidden" name="a" value="1">
              <input name="plain" value="x">
              <input type="submit" name="submit" value="Go">
              <input type="image" name="img">
              <input type="checkbox" name="off" value="1">
              <input type="checkbox" name="on" value="2" checked>
              <input type="radio" name="r" value="b" checked>
              <input type="hidden" name="dis" value="z" disabled>
              <input type="hidden" value="no-name">
              <textarea name="t">hello
            world</textarea>
              <select name="s"><option value="1">one</option><option value="2" selected>two</option></select>
              <button name="btn" value="1">Continue</button>
            </form></body></html>""",
        )
        assertEquals("Step 2 of 4 · Hello", p.title)
        val f = p.form("fwd")!!
        assertEquals("post", f.method)
        assertEquals("/next", f.action)
        assertEquals(
            listOf("a" to "1", "plain" to "x", "on" to "2", "r" to "b", "t" to "hello\n            world", "s" to "2"),
            f.fields,
        )
    }

    @Test
    fun findsMetaRefreshAndAnchors() {
        val p = PageParser.parse(
            """<html><head><meta http-equiv="Refresh" content="0; url='https://t.me/x'"></head>
            <body><a class="get-link disabled" href="#">wait</a></body></html>""",
        )
        assertEquals("https://t.me/x", p.metaRefresh)
        assertEquals(setOf("get-link", "disabled"), p.anchors.single().classes)
    }

    @Test
    fun jsRedirectVariants() {
        assertEquals("https://hindisink.com", PageParser.jsRedirect("""setTimeout(function(){ window.location.href = "https://hindisink.com"; });"""))
        assertEquals("/a", PageParser.jsRedirect("location.replace('/a')"))
        assertEquals("/b", PageParser.jsRedirect("location = \"/b\""))
        assertNull(PageParser.jsRedirect("console.log('hi')"))
    }

    @Test
    fun formWithoutMethodDefaultsToGet() {
        val p = PageParser.parse("<form id='x'><input name='q' value='1'></form>", "https://a.com/".toHttpUrl())
        assertEquals("get", p.form("x")!!.method)
    }

    @Test
    fun urlExtraction() {
        assertEquals(
            "https://linkshortx.in/AbC",
            ResolverRunner.extractUrl("Join now 👉 https://linkshortx.in/AbC. Thanks"),
        )
        assertEquals("https://linkshortx.in/AbC", ResolverRunner.normalize("linkshortx.in/AbC").toString())
        assertNull(ResolverRunner.normalize("hello there"))
    }

    @Test
    fun savedFixturesParse() {
        // Real pages from debug bundles go in src/test/resources/fixtures/; each must at least parse.
        val dir = javaClass.classLoader?.getResource("fixtures")?.file?.let { java.io.File(it) } ?: return
        dir.listFiles { f -> f.extension == "html" }?.forEach { f ->
            val p = PageParser.parse(f.readText())
            assertTrue(f.name, p.title.isNotEmpty() || p.forms.isNotEmpty() || p.anchors.isNotEmpty() || f.length() < 4000)
        }
    }
}
