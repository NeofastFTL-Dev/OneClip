package com.oneclip.app.ui

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oneclip.app.core.DownloadItem
import com.oneclip.app.core.DownloadSpec
import com.oneclip.app.core.DownloadStatus
import com.oneclip.app.core.Downloads
import com.oneclip.app.core.Exporter
import com.oneclip.app.core.Prefs
import com.oneclip.app.core.ProbeResult
import com.oneclip.app.core.Urls

@Composable
fun HomeScreen(vm: HomeViewModel) {
    val context = LocalContext.current
    val downloads by Downloads.items.collectAsStateWithLifecycle()
    var showSettings by remember { mutableStateOf(false) }
    var audioFormat by remember { mutableStateOf(Prefs.audioFormat) }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Header(onSettings = { showSettings = true })
            LinkField(vm)

            vm.clipHint?.let { link ->
                HintCard(
                    title = "Link in your clipboard",
                    body = link,
                    action = "Use it",
                    onAction = { vm.useClipboardHint() },
                    onDismiss = { vm.dismissHint() }
                )
            }

            vm.notice?.let { text ->
                HintCard(
                    title = "OneClip",
                    body = text,
                    action = "Dismiss",
                    onAction = { vm.clearNotice() },
                    onDismiss = { vm.clearNotice() }
                )
            }

            if (vm.probing) {
                Column {
                    Text("Reading link…", color = TextMuted, fontSize = 12.sp)
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }

            vm.error?.let { message ->
                Surface(
                    color = Color(0xFF2A1B1B),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text("Could not read this link", color = Warn, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Spacer(Modifier.height(4.dp))
                        Text(message, color = TextMuted, fontSize = 12.sp)
                    }
                }
            }

            vm.info?.let { info ->
                ResultCard(info) { spec -> vm.download(spec) }
            }

            if (vm.info == null && vm.error == null && !vm.probing) {
                TipCard(overlayOn = vm.overlayOn, onEnable = { vm.setOverlay(context, true) })
            }

            DownloadsSection(
                items = downloads,
                onCancel = { Downloads.cancel(it) },
                onOpen = { open(context, it) },
                onClear = { Downloads.clearFinished() }
            )

            Text(
                "For personal use. Respect each platform's terms and creators' rights.",
                color = TextMuted,
                fontSize = 11.sp
            )
            Spacer(Modifier.height(4.dp))
        }
    }

    if (showSettings) {
        SettingsDialog(
            vm = vm,
            audioFormat = audioFormat,
            onAudioFormat = {
                audioFormat = it
                vm.setAudioFormat(it)
            },
            onClose = { showSettings = false }
        )
    }
}

@Composable
private fun Header(onSettings: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("OneClip", color = TextMain, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text("Copy a link, pick a quality, done.", color = TextMuted, fontSize = 12.sp)
        }
        IconButton(onClick = onSettings) {
            Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = TextMuted)
        }
    }
}

@Composable
private fun LinkField(vm: HomeViewModel) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = vm.url,
            onValueChange = vm::onUrlChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Video, reel, short or VOD link") },
            placeholder = { Text("https://…") },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            trailingIcon = {
                TextButton(onClick = {
                    val link = Urls.extract(readClipboard(context))
                    if (link != null) vm.onUrlChange(link) else toast(context, "No link in the clipboard")
                }) {
                    Text("Paste", color = Mint, fontSize = 12.sp)
                }
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = TextMain,
                unfocusedTextColor = TextMain,
                focusedContainerColor = Panel,
                unfocusedContainerColor = Panel,
                focusedBorderColor = Mint,
                unfocusedBorderColor = Line,
                focusedLabelColor = Mint,
                unfocusedLabelColor = TextMuted,
                cursorColor = Mint
            )
        )

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = vm::fetch,
                enabled = vm.url.isNotBlank() && !vm.probing,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Mint,
                    contentColor = Color(0xFF04231A),
                    disabledContainerColor = PanelSoft,
                    disabledContentColor = TextMuted
                )
            ) {
                Text("Show qualities", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            }
            if (vm.info != null) {
                TextButton(onClick = { vm.onUrlChange(vm.url) }) {
                    Text("Clear", color = TextMuted, fontSize = 12.sp)
                }
            }
        }

        Text(
            "YouTube · Instagram · Facebook · Twitch · X",
            color = TextMuted,
            fontSize = 11.sp
        )
    }
}

