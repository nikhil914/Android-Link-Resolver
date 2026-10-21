package com.nik.linkresolver.ui

import android.content.Context
import android.content.Intent
import android.content.pm.verify.domain.DomainVerificationManager
import android.content.pm.verify.domain.DomainVerificationUserState
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.nik.linkresolver.BuildConfig
import com.nik.linkresolver.graph

class MainActivity : ComponentActivity() {
    private var linkStatus by mutableStateOf(LinkStatus.UNKNOWN)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val settings = application.graph.settings
        setContent {
            AppTheme {
                MainScreen(
                    linkStatus = linkStatus,
                    initialForceMobile = settings.forceMobileData,
                    initialAutoOpen = settings.autoOpenFinal,
                    onForceMobile = { settings.forceMobileData = it },
                    onAutoOpen = { settings.autoOpenFinal = it },
                    onEnableLinks = ::openLinkSettings,
                    onResolve = { url ->
                        startActivity(Intent(this, ResolveActivity::class.java).putExtra(ResolveActivity.EXTRA_URL, url))
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        linkStatus = linkOpeningStatus(this)
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
}

enum class LinkStatus { UNKNOWN, ENABLED, DISABLED, NOT_NEEDED }

const val LINK_HOST = "linkshortx.in"

fun linkOpeningStatus(ctx: Context): LinkStatus {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return LinkStatus.NOT_NEEDED
    val dvm = ctx.getSystemService(DomainVerificationManager::class.java) ?: return LinkStatus.UNKNOWN
    val state = runCatching { dvm.getDomainVerificationUserState(ctx.packageName) }.getOrNull() ?: return LinkStatus.UNKNOWN
    val host = state.hostToStateMap[LINK_HOST] ?: return LinkStatus.UNKNOWN
    return if (state.isLinkHandlingAllowed &&
        (host == DomainVerificationUserState.DOMAIN_STATE_SELECTED || host == DomainVerificationUserState.DOMAIN_STATE_VERIFIED)
    ) {
        LinkStatus.ENABLED
    } else {
        LinkStatus.DISABLED
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    linkStatus: LinkStatus,
    initialForceMobile: Boolean,
    initialAutoOpen: Boolean,
    onForceMobile: (Boolean) -> Unit,
    onAutoOpen: (Boolean) -> Unit,
    onEnableLinks: () -> Unit,
    onResolve: (String) -> Unit,
) {
    val ctx = LocalContext.current
    var text by remember { mutableStateOf("") }
    var forceMobile by remember { mutableStateOf(initialForceMobile) }
    var autoOpen by remember { mutableStateOf(initialAutoOpen) }

    Scaffold(topBar = { TopAppBar(title = { Text("Link Resolver") }) }) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ---- manual resolve
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Shortlink") },
                placeholder = { Text("https://linkshortx.in/…") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { ctx.clipboardText()?.let { text = it.trim() } ?: ctx.toast("Clipboard is empty") }) {
                    Text("Paste")
                }
                Button(onClick = { if (text.isNotBlank()) onResolve(text.trim()) }, enabled = text.isNotBlank()) {
                    Text("Resolve")
                }
            }

            // ---- link opening setup
            val (statusText, ok) = when (linkStatus) {
                LinkStatus.ENABLED -> "✓ $LINK_HOST links open in this app" to true
                LinkStatus.DISABLED -> "✗ $LINK_HOST links open in the browser" to false
                LinkStatus.NOT_NEEDED -> "Android will ask which app to use for $LINK_HOST links" to true
                LinkStatus.UNKNOWN -> "Link opening status unknown" to false
            }
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (ok) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer,
                ),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Open links in this app", style = MaterialTheme.typography.titleMedium)
                    Text(statusText, style = MaterialTheme.typography.bodyLarge)
                    if (linkStatus != LinkStatus.NOT_NEEDED) {
                        Text(
                            "Android 12+ only opens unverified links in an app if you allow it: tap below, turn on " +
                                "“Open supported links”, then tick $LINK_HOST under the supported links.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Button(onClick = onEnableLinks) { Text("Enable link opening") }
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Telegram tip", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Telegram opens links in its own in-app browser, which never hands them to Android. " +
                            "In Telegram: Settings → Chat Settings → turn off “In-App Browser” (external links then go " +
                            "to Android). After that, tapping a $LINK_HOST link opens this app.\n\n" +
                            "Fallback that always works: long-press the link → Share → Link Resolver.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            // ---- settings
            SettingRow("Force mobile data", "Use mobile data even when Wi-Fi is on (some networks can't reach the site)", forceMobile) {
                forceMobile = it
                onForceMobile(it)
            }
            SettingRow("Open final link automatically", "Skip the result screen and open the link right away", autoOpen) {
                autoOpen = it
                onAutoOpen(it)
            }

            Text("v${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
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
