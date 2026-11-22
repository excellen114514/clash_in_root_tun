package com.roottun.clash.core

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import com.roottun.clash.BuildConfig
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import rikka.shizuku.Shizuku

/**
 * Shizuku 可选后端。
 *
 * 事实边界：
 * - Shizuku 以 ADB shell 启动时 uid=2000，没有 CAP_NET_ADMIN，且 /dev/tun 属于 system:vpn 0660，
 *   因此不能创建 TUN、ip rule/iptables，不能替代 root 运行本项目的 TUN 代理；
 * - Shizuku 以 root 启动时 uid=0，但此时设备本身已有 root，本项目的 TUN 路径仍走 RootShell，行为不变；
 * - Shizuku 的实用价值是 shell 级应用列表查询（pm list packages），用于包可见性受限时补充列表。
 *
 * UserService 在 Shizuku 的 root/shell 进程里执行命令，避免使用已私有化的 Shizuku.newProcess。
 */
object ShizukuManager {

    private const val TAG = "SHIZUKU"
    private const val PACKAGE = "moe.shizuku.privileged.api"
    private const val REQUEST_CODE = 0x5348

    data class State(
        val installed: Boolean = false,
        val running: Boolean = false,
        val permission: Boolean = false,
        val uid: Int? = null,
        val version: Int? = null,
        val bound: Boolean = false,
        val error: String? = null,
    ) {
        val isRoot: Boolean get() = uid == 0
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private lateinit var appContext: Context

    @Volatile
    private var service: IShizukuService? = null

    @Volatile
    private var serviceLatch = CountDownLatch(1)

    @Volatile
    private var initialized = false

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == REQUEST_CODE) {
            LogBus.info(TAG, "Shizuku 权限请求结果：${if (grantResult == PackageManager.PERMISSION_GRANTED) "已授权" else "被拒绝"}")
            refresh()
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        LogBus.info(TAG, "Shizuku binder 已就绪")
        refresh()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        LogBus.warn(TAG, "Shizuku binder 已断开")
        service = null
        serviceLatch = CountDownLatch(1)
        _state.update { it.copy(running = false, permission = false, uid = null, bound = false) }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = binder?.let { IShizukuService.Stub.asInterface(it) }
            serviceLatch.countDown()
            _state.update { it.copy(bound = service != null, error = if (service == null) "Shizuku UserService 返回空 binder" else null) }
            LogBus.info(TAG, "Shizuku UserService 已连接（uid=${_state.value.uid ?: "?"}）")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            serviceLatch = CountDownLatch(1)
            _state.update { it.copy(bound = false) }
            LogBus.warn(TAG, "Shizuku UserService 已断开")
        }
    }

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        appContext = context.applicationContext
        runCatching { Shizuku.addRequestPermissionResultListener(permissionListener) }
        runCatching { Shizuku.addBinderReceivedListener(binderReceivedListener) }
        runCatching { Shizuku.addBinderDeadListener(binderDeadListener) }
        initialized = true
        LogBus.info(TAG, "Shizuku 管理已初始化")
        refresh()
    }

    @Synchronized
    fun refresh() {
        if (!initialized) return
        val installed = runCatching {
            appContext.packageManager.getPackageInfo(PACKAGE, 0)
            true
        }.getOrDefault(false)

        val running = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        var permission = false
        var uid: Int? = null
        var version: Int? = null
        var error: String? = null

        if (running) {
            version = runCatching { Shizuku.getVersion() }.getOrNull()
            permission = runCatching {
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            }.getOrDefault(false)
            if (permission) {
                uid = runCatching { Shizuku.getUid() }.getOrNull()
                if (service == null) bindService()
            }
        }

        _state.update {
            it.copy(
                installed = installed,
                running = running,
                permission = permission,
                uid = uid,
                version = version,
                bound = service != null,
                error = error ?: it.error,
            )
        }
    }

    private fun bindService() {
        if (service != null) return
        val version = runCatching { Shizuku.getVersion() }.getOrDefault(0)
        if (version < 11) {
            _state.update { it.copy(error = "Shizuku 版本过低（v$version），需要 v11+") }
            return
        }
        serviceLatch = CountDownLatch(1)
        val args = Shizuku.UserServiceArgs(
            ComponentName(appContext.packageName, ShizukuUserService::class.java.name),
        )
            .daemon(false)
            .processNameSuffix("shizuku")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
        runCatching {
            Shizuku.bindUserService(args, connection)
        }.onFailure {
            _state.update { state -> state.copy(error = "无法绑定 Shizuku UserService：${it.message}") }
            LogBus.warn(TAG, "绑定 Shizuku UserService 失败：${it.message}")
        }
    }

    /** 请求 Shizuku 授权；未运行时打开 Shizuku 管理界面。 */
    fun requestPermission() {
        val current = _state.value
        if (!current.installed) {
            _state.update { it.copy(error = "未安装 Shizuku") }
            return
        }
        if (!current.running) {
            openManager()
            return
        }
        runCatching {
            if (Shizuku.isPreV11()) {
                _state.update { it.copy(error = "Shizuku 版本过低，需要 v11+") }
                return
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                refresh()
                return
            }
            if (Shizuku.shouldShowRequestPermissionRationale()) {
                _state.update { it.copy(error = "Shizuku 权限已被拒绝，请在 Shizuku 管理器中重新授权") }
                return
            }
            Shizuku.requestPermission(REQUEST_CODE)
        }.onFailure {
            _state.update { state -> state.copy(error = "请求 Shizuku 权限失败：${it.message}") }
            LogBus.warn(TAG, "请求 Shizuku 权限失败：${it.message}")
        }
    }

    fun openManager() {
        val intent = runCatching {
            appContext.packageManager.getLaunchIntentForPackage(PACKAGE)
        }.getOrNull()
        if (intent == null) {
            _state.update { it.copy(error = "未找到 Shizuku 管理界面") }
            return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { appContext.startActivity(intent) }.onFailure {
            _state.update { state -> state.copy(error = "无法打开 Shizuku：${it.message}") }
        }
    }

    fun listPackages(userId: Int): List<String>? = withService { it.listPackages(userId) }

    fun listSystemPackages(userId: Int): List<String>? = withService { it.listSystemPackages(userId) }

    fun exec(command: String): String? = withService { it.exec(command) }

    private fun <T> withService(block: (IShizukuService) -> T): T? {
        if (!initialized || !_state.value.permission) return null
        val current = service ?: run {
            bindService()
            runCatching { serviceLatch.await(6, TimeUnit.SECONDS) }
            service
        } ?: return null
        return runCatching { block(current) }.onFailure {
            LogBus.warn(TAG, "Shizuku UserService 调用失败：${it.message}")
            _state.update { state -> state.copy(error = "Shizuku 调用失败：${it.message}") }
        }.getOrNull()
    }
}
