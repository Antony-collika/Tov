package com.example.byokchat

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.example.byokchat.data.ThemeMode
import com.example.byokchat.ui.AppNav
import com.example.byokchat.ui.theme.ByokTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private var permissionResult: CompletableDeferred<Unit>? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            permissionResult?.complete(Unit)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = container.settings

        // Cho phép công cụ (lịch...) xin quyền Android khi cần
        container.permissions.requester = { perms ->
            val d = CompletableDeferred<Unit>()
            permissionResult = d
            withContext(Dispatchers.Main) { permissionLauncher.launch(perms) }
            d.await()
        }

        setContent {
            val mode by settings.themeMode.collectAsState()
            val dark = when (mode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            DisposableEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT)
                else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose { }
            }
            ByokTheme(dark) {
                Surface(Modifier.fillMaxSize()) { AppNav() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Kiểm tra lại quyền truy cập thư mục làm việc (có thể đã bị thu hồi / xóa)
        lifecycleScope.launch(Dispatchers.IO) { container.fs.refresh() }
    }

    override fun onDestroy() {
        if (isFinishing) container.permissions.requester = null
        super.onDestroy()
    }
}
