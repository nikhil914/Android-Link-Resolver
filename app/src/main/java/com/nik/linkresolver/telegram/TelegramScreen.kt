package com.nik.linkresolver.telegram

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nik.linkresolver.data.db.ResolveEntity
import com.nik.linkresolver.data.db.Status
import com.nik.linkresolver.ui.copyText
import com.nik.linkresolver.ui.formatTime
import com.nik.linkresolver.ui.openUrl

@Composable
fun TelegramScreen(vm: TelegramViewModel, onOpenBatch: (Long) -> Unit, modifier: Modifier = Modifier) {
    val auth by vm.auth.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val channel by vm.channel.collectAsStateWithLifecycle()

    Column(modifier.fillMaxSize()) {
        if (error != null) {
            Card(
                Modifier.fillMaxWidth().padding(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(error.orEmpty(), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = vm::clearError) { Text("OK") }
                }
            }
        }
        when (val a = auth) {
            TgAuth.NotConfigured -> SetupForm(vm)
            TgAuth.Starting, TgAuth.LoggingOut -> Centered { CircularProgressIndicator() }
            TgAuth.WaitPhone -> LoginStep(
                title = "Log in to Telegram",
                info = "Your phone number in international format, e.g. +971 50 123 4567. Telegram sends a login code.",
                label = "Phone number",
                keyboard = KeyboardType.Phone,
                button = "Send code",
                busy = busy,
                onSubmit = vm::submitPhone,
            )
            is TgAuth.WaitCode -> LoginStep(
                title = "Enter the code",
                info = "Telegram sent a code ${a.sentTo}.",
                label = "Code",
                keyboard = KeyboardType.Number,
                button = "Log in",
                busy = busy,
                onSubmit = vm::submitCode,
            )
            is TgAuth.WaitPassword -> LoginStep(
                title = "Two-step verification",
                info = if (a.hint.isNotBlank()) "Password hint: ${a.hint}" else "Enter your Telegram cloud password.",
                label = "Password",
                keyboard = KeyboardType.Password,
                password = true,
                button = "Unlock",
                busy = busy,
                onSubmit = vm::submitPassword,
            )
            is TgAuth.Unsupported -> Centered {
                Text("Telegram asks for “${a.what}”, which this app can't do. Log in once in the official app, then try again.")
            }
            TgAuth.Ready -> {
                val ch = channel
                if (ch == null) ChatList(vm) else ChannelView(vm, ch, onOpenBatch)
            }
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        content()
    }
}

@Composable
private fun SetupForm(vm: TelegramViewModel) {
    val ctx = LocalContext.current
    var id by rememberSaveable { mutableStateOf("") }
    var hash by rememberSaveable { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Connect Telegram", style = MaterialTheme.typography.titleLarge)
        Text(
            "To read your private channels the app logs in as you with Telegram's official library (read-only: " +
                "it never sends anything). Telegram requires your own API ID for that — one time:",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "1. Open my.telegram.org and log in with your phone number.\n" +
                "2. Tap “API development tools”.\n" +
                "3. Create an app (any name, e.g. “Link Resolver”, platform Android).\n" +
                "4. Copy “App api_id” and “App api_hash” here.",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedButton(onClick = { ctx.openUrl("https://my.telegram.org/apps") }) { Text("Open my.telegram.org") }
        OutlinedTextField(
            value = id, onValueChange = { id = it.filter(Char::isDigit) }, label = { Text("api_id") },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = hash, onValueChange = { hash = it.trim() }, label = { Text("api_hash") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        Text("Stored only on this phone.", style = MaterialTheme.typography.labelSmall)
        Button(onClick = { vm.configure(id, hash) }, enabled = id.isNotEmpty() && hash.length >= 16) { Text("Continue") }
    }
}

@Composable
private fun LoginStep(
    title: String,
    info: String,
    label: String,
    keyboard: KeyboardType,
    button: String,
    busy: Boolean,
    onSubmit: (String) -> Unit,
    password: Boolean = false,
) {
    var value by remember(title) { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(info, style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
            value = value, onValueChange = { value = it }, label = { Text(label) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = keyboard),
            visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { onSubmit(value) }, enabled = value.isNotBlank() && !busy) { Text(button) }
            if (busy) {
                Spacer(Modifier.width(12.dp))
                CircularProgressIndicator(Modifier.width(24.dp))
            }
        }
    }
}

@Composable
private fun ChatList(vm: TelegramViewModel) {
    val chats by vm.chats.collectAsStateWithLifecycle()
    val loading by vm.chatsLoading.collectAsStateWithLifecycle()
    val query by vm.chatQuery.collectAsStateWithLifecycle()
    val recent by vm.recent.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { if (vm.chats.value.isEmpty()) vm.loadChats() }
    val shown = remember(chats, query) {
        val q = query.trim()
        if (q.isEmpty()) chats else chats.filter { it.title.contains(q, ignoreCase = true) }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (recent.isNotEmpty()) {
                    Text("Recent", style = MaterialTheme.typography.titleSmall)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(recent, key = { it.first }) { (id, title) ->
                            AssistChip(onClick = { vm.openRecent(id, title) }, label = { Text((if (vm.isWatched(id)) "★ " else "") + title, maxLines = 1) })
                        }
                    }
                }
                OutlinedTextField(
                    value = query, onValueChange = { vm.chatQuery.value = it }, label = { Text("Search chats") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (loading) "Loading chats…" else "${shown.size} chats",
                        Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
                    )
                    TextButton(onClick = vm::loadChats, enabled = !loading) { Text("Refresh") }
                    TextButton(onClick = vm::logOut) { Text("Log out") }
                }
                if (loading && chats.isEmpty()) CircularProgressIndicator()
            }
        }
        items(shown, key = { it.id }) { chat ->
            Card(Modifier.fillMaxWidth().clickable { vm.openChat(chat) }) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(chat.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            chat.kind + if (chat.lastMessageDate > 0) " · " + formatTime(chat.lastMessageDate * 1000L) else "",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    Text("›", fontSize = 22.sp)
                }
            }
        }
    }
}

