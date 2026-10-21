package com.nik.linkresolver.data

import android.content.Context
import androidx.core.content.ContextCompat
import com.nik.linkresolver.data.db.BatchEntity
import com.nik.linkresolver.data.db.ResolveDao
import com.nik.linkresolver.data.db.ResolveEntity
import com.nik.linkresolver.data.db.ResolveLogEntity
import com.nik.linkresolver.data.db.Status
import com.nik.linkresolver.engine.QueueStore
import com.nik.linkresolver.engine.QueueWorker
import com.nik.linkresolver.engine.QueuedLink
import com.nik.linkresolver.engine.ResolveEvent
import com.nik.linkresolver.engine.ResolveResult
import com.nik.linkresolver.engine.ResolverRegistry
import com.nik.linkresolver.service.ResolveService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Queues links in the database and drives [ResolveService]. One instance per process (see AppGraph). */
class BatchManager(
    private val context: Context,
    private val dao: ResolveDao,
    private val registry: ResolverRegistry,
) : QueueStore {

    private val _progress = MutableStateFlow<Map<Long, String>>(emptyMap())
    /** Live status text for running links, by resolve id. */
    val progress: StateFlow<Map<Long, String>> = _progress

    /** Set by [ResolveService] while it drains the queue. */
    @Volatile var worker: QueueWorker? = null

    fun extract(text: String): List<String> = QueueWorker.extractLinks(text, registry)

    /**
     * Adds [urls] as a new batch and starts the service. Links already resolved successfully are copied from
     * history unless [fresh] is true. Returns the batch id.
     */
    suspend fun enqueue(urls: List<String>, source: String, label: String, fresh: Boolean = false, channel: String? = null): Long {
        val now = System.currentTimeMillis()
        val batchId = dao.insertBatch(BatchEntity(createdAt = now, source = source, label = label))
        val rows = urls.distinct().map { url ->
            val cached = if (fresh) null else dao.lastDone(url)
            ResolveEntity(
                batchId = batchId,
                sourceUrl = url,
                finalUrl = cached?.finalUrl,
                status = if (cached != null) Status.DONE else Status.QUEUED,
                site = siteOf(url),
                source = source,
                channel = channel,
                createdAt = now,
                finishedAt = if (cached != null) now else null,
                durationMs = if (cached != null) 0 else null,
                fromCache = cached != null,
            )
        }
        dao.insertAll(rows)
        if (rows.any { it.status == Status.QUEUED }) startService()
        return batchId
    }

    /** Saves a single resolve done on the result screen. */
    suspend fun recordSingle(url: String, source: String, result: ResolveResult, log: String) {
        val now = System.currentTimeMillis()
        val id = dao.insert(
            when (result) {
                is ResolveResult.Success -> ResolveEntity(
                    batchId = null, sourceUrl = url, finalUrl = result.finalUrl, status = Status.DONE, site = siteOf(url),
                    source = source, createdAt = now - result.elapsedMs, startedAt = now - result.elapsedMs,
                    finishedAt = now, durationMs = result.elapsedMs,
                )
                is ResolveResult.Failure -> ResolveEntity(
                    batchId = null, sourceUrl = url, status = Status.FAILED, error = result.message, errorKind = result.kind.name,
                    site = siteOf(url), source = source, createdAt = now - result.elapsedMs, startedAt = now - result.elapsedMs,
                    finishedAt = now, durationMs = result.elapsedMs,
                )
            },
        )
        dao.putLog(ResolveLogEntity(id, log.takeLast(MAX_LOG)))
    }

    suspend fun lastDone(url: String) = dao.lastDone(url)

    suspend fun retryFailed(batchId: Long) {
        if (dao.requeueFailed(batchId) > 0) startService()
    }

    suspend fun retry(id: Long) {
        if (dao.requeue(id) > 0) startService()
    }

    suspend fun cancelBatch(batchId: Long?) {
        dao.cancelQueued(batchId, System.currentTimeMillis())
        val w = worker ?: return
        dao.runningIds(batchId).forEach { w.cancel(it) }
    }

    suspend fun deleteBatch(batchId: Long) = dao.deleteBatch(batchId)

    suspend fun log(id: Long): String? = dao.log(id)

    fun startService() {
        val w = worker
        if (w != null) {
            w.wake()
        }
        // Always (re)start: a worker that just finished draining may be about to stop.
        ContextCompat.startForegroundService(context, ResolveService.intent(context))
    }

    // ---- QueueStore (used by the worker inside the service)

    override suspend fun claimNext(): QueuedLink? =
        dao.claimNext(System.currentTimeMillis())?.let { QueuedLink(it.id, it.sourceUrl) }

    override suspend fun finish(id: Long, result: ResolveResult, log: String) {
        val now = System.currentTimeMillis()
        when (result) {
            is ResolveResult.Success -> dao.finish(id, Status.DONE, result.finalUrl, null, null, now, result.elapsedMs)
            is ResolveResult.Failure -> dao.finish(id, Status.FAILED, null, result.message, result.kind.name, now, result.elapsedMs)
        }
        dao.putLog(ResolveLogEntity(id, log.takeLast(MAX_LOG)))
        clearProgress(id)
    }

    override suspend fun markCancelled(id: Long, log: String) {
        dao.finish(id, Status.CANCELLED, null, "Cancelled", null, System.currentTimeMillis(), null)
        dao.putLog(ResolveLogEntity(id, log.takeLast(MAX_LOG)))
        clearProgress(id)
    }

    fun onProgress(id: Long, event: ResolveEvent) {
        val text = when (event) {
            is ResolveEvent.Fetching -> "Opening…"
            is ResolveEvent.StepSubmitting -> "${step(event.step, event.total)} — submitting"
            is ResolveEvent.StepWaiting -> "${step(event.step, event.total)} — ${event.secondsLeft} s"
            is ResolveEvent.Retrying -> "Retry ${event.attempt}/${event.max}"
            is ResolveEvent.GetLinkWaiting -> "Get Link — ${event.secondsLeft} s"
            ResolveEvent.Finalizing -> "Getting final link…"
        }
        _progress.update { it + (id to text) }
    }

    private fun clearProgress(id: Long) = _progress.update { it - id }

    private fun step(step: Int, total: String) = if (step == 99) "Last step" else "Step $step/$total"

    private fun siteOf(url: String): String = url.toHttpUrlOrNull()?.host?.removePrefix("www.") ?: ""

    companion object {
        private const val MAX_LOG = 60_000
    }
}
