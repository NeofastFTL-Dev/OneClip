package com.oneclip.app.core

import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

class ProbeException(message: String) : Exception(message)

data class ProbeResult(
    val title: String,
    val uploader: String?,
    val durationSeconds: Long?,
    val heights: List<Int>
)

object Downloader {

    /** Reads metadata plus the video heights the extractor can offer for this link. */
    suspend fun probe(url: String): ProbeResult = withContext(Dispatchers.IO) {
        Engine.await()

        val request = YoutubeDLRequest(url).apply {
            addOption("--no-playlist")
            addOption("--dump-single-json")
            addOption("--no-warnings")
            addOption("--socket-timeout", "20")
        }
        val response = try {
            YoutubeDL.getInstance().execute(request, null, null)
        } catch (t: Throwable) {
            throw ProbeException(humanError(t))
        }

        val jsonText = response.out.lineSequence().map { it.trim() }.lastOrNull { it.startsWith("{") }
            ?: throw ProbeException("This link could not be read. It may be private, need a login, or be unsupported.")
        val json = try {
            JSONObject(jsonText)
        } catch (t: Throwable) {
            throw ProbeException("The extractor returned an unexpected response.")
        }

        val found = linkedSetOf<Int>()
        val formats = json.optJSONArray("formats")
        if (formats != null) {
            for (i in 0 until formats.length()) {
                val format = formats.optJSONObject(i) ?: continue
                if (format.optString("vcodec") == "none") continue
                val height = format.optInt("height", 0)
                if (height in 144..4320) found.add(height)
            }
        }
        if (found.isEmpty()) {
            val single = json.optInt("height", 0)
            if (single in 144..4320) found.add(single)
        }

        val sorted = found.sortedDescending()
        val preferred = sorted.filter { it >= 240 }.take(6)

        ProbeResult(
            title = json.optString("title").ifBlank { Urls.sourceLabel(url) },
            uploader = sequenceOf(
                json.optString("uploader"),
                json.optString("channel"),
                json.optString("uploader_id")
            ).firstOrNull { it.isNotBlank() },
            durationSeconds = json.optDouble("duration", 0.0).takeIf { it > 0 }?.toLong(),
            heights = if (preferred.isNotEmpty()) preferred else sorted.take(6)
        )
    }

    /**
     * Downloads one link into [workDir] and returns the produced files (largest first).
     * Progress is reported as a percentage plus yt-dlp's current status line.
     */
    suspend fun download(
        url: String,
        spec: DownloadSpec,
        workDir: File,
        processId: String,
        onProgress: (Float, String) -> Unit
    ): List<File> = withContext(Dispatchers.IO) {
        Engine.await()
        workDir.mkdirs()

        val callback: (Float, Long, String) -> Unit = { progress, _, line -> onProgress(progress, line) }
        try {
            YoutubeDL.getInstance().execute(buildRequest(url, spec, workDir, retry = false), processId, callback)
        } catch (t: Throwable) {
            if (t is YoutubeDL.CanceledException) throw t
            val message = t.message.orEmpty()
            val isYouTube = Urls.host(url).let { it == "youtu.be" || it.endsWith("youtube.com") }
            val retryable = isYouTube && (
                message.contains("403") ||
                    message.contains("PO Token", ignoreCase = true) ||
                    message.contains("Sign in to confirm", ignoreCase = true) ||
                    message.contains("not a bot", ignoreCase = true)
                )
            if (!retryable) throw t
            // A different YouTube client often sidesteps PO-token / 403 failures.
            YoutubeDL.getInstance().execute(buildRequest(url, spec, workDir, retry = true), processId, callback)
        }

        workDir.listFiles()
            ?.filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }
            ?.sortedByDescending { it.length() }
            ?: emptyList()
    }

    private fun buildRequest(url: String, spec: DownloadSpec, dir: File, retry: Boolean): YoutubeDLRequest =
        YoutubeDLRequest(url).apply {
            addOption("--no-playlist")
            addOption("--no-mtime")
            addOption("--newline")
            addOption("-o", File(dir, "%(title).90B [%(id)s].%(ext)s").absolutePath)
            if (retry) addOption("--extractor-args", "youtube:player_client=android_vr")

            when (spec.kind) {
                DownloadSpec.Kind.VIDEO -> {
                    val height = spec.height
                    if (height != null && height > 0) {
                        addOption("-f", "bv*[height<=$height]+ba/b[height<=$height]/b")
                        addOption("-S", "res:$height,ext:mp4:m4a")
                    } else {
                        addOption("-f", "bv*+ba/b")
                    }
                    addOption("--merge-output-format", "mp4")
                }

                DownloadSpec.Kind.AUDIO -> {
                    addOption("-f", "ba/b")
                    addOption("-x")
                    addOption("--audio-format", spec.audioFormat)
                    addOption("--audio-quality", "0")
                }
            }
        }

    fun cancel(processId: String) {
        try {
            YoutubeDL.getInstance().destroyProcessById(processId)
        } catch (t: Throwable) {
            // Process already gone.
        }
    }

    fun humanError(t: Throwable?): String {
        val raw = t?.message?.takeIf { it.isNotBlank() } ?: return "Download failed."
        val lines = raw.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val error = lines.firstOrNull { it.startsWith("ERROR", ignoreCase = true) }
            ?: lines.lastOrNull()
            ?: return "Download failed."
        return error.replace(Regex("^ERROR:\\s*", RegexOption.IGNORE_CASE), "").take(400)
    }
}
