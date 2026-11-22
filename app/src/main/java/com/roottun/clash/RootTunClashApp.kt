package com.roottun.clash

import android.app.Application
import android.os.Build
import com.roottun.clash.core.AppSettings
import com.roottun.clash.core.LogBus
import com.roottun.clash.core.MihomoUpdates
import com.roottun.clash.core.ProxyCore
import com.roottun.clash.core.Workspace
import java.io.File

class RootTunClashApp : Application() {

    override fun onCreate() {
        super.onCreate()
        Workspace.init(this)
        LogBus.attachFile(File(Workspace.logDir, "app.log"))
        AppSettings.init(this)
        LogBus.info(
            "APP",
            "应用启动：${BuildConfig.APPLICATION_ID} ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
        )
        LogBus.info(
            "APP",
            "设备：${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE} " +
                "(API ${Build.VERSION.SDK_INT}) / ABI=${Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"}",
        )
        LogBus.info("APP", "主题已加载（动态取色引擎）")
        MihomoUpdates.init(this)
        ProxyCore.get(this).init()
    }
}
