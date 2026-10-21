package com.nik.linkresolver.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nik.linkresolver.engine.ErrorKind
import com.nik.linkresolver.engine.userMessage

/** Per-site success rate and time, failures by reason, links per day, data used. */
@Composable
fun StatsScreen(vm: MainViewModel, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val sites by vm.siteStats.collectAsStateWithLifecycle()
    val kinds by vm.failureKinds.collectAsStateWithLifecycle()
    val days by vm.days.collectAsStateWithLifecycle()
    val totalBytes = sites.sumOf { it.bytes ?: 0 }
    val totalDone = sites.sumOf { it.done }
    val totalFailed = sites.sumOf { it.failed }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onClose) { Text("‹ History") }
            Text("Stats", style = MaterialTheme.typography.titleMedium)
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val rate = if (totalDone + totalFailed > 0) 100 * totalDone / (totalDone + totalFailed) else 0
                Text("$totalDone resolved · $totalFailed failed · $rate% success", style = MaterialTheme.typography.titleSmall)
                Text("About ${bytesText(totalBytes)} of data used by resolves (counted since v0.1.15)", style = MaterialTheme.typography.bodySmall)
                Text("Results copied from history are not counted.", style = MaterialTheme.typography.labelSmall)
            }
        }

        if (sites.isNotEmpty()) Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("By site", style = MaterialTheme.typography.titleSmall)
                sites.forEach { st ->
                    val rate = if (st.total > 0) st.done.toFloat() / st.total else 0f
                    Column {
                        Row {
                            Text(st.site, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                            Text("${(rate * 100).toInt()}%")
                        }
                        Bar(rate)
                        Text(
                            "${st.done} of ${st.total} · avg ${st.avgMs?.let { "%.0f s".format(it / 1000) } ?: "–"} · ${bytesText(st.bytes ?: 0)}" +
                                if (st.done > 0 && (st.bytes ?: 0) > 0) " (${bytesText((st.bytes ?: 0) / st.total)} per link)" else "",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }

        if (kinds.isNotEmpty()) Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Why links failed", style = MaterialTheme.typography.titleSmall)
                val max = kinds.maxOf { it.n }.coerceAtLeast(1)
                kinds.forEach { k ->
                    val label = k.errorKind?.let { runCatching { ErrorKind.valueOf(it).userMessage() }.getOrDefault(it) } ?: "Unknown"
                    Text("${k.n} × $label", style = MaterialTheme.typography.bodySmall)
                    Bar(k.n.toFloat() / max, error = true)
                }
            }
        }

        if (days.isNotEmpty()) Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Last 14 days", style = MaterialTheme.typography.titleSmall)
                val max = days.maxOf { it.done + it.failed }.coerceAtLeast(1)
                days.forEach { d ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(d.day.substring(5), Modifier.width(52.dp), style = MaterialTheme.typography.labelSmall)
                        Box(Modifier.weight(1f)) { Bar((d.done + d.failed).toFloat() / max) }
                        Text("  ${d.done}" + if (d.failed > 0) " / ${d.failed}✗" else "", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        if (sites.isEmpty()) {
            HorizontalDivider()
            Text("No resolves yet.", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun Bar(fraction: Float, error: Boolean = false) {
    Box(Modifier.fillMaxWidth().height(8.dp).background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small)) {
        Box(
            Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight()
                .background(if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary, MaterialTheme.shapes.small),
        )
    }
}

fun bytesText(b: Long): String = when {
    b >= 1L shl 30 -> "%.1f GB".format(b / (1L shl 30).toDouble())
    b >= 1L shl 20 -> "%.1f MB".format(b / (1L shl 20).toDouble())
    b >= 1024 -> "${b / 1024} KB"
    else -> "$b B"
}
