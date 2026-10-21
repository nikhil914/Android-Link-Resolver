package com.nik.linkresolver.ui

import android.annotation.SuppressLint
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.annotation.RequiresApi
import androidx.core.net.toUri
import com.nik.linkresolver.R
import com.nik.linkresolver.service.ClipboardTile

object QsTile {
    /** Android 13+: asks the system to add the "Resolve clipboard" tile. */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun requestAdd(context: Context) {
        context.getSystemService(StatusBarManager::class.java).requestAddTileService(
            ComponentName(context, ClipboardTile::class.java),
            context.getString(R.string.tile_label),
            Icon.createWithResource(context, R.drawable.ic_stat_link),
            context.mainExecutor,
        ) { }
    }
}

object BatteryHelp {
    /** Opens the "ignore battery optimisation" request for this app, or the general list. */
    @SuppressLint("BatteryLife")
    fun open(context: Context) {
        val direct = Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${context.packageName}".toUri())
        runCatching { context.startActivity(direct.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.onFailure {
            runCatching { context.startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }
}
