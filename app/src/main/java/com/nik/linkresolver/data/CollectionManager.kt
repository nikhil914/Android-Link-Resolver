package com.nik.linkresolver.data

import com.nik.linkresolver.data.db.CollectionDao
import com.nik.linkresolver.data.db.CollectionEntity
import com.nik.linkresolver.data.db.CollectionKind
import com.nik.linkresolver.data.db.CollectionPostEntity
import com.nik.linkresolver.data.db.Source
import com.nik.linkresolver.engine.QueueWorker
import com.nik.linkresolver.engine.ResolverRegistry
import com.nik.linkresolver.engine.ResolverRunner
import com.nik.linkresolver.telegram.TelegramClient
import com.nik.linkresolver.telegram.TelegramLinks
import com.nik.linkresolver.telegram.TgPost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi

/** Progress of a whole-channel save or update: messages read so far. */
data class SaveProgress(val read: Int, val saved: Int, val error: String? = null)

/** Saves channel posts as named collections, keeps them browsable offline, and processes them as batches. */
class CollectionManager(
    private val dao: CollectionDao,
    private val telegram: () -> TelegramClient,
    private val batches: BatchManager,
    private val registry: ResolverRegistry,
    private val scope: CoroutineScope,
) {
    private val _progress = MutableStateFlow<Map<Long, SaveProgress>>(emptyMap())
    val progress: StateFlow<Map<Long, SaveProgress>> = _progress

    /** Supported shortlinks among a saved post's URLs (decided now, so later-learned domains count). */
    fun links(post: CollectionPostEntity): List<String> = QueueWorker.extractLinks(post.urls, registry)

    /** Starts saving the whole history of a channel; returns the new collection id at once. */
    suspend fun saveWholeChannel(chatId: Long, chatTitle: String, name: String): Long {
        val now = System.currentTimeMillis()
        val id = dao.insert(
            CollectionEntity(name = name, kind = CollectionKind.CHANNEL, chatId = chatId, chatTitle = chatTitle, createdAt = now, updatedAt = now, saving = true),
        )
        scope.launch { readHistory(id, chatId, stopAtOrBelow = 0) }
        return id
    }

    /** Adds posts newer than the newest one saved. */
    suspend fun updateChannel(id: Long) {
        val c = dao.get(id) ?: return
        if (c.kind != CollectionKind.CHANNEL || c.saving) return
        dao.updateProgress(id, c.postCount, c.newestMessageId, System.currentTimeMillis(), saving = true)
        scope.launch { readHistory(id, c.chatId, stopAtOrBelow = c.newestMessageId) }
    }

    private suspend fun readHistory(id: Long, chatId: Long, stopAtOrBelow: Long) {
        val tg = telegram()
        var from = 0L
        var read = 0
        var newest = 0L
        try {
            if (!tg.awaitReady()) throw IllegalStateException("Telegram is not logged in")
            while (read < MAX_MESSAGES) {
                val page = tg.history(chatId, from, PAGE)
                if (page.isEmpty()) break
                if (newest == 0L) newest = page.first().id
                val fresh = page.filter { it.id > stopAtOrBelow }
                dao.insertPosts(fresh.mapNotNull { toEntity(id, it) })
                read += fresh.size
                val count = dao.postCount(id)
                _progress.update { it + (id to SaveProgress(read, count)) }
                dao.updateProgress(id, count, newest, System.currentTimeMillis(), saving = true)
                if (fresh.size < page.size || page.size < PAGE) break // reached saved posts or the start of the chat
                from = page.last().id
            }
            _progress.update { it - id }
        } catch (e: Exception) {
            _progress.update { it + (id to SaveProgress(read, dao.postCount(id), e.message ?: e.javaClass.simpleName)) }
        } finally {
            dao.updateProgress(id, dao.postCount(id), newest, System.currentTimeMillis(), saving = false)
        }
    }

    /** Saves the posts holding [selected] links (only those links) under [name]. */
    suspend fun saveSelection(chatId: Long, chatTitle: String, name: String, posts: List<TgPost>, selected: Set<String>): Long {
        val now = System.currentTimeMillis()
        val rows = posts.mapNotNull { p ->
            val links = p.links.filter { it in selected }
            if (links.isEmpty()) null else CollectionPostEntity(collectionId = 0, messageId = p.messageId, date = p.date, text = p.text, urls = links.joinToString("\n"))
        }
        val id = dao.insert(
            CollectionEntity(
                name = name, kind = CollectionKind.SELECTION, chatId = chatId, chatTitle = chatTitle, createdAt = now, updatedAt = now,
                postCount = rows.size, newestMessageId = rows.maxOfOrNull { it.messageId } ?: 0,
            ),
        )
        dao.insertPosts(rows.map { it.copy(collectionId = id) })
        return id
    }

    /**
     * Resolves [links] (link → post id) of collection [id] as one batch named after the collection.
     * Returns the batch id.
     */
    suspend fun process(id: Long, links: List<Pair<String, Long>>, fresh: Boolean): Long? {
        val c = dao.get(id) ?: return null
        if (links.isEmpty()) return null
        return batches.enqueue(
            urls = links.map { it.first },
            source = Source.COLLECTION,
            label = "${c.name} — results",
            fresh = fresh,
            channel = c.chatTitle,
            messageIds = links.toMap(),
            collectionId = id,
        )
    }

    suspend fun rename(id: Long, name: String) = dao.rename(id, name.trim(), System.currentTimeMillis())
    suspend fun delete(id: Long) = dao.delete(id)

    private fun toEntity(collectionId: Long, m: TdApi.Message): CollectionPostEntity? {
        val text = TelegramLinks.formattedText(m.content)?.text.orEmpty()
        val urls = TelegramLinks.allUrls(m)
            .map { u -> if (u.startsWith("http", ignoreCase = true) || u.any(Char::isWhitespace)) u else "https://$u" }
            .flatMap { ResolverRunner.extractAllUrls(it) }
            .distinct()
        if (text.isBlank() && urls.isEmpty()) return null
        return CollectionPostEntity(collectionId = collectionId, messageId = m.id, date = m.date, text = text, urls = urls.joinToString("\n"))
    }

    companion object {
        private const val PAGE = 500
        const val MAX_MESSAGES = 50_000
    }
}
