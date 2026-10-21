package com.nik.linkresolver.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nik.linkresolver.data.db.CollectionKind
import com.nik.linkresolver.data.db.ResolveEntity
import com.nik.linkresolver.data.db.Status

@Composable
fun SavedScreen(vm: SavedViewModel, onOpenBatch: (Long) -> Unit, modifier: Modifier = Modifier) {
    val openId by vm.openId.collectAsStateWithLifecycle()
    if (openId == null) CollectionList(vm, modifier) else CollectionDetail(vm, onOpenBatch, modifier)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CollectionList(vm: SavedViewModel, modifier: Modifier) {
    val list by vm.collections.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    Column(modifier.fillMaxSize()) {
    TopAppBar(title = { Text("Saved") }, windowInsets = WindowInsets(0))
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (list.isEmpty()) item {
            Text(
                "Nothing saved yet. In the Telegram tab, open a channel and tap Save — the whole channel, " +
                    "or just the links you selected, under a name you choose. Saved posts can be browsed and resolved later.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        items(list, key = { it.id }) { c ->
            Card(Modifier.fillMaxWidth().clickable { vm.openCollection(c.id) }) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(c.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        (if (c.kind == CollectionKind.CHANNEL) "Whole channel" else "Selection") + " · ${c.chatTitle}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "${c.postCount} posts · ${c.resolved} links resolved · ${c.runs} run${if (c.runs == 1) "" else "s"} · ${formatTime(c.updatedAt)}",
                        style = MaterialTheme.typography.labelSmall,
                    )
                    if (c.saving) {
                        val p = progress[c.id]
                        Text("Saving… ${p?.read ?: 0} messages read", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    } else {
                        progress[c.id]?.error?.let { Text("Stopped: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall) }
                    }
                }
            }
        }
    }    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CollectionDetail(vm: SavedViewModel, onOpenBatch: (Long) -> Unit, modifier: Modifier) {
    val c by vm.open.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    BackHandler { vm.openCollection(null) }
    val col = c ?: return

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0),
                title = { Text(col.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = { vm.openCollection(null) }) { Text("‹", fontSize = 28.sp) } },
                actions = {
                    IconButton(onClick = { menu = true }) { Text("⋮", fontSize = 22.sp) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Details") }, onClick = { menu = false; details = true })
                        DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; renaming = true })
                        if (col.kind == CollectionKind.CHANNEL) {
                            DropdownMenuItem(text = { Text("Update with new posts") }, enabled = !col.saving, onClick = { menu = false; vm.update() })
                        }
                        DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; deleting = true })
                    }
                },
            )
        },
        floatingActionButton = {
            if (tab == 0 && selected.isNotEmpty()) {
                ExtendedFloatingActionButton(onClick = { confirm = true }) { Text("Resolve ${selected.size}") }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (col.saving) {
                Text(
                    "Saving… ${progress[col.id]?.read ?: 0} messages read",
                    Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            PrimaryTabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Posts") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Results") })
            }
            if (tab == 0) PostsTab(vm, Modifier.weight(1f)) else ResultsTab(vm, col.name, Modifier.weight(1f))
        }
    }

    if (confirm) {
        var fresh by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Resolve ${selected.size} link${if (selected.size == 1) "" else "s"}?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Results go to “${col.name} — results”.", style = MaterialTheme.typography.bodyMedium)
                    Row(Modifier.fillMaxWidth().clickable { fresh = !fresh }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = fresh, onCheckedChange = { fresh = it })
                        Text("Resolve again even if already done", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { confirm = false; vm.resolveSelected(fresh, onOpenBatch) }) { Text("Resolve") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
    if (details) {
        AlertDialog(
            onDismissRequest = { details = false },
            title = { Text(col.name) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text((if (col.kind == CollectionKind.CHANNEL) "Whole channel" else "Selection") + " of ${col.chatTitle}")
                    Text("${col.postCount} posts")
                    Text("Saved ${formatTime(col.createdAt)}")
                    Text("Last changed ${formatTime(col.updatedAt)}")
                    if (col.saving) Text("Still saving: ${progress[col.id]?.read ?: 0} messages read")
                    progress[col.id]?.error?.let { Text("Stopped: $it", color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = { TextButton(onClick = { details = false }) { Text("Close") } },
        )
    }
    if (renaming) {
        NameDialog("Rename collection", col.name, onDismiss = { renaming = false }) { vm.rename(it); renaming = false }
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("Delete “${col.name}”?") },
            text = { Text("The saved posts are deleted. Resolved links stay in History.") },
            confirmButton = { TextButton(onClick = { vm.delete(); deleting = false }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PostsTab(vm: SavedViewModel, modifier: Modifier) {
    val ctx = LocalContext.current
    val posts by vm.posts.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val known by vm.known.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val only by vm.onlyWithLinks.collectAsStateWithLifecycle()
    val linkCount = remember(posts) { posts.sumOf { it.links.size } }
    val done = remember(posts, known) { posts.flatMap { it.links }.distinct().count { known[it]?.status == Status.DONE } }

    Column(modifier) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 88.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = query, onValueChange = { vm.query.value = it }, label = { Text("Search posts") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = only, onClick = { vm.onlyWithLinks.value = !only }, label = { Text("Only with links") })
                        AssistChip(onClick = vm::selectUnresolved, label = { Text("Unresolved") })
                        AssistChip(onClick = vm::selectAll, label = { Text("All") })
                        if (selected.isNotEmpty()) AssistChip(onClick = vm::clearSelection, label = { Text("None") })
                    }
                    Text("${posts.size} posts · $linkCount links · $done already resolved", style = MaterialTheme.typography.labelMedium)
                }
            }
            items(posts, key = { it.post.id }) { p ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(start = 4.dp, end = 12.dp, top = 6.dp, bottom = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable(enabled = p.links.isNotEmpty()) { vm.togglePost(p) }) {
                            if (p.links.isNotEmpty()) Checkbox(checked = p.links.all { it in selected }, onCheckedChange = { vm.togglePost(p) })
                            Text(formatTime(p.post.date * 1000L), Modifier.padding(start = if (p.links.isEmpty()) 12.dp else 0.dp, top = 8.dp, bottom = 4.dp), style = MaterialTheme.typography.labelMedium)
                        }
                        if (p.post.text.isNotBlank()) {
                            Text(p.post.text, Modifier.padding(start = 12.dp, bottom = 4.dp), style = MaterialTheme.typography.bodySmall)
                        }
                        if (p.links.isNotEmpty()) HorizontalDivider(Modifier.padding(start = 12.dp))
                        p.links.forEach { url ->
                            val k = known[url]
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = url in selected, onCheckedChange = { vm.toggle(url) })
                                Column(Modifier.weight(1f)) {
                                    Text(url.removePrefix("https://"), fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    when (k?.status) {
                                        Status.DONE -> Text(
                                            "✓ " + k.finalUrl.orEmpty(), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.clickable { ctx.openUrl(k.finalUrl.orEmpty()) },
                                        )
                                        Status.QUEUED, Status.RUNNING -> Text("… resolving", style = MaterialTheme.typography.bodySmall)
                                        Status.FAILED -> Text("✗ failed last time", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                                        else -> Unit
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultsTab(vm: SavedViewModel, name: String, modifier: Modifier) {
    val ctx = LocalContext.current
    val rows by vm.results.collectAsStateWithLifecycle()
    val done = rows.count { it.status == Status.DONE }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("$name — results", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (rows.isEmpty()) "Nothing processed yet: select links in Posts and resolve them."
                    else "$done resolved · ${rows.count { it.status == Status.FAILED }} failed · ${rows.count { it.status == Status.QUEUED || it.status == Status.RUNNING }} in progress",
                    style = MaterialTheme.typography.labelMedium,
                )
                if (rows.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { ctx.copyText("Final links", finalUrlsText(rows)) }, enabled = done > 0) { Text("Copy all") }
                    OutlinedButton(onClick = { ctx.shareText(finalUrlsText(rows)) }, enabled = done > 0) { Text("Share") }
                    OutlinedButton(onClick = { ctx.shareCsv(rows, safeFileName("$name results")) }) { Text("CSV") }
                }
            }
        }
        items(rows, key = { it.id }) { r -> ResultLine(r) }
    }
}

@Composable
private fun ResultLine(r: ResolveEntity) {
    val ctx = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp)) {
            Text(r.sourceUrl.removePrefix("https://"), fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            when (r.status) {
                Status.DONE -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        r.finalUrl.orEmpty(), Modifier.weight(1f).clickable { ctx.openUrl(r.finalUrl.orEmpty()) },
                        color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(onClick = { ctx.copyText("Final link", r.finalUrl.orEmpty()) }) { Text("Copy") }
                }
                Status.QUEUED, Status.RUNNING -> Text("… resolving", style = MaterialTheme.typography.bodySmall)
                else -> Text(r.error ?: r.status, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Text field dialog for naming a collection. */
@Composable
fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onConfirm(name.trim()) }, enabled = name.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

fun safeFileName(s: String): String = s.replace(Regex("[^A-Za-z0-9._ -]"), "_").trim().take(60).ifEmpty { "results" }
