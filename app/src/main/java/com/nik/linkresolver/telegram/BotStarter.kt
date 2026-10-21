package com.nik.linkresolver.telegram

import com.nik.linkresolver.data.Settings
import com.nik.linkresolver.data.db.BotStartDao
import com.nik.linkresolver.data.db.BotStartEntity
import com.nik.linkresolver.data.db.BotStatus
import com.nik.linkresolver.engine.TelegramUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi

/**
 * Starts bots for final `t.me/<bot>?start=…` links through the Telegram login, one at a time and paced
 * ([MIN_GAP_MS] between starts, waiting out flood limits), then saves what the bot replies. This is the only
 * feature that sends anything from the account, so it runs on request or with the opt-in auto setting.
 */
class BotStarter(
    private val telegram: () -> TelegramClient,
    private val dao: BotStartDao,
    private val settings: Settings,
    scope: CoroutineScope,
) {
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var lastStartAt = 0L

    init {
        scope.launch {
            dao.requeueRunning()
            while (true) {
                val next = dao.nextPending()
                if (next == null) {
                    wake.receive()
                    continue
                }
                runCatching { process(next) }.onFailure { e ->
                    dao.finish(next.id, BotStatus.FAILED, System.currentTimeMillis(), null, null, null, false, e.message ?: e.javaClass.simpleName)
                }
            }
        }
    }

    /** Queues a bot start for [finalUrl] unless one is pending, running or done ([again] forces a new one). */
    suspend fun request(finalUrl: String, again: Boolean = false): Boolean {
        val (bot, _) = TelegramUri.botStart(finalUrl) ?: return false
        val last = dao.latestFor(finalUrl)
        if (!again && last != null && last.status in setOf(BotStatus.PENDING, BotStatus.RUNNING, BotStatus.DONE)) return true
        dao.insert(BotStartEntity(finalUrl = finalUrl, bot = bot, status = BotStatus.PENDING, createdAt = System.currentTimeMillis()))
        wake.trySend(Unit)
        return true
    }

    private suspend fun process(job: BotStartEntity) {
        dao.setStatus(job.id, BotStatus.RUNNING)
        val tg = telegram()
        if (!tg.awaitReady()) {
            dao.finish(job.id, BotStatus.FAILED, System.currentTimeMillis(), null, null, null, false, "Telegram is not logged in (Telegram tab)")
            return
        }
        val (bot, param) = TelegramUri.botStart(job.finalUrl) ?: return

        val chat = tg.send(TdApi.SearchPublicChat(bot))
        val botUserId = (chat.type as? TdApi.ChatTypePrivate)?.userId
            ?: throw IllegalStateException("@$bot is not a bot")

        // Pace: never two starts closer than MIN_GAP_MS.
        val wait = lastStartAt + MIN_GAP_MS - System.currentTimeMillis()
        if (wait > 0) delay(wait)
        val sentAt = (System.currentTimeMillis() / 1000).toInt()
        sendWithFloodWait { tg.send(TdApi.SendBotStartMessage(botUserId, chat.id, param)) }
        lastStartAt = System.currentTimeMillis()

        // Collect replies from the bot: until none arrive for QUIET_MS, at most REPLY_TIMEOUT_MS.
        val replies = linkedMapOf<Long, TdApi.Message>()
        val deadline = System.currentTimeMillis() + REPLY_TIMEOUT_MS
        var lastNew = 0L
        while (System.currentTimeMillis() < deadline) {
            delay(POLL_MS)
            val page = tg.send(TdApi.GetChatHistory(chat.id, 0, 0, 30, false)).messages
            page.filter { m -> m.date >= sentAt - 2 && (m.senderId as? TdApi.MessageSenderUser)?.userId == botUserId }
                .forEach { m -> if (replies.put(m.id, m) == null) lastNew = System.currentTimeMillis() }
            if (replies.isNotEmpty() && System.currentTimeMillis() - lastNew > QUIET_MS) break
        }
        val ordered = replies.values.sortedBy { it.id }
        if (ordered.isEmpty()) {
            dao.finish(job.id, BotStatus.NO_REPLY, System.currentTimeMillis(), null, null, null, false, "No reply within ${REPLY_TIMEOUT_MS / 1000} s")
            return
        }

        val text = ordered.mapNotNull { TelegramLinks.formattedText(it.content)?.text?.takeIf(String::isNotBlank) }.joinToString("\n\n").take(4000)
        val urls = ordered.flatMap { TelegramLinks.allUrls(it) }.filter { it.startsWith("http", true) || it.contains("t.me") }.distinct()
        val files = ordered.mapNotNull { describeFile(it.content) }

        var forwarded = false
        if (settings.forwardBotReplies) {
            forwarded = runCatching {
                val me = tg.send(TdApi.GetMe())
                val saved = tg.send(TdApi.CreatePrivateChat(me.id, false))
                sendWithFloodWait {
                    tg.send(TdApi.ForwardMessages().apply {
                        chatId = saved.id
                        fromChatId = chat.id
                        messageIds = ordered.map { it.id }.toLongArray()
                    })
                }
            }.isSuccess
        }
        dao.finish(
            job.id, BotStatus.DONE, System.currentTimeMillis(), text.ifBlank { null },
            urls.joinToString("\n").ifBlank { null }, files.joinToString("\n").ifBlank { null }, forwarded, null,
        )
    }

    /** Telegram answers 429 "Too Many Requests: retry after N" when sending too fast: wait N s once, then retry. */
    private suspend fun <T> sendWithFloodWait(block: suspend () -> T): T = try {
        block()
    } catch (e: TdException) {
        val seconds = Regex("retry after (\\d+)", RegexOption.IGNORE_CASE).find(e.message.orEmpty())?.groupValues?.get(1)?.toLongOrNull()
        if (e.code == 429 && seconds != null && seconds <= 600) {
            delay(seconds * 1000 + 500)
            block()
        } else {
            throw e
        }
    }

    private fun describeFile(c: TdApi.MessageContent?): String? = when (c) {
        is TdApi.MessageDocument -> "File: ${c.document.fileName} (${size(c.document.document.size)})"
        is TdApi.MessageVideo -> "Video: ${c.video.fileName.ifBlank { "video" }} (${size(c.video.video.size)}, ${c.video.duration / 60} min)"
        is TdApi.MessageAudio -> "Audio: ${c.audio.fileName} (${size(c.audio.audio.size)})"
        is TdApi.MessagePhoto -> "Photo"
        is TdApi.MessageAnimation -> "Animation"
        else -> null
    }

    private fun size(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> "%.0f MB".format(bytes / (1L shl 20).toDouble())
        else -> "${bytes / 1024} KB"
    }

    companion object {
        const val MIN_GAP_MS = 10_000L
        private const val POLL_MS = 2_000L
        private const val QUIET_MS = 5_000L
        private const val REPLY_TIMEOUT_MS = 45_000L
    }
}

/** Invite link preview and join. */
object InviteLinks {
    suspend fun preview(tg: TelegramClient, url: String): TdApi.ChatInviteLinkInfo {
        if (!tg.awaitReady()) throw TdException(-1, "Log in on the Telegram tab first")
        return tg.send(TdApi.CheckChatInviteLink(url))
    }

    suspend fun join(tg: TelegramClient, url: String): TdApi.ChatJoinResult = tg.send(TdApi.JoinChatByInviteLink(url))
}
