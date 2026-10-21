package com.nik.linkresolver.ui

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.nik.linkresolver.data.db.ResolveEntity
import com.nik.linkresolver.data.db.Status
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Final URLs of successful rows, one per line, in list order. */
fun finalUrlsText(rows: List<ResolveEntity>): String =
    rows.filter { it.status == Status.DONE && !it.finalUrl.isNullOrEmpty() }.joinToString("\n") { it.finalUrl!! }

fun Context.shareCsv(rows: List<ResolveEntity>, name: String) {
    val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    fun q(s: String?) = "\"" + (s ?: "").replace("\"", "\"\"") + "\""
    val csv = buildString {
        append("source_url,final_url,status,error,site,source,channel,finished_at,duration_s\n")
        for (r in rows) {
            append(
                listOf(
                    q(r.sourceUrl), q(r.finalUrl), q(r.status), q(r.error), q(r.site), q(r.source), q(r.channel),
                    q(r.finishedAt?.let { fmt.format(Date(it)) }), q(r.durationMs?.let { (it / 1000).toString() }),
                ).joinToString(","),
            )
            append('\n')
        }
    }
    val dir = File(cacheDir, "exports").apply { mkdirs() }
    val file = File(dir, "$name.csv").apply { writeText(csv) }
    val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
    val send = Intent(Intent.ACTION_SEND).setType("text/csv").putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    startActivity(Intent.createChooser(send, "Export CSV"))
}

fun formatTime(ms: Long): String = SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(ms))
