package com.oneclip.app.overlay

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.oneclip.app.MainActivity
import com.oneclip.app.R
import com.oneclip.app.core.DownloadSpec
import com.oneclip.app.core.Downloader
import com.oneclip.app.core.Downloads
import com.oneclip.app.core.Prefs
import com.oneclip.app.core.ProbeResult
import com.oneclip.app.core.Urls
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Floating "copy a link" bubble. Tapping it reads the clipboard (via [ClipboardBridgeActivity]
 * when the platform blocks a background read), asks yt-dlp which qualities exist and shows a
 * small popup with video / audio choices.
 */
class OverlayService : Service() {

    companion object {
        const val ACTION_START = "com.oneclip.app.overlay.START"
        const val ACTION_CLIP = "com.oneclip.app.overlay.CLIP"
        const val ACTION_STOP = "com.oneclip.app.overlay.STOP"
        const val EXTRA_TEXT = "text"

        private const val NOTIF_ID = 77
        private const val CHANNEL = "overlay"

        private val TEXT = 0xFFE9F1F7.toInt()
        private val MUTED = 0xFF93A7BA.toInt()
        private val ACCENT = 0xFF7CE0B3.toInt()
        private val WARN = 0xFFFFB4AB.toInt()

        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, OverlayService::class.java).setAction(ACTION_START))
            } catch (t: Throwable) {
                // Ignored: the user can retry from the app.
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, OverlayService::class.java))
            } catch (t: Throwable) {
                // Nothing to stop.
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var windowManager: WindowManager
    private var bubble: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var panel: LinearLayout? = null
    private var panelTitle: TextView? = null
    private var panelSubtitle: TextView? = null
    private var panelRows: LinearLayout? = null

    private var currentUrl: String? = null
    private var currentTitle: String? = null
    private var lastHandled: Pair<String, Long>? = null
    private var clipListener: ClipboardManager.OnPrimaryClipChangedListener? = null
    private var available = false

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WindowManager::class.java)
        createChannel()
        try {
            startForeground(NOTIF_ID, notification())
        } catch (t: Throwable) {
            stopSelf()
            return
        }

        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        available = true
        addBubble()
        registerClipboardListener()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CLIP -> handleIncomingText(intent.getStringExtra(EXTRA_TEXT))
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        available = false
        clipListener?.let { listener ->
            try {
                getSystemService(ClipboardManager::class.java)?.removePrimaryClipChangedListener(listener)
            } catch (t: Throwable) {
                // Ignore.
            }
        }
        clipListener = null
        scope.cancel()
        hidePanel()
        bubble?.let { view ->
            try {
                windowManager.removeView(view)
            } catch (t: Throwable) {
                // Already detached.
            }
        }
        bubble = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------------------------------------------------------------- clipboard

    private fun readClipboard(): String? = try {
        getSystemService(ClipboardManager::class.java)
            ?.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(this)
            ?.toString()
    } catch (t: Throwable) {
        null
    }

    private fun registerClipboardListener() {
        val manager = getSystemService(ClipboardManager::class.java) ?: return
        val listener = ClipboardManager.OnPrimaryClipChangedListener {
            // Works on older releases and whenever OneClip happens to hold focus; on Android 10+
            // in the background this silently returns null and the bubble tap path takes over.
            val url = Urls.extract(readClipboard()) ?: return@OnPrimaryClipChangedListener
            handleUrl(url)
        }
        clipListener = listener
        manager.addPrimaryClipChangedListener(listener)
    }

    private fun onBubbleTap() {
        val text = readClipboard()
        if (text == null) {
            openBridge()
            return
        }
        val url = Urls.extract(text)
        if (url == null) {
            toast("No video link in the clipboard")
            return
        }
        handleUrl(url)
    }

    private fun openBridge() {
        try {
            startActivity(
                Intent(this, ClipboardBridgeActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            )
        } catch (t: Throwable) {
            toast("Could not read the clipboard")
        }
    }

    private fun handleIncomingText(text: String?) {
        if (text == null) {
            toast("Could not read the clipboard")
            return
        }
        val url = Urls.extract(text)
        if (url == null) {
            toast("No video link in the clipboard")
            return
        }
        handleUrl(url)
    }

    private fun handleUrl(url: String) {
        val now = SystemClock.elapsedRealtime()
        val last = lastHandled
        if (last != null && last.first == url && now - last.second < 8_000) return
        lastHandled = url to now

        currentUrl = url
        currentTitle = null
        showPanel()
        renderRows(loading = true, info = null, error = null)

        scope.launch {
            try {
                val info = Downloader.probe(url)
                if (currentUrl == url) {
                    currentTitle = info.title
                    renderRows(loading = false, info = info, error = null)
                    updateHeader(info.title, subtitleFor(url, info))
                }
            } catch (t: Throwable) {
                if (currentUrl == url) {
                    currentTitle = null
                    updateHeader(Urls.sourceLabel(url), "")
                    renderRows(loading = false, info = null, error = t.message ?: "Could not read this link")
                }
            }
        }
    }

    // ---------------------------------------------------------------- bubble

    @SuppressLint("ClickableViewAccessibility")
    private fun addBubble() {
        if (bubble != null) return
        val size = dp(56)
        val container = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xF2101720.toInt())
                setStroke(dp(2), ACCENT)
            }
            elevation = dp(8).toFloat()
        }
        container.addView(
            ImageView(this).apply {
                setImageResource(R.drawable.ic_bubble)
                setColorFilter(ACCENT)
                layoutParams = FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER)
            }
        )

        val params = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = resources.displayMetrics.widthPixels - size - dp(10)
            y = (resources.displayMetrics.heightPixels * 0.42f).toInt()
        }

        var startX = 0
        var startY = 0
        var downX = 0f
        var downY = 0f
        var moved = false
        var downAt = 0L

        container.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    downX = event.rawX
                    downY = event.rawY
                    moved = false
                    downAt = SystemClock.elapsedRealtime()
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt()
                    val dy = (event.rawY - downY).toInt()
                    if (abs(dx) > dp(6) || abs(dy) > dp(6)) moved = true
                    params.x = (startX + dx).coerceIn(0, resources.displayMetrics.widthPixels - params.width)
                    params.y = (startY + dy).coerceIn(0, resources.displayMetrics.heightPixels - params.height)
                    try {
                        windowManager.updateViewLayout(container, params)
                    } catch (t: Throwable) {
                        // View detached.
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (!moved && SystemClock.elapsedRealtime() - downAt < 450) onBubbleTap()
                    true
                }

                else -> false
            }
        }

        try {
            windowManager.addView(container, params)
        } catch (t: Throwable) {
            stopSelf()
            return
        }
        bubble = container
        bubbleParams = params
    }

    // ---------------------------------------------------------------- popup panel

    private fun showPanel() {
        if (panel != null) return

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(0xF2141B24.toInt())
                setStroke(dp(1), 0x337CE0B3)
            }
            elevation = dp(10).toFloat()
        }

        panelTitle = TextView(this).apply {
            setTextColor(TEXT)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        panelSubtitle = TextView(this).apply {
            setTextColor(MUTED)
            textSize = 11f
            setPadding(0, dp(2), 0, dp(10))
        }
        val close = TextView(this).apply {
            text = "✕"
            textSize = 16f
            setTextColor(MUTED)
            setPadding(dp(10), 0, 0, 0)
            setOnClickListener { hidePanel() }
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
        }
        header.addView(panelTitle, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(close)

        panelRows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        root.addView(header)
        root.addView(panelSubtitle)
        root.addView(panelRows)

        val width = minOf(dp(320), resources.displayMetrics.widthPixels - dp(28))
        val params = WindowManager.LayoutParams(
            width,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(72)
        }

        try {
            windowManager.addView(root, params)
        } catch (t: Throwable) {
            toast("Overlay permission is missing")
            return
        }
        panel = root
    }

    private fun hidePanel() {
        val view = panel ?: return
        panel = null
        panelRows = null
        panelTitle = null
        panelSubtitle = null
        try {
            windowManager.removeView(view)
        } catch (t: Throwable) {
            // Already detached.
        }
    }

    private fun updateHeader(title: String, subtitle: String) {
        panelTitle?.text = title
        panelSubtitle?.text = subtitle
    }

    private fun renderRows(loading: Boolean, info: ProbeResult?, error: String?) {
        val container = panelRows ?: return
        container.removeAllViews()
        updateHeader(
            when {
                info != null -> info.title
                else -> currentUrl?.let { Urls.sourceLabel(it) } ?: "OneClip"
            },
            when {
                loading -> "Checking available qualities…"
                info != null -> subtitleFor(currentUrl.orEmpty(), info)
                else -> ""
            }
        )

        when {
            loading -> {
                container.addView(label("Looking up this link…", MUTED, 12f))
                container.addView(
                    ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate = true },
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(6)).apply { topMargin = dp(10) }
                )
            }

            error != null -> {
                container.addView(label(error, WARN, 12f))
                container.addView(actionRow("Open OneClip", "Paste the link in the app", null))
            }

            info != null -> {
                if (info.heights.isEmpty()) {
                    container.addView(actionRow("Best quality", "Video · MP4 + audio", DownloadSpec(DownloadSpec.Kind.VIDEO)))
                } else {
                    info.heights.forEach { height ->
                        container.addView(
                            actionRow("${height}p", "Video · MP4 + audio", DownloadSpec(DownloadSpec.Kind.VIDEO, height))
                        )
                    }
                }
                container.addView(divider())
                container.addView(
                    actionRow("Audio only", "${Prefs.audioFormat.uppercase()} · no video", DownloadSpec(DownloadSpec.Kind.AUDIO, null, Prefs.audioFormat))
                )
                if (Prefs.audioFormat != "mp3") {
                    container.addView(
                        actionRow("Audio only", "MP3 · converted", DownloadSpec(DownloadSpec.Kind.AUDIO, null, "mp3"))
                    )
                }
            }
        }
    }

    private fun subtitleFor(url: String, info: ProbeResult): String {
        val parts = mutableListOf(Urls.sourceLabel(url))
        info.uploader?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
        info.durationSeconds?.let { seconds ->
            val minutes = seconds / 60
            val rest = seconds % 60
            parts.add(if (minutes >= 60) "%d:%02d:%02d".format(minutes / 60, minutes % 60, rest) else "%d:%02d".format(minutes, rest))
        }
        return parts.joinToString(" · ")
    }

    private fun actionRow(title: String, subtitle: String, spec: DownloadSpec?): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(7), 0, dp(7))
            isClickable = true
            if (spec != null) {
                setOnClickListener { startDownload(spec) }
            } else {
                setOnClickListener { openApp() }
            }
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(label(title, TEXT, 14f).apply { typeface = Typeface.DEFAULT_BOLD })
        texts.addView(label(subtitle, MUTED, 11f))
        row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(label("↓", ACCENT, 17f))
        return row
    }

    private fun label(text: String, color: Int, size: Float): TextView = TextView(this).apply {
        this.text = text
        setTextColor(color)
        textSize = size
        maxLines = 3
        ellipsize = TextUtils.TruncateAt.END
    }

    private fun divider(): View = View(this).apply {
        setBackgroundColor(0x1FFFFFFF)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
            topMargin = dp(6)
            bottomMargin = dp(4)
        }
    }

    private fun startDownload(spec: DownloadSpec) {
        val url = currentUrl ?: return
        Downloads.enqueue(url, currentTitle, spec)
        toast("Download queued · ${spec.label}")
        hidePanel()
    }

    private fun openApp() {
        try {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (t: Throwable) {
            // Ignore.
        }
        hidePanel()
    }

    // ---------------------------------------------------------------- notifications

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Floating bubble", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_bubble)
            .setContentTitle("OneClip bubble is on")
            .setContentText("Tap it after copying a video link")
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "Turn off", stop)
            .build()
    }

    private fun toast(message: String) {
        if (available) Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
