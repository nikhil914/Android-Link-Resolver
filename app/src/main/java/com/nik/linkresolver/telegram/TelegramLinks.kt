package com.nik.linkresolver.telegram

import com.nik.linkresolver.engine.QueueWorker
import com.nik.linkresolver.engine.ResolverRegistry
import org.drinkless.tdlib.TdApi

/** One channel post with the supported shortlinks found in it. */
data class TgPost(
    val messageId: Long,
    val date: Int,
    val text: String,
    val links: List<String>,
)

object TelegramLinks {
    /** Text and caption of a message, whatever its type. */
    fun formattedText(content: TdApi.MessageContent?): TdApi.FormattedText? = when (content) {
        is TdApi.MessageText -> content.text
        is TdApi.MessagePhoto -> content.caption
        is TdApi.MessageVideo -> content.caption
        is TdApi.MessageDocument -> content.caption
        is TdApi.MessageAnimation -> content.caption
        is TdApi.MessageAudio -> content.caption
        is TdApi.MessageVoiceNote -> content.caption
        else -> null
    }

    /**
     * Every URL in a message: plain URLs in the text, links hidden behind text (text_url entities), the link
     * preview, and URL buttons under the post.
     */
    fun allUrls(m: TdApi.Message): List<String> {
        val out = mutableListOf<String>()
        val ft = formattedText(m.content)
        if (ft != null) {
            val text = ft.text.orEmpty()
            ft.entities?.forEach { e ->
                when (val t = e.type) {
                    is TdApi.TextEntityTypeTextUrl -> out += t.url
                    is TdApi.TextEntityTypeUrl -> if (e.offset >= 0 && e.offset + e.length <= text.length) {
                        out += text.substring(e.offset, e.offset + e.length)
                    }
                    else -> Unit
                }
            }
            out += text // the regex in extractLinks also catches anything without an entity
        }
        (m.content as? TdApi.MessageText)?.linkPreview?.url?.let { out += it }
        (m.replyMarkup as? TdApi.ReplyMarkupInlineKeyboard)?.rows?.forEach { row ->
            row.forEach { b -> (b.type as? TdApi.InlineKeyboardButtonTypeUrl)?.url?.let { out += it } }
        }
        return out
    }

    /** Supported shortlinks in a message, in order, without duplicates. */
    fun supportedLinks(m: TdApi.Message, registry: ResolverRegistry): List<String> =
        QueueWorker.extractLinks(
            allUrls(m).joinToString("\n") { u ->
                // URL entities can be bare ("linkshortx.in/abc"); give those a scheme so they're recognised.
                if (u.startsWith("http", ignoreCase = true) || u.any { it.isWhitespace() }) u else "https://$u"
            },
            registry,
        )

    fun toPost(m: TdApi.Message, registry: ResolverRegistry): TgPost =
        TgPost(m.id, m.date, formattedText(m.content)?.text.orEmpty(), supportedLinks(m, registry))
}
