package com.nik.linkresolver.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nik.linkresolver.data.db.ResolveEntity
import com.nik.linkresolver.data.db.Status

/** One link: status, live progress, final link (tap to open) and actions. */
@Composable
fun ResolveRow(
    row: ResolveEntity,
    progress: String?,
    duplicateOf: Int?,
    onRetry: () -> Unit,
    bot: com.nik.linkresolver.data.db.BotStartEntity? = null,
    onShowLog: () -> Unit,
) {
    val ctx = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusLabel(row)
                Spacer(Modifier.width(8.dp))
                Text(
                    row.sourceUrl.removePrefix("https://"),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            when (row.status) {
                Status.RUNNING -> {
                    Text(progress ?: "Starting…", style = MaterialTheme.typography.bodyMedium)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                Status.QUEUED -> Text("Waiting in queue", style = MaterialTheme.typography.bodyMedium)
                Status.DONE -> {
                    val url = row.finalUrl.orEmpty()
                    Text(
                        url,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { ctx.openUrl(url) },
                    )
                    val meta = buildList {
                        if (row.fromCache) add("from history") else row.durationMs?.let { add("${it / 1000} s") }
                        row.finishedAt?.let { add(formatTime(it)) }
                        if (duplicateOf != null) add("⚠ same result as #$duplicateOf")
                    }.joinToString(" · ")
                    Text(meta, style = MaterialTheme.typography.labelSmall)
                    FinalLinkActions(url, bot)
                }
                else -> Text(row.error ?: row.status, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (row.status == Status.DONE) {
                    TextButton(onClick = { ctx.openUrl(row.finalUrl.orEmpty()) }) { Text("Open") }
                    TextButton(onClick = { ctx.copyText("Final link", row.finalUrl.orEmpty()) }) { Text("Copy") }
                }
                if (row.status != Status.QUEUED && row.status != Status.RUNNING) {
                    TextButton(onClick = onRetry) { Text(if (row.status == Status.DONE) "Resolve again" else "Retry") }
                    if (!row.fromCache) TextButton(onClick = onShowLog) { Text("Log") }
                }
            }
        }
    }
}

@Composable
private fun StatusLabel(row: ResolveEntity) {
    val (text, color) = when (row.status) {
        Status.DONE -> "✓" to Color(0xFF2E7D32)
        Status.FAILED -> "✗" to MaterialTheme.colorScheme.error
        Status.CANCELLED -> "—" to MaterialTheme.colorScheme.outline
        Status.RUNNING -> "…" to MaterialTheme.colorScheme.primary
        else -> "⏳" to MaterialTheme.colorScheme.outline
    }
    Text(text, color = color, fontWeight = FontWeight.Bold, fontSize = 18.sp)
}

/** Final URL that another (earlier) row in the list also got — a sign two links shared a session. */
fun duplicates(rows: List<ResolveEntity>): Map<Long, Int> {
    val firstIndex = mutableMapOf<String, Int>()
    val out = mutableMapOf<Long, Int>()
    rows.forEachIndexed { i, r ->
        val u = r.finalUrl ?: return@forEachIndexed
        if (r.status != Status.DONE) return@forEachIndexed
        val prev = firstIndex.putIfAbsent(u, i + 1)
        if (prev != null) out[r.id] = prev
    }
    return out
}

@Composable
fun LogDialog(text: String?, onDismiss: () -> Unit) {
    if (text == null) return
    val ctx = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Debug log") },
        text = {
            SelectionContainer(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text(text, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }
        },
        confirmButton = { TextButton(onClick = { ctx.copyText("Debug log", text) }) { Text("Copy") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
fun BatchScreen(vm: MainViewModel, batchId: Long, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val rows by remember(batchId) { vm.batchRows(batchId) }.collectAsState(initial = emptyList())
    val info by remember(batchId) { vm.batchInfo(batchId) }.collectAsState(initial = null)
    val progress by vm.progress.collectAsStateWithLifecycle()
    var log by remember { mutableStateOf<String?>(null) }
    val dups = remember(rows) { duplicates(rows) }
    val bots by vm.bots.collectAsStateWithLifecycle()

    val done = rows.count { it.status == Status.DONE }
    val failed = rows.count { it.status == Status.FAILED || it.status == Status.CANCELLED }
    val pending = rows.count { it.status == Status.QUEUED || it.status == Status.RUNNING }

    LazyColumn(modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(info?.let { "${it.label} · ${formatTime(it.createdAt)}" } ?: "", style = MaterialTheme.typography.titleMedium)
                Text("$done done · $failed failed · $pending left", style = MaterialTheme.typography.bodyMedium)
                if (rows.isNotEmpty()) {
                    LinearProgressIndicator(progress = { (done + failed).toFloat() / rows.size }, modifier = Modifier.fillMaxWidth())
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = { ctx.copyText("Final links", finalUrlsText(rows)) }, enabled = done > 0) { Text("Copy all") }
                    OutlinedButton(onClick = { ctx.shareText(finalUrlsText(rows)) }, enabled = done > 0) { Text("Share") }
                    OutlinedButton(onClick = { ctx.shareCsv(rows, "batch-$batchId") }, enabled = rows.isNotEmpty()) { Text("CSV") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (failed > 0) OutlinedButton(onClick = { vm.retryFailed(batchId) }) { Text("Retry failed") }
                    if (pending > 0) OutlinedButton(onClick = { vm.cancel(batchId) }) { Text("Cancel") }
                }
                if (dups.isNotEmpty()) {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Text(
                            "${dups.size} link(s) got the same final link as another one. If they should differ, " +
                                "lower parallel resolves in settings and resolve them again.",
                            Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
        items(rows, key = { it.id }) { row ->
            ResolveRow(row, progress[row.id], dups[row.id], onRetry = { vm.retry(row.id) }, onShowLog = { vm.loadLog(row.id) { log = it } }, bot = bots[row.finalUrl])
        }
    }
    LogDialog(log) { log = null }
}

@Composable
fun HistoryScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    var stats by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    if (stats) {
        androidx.activity.compose.BackHandler { stats = false }
        StatsScreen(vm, onClose = { stats = false }, modifier = modifier)
        return
    }
    val grouped by vm.historyGrouped.collectAsStateWithLifecycle()
    HistoryList(vm, grouped, onStats = { stats = true }, modifier = modifier)
}

@Composable
private fun HistoryList(vm: MainViewModel, grouped: Boolean, onStats: () -> Unit, modifier: Modifier) {
    val ctx = LocalContext.current
    val rows by vm.history.collectAsStateWithLifecycle()
    val query by vm.historyQuery.collectAsStateWithLifecycle()
    val status by vm.historyStatus.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val bots by vm.bots.collectAsStateWithLifecycle()
    var log by remember { mutableStateOf<String?>(null) }
    val groups = remember(rows, grouped) {
        if (!grouped) emptyList()
        else rows.filter { it.status == Status.DONE && !it.finalUrl.isNullOrEmpty() }
            .groupBy { it.finalUrl!! }.toList().sortedByDescending { it.second.size }
    }

    LazyColumn(modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { vm.historyQuery.value = it },
                    label = { Text("Search links") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("" to "All", Status.DONE to "Done", Status.FAILED to "Failed", Status.QUEUED to "Queued").forEach { (s, label) ->
                        FilterChip(selected = status == s, onClick = { vm.historyStatus.value = s }, label = { Text(label) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = grouped, onClick = { vm.historyGrouped.value = !grouped }, label = { Text("By final link") })
                    AssistChip(onClick = { ctx.copyText("Final links", finalUrlsText(rows)) }, label = { Text("Copy") })
                    AssistChip(onClick = { ctx.shareCsv(rows, "history") }, label = { Text("CSV") })
                    AssistChip(onClick = onStats, label = { Text("Stats") })
                }
                Text(
                    if (grouped) "${groups.size} final links from ${groups.sumOf { it.second.size }} shortlinks" else "${rows.size} shown",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
        if (grouped) items(groups, key = { it.first }) { (finalUrl, members) ->
            DestinationCard(finalUrl, members, bots[finalUrl])
        } else items(rows, key = { it.id }) { row ->
            ResolveRow(row, progress[row.id], null, onRetry = { vm.retry(row.id) }, onShowLog = { vm.loadLog(row.id) { log = it } }, bot = bots[row.finalUrl])
        }
    }
    LogDialog(log) { log = null }
}


/** One destination and every shortlink (and channel) that led to it. */
@Composable
private fun DestinationCard(finalUrl: String, members: List<ResolveEntity>, bot: com.nik.linkresolver.data.db.BotStartEntity?) {
    val ctx = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    val sources = members.map { it.sourceUrl }.distinct()
    val channels = members.mapNotNull { it.channel }.distinct()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(finalUrl, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable { ctx.openUrl(finalUrl) })
            Text(
                "${sources.size} shortlink${if (sources.size == 1) "" else "s"}" + if (channels.isNotEmpty()) " · from ${channels.joinToString()}" else "",
                style = MaterialTheme.typography.labelSmall,
            )
            if (expanded) sources.forEach {
                Text(it.removePrefix("https://"), fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row {
                TextButton(onClick = { ctx.copyText("Final link", finalUrl) }) { Text("Copy") }
                if (sources.size > 1 || !expanded) TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide shortlinks" else "Show shortlinks") }
            }
            FinalLinkActions(finalUrl, bot)
        }
    }
}
