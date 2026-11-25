package com.oneclip.app.core

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

enum class DownloadStatus { QUEUED, RUNNING, DONE, FAILED, CANCELED }

data class DownloadSpec(
    val kind: Kind,
    val height: Int? = null,
    val audioFormat: String = "m4a"
) {
    enum class Kind { VIDEO, AUDIO }

    val label: String
        get() = if (kind == Kind.VIDEO) {
            height?.let { "${it}p" } ?: "Best"
        } else {
            "Audio ${audioFormat.uppercase()}"
        }
}

data class DownloadItem(
    val id: String,
    val url: String,
    val title: String,
    val spec: DownloadSpec,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val progress: Float = 0f,
    val detail: String = "",
    val fileName: String? = null,
    val uri: String? = null,
    val error: String? = null,
    val createdAt: Long = System.currentTimeMillis()
) {
    val label: String get() = spec.label
}

/**
 * Queue + observable state for every download. [DownloadService] drains it sequentially;
 * finished items are persisted so they survive an app restart.
 */
object Downloads {

    private val _items = MutableStateFlow<List<DownloadItem>>(emptyList())
    val items: StateFlow<List<DownloadItem>> = _items.asStateFlow()

    private lateinit var app: Context
    private val lock = Any()

    fun init(context: Context) {
        app = context.applicationContext
        _items.value = History.load(app)
    }

    fun enqueue(url: String, title: String?, spec: DownloadSpec): String {
        val id = UUID.randomUUID().toString().replace("-", "").take(10)
        val item = DownloadItem(
            id = id,
            url = url,
            title = title?.takeIf { it.isNotBlank() } ?: Urls.sourceLabel(url),
            spec = spec
        )
        _items.update { listOf(item) + it }
        try {
            app.startForegroundService(Intent(app, DownloadService::class.java).setAction(DownloadService.ACTION_START))
        } catch (t: Throwable) {
            fail(id, "Android blocked the download service. Open OneClip and start the download again.")
        }
        return id
    }

    internal fun takeNextQueued(): DownloadItem? = synchronized(lock) {
        val next = _items.value.firstOrNull { it.status == DownloadStatus.QUEUED }
            ?: return@synchronized null
        _items.update { list ->
            list.map {
                if (it.id == next.id) it.copy(status = DownloadStatus.RUNNING, detail = "Starting…") else it
            }
        }
        _items.value.firstOrNull { it.id == next.id }
    }

    internal fun progress(id: String, progress: Float, detail: String) {
        _items.update { list ->
            list.map {
                if (it.id == id && (it.status == DownloadStatus.RUNNING || it.status == DownloadStatus.QUEUED)) {
                    it.copy(
                        status = DownloadStatus.RUNNING,
                        progress = progress.coerceIn(0f, 100f),
                        detail = detail
                    )
                } else {
                    it
                }
            }
        }
    }

    internal fun complete(id: String, uri: String?, fileName: String) {
        _items.update { list ->
            list.map {
                if (it.id == id) {
                    it.copy(
                        status = DownloadStatus.DONE,
                        progress = 100f,
                        detail = "Saved to Downloads/OneClip",
                        uri = uri,
                        fileName = fileName
                    )
                } else {
                    it
                }
            }
        }
        History.save(app, _items.value)
    }

    internal fun fail(id: String, error: String) {
        _items.update { list ->
            list.map {
                if (it.id == id) it.copy(status = DownloadStatus.FAILED, error = error, detail = "") else it
            }
        }
    }

    internal fun markCanceled(id: String) {
        _items.update { list ->
            list.map {
                if (it.id == id && it.status != DownloadStatus.DONE) {
                    it.copy(status = DownloadStatus.CANCELED, detail = "")
                } else {
                    it
                }
            }
        }
    }

    fun cancel(id: String) {
        _items.update { list ->
            list.map {
                if (it.id == id && (it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.RUNNING)) {
                    it.copy(status = DownloadStatus.CANCELED, detail = "")
                } else {
                    it
                }
            }
        }
        Downloader.cancel(id)
    }

    fun clearFinished() {
        _items.update { list ->
            list.filter { it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.RUNNING }
        }
        History.save(app, _items.value)
    }

    fun find(id: String): DownloadItem? = _items.value.firstOrNull { it.id == id }
}

private object History {

    private fun file(context: Context) = File(context.filesDir, "history.json")

    fun load(context: Context): List<DownloadItem> = try {
        val f = file(context)
        if (!f.exists()) {
            emptyList()
        } else {
            val array = JSONArray(f.readText())
            (0 until array.length()).mapNotNull { index ->
                val o = array.optJSONObject(index) ?: return@mapNotNull null
                val kind = if (o.optString("kind") == "AUDIO") DownloadSpec.Kind.AUDIO else DownloadSpec.Kind.VIDEO
                DownloadItem(
                    id = o.optString("id"),
                    url = o.optString("url"),
                    title = o.optString("title"),
                    spec = DownloadSpec(
                        kind = kind,
                        height = if (o.isNull("height")) null else o.optInt("height"),
                        audioFormat = o.optString("audio_format", "m4a")
                    ),
                    status = DownloadStatus.DONE,
                    progress = 100f,
                    detail = "Saved to Downloads/OneClip",
                    fileName = if (o.isNull("file_name")) null else o.optString("file_name"),
                    uri = if (o.isNull("uri")) null else o.optString("uri"),
                    createdAt = o.optLong("created_at")
                )
            }
        }
    } catch (t: Throwable) {
        emptyList()
    }

    fun save(context: Context, items: List<DownloadItem>) {
        try {
            val done = items.filter { it.status == DownloadStatus.DONE }.take(100)
            val array = JSONArray()
            done.forEach { item ->
                array.put(JSONObject().apply {
                    put("id", item.id)
                    put("url", item.url)
                    put("title", item.title)
                    put("kind", item.spec.kind.name)
                    put("height", item.spec.height ?: JSONObject.NULL)
                    put("audio_format", item.spec.audioFormat)
                    put("file_name", item.fileName ?: JSONObject.NULL)
                    put("uri", item.uri ?: JSONObject.NULL)
                    put("created_at", item.createdAt)
                })
            }
            file(context).writeText(array.toString())
        } catch (t: Throwable) {
            // History is best effort; never break a download over it.
        }
    }
}
