package com.nik.linkresolver.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import com.nik.linkresolver.engine.TelegramUri
import com.nik.linkresolver.graph

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colors, content = content)
}

fun Context.openUrl(url: String) {
    try {
        startActivity(viewIntent(url))
    } catch (e: ActivityNotFoundException) {
        toast("No app can open this link")
    }
}

/**
 * VIEW intent for a final link. t.me links go straight to the Telegram app (tg://) when it is installed and the
 * setting is on, skipping the browser hop; otherwise the https link is used.
 */
fun Context.viewIntent(url: String): Intent {
    val direct = (applicationContext as? android.app.Application)?.graph?.settings?.openTelegramDirectly ?: true
    val tg = if (direct) TelegramUri.toApp(url) else null
    if (tg != null) {
        val i = Intent(Intent.ACTION_VIEW, tg.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (i.resolveActivity(packageManager) != null) return i
    }
    return Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

fun Context.copyText(label: String, text: String) {
    getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) toast("Copied")
}

fun Context.shareText(text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    startActivity(Intent.createChooser(send, null))
}

fun Context.toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

fun Context.clipboardText(): String? =
    getSystemService(ClipboardManager::class.java).primaryClip?.takeIf { it.itemCount > 0 }
        ?.getItemAt(0)?.coerceToText(this)?.toString()
