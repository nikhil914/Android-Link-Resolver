package com.nik.linkresolver.telegram

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nik.linkresolver.data.db.ResolveEntity
import com.nik.linkresolver.data.db.Source
import com.nik.linkresolver.graph
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ChannelState(
    val chat: TgChat,
    val posts: List<TgPost> = emptyList(),
    /** Messages read so far (posts without links are not listed). */
    val scanned: Int = 0,
    val oldestId: Long = 0,
    val loading: Boolean = false,
    val endReached: Boolean = false,
    /** Posts with an id above this arrived since the last visit. */
    val lastSeenId: Long = 0,
    val error: String? = null,
    /** Newest message id in the chat (from the first page). */
    val newestId: Long = 0,
    /** Checked in the background for new posts. */
    val watching: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
class TelegramViewModel(app: Application) : AndroidViewModel(app) {
    private val g = app.graph
    private val tg = g.telegram
    val auth: StateFlow<TgAuth> = tg.auth
    val error: StateFlow<String?> = tg.error

    val busy = MutableStateFlow(false)

    val chats = MutableStateFlow<List<TgChat>>(emptyList())
    val chatsLoading = MutableStateFlow(false)
    val chatQuery = MutableStateFlow("")
    val recent = MutableStateFlow(tg.prefs.recentChats())

    val channel = MutableStateFlow<ChannelState?>(null)
    val selected = MutableStateFlow<Set<String>>(emptySet())

    /** Latest saved result per link shown in the open channel. */
    val known: StateFlow<Map<String, ResolveEntity>> = channel
        .map { st -> st?.posts?.flatMap { it.links }?.distinct().orEmpty() }
        .flatMapLatest { urls ->
            if (urls.isEmpty()) {
                flowOf(emptyMap())
            } else {
                val flows = urls.chunked(500).map { g.db.resolves().observeLatestFor(it) }
                combine(flows) { parts -> parts.toList().flatten().associateBy { it.sourceUrl } }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    init {
        tg.start()
    }

    fun clearError() = tg.clearError()

    // ---- login

    fun configure(apiId: String, apiHash: String) {
        val id = apiId.trim().toIntOrNull()
        if (id == null || id <= 0 || apiHash.trim().length < 16) return
        tg.configure(id, apiHash)
    }

    fun submitPhone(p: String) = step { tg.submitPhone(p) }
    fun submitCode(c: String) = step { tg.submitCode(c) }
    fun submitPassword(p: String) = step { tg.submitPassword(p) }
    fun logOut() = step { tg.logOut(); chats.value = emptyList(); channel.value = null }

    private fun step(block: suspend () -> Unit) {
        viewModelScope.launch {
            busy.value = true
            try { block() } finally { busy.value = false }
        }
    }

    // ---- chats

    fun loadChats() {
        if (chatsLoading.value) return
        viewModelScope.launch {
            chatsLoading.value = true
            try {
                chats.value = tg.chats().sortedWith(compareBy<TgChat> { kindOrder(it.kind) }.thenByDescending { it.lastMessageDate })
            } catch (e: TdException) {
                tg.clearError()
            } finally {
                chatsLoading.value = false
            }
        }
    }

    private fun kindOrder(kind: String) = when (kind) {
        "Channel" -> 0
        "Group" -> 1
        else -> 2
    }

    // ---- one channel

    fun openChat(chat: TgChat) {
        tg.prefs.addRecent(chat.id, chat.title)
        recent.value = tg.prefs.recentChats()
        selected.value = emptySet()
        channel.value = ChannelState(chat, lastSeenId = tg.prefs.lastSeen(chat.id), watching = tg.prefs.isWatched(chat.id))
        loadMore(PAGE)
    }

    fun openRecent(id: Long, title: String) = openChat(TgChat(id, title, "", 0))

    fun closeChat() {
        channel.value = null
        selected.value = emptySet()
    }

    /** Reads the next [count] older messages and adds the posts that contain supported links. */
    fun loadMore(count: Int = PAGE) {
        val st = channel.value ?: return
        if (st.loading || st.endReached) return
        channel.value = st.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                val messages = tg.history(st.chat.id, st.oldestId, count)
                val posts = messages.map { TelegramLinks.toPost(it, g.registry) }.filter { it.links.isNotEmpty() }
                channel.update { cur ->
                    if (cur == null || cur.chat.id != st.chat.id) return@update cur
                    cur.copy(
                        posts = (cur.posts + posts).distinctBy { it.messageId },
                        scanned = cur.scanned + messages.size,
                        oldestId = messages.lastOrNull()?.id ?: cur.oldestId,
                        endReached = messages.size < count,
                        loading = false,
                        newestId = if (cur.newestId == 0L) messages.firstOrNull()?.id ?: 0 else cur.newestId,
                    )
                }
                // Remember the newest post so the next visit can mark what's new.
                if (st.oldestId == 0L) messages.firstOrNull()?.let { tg.prefs.setLastSeen(st.chat.id, it.id) }
            } catch (e: TdException) {
                channel.update { it?.copy(loading = false, error = e.message) }
            }
        }
    }

    /** Re-reads from the newest message (e.g. to pick up new posts). */
    fun refresh() {
        val st = channel.value ?: return
        channel.value = ChannelState(st.chat, lastSeenId = st.lastSeenId, watching = st.watching)
        loadMore(PAGE)
    }

    /** Watch on: posts from now on are checked in the background (existing posts count as handled). */
    fun toggleWatch() {
        val st = channel.value ?: return
        if (st.watching) {
            tg.prefs.unwatch(st.chat.id)
        } else {
            tg.prefs.watch(st.chat.id, st.chat.title, st.newestId)
            if (g.settings.watchIntervalMin <= 0) {
                g.settings.watchIntervalMin = 60
                com.nik.linkresolver.service.ChannelWatchWorker.schedule(getApplication(), 60)
            }
        }
        channel.update { it?.copy(watching = !st.watching) }
        recent.value = tg.prefs.recentChats()
    }

    fun isWatched(id: Long) = tg.prefs.isWatched(id)

    // ---- selection

    fun toggle(url: String) = selected.update { if (url in it) it - url else it + url }

    fun togglePost(post: TgPost) = selected.update { sel ->
        if (post.links.all { it in sel }) sel - post.links.toSet() else sel + post.links
    }

    fun selectAll() { selected.value = allLinks().toSet() }

    fun selectUnresolved() {
        val k = known.value
        selected.value = allLinks().filter { k[it]?.status != "DONE" }.toSet()
    }

    fun selectNew() {
        val st = channel.value ?: return
        selected.value = st.posts.filter { it.messageId > st.lastSeenId && st.lastSeenId > 0 }.flatMap { it.links }.toSet()
    }

    fun clearSelection() { selected.value = emptySet() }

    private fun allLinks(): List<String> = channel.value?.posts?.flatMap { it.links }?.distinct().orEmpty()

    /** Queues the selected links (in post order) as one batch; [onCreated] gets the batch id. */
    fun resolveSelected(fresh: Boolean, onCreated: (Long) -> Unit) {
        val st = channel.value ?: return
        val sel = selected.value
        val ordered = st.posts.flatMap { p -> p.links.filter { it in sel }.map { it to p.messageId } }.distinctBy { it.first }
        if (ordered.isEmpty()) return
        viewModelScope.launch {
            val id = g.batches.enqueue(
                urls = ordered.map { it.first },
                source = Source.TELEGRAM,
                label = "${st.chat.title}: ${ordered.size} link" + if (ordered.size == 1) "" else "s",
                fresh = fresh,
                channel = st.chat.title,
                messageIds = ordered.toMap(),
            )
            selected.value = emptySet()
            onCreated(id)
        }
    }

    companion object {
        const val PAGE = 200
    }
}
