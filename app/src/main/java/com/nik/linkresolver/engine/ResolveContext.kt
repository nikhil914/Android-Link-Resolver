package com.nik.linkresolver.engine

import kotlinx.coroutines.delay as coDelay

/** Time source. Tests swap in a virtual clock so 9-second waits run instantly. */
interface Clock {
    fun nowMs(): Long

    companion object {
        val SYSTEM: Clock = object : Clock {
            override fun nowMs(): Long = System.currentTimeMillis()
        }
    }
}

/** Cancellable sleep. Default delegates to coroutines' delay. */
fun interface Sleeper {
    suspend fun sleep(ms: Long)

    companion object {
        val REAL: Sleeper = Sleeper { coDelay(it) }
    }
}

/** Everything a [Resolver] needs for one resolve. Create a new one per resolve (fresh cookies). */
class ResolveContext(
    val http: FlowHttp,
    val clock: Clock,
    private val sleeper: Sleeper,
    val debug: DebugRecorder,
    private val onProgress: (ResolveEvent) -> Unit,
) {
    fun progress(event: ResolveEvent) = onProgress(event)

    fun log(message: String) = debug.log(message)

    suspend fun delay(ms: Long) {
        if (ms > 0) sleeper.sleep(ms)
    }

    /** Sleeps [ms], emitting [event] with the whole seconds left roughly once a second. */
    suspend fun countdown(ms: Long, event: (secondsLeft: Int) -> ResolveEvent) {
        val end = clock.nowMs() + ms
        while (true) {
            val left = end - clock.nowMs()
            if (left <= 0) break
            progress(event(((left + 999) / 1000).toInt()))
            delay(minOf(left, 1000L))
        }
    }
}
