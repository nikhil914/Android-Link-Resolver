package com.nik.linkresolver.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.nik.linkresolver.data.db.Source
import com.nik.linkresolver.engine.ResolverRunner
import com.nik.linkresolver.graph
import com.nik.linkresolver.service.ResultNotifier
import kotlinx.coroutines.launch

/**
 * Invisible screen opened by the Quick Settings tile. Android only lets the app in front read the clipboard, and
 * only once its window has focus, so this reads it then, queues the links and closes.
 */
class ClipboardActivity : ComponentActivity() {
    private var done = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) done = true
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || done) return
        done = true
        val g = application.graph
        val text = clipboardText().orEmpty()
        val links = g.batches.extract(text)
            .ifEmpty { listOfNotNull(ResolverRunner.extractUrl(text)?.let { ResolverRunner.normalize(it)?.toString() }) }
        if (links.isEmpty()) {
            toast("No link in the clipboard")
            finish()
            return
        }
        lifecycleScope.launch {
            try {
                val cached = if (links.size == 1) g.batches.lastDone(links[0]) else null
                if (cached?.finalUrl != null) {
                    ResultNotifier.notify(applicationContext, 1003, "Already resolved", listOf(cached.finalUrl), 0)
                } else {
                    g.batches.enqueue(links, Source.CLIPBOARD, if (links.size == 1) "Clipboard link" else "${links.size} clipboard links")
                    toast(if (links.size == 1) "Resolving the clipboard link…" else "Resolving ${links.size} links…")
                }
            } finally {
                finish()
            }
        }
    }
}
