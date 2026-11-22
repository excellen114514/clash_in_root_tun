package com.roottun.clash

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.roottun.clash.core.AppSettings
import com.roottun.clash.core.LogBus
import com.roottun.clash.core.ProxyCore
import com.roottun.clash.ui.AppRoot
import com.roottun.clash.ui.theme.RootTunClashTheme

class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            LogBus.info("APP", "通知权限请求结果：${if (granted) "已授予" else "被拒绝"}")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        LogBus.info("APP", "MainActivity onCreate")

        setContent {
            val settings by AppSettings.state.collectAsStateWithLifecycle()
            RootTunClashTheme(settings = settings) {
                AppRoot(core = ProxyCore.get(this))
            }
        }

        requestNotificationPermissionIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        // 用户可能刚从 Root 授权弹窗返回，回到前台时重新探测一次（内部有节流）。
        ProxyCore.get(this).refreshRootStatus()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            LogBus.info("APP", "请求通知权限（前台保活通知）")
            runCatching { notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
                .onFailure { LogBus.warn("APP", "请求通知权限失败：${it.message}") }
        }
    }
}
