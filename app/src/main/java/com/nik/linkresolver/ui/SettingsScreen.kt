package com.nik.linkresolver.ui

import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkInfo
import com.nik.linkresolver.AppGraph
import com.nik.linkresolver.BuildConfig
import com.nik.linkresolver.data.SiteStore
import com.nik.linkresolver.service.HealthCheckWorker

@Composable
fun SettingsScreen(
    g: AppGraph,
    linkStatus: Map<String, LinkStatus>,
    onEnableLinks: () -> Unit,
    onShareDebug: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val s = g.settings
    var forceMobile by remember { mutableStateOf(s.forceMobileData) }
    var autoOpen by remember { mutableStateOf(s.autoOpenFinal) }
    var concurrency by remember { mutableStateOf(s.concurrency.toFloat()) }
    var health by remember { mutableStateOf(s.healthCheck) }
    var browserMode by remember { mutableStateOf(BrowserMode.isEnabled(ctx)) }
    var forward by remember { mutableStateOf(s.forwardBrowser) }
    var newDomain by rememberSaveable { mutableStateOf("") }
    var tuned by remember { mutableStateOf(g.sites.tunedSites()) }
    val domains by g.sites.domainsFlow.collectAsStateWithLifecycle()
    val healthList by g.sites.health.collectAsStateWithLifecycle()
    val checkRuns by remember { HealthCheckWorker.runningFlow(ctx) }.collectAsState(initial = emptyList())
    val checking = checkRuns.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
    val browsers = remember { BrowserMode.browsers(ctx) }

    Column(
        modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SetupCard(linkStatus, onEnableLinks)

        // ---- background & shortcuts
        var background by remember { mutableStateOf(s.backgroundMode) }
        var tgDirect by remember { mutableStateOf(s.openTelegramDirectly) }
        var lock by remember { mutableStateOf(s.appLock) }
        val canLock = remember { AppLock.canLock(ctx) }
        Section("Background and shortcuts") {
            ToggleRow(
                "Background mode",
                "Tapped and shared links resolve without opening a screen; the final link arrives as a notification with Open and Copy.",
                background,
            ) { background = it; s.backgroundMode = it }
            ToggleRow("Open Telegram links in Telegram", "Final t.me links skip the browser and open the Telegram app directly.", tgDirect) {
                tgDirect = it
                s.openTelegramDirectly = it
            }
            ToggleRow(
                "App lock",
                if (canLock) "Fingerprint, face or screen PIN to open the app (it holds your Telegram login). Link taps still resolve."
                else "Set up a screen lock on the phone first.",
                lock && canLock,
            ) { if (canLock) { lock = it; s.appLock = it } }
            Text(
                "Quick Settings tile: pull down the quick settings, tap the pencil and drag “Resolve clipboard” in. " +
                    "It resolves every link in the clipboard.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                OutlinedButton(onClick = { QsTile.requestAdd(ctx) }) { Text("Add tile") }
            }
        }

        // ---- bot links
        var botAuto by remember { mutableStateOf(s.autoBotStart) }
        var botForward by remember { mutableStateOf(s.forwardBotReplies) }
        Section("Bot links") {
            Text(
                "When a final link is a bot start link (t.me/<bot>?start=…), the app can start the bot through your Telegram " +
                    "login and save what it replies (files, links, text). This sends a /start message from your account, " +
                    "paced at one bot every ${com.nik.linkresolver.telegram.BotStarter.MIN_GAP_MS / 1000} s. Without the switch, tap Start under a result.",
                style = MaterialTheme.typography.bodySmall,
            )
            ToggleRow("Start bot links automatically", "Every newly resolved bot link is started on its own.", botAuto) {
                botAuto = it
                s.autoBotStart = it
            }
            ToggleRow("Forward bot replies to Saved Messages", "Keeps every file the bots send in one place in Telegram.", botForward) {
                botForward = it
                s.forwardBotReplies = it
            }
        }

        // ---- channel watch
        val tgPrefs = g.telegram.prefs
        var watched by remember { mutableStateOf(tgPrefs.watched()) }
        var interval by remember { mutableStateOf(s.watchIntervalMin) }
        var autoResolve by remember { mutableStateOf(s.watchAutoResolve) }
        val watchRuns by remember { com.nik.linkresolver.service.ChannelWatchWorker.runningFlow(ctx) }.collectAsState(initial = emptyList())
        val watching = watchRuns.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
        Section("Channel watch") {
            Text(
                if (watched.isEmpty()) "No channels watched. Open a channel in the Telegram tab and tap Watch."
                else "Watched channels are checked for new posts; new links are resolved and sent as a notification.",
                style = MaterialTheme.typography.bodySmall,
            )
            watched.forEach { (id, title) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("★ $title", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    TextButton(onClick = { tgPrefs.unwatch(id); watched = tgPrefs.watched() }) { Text("Stop") }
                }
            }
            var open by remember { mutableStateOf(false) }
            val options = listOf(0 to "Off", 15 to "Every 15 min", 30 to "Every 30 min", 60 to "Every hour", 180 to "Every 3 hours", 360 to "Every 6 hours")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Check", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { open = true }) { Text(options.firstOrNull { it.first == interval }?.second ?: "$interval min") }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    options.forEach { (min, label) ->
                        DropdownMenuItem(text = { Text(label) }, onClick = {
                            interval = min
                            s.watchIntervalMin = min
                            com.nik.linkresolver.service.ChannelWatchWorker.schedule(ctx, min)
                            open = false
                        })
                    }
                }
            }
            ToggleRow("Resolve new links automatically", "Off: just notify, and you pick which to resolve.", autoResolve) {
                autoResolve = it
                s.watchAutoResolve = it
            }
            Text(
                "Android may delay background checks to save battery. For reliable checks, exempt Link Resolver from " +
                    "battery optimisation. At most ${com.nik.linkresolver.service.ChannelWatchWorker.MAX_LINKS_PER_RUN} links per check.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { com.nik.linkresolver.service.ChannelWatchWorker.runNow(ctx) }, enabled = watched.isNotEmpty() && !watching) {
                    Text(if (watching) "Checking…" else "Check now")
                }
                OutlinedButton(onClick = { BatteryHelp.open(ctx) }) { Text("Battery settings") }
            }
        }

        // ---- domains
        Section("Shortener domains") {
            Text(
                "Built in: ${g.registry.builtInHosts().sorted().joinToString()}. Any other link you share or paste is " +
                    "tried with the generic resolver; a domain that works is added here, so batches and Telegram pick it up.",
                style = MaterialTheme.typography.bodySmall,
            )
            domains.sorted().forEach { d ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(d, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    TextButton(onClick = { g.sites.removeDomain(d) }) { Text("Remove") }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = newDomain, onValueChange = { newDomain = it }, label = { Text("Add domain") },
                    placeholder = { Text("e.g. shortx.io") }, singleLine = true, modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = { g.sites.addDomain(newDomain); newDomain = "" },
                    enabled = SiteStore.normalizeHost(newDomain) != null,
                ) { Text("Add") }
            }
        }

        // ---- browser mode
        Section("Browser mode") {
            ToggleRow(
                "Act as a browser",
                "Tapped links from any domain come to this app first: shortlinks are resolved, everything else opens in your browser at once.",
                browserMode,
            ) {
                browserMode = it
                s.actAsBrowser = it
                BrowserMode.setEnabled(ctx, it)
            }
            if (browserMode) {
                var open by remember { mutableStateOf(false) }
                val label = browsers.firstOrNull { it.packageName == forward }?.label ?: browsers.firstOrNull()?.label ?: "none found"
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Other links open in", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { open = true }) { Text(label) }
                    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        browsers.forEach { b ->
                            DropdownMenuItem(text = { Text(b.label) }, onClick = { forward = b.packageName; s.forwardBrowser = b.packageName; open = false })
                        }
                    }
                }
                Text(
                    "Then make Link Resolver your default browser (Android settings → Default apps → Browser app). " +
                        "Domains added later work in browser mode too.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(onClick = { BrowserMode.openDefaultAppsSettings(ctx) }) { Text("Open default apps") }
            }
        }

        // ---- health
        Section("Site health") {
            ToggleRow("Daily check", "Once a day, re-resolve the latest working link of each site and notify if a site breaks.", health) {
                health = it
                s.healthCheck = it
                HealthCheckWorker.schedule(ctx, it)
            }
            if (healthList.isEmpty()) {
                Text("No checks yet. A site is checked once it has a resolved link.", style = MaterialTheme.typography.bodySmall)
            }
            healthList.forEach { h ->
                Column {
                    Text(
                        (if (h.ok) "✓ " else "✗ ") + h.site,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (h.ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                    )
                    Text("${formatTime(h.checkedAt)} · ${h.message}", style = MaterialTheme.typography.bodySmall)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { HealthCheckWorker.runNow(ctx) }, enabled = !checking) {
                    Text(if (checking) "Checking…" else "Check now")
                }
                if (healthList.any { !it.ok }) OutlinedButton(onClick = onShareDebug) { Text("Share debug bundle") }
            }
            if (tuned.isNotEmpty()) {
                HorizontalDivider()
                Text("Learned extra waits", style = MaterialTheme.typography.titleSmall)
                tuned.forEach { (site, ms) -> Text("$site: +${"%.1f".format(ms / 1000.0)} s per step", style = MaterialTheme.typography.bodyMedium) }
                TextButton(onClick = { g.sites.resetTuning(); tuned = g.sites.tunedSites() }) { Text("Reset") }
            }
        }

        // ---- site rules
        Section("Site rules") {
            val rs by g.rules.status.collectAsStateWithLifecycle()
            var checkingRules by remember { mutableStateOf(false) }
            val scope = rememberCoroutineScope()
            Text(
                if (rs.version == 0) "Built-in rules" else "v${rs.version} (${rs.source}" + (if (rs.updated.isNotEmpty()) ", ${rs.updated}" else "") + ") · ${rs.sites} site" + (if (rs.sites == 1) "" else "s"),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                "Waits, form names and hosts per site, downloaded from the GitHub repo once a day, so small site changes need no app update." +
                    (if (rs.checkedAt > 0) "\nLast check ${formatTime(rs.checkedAt)}" + (if (rs.message.isNotEmpty()) ": ${rs.message}" else "") else ""),
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    checkingRules = true
                    scope.launch { g.rules.refresh(); checkingRules = false }
                }, enabled = !checkingRules) { Text(if (checkingRules) "Checking…" else "Check now") }
                if (rs.source == "downloaded") TextButton(onClick = { g.rules.reset() }) { Text("Use bundled") }
            }
        }

        // ---- resolving
        Section("Resolving") {
            Column {
                Text("Parallel resolves: ${concurrency.toInt()}", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Each link always gets its own session. More at once is faster, but all requests come from your phone's IP.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Slider(
                    value = concurrency, onValueChange = { concurrency = it },
                    onValueChangeFinished = { s.concurrency = concurrency.toInt() },
                    valueRange = 1f..6f, steps = 4,
                )
            }
            ToggleRow("Force mobile data", "Use mobile data even when Wi-Fi is on (some networks can't reach the sites)", forceMobile) {
                forceMobile = it
                s.forceMobileData = it
            }
            ToggleRow("Open final link automatically", "Single links: skip the result screen and open the link right away", autoOpen) {
                autoOpen = it
                s.autoOpenFinal = it
            }
        }
        Text("v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SetupCard(linkStatus: Map<String, LinkStatus>, onEnableLinks: () -> Unit) {
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
                Text("Tap below, turn on “Open supported links”, then tick every domain.", style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!notNeeded) Button(onClick = onEnableLinks) { Text("Enable link opening") }
                TextButton(onClick = { showTip = !showTip }) { Text(if (showTip) "Hide Telegram tip" else "Telegram tip") }
            }
            if (showTip) {
                Text(
                    "Telegram opens links in its own in-app browser, which never hands them to Android. " +
                        "In Telegram: Settings → Chat Settings → turn off “In-App Browser”.\n\n" +
                        "Many links at once: select several posts in Telegram → Copy, then Paste on the Resolve tab. " +
                        "Or Share them to Link Resolver.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