@Composable
private fun ResultCard(info: ProbeResult, onPick: (DownloadSpec) -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Panel),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                info.title,
                color = TextMain,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            val meta = buildList {
                if (!info.uploader.isNullOrBlank()) add(info.uploader)
                val duration = formatDuration(info.durationSeconds)
                if (duration != null) add(duration)
                add("video + audio")
            }.joinToString(" · ")
            Text(meta, color = TextMuted, fontSize = 11.sp)

            Spacer(Modifier.height(16.dp))
            SectionLabel("VIDEO")
            Spacer(Modifier.height(8.dp))
            if (info.heights.isEmpty()) {
                ChoiceRow("Best available", "MP4, merged with audio") {
                    onPick(DownloadSpec(DownloadSpec.Kind.VIDEO))
                }
            } else {
                info.heights.forEachIndexed { index, height ->
                    if (index > 0) Spacer(Modifier.height(6.dp))
                    ChoiceRow("${height}p", "MP4, video + audio") {
                        onPick(DownloadSpec(DownloadSpec.Kind.VIDEO, height))
                    }
                }
            }

            Spacer(Modifier.height(18.dp))
            SectionLabel("AUDIO ONLY")
            Spacer(Modifier.height(8.dp))
            ChoiceRow("M4A", "Original audio track") {
                onPick(DownloadSpec(DownloadSpec.Kind.AUDIO, null, "m4a"))
            }
            Spacer(Modifier.height(6.dp))
            ChoiceRow("MP3", "Converted with ffmpeg") {
                onPick(DownloadSpec(DownloadSpec.Kind.AUDIO, null, "mp3"))
            }
        }
    }
}

@Composable
private fun ChoiceRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(PanelSoft, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = TextMain, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = TextMuted, fontSize = 11.sp)
        }
        Text("↓", color = Mint, fontSize = 17.sp)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, color = TextMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
}

