package com.nik.linkresolver.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nik.linkresolver.data.db.BatchEntity
import com.nik.linkresolver.data.db.BatchSummary
import com.nik.linkresolver.data.db.ResolveEntity
import com.nik.linkresolver.graph
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val g = app.graph
    private val dao = g.db.resolves()

    val batches: StateFlow<List<BatchSummary>> =
        dao.observeBatches().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val progress: StateFlow<Map<Long, String>> = g.batches.progress

    val historyQuery = MutableStateFlow("")
    val historyStatus = MutableStateFlow("")

    val history: StateFlow<List<ResolveEntity>> =
        combine(historyQuery.debounce(250), historyStatus) { q, s -> q.trim() to s }
            .flatMapLatest { (q, s) -> dao.observeHistory(q, s) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun batchRows(batchId: Long): Flow<List<ResolveEntity>> = dao.observeBatch(batchId)
    fun batchInfo(batchId: Long): Flow<BatchEntity?> = dao.observeBatchInfo(batchId)

    fun extract(text: String): List<String> = g.batches.extract(text)

    /** Queues [urls] as a batch; [onCreated] gets the batch id. */
    fun enqueue(urls: List<String>, source: String, fresh: Boolean, onCreated: (Long) -> Unit) {
        viewModelScope.launch {
            val label = "${urls.size} link" + (if (urls.size == 1) "" else "s")
            onCreated(g.batches.enqueue(urls, source, label, fresh))
        }
    }

    fun retryFailed(batchId: Long) = viewModelScope.launch { g.batches.retryFailed(batchId) }
    fun retry(id: Long) = viewModelScope.launch { g.batches.retry(id) }
    fun cancel(batchId: Long) = viewModelScope.launch { g.batches.cancelBatch(batchId) }
    fun delete(batchId: Long) = viewModelScope.launch { g.batches.deleteBatch(batchId) }

    fun loadLog(id: Long, onLoaded: (String) -> Unit) = viewModelScope.launch {
        onLoaded(g.batches.log(id) ?: "(no log saved)")
    }
}
