package com.nik.linkresolver.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.nik.linkresolver.data.db.Source
import com.nik.linkresolver.engine.ResolverRunner
import com.nik.linkresolver.graph
import kotlinx.coroutines.launch

/** Opened by link taps (VIEW), Share (SEND) and the main screen. */
class ResolveActivity : ComponentActivity() {
    private val vm: ResolveViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null && inBackground(intent)) return // finishes itself once queued
        handle(intent) // no-op on rotation: the ViewModel already has this URL
        setContent {
            AppTheme {
                val ctx = LocalContext.current
                LaunchedEffect(Unit) { vm.openRequests.collect { ctx.openUrl(it) } }
                val state by vm.state.collectAsStateWithLifecycle()
                ResolveScreen(
                    state = state,
                    onCancel = vm::cancel,
                    onRetry = vm::retry,
                    onResolveAgain = { vm.start(vm.state.value.url) },
                    onShareDebug = { vm.shareDebugIntent()?.let(::startActivity) ?: ctx.toast("No debug data yet") },
                    onClose = ::finish,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (inBackground(intent)) return
        handle(intent)
    }

    /**
     * Background mode: a tapped or shared single link is queued (or answered from history) and the result comes as a
     * notification; no screen is shown. Returns false when the normal screen should handle it.
     */
    private fun inBackground(intent: Intent): Boolean {
        val g = application.graph
        if (!g.settings.backgroundMode) return false
        if (intent.action != Intent.ACTION_VIEW && intent.action != Intent.ACTION_SEND) return false
        if (!androidx.core.app.NotificationManagerCompat.from(this).areNotificationsEnabled()) return false
        val raw = when (intent.action) {
            Intent.ACTION_VIEW -> intent.dataString
            else -> intent.getStringExtra(Intent.EXTRA_TEXT)
        } ?: return false
        val links = g.batches.extract(raw).ifEmpty { listOfNotNull(ResolverRunner.extractUrl(raw)?.let { ResolverRunner.normalize(it)?.toString() }) }
        if (links.isEmpty()) return false
        val source = if (intent.action == Intent.ACTION_VIEW) Source.LINK else Source.SHARE
        // Queue while this (invisible) screen is still in front, so the foreground service may start; then close.
        lifecycleScope.launch {
            try {
                val cached = if (links.size == 1) g.batches.lastDone(links[0]) else null
                if (cached?.finalUrl != null) {
                    com.nik.linkresolver.service.ResultNotifier.notify(applicationContext, 1003, "Already resolved", listOf(cached.finalUrl), 0)
                } else {
                    g.batches.enqueue(links, source, if (links.size == 1) "1 link" else "${links.size} links")
                }
            } finally {
                finish()
            }
        }
        toast(if (links.size == 1) "Resolving in the background…" else "Resolving ${links.size} links in the background…")
        return true
    }

    private fun handle(intent: Intent) {
        if (intent.action == Intent.ACTION_SEND) {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
            val links = application.graph.batches.extract(text)
            if (links.size > 1) {
                // Several links shared at once (e.g. copied Telegram posts): resolve them as a batch.
                lifecycleScope.launch {
                    val id = application.graph.batches.enqueue(links, Source.SHARE, "${links.size} shared links")
                    startActivity(MainActivity.openBatch(this@ResolveActivity, id))
                    finish()
                }
                return
            }
        }
        val raw = when (intent.action) {
            Intent.ACTION_VIEW -> intent.dataString
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)?.let { ResolverRunner.extractUrl(it) ?: it }
            else -> intent.getStringExtra(EXTRA_URL)
        }
        if (raw.isNullOrBlank()) {
            toast("No link found")
            finish()
            return
        }
        val source = when (intent.action) {
            Intent.ACTION_VIEW -> Source.LINK
            Intent.ACTION_SEND -> Source.SHARE
            else -> Source.PASTE
        }
        vm.startIfNew(raw.trim(), source)
    }

    companion object {
        const val EXTRA_URL = "url"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResolveScreen(
    state: ResolveScreenState,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onResolveAgain: () -> Unit,
    onShareDebug: () -> Unit,
    onClose: () -> Unit,
) {
    val ctx = LocalContext.current
    var showLog by remember { mutableStateOf(false) }

    Scaffold(topBar = { TopAppBar(title = { Text("Link Resolver") }) }) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(state.url, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            if (state.network.isNotEmpty()) {
                Text("Network: ${state.network}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
            }

            when (val ui = state.ui) {
                ResolveUi.Idle -> Unit
                is ResolveUi.Running -> Running(ui, onCancel)
                is ResolveUi.Done -> {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Final link", style = MaterialTheme.typography.labelLarge)
                            Text(
                                ui.finalUrl,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                                textDecoration = TextDecoration.Underline,
                                modifier = Modifier.clickable { ctx.openUrl(ui.finalUrl) },
                            )
                            Text(
                                if (ui.cached) "From history — resolved earlier" else "Resolved in ${ui.elapsedMs / 1000} s",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { ctx.openUrl(ui.finalUrl) }) { Text("Open") }
                        OutlinedButton(onClick = { ctx.copyText("Final link", ui.finalUrl) }) { Text("Copy") }
                        OutlinedButton(onClick = { ctx.shareText(ui.finalUrl) }) { Text("Share") }
                    }
                    if (ui.cached) OutlinedButton(onClick = onResolveAgain) { Text("Resolve again") }
                    val g = (ctx.applicationContext as android.app.Application).graph
                    val bot by remember(ui.finalUrl) { g.db.botStarts().observeLatest() }.collectAsState(initial = emptyList())
                    FinalLinkActions(ui.finalUrl, bot.firstOrNull { it.finalUrl == ui.finalUrl })
                }
                is ResolveUi.Failed -> {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Couldn't resolve", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                            Text(ui.message)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onRetry) { Text("Retry") }
                        OutlinedButton(onClick = { showLog = true }) { Text("Show debug log") }
                    }
                }
                ResolveUi.Cancelled -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Cancelled", Modifier.align(Alignment.CenterVertically))
                    Button(onClick = onRetry) { Text("Retry") }
                }
            }

            Spacer(Modifier.height(8.dp))
            if (state.ui !is ResolveUi.Running) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.ui !is ResolveUi.Failed) TextButton(onClick = { showLog = true }) { Text("Debug log") }
                    if (state.hasDebugBundle) TextButton(onClick = onShareDebug) { Text("Share debug bundle") }
                    TextButton(onClick = onClose) { Text("Close") }
                }
            }
        }
    }

    if (showLog) {
        AlertDialog(
            onDismissRequest = { showLog = false },
            title = { Text("Debug log") },
            text = {
                SelectionContainer(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Text(state.log.ifEmpty { "(empty)" }, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
            },
            confirmButton = { TextButton(onClick = { ctx.copyText("Debug log", state.log) }) { Text("Copy") } },
            dismissButton = { TextButton(onClick = { showLog = false }) { Text("Close") } },
        )
    }
}

@Composable
private fun Running(ui: ResolveUi.Running, onCancel: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.height(24.dp))
                Text(ui.headline, style = MaterialTheme.typography.titleMedium)
            }
            if (ui.step != null && ui.total != null && ui.total > 0 && ui.step <= ui.total) {
                LinearProgressIndicator(progress = { ui.step.toFloat() / ui.total }, modifier = Modifier.fillMaxWidth())
            }
            if (ui.secondsLeft != null) {
                Text("${ui.secondsLeft}", fontSize = 40.sp, fontWeight = FontWeight.Bold)
            }
            Text(
                "This takes ~20–40 s: the site enforces a real wait on its server for each step.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}
