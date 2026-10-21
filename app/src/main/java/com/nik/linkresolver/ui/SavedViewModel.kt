package com.nik.linkresolver.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nik.linkresolver.data.SaveProgress
import com.nik.linkresolver.data.db.CollectionEntity
import com.nik.linkresolver.data.db.CollectionPostEntity
import com.nik.linkresolver.data.db.CollectionSummary
import com.nik.linkresolver.data.db.ResolveEntity
import com.nik.linkresolver.data.db.Status
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

/** A saved post with its supported links worked out. */
data class SavedPost(val post: CollectionPostEntity, val links: List<String>)

@OptIn(ExperimentalCoroutinesApi::class)
class SavedViewModel(app: Application) : AndroidViewModel(app) {
    private val g = app.graph
    private val dao = g.db.collections()

    val collections: StateFlow<List<CollectionSummary>> =
        dao.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val progress: StateFlow<Map<Long, SaveProgress>> = g.collections.progress

    /** Open collection id, or null for the list. */
    val openId = MutableStateFlow<Long?>(null)
    val query = MutableStateFlow("")
    val onlyWithLinks = MutableStateFlow(true)
    val selected = MutableStateFlow<Set<String>>(emptySet())

    val open: StateFlow<CollectionEntity?> = openId
        .flatMapLatest { id -> if (id == null) flowOf(null) else dao.observe(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val allPosts = openId
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else dao.observePosts(id) }
        .map { list -> list.map { SavedPost(it, g.collections.links(it)) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val posts: StateFlow<List<SavedPost>> = combine(allPosts, query, onlyWithLinks) { list, q, only ->
        val t = q.trim()
        list.filter { (!only || it.links.isNotEmpty()) && (t.isEmpty() || it.post.text.contains(t, ignoreCase = true) || it.post.urls.contains(t, ignoreCase = true)) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Latest result per link anywhere in history (for ✓ badges in the posts list). */
    val known: StateFlow<Map<String, ResolveEntity>> = allPosts
        .map { l -> l.flatMap { it.links }.distinct() }
        .flatMapLatest { urls ->
            if (urls.isEmpty()) flowOf(emptyMap())
            else combine(urls.chunked(500).map { g.db.resolves().observeLatestFor(it) }) { parts -> parts.toList().flatten().associateBy { it.sourceUrl } }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** This collection's own results (its runs only). */
    val results: StateFlow<List<ResolveEntity>> = openId
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else dao.observeResults(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun openCollection(id: Long?) {
        openId.value = id
        selected.value = emptySet()
        query.value = ""
    }

    fun toggle(url: String) = selected.update { if (url in it) it - url else it + url }
    fun togglePost(p: SavedPost) = selected.update { s -> if (p.links.all { it in s }) s - p.links.toSet() else s + p.links }
    fun selectAll() { selected.value = posts.value.flatMap { it.links }.toSet() }
    fun selectUnresolved() { selected.value = posts.value.flatMap { it.links }.filter { known.value[it]?.status != Status.DONE }.toSet() }
    fun clearSelection() { selected.value = emptySet() }

    fun resolveSelected(fresh: Boolean, onBatch: (Long) -> Unit) {
        val id = openId.value ?: return
        val sel = selected.value
        val links = allPosts.value.flatMap { p -> p.links.filter { it in sel }.map { it to p.post.messageId } }.distinctBy { it.first }
        viewModelScope.launch {
            g.collections.process(id, links, fresh)?.let { selected.value = emptySet(); onBatch(it) }
        }
    }

    fun update() = openId.value?.let { id -> viewModelScope.launch { g.collections.updateChannel(id) } }
    fun rename(name: String) = openId.value?.let { id -> viewModelScope.launch { g.collections.rename(id, name) } }
    fun delete() = openId.value?.let { id -> viewModelScope.launch { g.collections.delete(id); openId.value = null } }
}
