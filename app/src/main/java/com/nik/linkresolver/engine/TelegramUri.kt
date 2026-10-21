package com.nik.linkresolver.engine

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Turns t.me / telegram.me links into tg:// links that open the Telegram app directly. Pure Kotlin for tests. */
object TelegramUri {
    private val HOSTS = setOf("t.me", "telegram.me", "telegram.dog", "www.t.me", "www.telegram.me")
    private val NAME = Regex("^[A-Za-z][A-Za-z0-9_]{3,}$")

    fun toApp(url: String): String? {
        val u = url.toHttpUrlOrNull() ?: return null
        if (u.host.lowercase() !in HOSTS) return null
        val seg = u.pathSegments.filter { it.isNotEmpty() }
        if (seg.isEmpty()) return null
        val first = seg[0]
        fun q(name: String) = u.queryParameter(name)?.let { "&$name=" + java.net.URLEncoder.encode(it, "UTF-8") } ?: ""
        return when {
            first.startsWith("+") -> "tg://join?invite=" + first.removePrefix("+")
            first == "joinchat" && seg.size > 1 -> "tg://join?invite=" + seg[1]
            first == "addlist" || first == "s" || first == "c" || first == "share" -> null // leave these to the browser hop
            NAME.matches(first) && seg.size > 1 && seg[1].all(Char::isDigit) -> "tg://resolve?domain=$first&post=${seg[1]}"
            NAME.matches(first) -> "tg://resolve?domain=$first" + q("start") + q("startgroup")
            else -> null
        }
    }
}
