package com.oneclip.app.core

import android.content.Context
import android.content.SharedPreferences

object Prefs {

    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        sp = context.applicationContext.getSharedPreferences("oneclip", Context.MODE_PRIVATE)
    }

    var overlayEnabled: Boolean
        get() = sp.getBoolean("overlay_enabled", false)
        set(value) {
            sp.edit().putBoolean("overlay_enabled", value).apply()
        }

    var audioFormat: String
        get() = sp.getString("audio_format", "m4a") ?: "m4a"
        set(value) {
            sp.edit().putString("audio_format", value).apply()
        }

    var lastExtractorUpdate: Long
        get() = sp.getLong("extractor_update", 0L)
        set(value) {
            sp.edit().putLong("extractor_update", value).apply()
        }

    /** The bundled yt-dlp goes stale fast; refresh it weekly (and on first run). */
    fun extractorRefreshDue(now: Long = System.currentTimeMillis()): Boolean {
        val last = lastExtractorUpdate
        return last == 0L || now - last > 7L * 24L * 60L * 60L * 1000L
    }
}
