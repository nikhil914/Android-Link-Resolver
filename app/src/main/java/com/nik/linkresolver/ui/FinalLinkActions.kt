package com.nik.linkresolver.ui

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nik.linkresolver.data.db.BotStartEntity
import com.nik.linkresolver.data.db.BotStatus
import com.nik.linkresolver.engine.TelegramUri
import com.nik.linkresolver.graph
import com.nik.linkresolver.telegram.InviteLinks
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi

/**
 * Extra actions under a final link: start a bot link (and show what the bot sent), or preview and join an invite
 * link. Nothing is shown for other links.
 */
@Composable
fun FinalLinkActions(finalUrl: String, bot: BotStartEntity?) {
    val botStart = remember(finalUrl) { TelegramUri.botStart(finalUrl) }
    val invite = remember(finalUrl) { TelegramUri.isInvite(finalUrl) }
    if (botStart != null) BotSection(finalUrl, botStart.first, bot)
    if (invite) InviteSection(finalUrl)
}

@Composable
private fun BotSection(finalUrl: String, name: String, bot: BotStartEntity?) {
    val ctx = LocalContext.current
    val g = (ctx.applicationContext as Application).graph
    val scope = rememberCoroutineScope()
    fun start(again: Boolean) = scope.launch { g.bots.request(finalUrl, again) }

    Column(Modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        when (bot?.status) {
            null -> TextButton(onClick = { start(false) }) { Text("Start @$name") }
            BotStatus.PENDING, BotStatus.RUNNING -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.width(16.dp), strokeWidth = 2.dp)
                Text(
                    if (bot.status == BotStatus.PENDING) "  Waiting to start @$name (paced)…" else "  Started @$name, waiting for its reply…",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            BotStatus.DONE -> {
                Text("@$name replied" + if (bot.forwarded) " · forwarded to Saved Messages" else "", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                bot.replyFiles?.lines()?.forEach { Text(it, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold) }
                bot.replyText?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 4, overflow = TextOverflow.Ellipsis) }
                bot.replyUrls?.lines()?.take(5)?.forEach { u ->
                    Text(u, Modifier.clickable { ctx.openUrl(u) }, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Row {
                    TextButton(onClick = { ctx.openUrl("https://t.me/$name") }) { Text("Open chat") }
                    TextButton(onClick = { start(true) }) { Text("Start again") }
                }
            }
            else -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(bot.error ?: "No reply", Modifier.weight(1f), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { start(true) }) { Text("Retry") }
            }
        }
    }
}

@Composable
private fun InviteSection(finalUrl: String) {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = true }) { Text("Preview & join") }
    if (open) InviteDialog(finalUrl) { open = false }
}

@Composable
private fun InviteDialog(url: String, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val g = (ctx.applicationContext as Application).graph
    val scope = rememberCoroutineScope()
    var info by remember { mutableStateOf<TdApi.ChatInviteLinkInfo?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var joining by remember { mutableStateOf(false) }
    var joined by remember { mutableStateOf(false) }
    LaunchedEffect(url) {
        runCatching { InviteLinks.preview(g.telegram, url) }.onSuccess { info = it }.onFailure { error = it.message }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(info?.title ?: "Invite link") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val i = info
                when {
                    error != null -> Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
                    i == null -> CircularProgressIndicator()
                    else -> {
                        val kind = when (i.type) {
                            is TdApi.InviteLinkChatTypeChannel -> "Channel"
                            is TdApi.InviteLinkChatTypeSupergroup, is TdApi.InviteLinkChatTypeBasicGroup -> "Group"
                            else -> "Chat"
                        }
                        Text("$kind · ${i.memberCount} members" + if (i.isPublic) " · public" else " · private")
                        if (i.description.isNotBlank()) Text(i.description, style = MaterialTheme.typography.bodySmall, maxLines = 6, overflow = TextOverflow.Ellipsis)
                        if (i.createsJoinRequest) Text("Joining sends a request the admins must approve.", style = MaterialTheme.typography.bodySmall)
                        if (i.chatId != 0L || joined) Text(if (joined) "Joined." else "You're already a member.", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        },
        confirmButton = {
            val i = info
            if (i != null && (i.chatId != 0L || joined)) {
                TextButton(onClick = { ctx.openUrl(url); onDismiss() }) { Text("Open") }
            } else if (i != null) {
                TextButton(enabled = !joining, onClick = {
                    joining = true
                    scope.launch {
                        runCatching { InviteLinks.join(g.telegram, url) }
                            .onSuccess { joined = true }
                            .onFailure { error = it.message }
                        joining = false
                    }
                }) { Text(if (i.createsJoinRequest) "Request to join" else "Join") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
