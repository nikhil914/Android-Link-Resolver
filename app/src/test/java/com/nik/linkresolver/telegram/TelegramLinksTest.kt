package com.nik.linkresolver.telegram

import com.nik.linkresolver.engine.ResolverRegistry
import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertEquals
import org.junit.Test

class TelegramLinksTest {
    private val registry = ResolverRegistry.default()

    private fun message(content: TdApi.MessageContent, markup: TdApi.ReplyMarkup? = null) =
        TdApi.Message().apply { id = 42; date = 1_792_560_000; this.content = content; replyMarkup = markup }

    @Test
    fun findsPlainHiddenPreviewAndButtonLinks() {
        val text = "Episode 5 👉 Download here\nMirror: linkshortx.in/Plain1 and https://example.com/other"
        val hiddenStart = text.indexOf("Download here")
        val bareStart = text.indexOf("linkshortx.in/Plain1")
        val ft = TdApi.FormattedText(
            text,
            arrayOf(
                TdApi.TextEntity(hiddenStart, "Download here".length, TdApi.TextEntityTypeTextUrl("https://urlshortx.io/Hidden")),
                TdApi.TextEntity(bareStart, "linkshortx.in/Plain1".length, TdApi.TextEntityTypeUrl()),
            ),
        )
        val content = TdApi.MessageText().apply {
            this.text = ft
            linkPreview = TdApi.LinkPreview().apply { url = "https://linkshortx.in/Preview" }
        }
        val button = TdApi.InlineKeyboardButton().apply { this.text = "Get"; type = TdApi.InlineKeyboardButtonTypeUrl("https://linkshortx.in/Button") }
        val markup = TdApi.ReplyMarkupInlineKeyboard().apply { rows = arrayOf(arrayOf(button)) }

        val post = TelegramLinks.toPost(message(content, markup), registry)
        assertEquals(
            listOf(
                "https://urlshortx.io/Hidden",
                "https://linkshortx.in/Plain1",
                "https://linkshortx.in/Preview",
                "https://linkshortx.in/Button",
            ),
            post.links,
        )
        assertEquals(42L, post.messageId)
    }

    @Test
    fun readsPhotoCaptionsAndIgnoresUnsupported() {
        val caption = TdApi.FormattedText("Part 2 https://linkshortx.in/Cap2 also https://t.me/somechannel", emptyArray())
        val content = TdApi.MessagePhoto().apply { this.caption = caption }
        assertEquals(listOf("https://linkshortx.in/Cap2"), TelegramLinks.toPost(message(content), registry).links)
    }

    @Test
    fun messageWithoutTextHasNoLinks() {
        assertEquals(emptyList<String>(), TelegramLinks.toPost(message(TdApi.MessageSticker()), registry).links)
    }
}
