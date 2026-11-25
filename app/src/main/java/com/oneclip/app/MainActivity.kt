package com.oneclip.app

import android.Manifest
import android.content.ClipboardManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.oneclip.app.core.Prefs
import com.oneclip.app.core.Urls
import com.oneclip.app.overlay.OverlayService
import com.oneclip.app.ui.HomeScreen
import com.oneclip.app.ui.HomeViewModel
import com.oneclip.app.ui.OneClipTheme

class MainActivity : ComponentActivity() {

    private val vm: HomeViewModel by viewModels()

    private val notifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            OneClipTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    HomeScreen(vm)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.refreshOverlay()
        vm.refreshEngine()
        if (Prefs.overlayEnabled && Settings.canDrawOverlays(this)) OverlayService.start(this)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
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
        vm.onClipboard(Urls.extract(text))
    }
}