@Composable
private fun HintCard(
    title: String,
    body: String,
    action: String,
    onAction: () -> Unit,
    onDismiss: () -> Unit
) {
    Surface(color = PanelSoft, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    color = TextMain,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "✕",
                    color = TextMuted,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .clickable(onClick = onDismiss)
                        .padding(start = 10.dp, bottom = 4.dp)
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(body, color = TextMuted, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            TextButton(
                onClick = onAction,
                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 2.dp)
            ) {
                Text(action, color = Mint, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun TipCard(overlayOn: Boolean, onEnable: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Panel),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Download from anywhere", color = TextMain, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Step("1", "Open YouTube, Instagram, Facebook, Twitch or X and copy a video link.")
            Step("2", "Tap the floating OneClip bubble, or come back to this app.")
            Step("3", "Pick a quality, or grab the audio only.")
            if (!overlayOn) {
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = onEnable,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Color(0xFF04231A))
                ) {
                    Text("Turn on the floating bubble", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                }
            }
            Text(
                "Android 10+ blocks background clipboard reads, so the bubble needs one tap after you copy.",
                color = TextMuted,
                fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun Step(number: String, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(color = PanelSoft, shape = RoundedCornerShape(50), modifier = Modifier.size(20.dp)) {
            Text(
                number,
                color = Mint,
                fontSize = 11.sp,
                modifier = Modifier.wrapContentSize().padding(top = 3.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(text, color = TextMuted, fontSize = 12.sp)
    }
}

@Composable
private fun DownloadsSection(
    items: List<DownloadItem>,
    onCancel: (String) -> Unit,
    onOpen: (DownloadItem) -> Unit,
    onClear: () -> Unit
) {
    if (items.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Downloads",
                color = TextMain,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                modifier = Modifier.weight(1f)
            )
            if (items.any { it.status == DownloadStatus.DONE || it.status == DownloadStatus.FAILED || it.status == DownloadStatus.CANCELED }) {
                TextButton(onClick = onClear) {
                    Text("Clear finished", color = TextMuted, fontSize = 12.sp)
                }
            }
        }
        items.forEach { item ->
            DownloadRow(item, onCancel, onOpen)
        }
    }
}

@Composable
private fun DownloadRow(item: DownloadItem, onCancel: (String) -> Unit, onOpen: (DownloadItem) -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Panel),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        item.title,
                        color = TextMain,
                        fontSize = 14.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "${Urls.sourceLabel(item.url)} · ${item.label}",
                        color = TextMuted,
                        fontSize = 11.sp
                    )
                }
                when (item.status) {
                    DownloadStatus.DONE -> TextButton(onClick = { onOpen(item) }) {
                        Text("Open", color = Mint, fontSize = 12.sp)
                    }

                    DownloadStatus.QUEUED, DownloadStatus.RUNNING -> TextButton(onClick = { onCancel(item.id) }) {
                        Text("Cancel", color = Warn, fontSize = 12.sp)
                    }

                    else -> Unit
                }
            }

            when (item.status) {
                DownloadStatus.QUEUED -> Text("Waiting…", color = TextMuted, fontSize = 11.sp)

                DownloadStatus.RUNNING -> {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { item.progress / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        item.detail.ifBlank { "${item.progress.toInt()}%" },
                        color = TextMuted,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                DownloadStatus.DONE -> Text(item.detail, color = TextMuted, fontSize = 11.sp)
                DownloadStatus.FAILED -> Text(item.error ?: "Failed", color = Warn, fontSize = 11.sp)
                DownloadStatus.CANCELED -> Text("Canceled", color = TextMuted, fontSize = 11.sp)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsDialog(
    vm: HomeViewModel,
    audioFormat: String,
    onAudioFormat: (String) -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = Panel,
        title = { Text("Settings", color = TextMain, fontSize = 18.sp) },
        confirmButton = {
            TextButton(onClick = onClose) { Text("Done", color = Mint) }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Floating bubble", color = TextMain, fontSize = 14.sp)
                        Text("Tap it after copying a link", color = TextMuted, fontSize = 11.sp)
                    }
                    Switch(checked = vm.overlayOn, onCheckedChange = { vm.setOverlay(context, it) })
                }

                Column {
                    Text("Default audio format", color = TextMain, fontSize = 14.sp)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("m4a", "mp3").forEach { format ->
                            FilterChip(
                                selected = audioFormat == format,
                                onClick = { onAudioFormat(format) },
                                label = { Text(format.uppercase(), fontSize = 12.sp) }
                            )
                        }
                    }
                }

                Column {
                    Text("Download engine", color = TextMain, fontSize = 14.sp)
                    Text(
                        vm.engineVersion?.let { "yt-dlp $it" } ?: if (vm.engineReady) "Ready" else "Starting…",
                        color = TextMuted,
                        fontSize = 11.sp
                    )
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(onClick = { vm.updateExtractors() }, enabled = !vm.busy) {
                        Text(if (vm.busy) "Updating…" else "Update extractors", color = Mint, fontSize = 12.sp)
                    }
                }

                Text("Files are saved to Downloads/OneClip.", color = TextMuted, fontSize = 11.sp)
            }
        }
    )
}

private fun readClipboard(context: Context): String? = try {
    context.getSystemService(ClipboardManager::class.java)
        ?.primaryClip
        ?.takeIf { it.itemCount > 0 }
        ?.getItemAt(0)
        ?.coerceToText(context)
        ?.toString()
} catch (t: Throwable) {
    null
}

private fun open(context: Context, item: DownloadItem) {
    val uri = item.uri?.let(Uri::parse)
    if (uri == null) {
        toast(context, "File is no longer available")
        return
    }
    val mime = Exporter.mimeOf(item.fileName?.substringAfterLast('.', ""))
    try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (t: Throwable) {
        toast(context, "No app can open this file")
    }
}

private fun toast(context: Context, message: String) {
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}

private fun formatDuration(seconds: Long?): String? {
    if (seconds == null || seconds <= 0) return null
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val rest = seconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, rest) else "%d:%02d".format(minutes, rest)
}
