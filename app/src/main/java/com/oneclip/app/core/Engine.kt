package com.oneclip.app.core

import android.content.Context
import android.util.Log
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns the bundled python / yt-dlp / ffmpeg runtime. Initialising unzips the python
 * distribution, which takes a moment, so it happens once on a background thread and every
 * caller awaits [await] before touching [YoutubeDL].
 */
object Engine {

    private const val TAG = "OneClip/Engine"

    private val ready = CompletableDeferred<Unit>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var failure: String? = null
    @Volatile private var started = false

    fun init(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        scope.launch {
            try {
                YoutubeDL.getInstance().init(app)
                FFmpeg.getInstance().init(app)
                refreshExtractorsIfDue(app)
                ready.complete(Unit)
            } catch (t: Throwable) {
                Log.e(TAG, "engine init failed", t)
                failure = t.message ?: "The download engine failed to start."
                ready.completeExceptionally(t)
            }
        }
    }

    suspend fun await() {
        try {
            ready.await()
        } catch (t: Throwable) {
            throw ProbeException(failure ?: "The download engine failed to start.")
        }
    }

    /**
     * The yt-dlp binary baked into the APK ages quickly and sites stop working once it does,
     * so a fresh install (and an install older than a week) refreshes it before the first probe.
     */
    private fun refreshExtractorsIfDue(context: Context) {
        if (!Prefs.extractorRefreshDue()) return
        try {
            YoutubeDL.getInstance().updateYoutubeDL(context, YoutubeDL.UpdateChannel.STABLE)
            Prefs.lastExtractorUpdate = System.currentTimeMillis()
            Log.i(TAG, "extractors refreshed: ${YoutubeDL.version(context)}")
        } catch (t: Throwable) {
            // Offline or rate limited: keep the bundled extractor and retry on a later launch.
            Log.w(TAG, "extractor refresh failed", t)
        }
    }

    fun isReady(): Boolean = ready.isCompleted && failure == null

    fun version(context: Context): String? = try {
        YoutubeDL.version(context)
    } catch (t: Throwable) {
        null
    }

    suspend fun updateExtractors(context: Context): String = withContext(Dispatchers.IO) {
        await()
        val status = YoutubeDL.getInstance().updateYoutubeDL(context, YoutubeDL.UpdateChannel.STABLE)
        Prefs.lastExtractorUpdate = System.currentTimeMillis()
        if (status == YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE) {
            "Extractors are already up to date."
        } else {
            "Extractors updated."
        }
    }
}
