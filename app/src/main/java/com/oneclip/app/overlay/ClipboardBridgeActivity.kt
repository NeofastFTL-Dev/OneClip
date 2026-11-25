package com.oneclip.app.overlay

import android.app.Activity
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle

/**
 * Android 10+ only allows the app that currently has input focus to read the clipboard.
 * The floating bubble runs in a background service, so when it cannot read the clipboard it
 * shows this transparent, zero-animation activity for a frame, reads the text while focused
 * and hands it straight back to [OverlayService].
 */
class ClipboardBridgeActivity : Activity() {

    private var handled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setDimAmount(0f)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || handled) return
        handled = true

        val text = try {
            getSystemService(ClipboardManager::class.java)
                ?.primaryClip
                ?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)
                ?.coerceToText(this)
                ?.toString()
        } catch (t: Throwable) {
            null
        }

        val intent = Intent(this, OverlayService::class.java)
            .setAction(OverlayService.ACTION_CLIP)
            .putExtra(OverlayService.EXTRA_TEXT, text)
        try {
            startService(intent)
        } catch (t: Throwable) {
            // Service will fall back to its own error message.
        }

        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
