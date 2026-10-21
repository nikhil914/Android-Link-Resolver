package com.nik.linkresolver.data

import android.content.Context
import androidx.core.content.edit

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Route requests over mobile data even when Wi-Fi is connected (home broadband can't reach the sites). */
    var forceMobileData: Boolean
        get() = prefs.getBoolean(KEY_FORCE_MOBILE, true)
        set(v) = prefs.edit { putBoolean(KEY_FORCE_MOBILE, v) }

    var autoOpenFinal: Boolean
        get() = prefs.getBoolean(KEY_AUTO_OPEN, false)
        set(v) = prefs.edit { putBoolean(KEY_AUTO_OPEN, v) }

    /** How many links resolve at the same time in a batch (1–6). */
    var concurrency: Int
        get() = prefs.getInt(KEY_CONCURRENCY, 3).coerceIn(1, 6)
        set(v) = prefs.edit { putInt(KEY_CONCURRENCY, v.coerceIn(1, 6)) }

    private companion object {
        const val KEY_CONCURRENCY = "concurrency"
        const val KEY_FORCE_MOBILE = "force_mobile_data"
        const val KEY_AUTO_OPEN = "auto_open_final"
    }
}
