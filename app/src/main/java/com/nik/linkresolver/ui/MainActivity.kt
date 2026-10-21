package com.nik.linkresolver.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.verify.domain.DomainVerificationManager
import android.content.pm.verify.domain.DomainVerificationUserState
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nik.linkresolver.BuildConfig
import com.nik.linkresolver.data.db.BatchSummary
import com.nik.linkresolver.data.db.Source
import com.nik.linkresolver.graph
import com.nik.linkresolver.telegram.TelegramScreen
import com.nik.linkresolver.telegram.TelegramViewModel

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()
    private val tgVm: TelegramViewModel by viewModels()
    private var linkStatus by mutableStateOf(LINK_HOSTS.associateWith { LinkStatus.UNKNOWN })
    private var tab by mutableStateOf(TAB_HOME)
    private var openBatch by mutableStateOf<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIntent(intent)
        setContent { AppTheme { Root() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        intent.getStringExtra(EXTRA_TAB)?.let { tab = it; openBatch = null }
        intent.getLongExtra(EXTRA_BATCH, -1).takeIf { it > 0 }?.let { tab = TAB_HOME; openBatch = it }
    }

    override fun onResume() {
        super.onResume()
        linkStatus = linkOpeningStatus(this)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Root() {
        BackHandler(enabled = openBatch != null) { openBatch = null }
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(if (openBatch != null) "Batch" else "Link Resolver") },
                    navigationIcon = { if (openBatch != null) TextButton(onClick = { openBatch = null }) { Text("‹ Back") } },
                )
            },
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        selected = tab == TAB_HOME && openBatch == null,
                        onClick = { tab = TAB_HOME; openBatch = null },
                        icon = { Text("⌂") }, label = { Text("Resolve") },
                    )
                    NavigationBarItem(
                        selected = tab == TAB_TELEGRAM && openBatch == null,
                        onClick = { tab = TAB_TELEGRAM; openBatch = null },
                        icon = { Text("✈") }, label = { Text("Telegram") },
                    )
                    NavigationBarItem(
                        selected = tab == TAB_HISTORY && openBatch == null,
                        onClick = { tab = TAB_HISTORY; openBatch = null },
                        icon = { Text("☰") }, label = { Text("History") },
                    )
                }
            },
        ) { pad ->
            val m = Modifier.padding(pad)
            val batch = openBatch
            when {
                batch != null -> BatchScreen(vm, batch, m)
                tab == TAB_HISTORY -> HistoryScreen(vm, m)
                tab == TAB_TELEGRAM -> TelegramScreen(tgVm, onOpenBatch = { openBatch = it }, modifier = m)
                else -> HomeScreen(m)
            }
        }
    }

    @Composable
    private fun HomeScreen(modifier: Modifier) {
        val ctx = LocalContext.current
        val settings = application.graph.settings
        var text by rememberSaveable { mutableStateOf("") }
        var fresh by rememberSaveable { mutableStateOf(false) }
        var forceMobile by remember { mutableStateOf(settings.forceMobileData) }
        var autoOpen by remember { mutableStateOf(settings.autoOpenFinal) }
        var concurrency by remember { mutableStateOf(settings.concurrency.toFloat()) }
        val batches by vm.batches.collectAsStateWithLifecycle()
        val links = remember(text) { vm.extract(text) }

        Column(
            modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ---- input
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Links or copied posts") },
                placeholder = { Text("Paste one or many links — or several copied Telegram posts") },
                minLines = 3,
                maxLines = 8,
                modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
            )
            Text(
                when (links.size) {
                    0 -> if (text.isBlank()) "Supported: ${LINK_HOSTS.joinToString()}" else "No supported links found"
                    1 -> "1 link found"
                    else -> "${links.size} links found — they'll resolve ${concurrency.toInt()} at a time"
                },
                style = MaterialTheme.typography.labelMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = {
                    val clip = ctx.clipboardText()
                    if (clip.isNullOrBlank()) ctx.toast("Clipboard is empty") else text = clip.trim()
                }) { Text("Paste") }
                if (text.isNotEmpty()) TextButton(onClick = { text = "" }) { Text("Clear") }
                Button(onClick = {
                    when {
                        links.size == 1 && !fresh -> {
                            startActivity(Intent(this@MainActivity, ResolveActivity::class.java).putExtra(ResolveActivity.EXTRA_URL, links[0]))
                        }
                        links.isNotEmpty() -> {
                            askNotificationPermission()
                            vm.enqueue(links, Source.PASTE, fresh) { id -> openBatch = id; text = "" }
                        }
                    }
                }, enabled = links.isNotEmpty()) { Text(if (links.size > 1) "Resolve ${links.size}" else "Resolve") }
            }
            Row(Modifier.fillMaxWidth().clickable { fresh = !fresh }, verticalAlignment = Alignment.CenterVertically) {
                Text("Resolve again even if already in history", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(checked = fresh, onCheckedChange = { fresh = it })
            }

            // ---- recent batches
            if (batches.isNotEmpty()) {
                Text("Recent batches", style = MaterialTheme.typography.titleMedium)
                batches.take(10).forEach { b -> BatchCard(b) { openBatch = b.id } }
            }

            SetupCard()

            // ---- settings
            Text("Settings", style = MaterialTheme.typography.titleMedium)
            Column {
                Text("Parallel resolves: ${concurrency.toInt()}", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Each link always gets its own session. More at once is faster, but all requests come from your phone's IP.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Slider(
                    value = concurrency,
                    onValueChange = { concurrency = it },
                    onValueChangeFinished = { settings.concurrency = concurrency.toInt() },
                    valueRange = 1f..6f,
                    steps = 4,
                )
            }
            SettingRow("Force mobile data", "Use mobile data even when Wi-Fi is on (some networks can't reach the site)", forceMobile) {
                forceMobile = it
                settings.forceMobileData = it
            }
            SettingRow("Open final link automatically", "Single links: skip the result screen and open the link right away", autoOpen) {
                autoOpen = it
                settings.autoOpenFinal = it
            }
            Text("v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
    }

    @Composable
    private fun BatchCard(b: BatchSummary, onClick: () -> Unit) {
        Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
            Column(Modifier.padding(12.dp)) {
                Text("${b.label} · ${formatTime(b.createdAt)}", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "${b.done} done · ${b.failed} failed" + if (b.pending > 0) " · ${b.pending} in progress" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (b.pending > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    @Composable
    private fun SetupCard() {
        var showTip by rememberSaveable { mutableStateOf(false) }
        val notNeeded = linkStatus.values.all { it == LinkStatus.NOT_NEEDED }
        val ok = linkStatus.values.all { it == LinkStatus.ENABLED || it == LinkStatus.NOT_NEEDED }
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (ok) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer,
            ),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Open links in this app", style = MaterialTheme.typography.titleMedium)
                for ((host, st) in linkStatus) {
                    val line = when (st) {
                        LinkStatus.ENABLED -> "✓ $host opens in this app"
                        LinkStatus.DISABLED -> "✗ $host opens in the browser"
                        LinkStatus.NOT_NEEDED -> "Android will ask which app to use for $host"
                        LinkStatus.UNKNOWN -> "? $host — status unknown"
                    }
                    Text(line, style = MaterialTheme.typography.bodyMedium)
                }
                if (!notNeeded && !ok) {
                    Text(
                        "Tap below, turn on “Open supported links”, then tick every domain.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!notNeeded) Button(onClick = ::openLinkSettings) { Text("Enable link opening") }
                    TextButton(onClick = { showTip = !showTip }) { Text(if (showTip) "Hide Telegram tip" else "Telegram tip") }
                }
                if (showTip) {
                    Text(
                        "Telegram opens links in its own in-app browser, which never hands them to Android. " +
                            "In Telegram: Settings → Chat Settings → turn off “In-App Browser”.\n\n" +
                            "Many links at once: select several posts in Telegram → Copy, then Paste here. " +
                            "Or Share them to Link Resolver.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    private fun openLinkSettings() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Intent(AndroidSettings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, "package:$packageName".toUri())
        } else {
            Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri())
        }
        runCatching { startActivity(intent) }.onFailure {
            startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri()))
        }
    }

    companion object {
        const val EXTRA_TAB = "tab"
        const val EXTRA_BATCH = "batch"
        const val TAB_HOME = "home"
        const val TAB_HISTORY = "history"
        const val TAB_TELEGRAM = "telegram"

        fun openBatch(context: Context, batchId: Long) = Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_BATCH, batchId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
}

enum class LinkStatus { UNKNOWN, ENABLED, DISABLED, NOT_NEEDED }

/** Hosts declared in the manifest's VIEW intent filter. */
val LINK_HOSTS = listOf("linkshortx.in", "urlshortx.io")

/** Per-host status of "Open supported links" (Android 12+). */
fun linkOpeningStatus(ctx: Context): Map<String, LinkStatus> {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return LINK_HOSTS.associateWith { LinkStatus.NOT_NEEDED }
    val dvm = ctx.getSystemService(DomainVerificationManager::class.java)
    val state = runCatching { dvm?.getDomainVerificationUserState(ctx.packageName) }.getOrNull()
        ?: return LINK_HOSTS.associateWith { LinkStatus.UNKNOWN }
    return LINK_HOSTS.associateWith { host ->
        when (state.hostToStateMap[host]) {
            null -> LinkStatus.UNKNOWN
            DomainVerificationUserState.DOMAIN_STATE_SELECTED, DomainVerificationUserState.DOMAIN_STATE_VERIFIED ->
                if (state.isLinkHandlingAllowed) LinkStatus.ENABLED else LinkStatus.DISABLED
            else -> LinkStatus.DISABLED
        }
    }
}

@Composable
private fun SettingRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
