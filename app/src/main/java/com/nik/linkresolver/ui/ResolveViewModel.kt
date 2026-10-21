package com.nik.linkresolver.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nik.linkresolver.engine.InMemoryDebugRecorder
import com.nik.linkresolver.engine.ResolveEvent
import com.nik.linkresolver.engine.ResolveResult
import com.nik.linkresolver.data.db.Source
import com.nik.linkresolver.engine.ResolverRunner
import com.nik.linkresolver.graph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface ResolveUi {
    data object Idle : ResolveUi
    data class Running(val headline: String, val secondsLeft: Int? = null, val step: Int? = null, val total: Int? = null) : ResolveUi
    data class Done(val finalUrl: String, val elapsedMs: Long, val cached: Boolean = false) : ResolveUi
    data class Failed(val message: String, val elapsedMs: Long) : ResolveUi
    data object Cancelled : ResolveUi
}

data class ResolveScreenState(
    val url: String = "",
    val ui: ResolveUi = ResolveUi.Idle,
    val network: String = "",
    val log: String = "",
    val hasDebugBundle: Boolean = false,
)

/** Resolution lives here, not in the Activity, so rotation doesn't restart it. */
class ResolveViewModel(app: Application) : AndroidViewModel(app) {
    private val g = app.graph
    private val _state = MutableStateFlow(ResolveScreenState())
    val state: StateFlow<ResolveScreenState> = _state

    private val _openRequests = Channel<String>(Channel.BUFFERED)
    /** Final URLs to open automatically (when the setting is on). */
    val openRequests = _openRequests.receiveAsFlow()

    private var job: Job? = null

    /** Starts resolving [url] unless it's already the one in progress/done. */
    private var source = Source.LINK

    fun startIfNew(raw: String, source: String) {
        val url = ResolverRunner.normalize(raw)?.toString() ?: raw
        val ui = _state.value.ui
        if (url == _state.value.url && (ui is ResolveUi.Running || ui is ResolveUi.Done)) return
        this.source = source
        job?.cancel()
        _state.value = ResolveScreenState(url = url, ui = ResolveUi.Running("Opening link…"), network = g.networks.describe())
        job = viewModelScope.launch {
            // Already resolved before? Show it instantly (the user can still resolve again).
            val cached = g.batches.lastDone(url)
            if (cached?.finalUrl != null) {
                _state.update { it.copy(ui = ResolveUi.Done(cached.finalUrl, cached.durationMs ?: 0, cached = true), network = "") }
                if (g.settings.autoOpenFinal) _openRequests.trySend(cached.finalUrl)
            } else {
                start(url)
            }
        }
    }

    fun start(url: String) {
        job?.cancel()
        _state.value = ResolveScreenState(url = url, ui = ResolveUi.Running("Opening link…"), network = g.networks.describe())
        job = viewModelScope.launch(Dispatchers.Default) {
            val recorder = InMemoryDebugRecorder()
            var finished: ResolveResult? = null
            val lease = if (g.settings.forceMobileData) {
                _state.update { it.copy(ui = ResolveUi.Running("Connecting to mobile data…")) }
                g.networks.acquireCellular()
            } else {
                null
            }
            try {
                val label = when {
                    lease != null -> "Mobile data (forced)"
                    g.settings.forceMobileData -> g.networks.describe() + " — mobile data unavailable"
                    else -> g.networks.describe()
                }
                recorder.log("network: $label")
                _state.update { it.copy(network = label, ui = ResolveUi.Running("Opening link…")) }
                val client = lease?.let { g.http.boundTo(it.network) } ?: g.http.base

                val result = g.runner.run(url, client, recorder) { event -> onEvent(event) }
                finished = result
                withContext(NonCancellable + Dispatchers.IO) {
                    runCatching { g.batches.recordSingle(url, source, result, recorder.asText()) }
                }
                _state.update {
                    it.copy(
                        ui = when (result) {
                            is ResolveResult.Success -> ResolveUi.Done(result.finalUrl, result.elapsedMs)
                            is ResolveResult.Failure -> ResolveUi.Failed(result.message, result.elapsedMs)
                        },
                        log = recorder.asText(),
                    )
                }
                if (result is ResolveResult.Success && g.settings.autoOpenFinal) _openRequests.trySend(result.finalUrl)
            } finally {
                lease?.close()
                withContext(NonCancellable + Dispatchers.IO) {
                    val summary = "url: $url\nresult: ${finished ?: "cancelled"}"
                    runCatching { g.debugStore.save(recorder, summary) }
                    _state.update {
                        it.copy(
                            log = recorder.asText(),
                            hasDebugBundle = g.debugStore.hasBundle(),
                            ui = if (finished == null) ResolveUi.Cancelled else it.ui,
                        )
                    }
                }
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    fun retry() {
        val url = _state.value.url
        if (url.isNotEmpty()) start(url)
    }

    fun shareDebugIntent() = g.debugStore.shareIntent()

    private fun stepLabel(step: Int, total: String) = if (step == 99) "Last step" else "Step $step of $total"

    private fun onEvent(e: ResolveEvent) {
        val ui = when (e) {
            is ResolveEvent.Fetching -> ResolveUi.Running("Opening link…")
            is ResolveEvent.StepSubmitting -> ResolveUi.Running("${stepLabel(e.step, e.total)} — submitting…", null, e.step, e.total.toIntOrNull())
            is ResolveEvent.StepWaiting -> ResolveUi.Running(
                "${stepLabel(e.step, e.total)} — waiting ${e.secondsLeft} s (server timer)", e.secondsLeft, e.step, e.total.toIntOrNull(),
            )
            is ResolveEvent.Retrying -> ResolveUi.Running("${e.reason} — retry ${e.attempt}/${e.max}")
            is ResolveEvent.GetLinkWaiting -> ResolveUi.Running("Getting final link — waiting ${e.secondsLeft} s", e.secondsLeft)
            ResolveEvent.Finalizing -> ResolveUi.Running("Getting final link…")
        }
        _state.update { it.copy(ui = ui) }
    }
}
