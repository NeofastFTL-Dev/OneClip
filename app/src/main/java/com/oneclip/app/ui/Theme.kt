package com.oneclip.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Ink = Color(0xFF0B0F14)
val Panel = Color(0xFF141B24)
val PanelSoft = Color(0xFF1B2431)
val Mint = Color(0xFF7CE0B3)
val TextMain = Color(0xFFE9F1F7)
val TextMuted = Color(0xFF93A7BA)
val Line = Color(0xFF273345)
val Warn = Color(0xFFFFB4AB)

private val scheme = darkColorScheme(
    primary = Mint,
    onPrimary = Color(0xFF04231A),
    secondary = Mint,
    background = Ink,
    onBackground = TextMain,
    surface = Panel,
    onSurface = TextMain,
    surfaceVariant = PanelSoft,
    onSurfaceVariant = TextMuted,
    outline = Line,
    error = Warn
)

@Composable
fun OneClipTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
