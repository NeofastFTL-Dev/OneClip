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

    fun isReady(): Boolean = ready.isCompleted && failure == null

    fun version(context: Context): String? = try {
        YoutubeDL.version(context)
    } catch (t: Throwable) {
        null
    }

    suspend fun updateExtractors(context: Context): String = withContext(Dispatchers.IO) {
        await()
        val status = YoutubeDL.getInstance().updateYoutubeDL(context, YoutubeDL.UpdateChannel.STABLE)
        if (status == YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE) {
            "Extractors are already up to date."
        } else {
            "Extractors updated."
        }
    }
}
