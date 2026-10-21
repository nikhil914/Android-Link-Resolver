package com.nik.linkresolver.engine

import okhttp3.HttpUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.FormElement

data class HtmlForm(
    val id: String,
    /** Raw action attribute ("" means the page URL). */
    val action: String,
    val method: String,
    /** Fields exactly as a browser would submit them (no submit buttons). */
    val fields: List<Pair<String, String>>,
)

data class HtmlAnchor(val href: String, val classes: Set<String>, val id: String)

data class ParsedPage(
    val title: String,
    val forms: List<HtmlForm>,
    val anchors: List<HtmlAnchor>,
    val ids: Set<String>,
    val metaRefresh: String?,
) {
    fun form(id: String): HtmlForm? = forms.firstOrNull { it.id == id }
}

object PageParser {
    private val META_URL = Regex("""url\s*=\s*['"]?([^'";]+)""", RegexOption.IGNORE_CASE)
    private val JS_ASSIGN = Regex("""(?:window\.)?location(?:\.href)?\s*=\s*['"]([^'"]+)['"]""")
    private val JS_CALL = Regex("""location\.(?:replace|assign)\(\s*['"]([^'"]+)['"]""")
    private val SKIP_INPUT_TYPES = setOf("submit", "button", "image", "reset", "file")

    fun parse(html: String, baseUrl: HttpUrl? = null): ParsedPage {
        val doc = Jsoup.parse(html, baseUrl?.toString() ?: "")
        val title = doc.title().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")

        val forms = doc.select("form").map { el ->
            HtmlForm(
                id = el.id(),
                action = el.attr("action"),
                method = el.attr("method").ifEmpty { "get" }.lowercase(),
                fields = formFields(el),
            )
        }
        val anchors = doc.select("a").map { a ->
            HtmlAnchor(a.attr("href"), a.classNames(), a.id())
        }
        val ids = doc.select("[id]").map { it.id() }.toSet()
        val meta = doc.select("meta[http-equiv]")
            .firstOrNull { it.attr("http-equiv").equals("refresh", ignoreCase = true) }
            ?.attr("content")
            ?.let { META_URL.find(it)?.groupValues?.get(1)?.trim() }

        return ParsedPage(title, forms, anchors, ids, meta)
    }

    /** Named controls a browser would submit: skips buttons/submit/image/file, unchecked boxes, disabled. */
    private fun formFields(form: Element): List<Pair<String, String>> {
        val controls: List<Element> = if (form is FormElement) form.elements() else form.select("input, textarea, select")
        val out = mutableListOf<Pair<String, String>>()
        for (c in controls) {
            val name = c.attr("name")
            if (name.isEmpty() || c.hasAttr("disabled")) continue
            when (c.tagName().lowercase()) {
                "input" -> {
                    val type = c.attr("type").ifEmpty { "text" }.lowercase()
                    if (type in SKIP_INPUT_TYPES) continue
                    if ((type == "checkbox" || type == "radio") && !c.hasAttr("checked")) continue
                    val value = if (c.hasAttr("value")) c.attr("value") else if (type == "checkbox" || type == "radio") "on" else ""
                    out += name to value
                }
                "textarea" -> out += name to c.wholeText()
                "select" -> {
                    val opt = c.select("option[selected]").firstOrNull() ?: c.select("option").firstOrNull()
                    if (opt != null) out += name to (if (opt.hasAttr("value")) opt.attr("value") else opt.text())
                }
                // <button> is never sent: we submit the form directly, not via a button click.
            }
        }
        return out
    }

    /** `window.location.href = "..."` / `location.replace("...")` on small redirect pages. */
    fun jsRedirect(html: String): String? =
        (JS_ASSIGN.find(html) ?: JS_CALL.find(html))?.groupValues?.get(1)
}
