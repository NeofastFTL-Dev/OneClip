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
}
