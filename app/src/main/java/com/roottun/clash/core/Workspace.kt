package com.roottun.clash.core

import android.content.Context
import java.io.File

/**
 * 所有运行期路径都从 `context.filesDir` 推导，绝不写死 `/data/adb/...`
 * （参考项目 tunclash 的硬编码路径问题已在本次实现中修复）。
 */
object Workspace {
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        ensureDirs()
    }

    private val context: Context
        get() = appContext ?: throw IllegalStateException("Workspace 未初始化")

    val baseDir: File get() = File(context.filesDir, "clash")
    val homeDir: File get() = File(baseDir, "mihomo")
    val binFile: File get() = File(homeDir, "mihomo")
    val configFile: File get() = File(homeDir, "config.yaml")
    val pidFile: File get() = File(homeDir, "mihomo.pid")
    /** 控制器 unix socket：不开放任何本地 TCP 端口，避免被应用扫描发现。 */
    val controllerSocket: File get() = File(homeDir, "ctl.sock")
    val scriptDir: File get() = File(baseDir, "scripts")
    val subscriptionDir: File get() = File(baseDir, "subscriptions")
    val logDir: File get() = File(baseDir, "logs")

    fun subscriptionFile(id: String): File = File(subscriptionDir, "$id.yaml")

    fun ensureDirs(): Boolean {
        var ok = true
        listOf(baseDir, homeDir, scriptDir, subscriptionDir, logDir).forEach { dir ->
            if (!dir.exists()) {
                ok = dir.mkdirs() && ok
            }
        }
        return ok
    }

    /** 单引号安全转义，供 `su -c` 的 sh 使用。 */
    fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