@Composable
private fun ChannelView(vm: TelegramViewModel, st: ChannelState, onOpenBatch: (Long) -> Unit) {
    val selected by vm.selected.collectAsStateWithLifecycle()
    val known by vm.known.collectAsStateWithLifecycle()
    var fresh by rememberSaveable { mutableStateOf(false) }
    BackHandler { vm.closeChat() }

    val allLinks = remember(st.posts) { st.posts.flatMap { it.links }.distinct() }
    val doneCount = allLinks.count { known[it]?.status == Status.DONE }
    val newPosts = if (st.lastSeenId > 0) st.posts.count { it.messageId > st.lastSeenId } else 0

    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = vm::closeChat) { Text("‹ Chats") }
                        Text(st.chat.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        TextButton(onClick = vm::refresh, enabled = !st.loading) { Text("Refresh") }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (st.watching) "★ Watching: new posts are checked in the background and their links resolved"
                            else "Watch this channel to get new links resolved automatically",
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (st.watching) OutlinedButton(onClick = vm::toggleWatch) { Text("Stop") }
                        else Button(onClick = vm::toggleWatch, enabled = st.newestId != 0L) { Text("Watch") }
                    }
                    Text(
                        "${st.posts.size} posts with links · ${allLinks.size} links · $doneCount already resolved" +
                            (if (newPosts > 0) " · $newPosts new since last visit" else "") +
                            " · ${st.scanned} messages read",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        AssistChip(onClick = vm::selectUnresolved, label = { Text("Unresolved") })
                        if (newPosts > 0) AssistChip(onClick = vm::selectNew, label = { Text("New") })
                        AssistChip(onClick = vm::selectAll, label = { Text("All") })
                        if (selected.isNotEmpty()) AssistChip(onClick = vm::clearSelection, label = { Text("None") })
                    }
                    st.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
            items(st.posts, key = { it.messageId }) { post ->
                PostCard(post, isNew = st.lastSeenId > 0 && post.messageId > st.lastSeenId, selected, known, vm)
            }
            item {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    when {
                        st.loading -> CircularProgressIndicator()
                        st.endReached -> Text("Start of the chat reached", style = MaterialTheme.typography.labelMedium)
                        else -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { vm.loadMore(TelegramViewModel.PAGE) }) { Text("Load older") }
                            OutlinedButton(onClick = { vm.loadMore(1000) }) { Text("Load 1000 more") }
                        }
                    }
                }
            }
        }
        // ---- action bar
        Surface(tonalElevation = 3.dp) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Resolve again even if done", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    Switch(checked = fresh, onCheckedChange = { fresh = it })
                }
                Button(
                    onClick = { vm.resolveSelected(fresh, onOpenBatch) },
                    enabled = selected.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (selected.isEmpty()) "Select links to resolve" else "Resolve ${selected.size} selected") }
            }
        }
    }
}

@Composable
private fun PostCard(post: TgPost, isNew: Boolean, selected: Set<String>, known: Map<String, ResolveEntity>, vm: TelegramViewModel) {
    val ctx = LocalContext.current
    val allSel = post.links.all { it in selected }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 4.dp, end = 12.dp, top = 6.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { vm.togglePost(post) }) {
                Checkbox(checked = allSel, onCheckedChange = { vm.togglePost(post) })
                Text(
                    formatTime(post.date * 1000L) + if (isNew) "  • NEW" else "",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (isNew) FontWeight.Bold else FontWeight.Normal,
                    color = if (isNew) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (post.text.isNotBlank()) {
                Text(
                    post.text.lines().filter { it.isNotBlank() }.joinToString(" · ").take(220),
                    Modifier.padding(start = 12.dp, bottom = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            HorizontalDivider(Modifier.padding(start = 12.dp))
            post.links.forEach { url ->
                val k = known[url]
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = url in selected, onCheckedChange = { vm.toggle(url) })
                    Column(Modifier.weight(1f)) {
                        Text(url.removePrefix("https://"), fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        when (k?.status) {
                            Status.DONE -> Text(
                                "✓ " + k.finalUrl.orEmpty(),
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.clickable { ctx.openUrl(k.finalUrl.orEmpty()) },
                            )
                            Status.QUEUED, Status.RUNNING -> Text("… resolving", style = MaterialTheme.typography.bodySmall)
                            Status.FAILED -> Text("✗ failed last time", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            else -> Unit
                        }
                    }
                    if (k?.status == Status.DONE) {
                        TextButton(onClick = { ctx.copyText("Final link", k.finalUrl.orEmpty()) }) { Text("Copy") }
                    }
                }
            }
        }
    }
}
