package com.nik.linkresolver.ui

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.core.net.toUri
import com.nik.linkresolver.graph

/** Invisible entry point for browser mode: resolve known shortlinks, forward everything else. */
class BrowserEntryActivity : android.app.Activity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        val g = application.graph
        val data = intent.data
        val url = intent.dataString?.let { com.nik.linkresolver.engine.ResolverRunner.normalize(it) }
        when {
            data == null -> Unit
            url != null && g.registry.find(url) != null ->
                startActivity(Intent(this, ResolveActivity::class.java).setAction(Intent.ACTION_VIEW).setData(data))
            else -> BrowserMode.forward(this, data, g.settings.forwardBrowser)
        }
        finish()
    }
}

/**
 * Browser mode: an entry activity (off by default) that makes the app a browser choice for every http(s) link.
 * Shortlinks are resolved; everything else is handed to the user's real browser right away.
 */
object BrowserMode {
    fun isEnabled(context: Context): Boolean =
        context.packageManager.getComponentEnabledSetting(ComponentName(context.packageName, ALIAS)) ==
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    private const val ALIAS = "com.nik.linkresolver.ui.BrowserEntryActivity"

    data class Browser(val packageName: String, val label: String)

    fun setEnabled(context: Context, on: Boolean) {
        context.packageManager.setComponentEnabledSetting(
            ComponentName(context.packageName, ALIAS),
            if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
    }

    /** Installed browsers other than this app. */
    fun browsers(context: Context): List<Browser> {
        val pm = context.packageManager
        val probe = Intent(Intent.ACTION_VIEW, "https://example.com/".toUri()).addCategory(Intent.CATEGORY_BROWSABLE)
        return pm.queryIntentActivities(probe, PackageManager.MATCH_ALL)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filter { it.first != context.packageName }
            .distinctBy { it.first }
            .map { Browser(it.first, it.second) }
            .sortedBy { it.label.lowercase() }
    }

    /** Opens [uri] in the chosen (or first) real browser, never in this app. */
    fun forward(context: Context, uri: Uri, preferred: String?) {
        val list = browsers(context)
        val pkg = preferred?.takeIf { p -> list.any { it.packageName == p } } ?: list.firstOrNull()?.packageName
        val view = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            if (pkg != null) context.startActivity(Intent(view).setPackage(pkg)) else context.startActivity(view)
        } catch (e: ActivityNotFoundException) {
            context.toast("No browser found")
        }
    }

    fun openDefaultAppsSettings(context: Context) {
        runCatching { context.startActivity(Intent(AndroidSettings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { context.startActivity(Intent(AndroidSettings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}
