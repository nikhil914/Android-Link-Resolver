package com.nik.linkresolver.engine

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Turns t.me / telegram.me links into tg:// links that open the Telegram app directly. Pure Kotlin for tests. */
object TelegramUri {
    private val HOSTS = setOf("t.me", "telegram.me", "telegram.dog", "www.t.me", "www.telegram.me")
    private val NAME = Regex("^[A-Za-z][A-Za-z0-9_]{3,}$")

    /** `t.me/<bot>?start=<param>` → (bot, param), else null. */
    fun botStart(url: String): Pair<String, String>? {
        val u = url.toHttpUrlOrNull() ?: return null
        if (u.host.lowercase() !in HOSTS) return null
        val seg = u.pathSegments.filter { it.isNotEmpty() }
        val param = u.queryParameter("start") ?: return null
        val name = seg.singleOrNull() ?: return null
        return if (NAME.matches(name)) name to param else null
    }

    /** True for invite links (`t.me/+hash`, `t.me/joinchat/hash`). */
    fun isInvite(url: String): Boolean {
        val u = url.toHttpUrlOrNull() ?: return false
        if (u.host.lowercase() !in HOSTS) return false
        val seg = u.pathSegments.filter { it.isNotEmpty() }
        return seg.firstOrNull()?.startsWith("+") == true || (seg.firstOrNull() == "joinchat" && seg.size > 1)
    }

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
