package com.nik.linkresolver.data

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.nik.linkresolver.engine.InMemoryDebugRecorder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Keeps the HTML + log of the last resolve in cache, and zips it for sharing when a site changes. */
class DebugStore(private val context: Context) {
    private val root get() = File(context.cacheDir, "debug")
    private val lastDir get() = File(root, "last")

    fun save(recorder: InMemoryDebugRecorder, summary: String) {
        lastDir.deleteRecursively()
        lastDir.mkdirs()
        File(lastDir, "log.txt").writeText(summary + "\n\n" + recorder.asText() + "\n")
        for (p in recorder.pages) {
            val host = runCatching { java.net.URI(p.url).host }.getOrNull() ?: "page"
            val ext = if (p.body.trimStart().startsWith("{")) "json" else "html"
            File(lastDir, "%02d_%s.%s".format(p.index, host, ext))
                .writeText("<!-- ${p.method} ${p.url} -> ${p.status} -->\n${p.body}")
        }
    }

    fun hasBundle(): Boolean = File(lastDir, "log.txt").exists()

    /** Share sheet intent for a zip of the last resolve, or null if there is nothing saved. */
    fun shareIntent(): Intent? {
        val files = lastDir.listFiles()?.sortedBy { it.name } ?: return null
        if (files.isEmpty()) return null
        val zip = File(root, "link-resolver-debug.zip")
        ZipOutputStream(zip.outputStream()).use { out ->
            for (f in files) {
                out.putNextEntry(ZipEntry(f.name))
                f.inputStream().use { it.copyTo(out) }
                out.closeEntry()
            }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", zip)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/zip")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, "Link Resolver debug bundle")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, "Share debug bundle")
    }
}
