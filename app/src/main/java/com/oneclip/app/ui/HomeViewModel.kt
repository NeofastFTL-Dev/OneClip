package com.oneclip.app.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.oneclip.app.core.DownloadSpec
import com.oneclip.app.core.Downloader
import com.oneclip.app.core.Downloads
import com.oneclip.app.core.Engine
import com.oneclip.app.core.Prefs
import com.oneclip.app.core.ProbeResult
import com.oneclip.app.core.Urls
import com.oneclip.app.overlay.OverlayService
import kotlinx.coroutines.launch

class HomeViewModel(app: Application) : AndroidViewModel(app) {

    var url by mutableStateOf("")
        private set
    var probing by mutableStateOf(false)
        private set
    var info by mutableStateOf<ProbeResult?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var notice by mutableStateOf<String?>(null)
        private set
    var clipHint by mutableStateOf<String?>(null)
        private set
    var overlayOn by mutableStateOf(Prefs.overlayEnabled)
        private set
    var engineReady by mutableStateOf(false)
        private set
    var engineVersion by mutableStateOf<String?>(null)
        private set
    var busy by mutableStateOf(false)
        private set

    private var lastClipboard: String? = null

    init {
        refreshEngine()
    }

    fun onUrlChange(value: String) {
        url = value
        info = null
        error = null
    }

    fun onClipboard(link: String?) {
        if (link == null || link == lastClipboard) return
        lastClipboard = link
        if (link != url) clipHint = link
    }

    fun useClipboardHint() {
        val link = clipHint ?: return
        url = link
        clipHint = null
        info = null
        error = null
        fetch()
    }

    fun dismissHint() {
        clipHint = null
    }

    fun fetch() {
        val link = url.trim()
        if (link.isEmpty() || probing) return
        if (!link.startsWith("http://", true) && !link.startsWith("https://", true)) {
            error = "That does not look like a link. Copy a video URL and try again."
            return
        }
        probing = true
        error = null
        info = null
        viewModelScope.launch {
            try {
                info = Downloader.probe(link)
            } catch (t: Throwable) {
                error = t.message ?: "Could not read this link."
            } finally {
                probing = false
            }
        }
    }

    fun download(spec: DownloadSpec) {
        val link = url.trim()
        if (link.isEmpty()) return
        Downloads.enqueue(link, info?.title, spec)
        notice = "Queued ${spec.label}. Progress shows under Downloads."
    }

    fun clearNotice() {
        notice = null
    }

    fun setAudioFormat(value: String) {
        Prefs.audioFormat = value
    }

    fun refreshEngine() {
        engineVersion = Engine.version(getApplication())
        engineReady = Engine.isReady()
    }

    fun updateExtractors() {
        if (busy) return
        busy = true
        viewModelScope.launch {
            notice = try {
                Engine.updateExtractors(getApplication())
            } catch (t: Throwable) {
                Downloader.humanError(t)
            }
            refreshEngine()
            busy = false
        }
    }

    fun setOverlay(context: Context, enabled: Boolean) {
        if (enabled && !Settings.canDrawOverlays(context)) {
            Prefs.overlayEnabled = true
            overlayOn = true
            try {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (t: Throwable) {
                notice = "Open Settings › Apps › OneClip › Display over other apps."
            }
            return
        }
        Prefs.overlayEnabled = enabled
        overlayOn = enabled
        if (enabled) OverlayService.start(context) else OverlayService.stop(context)
    }

    fun refreshOverlay() {
        overlayOn = Prefs.overlayEnabled && Settings.canDrawOverlays(getApplication())
    }
}
