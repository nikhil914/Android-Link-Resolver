package com.nik.linkresolver.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap

/** A link waiting in the queue. */
data class QueuedLink(val id: Long, val url: String)

/** Persistence for the queue (Room in the app, in-memory in tests). */
interface QueueStore {
    /** Atomically takes the oldest queued link and marks it running, or returns null. */
    suspend fun claimNext(): QueuedLink?

    suspend fun finish(id: Long, result: ResolveResult, log: String)

    suspend fun markCancelled(id: Long, log: String)
}

/**
 * Drains a [QueueStore] with up to [concurrency] resolves at once. Every link gets its own [ResolverRunner.run]
 * call, which means its own [FlowHttp] with a fresh cookie jar: links never share a session, so the site can't
 * hand one link's result to another.
 */
class QueueWorker(
    private val runner: ResolverRunner,
    private val store: QueueStore,
    private val concurrency: () -> Int,
    private val clock: Clock = Clock.SYSTEM,
    private val onProgress: (id: Long, event: ResolveEvent) -> Unit = { _, _ -> },
    private val onFinished: (id: Long, result: ResolveResult?) -> Unit = { _, _ -> },
) {
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val running = ConcurrentHashMap<Long, Job>()

    val activeCount: Int get() = running.size

    /** Call after adding links to the store so an idle-but-draining worker picks them up. */
    fun wake() {
        wake.trySend(Unit)
    }

    /** Cancels one running link (it is marked cancelled). Queued links are cancelled in the store directly. */
    fun cancel(id: Long) {
        running[id]?.cancel()
    }

    fun cancelAll() {
        running.values.forEach { it.cancel() }
    }

    /** Runs until the store has nothing queued and nothing is running. */
    suspend fun drain(client: () -> OkHttpClient) = coroutineScope {
        val slots = Semaphore(concurrency().coerceIn(1, MAX_CONCURRENCY))
        while (true) {
            slots.acquire()
            val item = store.claimNext()
            if (item == null) {
                slots.release()
                if (running.isEmpty()) break
                wake.receive() // a job finished or something was enqueued
                continue
            }
            val job = launch(start = CoroutineStart.LAZY) {
                val debug = InMemoryDebugRecorder(clock)
                try {
                    val result = runner.run(item.url, client(), debug) { onProgress(item.id, it) }
                    withContext(NonCancellable) { runCatching { store.finish(item.id, result, debug.asText()) } }
                    onFinished(item.id, result)
                } catch (e: CancellationException) {
                    withContext(NonCancellable) { runCatching { store.markCancelled(item.id, debug.asText()) } }
                    onFinished(item.id, null)
                    throw e
                } finally {
                    running.remove(item.id)
                    slots.release()
                    wake.trySend(Unit)
                }
            }
            running[item.id] = job
            job.start()
        }
    }

    companion object {
        const val MAX_CONCURRENCY = 6

        /** Every supported link in [text], in order, without duplicates (e.g. several copied Telegram posts). */
        fun extractLinks(text: String, registry: ResolverRegistry): List<String> =
            ResolverRunner.extractAllUrls(text)
                .mapNotNull { ResolverRunner.normalize(it) }
                .filter { registry.find(it) != null }
                .map { it.toString() }
                .distinct()
    }
}
