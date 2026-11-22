package com.roottun.clash.core

import android.content.Context
import android.content.Intent
import com.roottun.clash.BuildConfig
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class ProxyStatus { STOPPED, STARTING, RUNNING, STOPPING, ERROR }

data class ProxyState(
    val status: ProxyStatus = ProxyStatus.STOPPED,
    val statusText: String = "已停止",
    val detail: String = "",
    val error: String? = null,
    val rootGranted: Boolean? = null,
    val kernelPid: Int? = null,
    val tunDevice: String? = null,
    val controllerPort: Int = 0,
    val mixedPort: Int = 0,
    val startedAt: Long = 0L,
    val activeSubName: String? = null,
    val activeSubFetchedAt: Long = 0L,
    val kernelVersion: String? = null,
) {
    val running: Boolean get() = status == ProxyStatus.RUNNING
    val busy: Boolean get() = status == ProxyStatus.STARTING || status == ProxyStatus.STOPPING
}

/**
 * 启动 / 停止编排器：把 Root 检查、二进制释放、订阅配置、配置校验、TUN 节点、
 * 内核启动、策略路由这些步骤串成一条可回滚的事务。
 */
class ProxyCore private constructor(private val context: Context) {

    companion object {
        private const val TAG = "CORE"

        @Volatile
        private var instance: ProxyCore? = null

        fun get(context: Context): ProxyCore = instance ?: synchronized(this) {
            instance ?: ProxyCore(context.applicationContext).also { instance = it }
        }
    }

    val rootShell = RootShell(context)
    val subscriptions = SubscriptionRepository(context)

    private val installer = MihomoInstaller(context, rootShell)
    private val kernel = KernelManager(rootShell)
    private val netGuard = NetGuard(rootShell)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    private val nodeSelector = NodeSelector(scope)
    private val trafficMonitor = TrafficMonitor(scope)

    /** 「节点」页数据（策略组、当前选择、测速结果）。 */
    val nodes: StateFlow<NodesUiState> = nodeSelector.state

    /** 主页面流量卡片与通知栏共用的累计流量/速率。 */
    val traffic: StateFlow<TrafficSnapshot> = trafficMonitor.state

    private val _state = MutableStateFlow(ProxyState())
    val state: StateFlow<ProxyState> = _state.asStateFlow()

    @Volatile
    private var activeTunDevice: String = ""

    @Volatile
    private var lastRootRefreshAt: Long = 0L

    @Volatile
    private var kernelVersion: String? = null

    @Volatile
    private var kernelAttempt: Any? = null

    // ------------------------------------------------------------- lifecycle

    fun init() {
        // Reserve the lifecycle lock before scheduling cleanup, so a fast tap cannot race it.
        scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            mutex.withLock { kotlinx.coroutines.withContext(Dispatchers.IO) { initializeLocked() } }
        }
    }

    private fun initializeLocked() {
            LogBus.info(TAG, "RootTun Clash ${BuildConfig.VERSION_NAME} 初始化，包名=${context.packageName}")
            LogBus.info(TAG, "工作目录：${Workspace.baseDir.absolutePath}")
            val rooted = rootShell.checkRoot(force = true)
            _state.update { it.copy(rootGranted = rooted) }
            if (rooted) {
                terminateOrphanKernel()
            }
            if (rooted) {
                runCatching {
                    netGuard.cleanup(AppSettings.state.value.tunDevice)
                }.onFailure { LogBus.warn(TAG, "启动清理失败：${it.message}") }
                // 旧版本默认设备名 mihomo0，升级后兜底清理一次，避免残留接口/规则。
                val legacy = "mihomo0"
                if (AppSettings.state.value.tunDevice != legacy) {
                    runCatching { netGuard.cleanup(legacy) }
                }
            } else {
                LogBus.warn(TAG, "未获得 Root：跳过启动期网络规则清理")
            }
            val active = subscriptions.activeSubscription()
            _state.update {
                it.copy(
                    activeSubName = active?.name,
                    activeSubFetchedAt = active?.lastFetchedAt ?: 0L,
                    kernelVersion = null,
                )
            }
    }

    fun refreshRootStatus(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastRootRefreshAt < 15_000L) {
            LogBus.debug(TAG, "跳过 Root 重新检测（15 秒节流）")
            return
        }
        lastRootRefreshAt = now
        scope.launch {
            val rooted = rootShell.checkRoot(force = true)
            _state.update { it.copy(rootGranted = rooted) }
            if (rooted) {
                LogBus.info(TAG, "Root 通道：${rootShell.modeLabel()}")
            }
        }
    }

    fun toggle() {
        when (_state.value.status) {
            ProxyStatus.RUNNING, ProxyStatus.STARTING -> stop()
            else -> start()
        }
    }

    fun start() {
        scope.launch {
            mutex.withLock { startInternal() }
        }
    }

    fun stop() {
        scope.launch {
            mutex.withLock { stopInternal("用户停止") }
        }
    }

    fun restart() {
        scope.launch {
            mutex.withLock {
                stopInternal("重启内核")
                startInternal()
            }
        }
    }

    fun refreshActiveSubscription() {
        scope.launch {
            val active = subscriptions.activeSubscription()
            if (active == null) {
                LogBus.warn(TAG, "当前没有活动订阅，无法刷新")
                return@launch
            }
            _state.update { it.copy(detail = "正在刷新订阅「${active.name}」…") }
            val result = subscriptions.refresh(active.id)
            val latest = subscriptions.activeSubscription()
            result.onSuccess { count ->
                LogBus.info(TAG, "活动订阅「${active.name}」刷新完成：$count 个节点")
            }.onFailure { error ->
                _state.update { it.copy(detail = "订阅刷新失败：${error.message}") }
            }
            _state.update {
                it.copy(
                    activeSubName = latest?.name,
                    activeSubFetchedAt = latest?.lastFetchedAt ?: 0L,
                    detail = if (result.isSuccess) "" else it.detail,
                )
            }
            if (result.isSuccess && _state.value.status == ProxyStatus.RUNNING) {
                LogBus.info(TAG, "订阅已更新，重启内核使其生效")
                mutex.withLock {
                    stopInternal("订阅更新后重载")
                    startInternal()
                }
            }
        }
    }

    // ---------------------------------------------------------------- nodes

    fun refreshNodes() = nodeSelector.refresh()

    fun selectNode(group: NodeGroup, member: NodeMember) = nodeSelector.select(group, member)

    fun testGroupLatency(group: NodeGroup) = nodeSelector.testGroup(group)

    fun testNodeLatency(name: String) = nodeSelector.testNode(name)

    fun clearError() {
        if (_state.value.status == ProxyStatus.ERROR) {
            _state.update { it.copy(status = ProxyStatus.STOPPED, statusText = "已停止", error = null, detail = "") }
        }
    }

    /**
     * 活动订阅发生变化时调用：若代理正在运行，重启内核使新订阅立即生效。
     */
    fun onActiveSubscriptionChanged() {
        scope.launch {
            val active = subscriptions.activeSubscription()
            _state.update {
                it.copy(
                    activeSubName = active?.name,
                    activeSubFetchedAt = active?.lastFetchedAt ?: 0L,
                )
            }
            LogBus.info(TAG, "活动订阅已切换为「${active?.name ?: "无"}」")
            if (_state.value.status == ProxyStatus.RUNNING) {
                LogBus.info(TAG, "代理正在运行，重启内核以应用新的活动订阅")
                mutex.withLock {
                    stopInternal("切换活动订阅")
                    startInternal()
                }
            }
        }
    }

    /** Choose MTU before configuring the userspace stack: changing just the kernel link later
     * leaves gVisor's advertised MSS out of sync. Reserve headroom on cellular/tunneled links. */
    private fun effectiveTunMtu(configured: Int): Int {
        val upstream = runCatching {
            val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
            val link = cm.getLinkProperties(cm.activeNetwork)
            // Android 的逻辑链路（如 APN）与接口可能声明不同上限。
            val ifaceMtu = runCatching { link?.interfaceName?.let { java.net.NetworkInterface.getByName(it)?.mtu } }.getOrNull()
            val linkMtu = if (android.os.Build.VERSION.SDK_INT >= 29) link?.mtu else null
            listOfNotNull(linkMtu, ifaceMtu).filter { it >= 1280 }.minOrNull()
        }.getOrNull()
        val mtu = minOf(configured.coerceIn(1280, 1500), upstream ?: 1400)
        LogBus.info("TUN", "上行接口 MTU=${upstream ?: "未知"}，TUN 使用 $mtu；在协议栈创建前应用")
        return mtu
    }

    // ---------------------------------------------------------------- start

    private suspend fun startInternal() {
        val current = _state.value
        if (current.status == ProxyStatus.RUNNING || current.status == ProxyStatus.STARTING) {
            LogBus.warn(TAG, "当前状态为 ${current.statusText}，忽略本次启动请求")
            return
        }

        val settings = AppSettings.state.value
        val networkOptions = RuntimeOptions(
            mixedPort = settings.mixedPort,
            controllerPort = settings.controllerPort,
            dnsPort = settings.dnsPort,
            tunDevice = settings.tunDevice,
            tunStack = settings.tunStack,
            tunMtu = effectiveTunMtu(settings.tunMtu),
            strictRoute = settings.strictRoute,
            controllerSocketPath = Workspace.controllerSocket.absolutePath,
        )

        _state.update {
            it.copy(status = ProxyStatus.STARTING, statusText = "正在启动", error = null, detail = "检查 Root 权限…")
        }
        var completed = false
        var bootSlots: KernelSlots? = null

        try {
            // 1) Root 检查必须发生在任何 TUN 操作之前。
            LogBus.info(TAG, "步骤 1/7：检查 Root 权限")
            if (!rootShell.checkRoot(force = true)) {
                _state.update { it.copy(rootGranted = false) }
                throw IllegalStateException(
                    "未获得 Root 权限，无法创建 TUN 设备。请在设备的 Root 管理器（MuMu：设置 → 开启 Root 权限）中授权后重试。",
                )
            }
            _state.update { it.copy(rootGranted = true, detail = "释放 mihomo 内核…") }

            // 2) 释放内核 + 地理数据库。
            LogBus.info(TAG, "步骤 2/7：准备 mihomo 内核与地理数据库")
            val bootPlan = installer.beginBoot()
            bootSlots = bootPlan

            // Resolve package identities in this start transaction; never persist numeric UIDs.
            val options = networkOptions.copy(
                appRouting = AppRoutingPolicy.resolve(
                    context,
                    rootShell,
                    AppRoutingPolicy.rootListEnabled(context),
                    AppRoutingPolicy.shizukuListEnabled(context),
                ),
            )
            // 3) 准备订阅配置（没有订阅时使用内置 DIRECT 自检配置）。
            LogBus.info(TAG, "步骤 3/7：准备订阅配置")
            val active = subscriptions.activeSubscription()
            val baseConfig = resolveBaseConfig(active, options)
            _state.update {
                it.copy(
                    activeSubName = active?.name,
                    activeSubFetchedAt = active?.lastFetchedAt ?: 0L,
                    detail = "写入并校验 mihomo 配置…",
                )
            }

            // TUN availability is independent of the kernel release; do not reject a release for it.
            _state.update { it.copy(detail = "准备 TUN 设备节点…") }
            val tun = netGuard.ensureTunDevice()
            check(tun.available) { "/dev/net/tun 不可用，无法创建 TUN 设备（内核可能未启用 TUN 模块）" }

            val boot = bootKernel(bootPlan, baseConfig, options)
            val handle = boot.handle
            kernelVersion = boot.installation.version
            activeTunDevice = handle.tunDevice

            // 地址、MTU、回程路由统一由 mihomo 创建；不在建链之后删除 IPv6 源地址。

            // 7) 策略路由 / iptables。
            LogBus.info(TAG, "步骤 7/7：检查并接管策略路由")
            _state.update { it.copy(detail = "应用策略路由规则…") }
            val routing = netGuard.prepareRouting(options, settings.manualPolicyRouting)
            routing.notes.forEach { LogBus.info(TAG, it) }
            check(routing.autoRouteDetected || routing.manualApplied) { "未建立到 TUN 的有效策略路由，已取消启动。请检查 netd / 路由表。" }

            netGuard.captureApplicationTraffic(options.tunDevice, if (routing.autoRouteDetected) 2022 else 2023, settings.bypassOwnTraffic)

            // 让应用自身的订阅/管理流量绕过 TUN，避免「订阅节点不可用时无法再拉取订阅」的死锁。
            if (settings.bypassOwnTraffic) {
                netGuard.enableOwnTrafficBypass(android.os.Process.myUid())
            }

            // 双保险：即使配置关闭了本地监听，也在回环上封堵历史/备用代理端口。
            if (settings.blockLocalProxyPorts) {
                netGuard.applyLocalProxyIsolation(listOf(settings.mixedPort, settings.dnsPort))
            }

            check(handle.process.isAlive) { "mihomo 在应用路由时退出" }
            installer.confirmBoot(bootPlan, boot.ref, boot.failures)

            startForegroundService()
            nodeSelector.onProxyState(true, Workspace.controllerSocket.absolutePath)
            trafficMonitor.start(Workspace.controllerSocket.absolutePath)

            completed = true
            _state.update {
                it.copy(
                    status = ProxyStatus.RUNNING,
                    statusText = "运行中",
                    detail = if (active == null) "内置 DIRECT 自检配置" else "TUN ${handle.tunDevice} · pid ${handle.pid}",
                    error = null,
                    kernelPid = handle.pid.takeIf { pid -> pid > 0 },
                    tunDevice = handle.tunDevice,
                    controllerPort = 0,
                    mixedPort = 0,
                    startedAt = System.currentTimeMillis(),
                    kernelVersion = kernelVersion,
                    activeSubName = active?.name,
                    activeSubFetchedAt = active?.lastFetchedAt ?: 0L,
                )
            }
            LogBus.info(
                TAG,
                "代理已启动：TUN=${handle.tunDevice}, stack=${handle.stack}, pid=${handle.pid}, " +
                    "入站=TUN，控制器=应用私有 Unix socket，MTU=${options.tunMtu}",
            )
        } catch (t: Throwable) {
            LogBus.exception(TAG, "启动失败", t)
            _state.update {
                it.copy(
                    status = ProxyStatus.ERROR,
                    statusText = "错误",
                    error = t.message ?: t.javaClass.simpleName,
                    detail = "",
                )
            }
        } finally {
            if (!completed) {
                kernelAttempt = null
                LogBus.warn(TAG, "启动流程未完成")
                if (_state.value.rootGranted == true) rollback("启动失败")
                _state.update {
                    it.copy(kernelPid = null, tunDevice = null)
                }
            }
            if (bootSlots != null) runCatching { installer.endBoot() }
                .onFailure { LogBus.warn(TAG, "无法清理旧的更新文件：${it.message}") }
        }
    }

    private data class BootedKernel(
        val ref: KernelRef?,
        val installation: MihomoInstaller.Installation,
        val handle: KernelManager.Handle,
        val failures: Set<KernelRef>,
    )

    private fun bootKernel(plan: KernelSlots, baseConfig: String, options: RuntimeOptions): BootedKernel {
        val failedRefs = mutableSetOf<KernelRef>()
        val errors = mutableListOf<String>()
        for (ref in plan.bootOrder()) {
            val tag = ref?.tag ?: MihomoRelease.BUNDLED
            val attempt = Any()
            kernelAttempt = attempt
            try {
                val installation = installer.install(ref)
                _state.update { it.copy(detail = "校验 $tag 内核配置…") }
                writeAndValidateConfig(baseConfig, options)
                _state.update { it.copy(detail = "启动 $tag 内核…") }
                runCatching { Workspace.controllerSocket.delete() }
                val handle = launchKernelWithFallback(baseConfig, options) { onKernelExit(attempt, it) }
                check(handle.process.isAlive) { "mihomo 在启动验证后退出" }
                return BootedKernel(ref, installation, handle, failedRefs.toSet())
            } catch (e: Exception) {
                kernelAttempt = null
                if (ref != null) failedRefs += ref
                errors += "$tag：${e.message ?: e.javaClass.simpleName}"
                LogBus.warn(TAG, "内核启动验证失败：${errors.last()}")
                // Do not retry another binary if this attempt could not be stopped.
                if (kernel.isRunning || Workspace.pidFile.exists()) kernel.stop()
                runCatching { Workspace.controllerSocket.delete() }
            }
        }
        throw MihomoInstaller.InstallException(errors.joinToString("；"))
    }

    private suspend fun resolveBaseConfig(active: Subscription?, options: RuntimeOptions): String {
        if (active == null) {
            LogBus.warn(TAG, "当前没有活动订阅：使用内置 DIRECT 配置进行链路自检（不会代理任何流量）")
            return ConfigComposer.directConfig(options)
        }
        val cached = subscriptions.cachedConfig(active)
        if (cached != null) {
            LogBus.info(TAG, "使用活动订阅「${active.name}」的本地缓存")
            return cached
        }
        LogBus.info(TAG, "活动订阅「${active.name}」本地无缓存，立即拉取")
        val result = subscriptions.refresh(active.id)
        val refreshed = subscriptions.cachedConfig(active)
        if (refreshed == null) {
            throw IllegalStateException(
                "订阅「${active.name}」拉取/解析失败：${result.exceptionOrNull()?.message ?: "未知原因"}",
            )
        }
        return refreshed
    }

    private fun writeAndValidateConfig(baseConfig: String, options: RuntimeOptions) {
        val composition = ConfigComposer.compose(baseConfig, options)
        val configFile: File = Workspace.configFile
        configFile.writeText(composition.yaml)
        LogBus.info(
            TAG,
            "配置已写入 ${configFile.absolutePath}（${composition.yaml.length} 字符，" +
                "${composition.nodeCount} 个节点，stack=${options.tunStack}）",
        )
        composition.notes.forEach { LogBus.warn(TAG, it) }

        val validation = rootShell.exec(
            "${Workspace.shellQuote(Workspace.binFile.absolutePath)} -t " +
                "-d ${Workspace.shellQuote(Workspace.homeDir.absolutePath)} " +
                "-f ${Workspace.shellQuote(configFile.absolutePath)} 2>&1",
            timeoutMillis = 60_000,
            tag = "MIHOMO",
        )
        if (!validation.ok) {
            val output = validation.stdoutText().ifBlank { validation.stderrText() }
            throw IllegalStateException(
                "mihomo 配置校验失败（exit=${validation.code}）：${output.take(700).ifBlank { "无输出" }}" +
                    "\n提示：若订阅来自会按 User-Agent 返回不同内容的机场，请在「设置 → 订阅 User-Agent」中" +
                    "改用 ClashMetaForAndroid/… 后重新拉取订阅。",
            )
        }
        LogBus.info(TAG, "mihomo 配置校验通过")
    }

    private fun launchKernelWithFallback(baseConfig: String, options: RuntimeOptions, onExit: (Int) -> Unit): KernelManager.Handle {
        val primaryStack = options.tunStack
        try {
            return kernel.start(
                binary = Workspace.binFile,
                homeDir = Workspace.homeDir,
                config = Workspace.configFile,
                opts = options,
                onExit = onExit,
            )
        } catch (primary: KernelManager.KernelStartException) {
            LogBus.warn(TAG, "内核以 $primaryStack 栈启动失败：${primary.message}")
            if (primaryStack == "system") {
                logKernelDiagnostics(primary)
                throw primary
            }
            LogBus.warn(TAG, "自动降级为 system 栈重试一次")
            val fallbackOptions = options.copy(tunStack = "system")
            writeAndValidateConfig(baseConfig, fallbackOptions)
            return try {
                kernel.start(
                    binary = Workspace.binFile,
                    homeDir = Workspace.homeDir,
                    config = Workspace.configFile,
                    opts = fallbackOptions,
                    onExit = onExit,
                )
            } catch (fallback: KernelManager.KernelStartException) {
                logKernelDiagnostics(primary)
                logKernelDiagnostics(fallback)
                throw KernelManager.KernelStartException(
                    "mihomo 内核启动失败（gvisor 与 system 栈均超时）：${fallback.message}",
                    fallback.diagnostics,
                )
            }
        }
    }

    private fun logKernelDiagnostics(error: KernelManager.KernelStartException) {
        if (error.diagnostics.isEmpty()) {
            LogBus.error(TAG, "内核没有产生任何输出，可能是 su 拒绝执行或二进制 ABI 不匹配")
            return
        }
        LogBus.error(TAG, "内核诊断输出（最后 ${minOf(20, error.diagnostics.size)} 行）：")
        error.diagnostics.takeLast(20).forEach { LogBus.error(TAG, "  $it") }
    }

    // ----------------------------------------------------------------- stop

    private fun stopInternal(reason: String) {
        if (_state.value.status == ProxyStatus.STOPPED && !kernel.isRunning) {
            LogBus.debug(TAG, "代理已处于停止状态（$reason）")
            return
        }
        LogBus.info(TAG, "开始停止代理（$reason）")
        _state.update { it.copy(status = ProxyStatus.STOPPING, statusText = "正在停止", detail = "回滚网络规则…") }

        // 逆序清理：iptables → 策略路由 → 内核进程 → TUN 设备。
        val tunToClean = activeTunDevice.ifBlank { AppSettings.state.value.tunDevice }
        runCatching { netGuard.cleanup(tunToClean, includeTunDevice = false) }
            .onFailure { LogBus.error(TAG, "回滚规则失败：${it.message}") }
        runCatching { kernel.stop() }
            .onFailure { LogBus.error(TAG, "停止内核失败：${it.message}") }
        nodeSelector.onProxyState(false, "")
        trafficMonitor.stop()
        runCatching { Workspace.controllerSocket.delete() }
        runCatching { netGuard.removeTunDevice(tunToClean) }
            .onFailure { LogBus.warn(TAG, "删除 TUN 设备失败：${it.message}") }
        stopForegroundService()
        activeTunDevice = ""

        _state.update {
            it.copy(
                status = ProxyStatus.STOPPED,
                statusText = "已停止",
                detail = "",
                error = null,
                kernelPid = null,
                tunDevice = null,
                startedAt = 0L,
            )
        }
        LogBus.info(TAG, "代理已停止，iptables / 策略路由已回滚")
    }

    private fun rollback(reason: String) {
        LogBus.warn(TAG, "回滚（$reason）")
        val tunToClean = activeTunDevice.ifBlank { AppSettings.state.value.tunDevice }
        // 先停内核，再回滚规则，最后兜底删除可能残留的 TUN 设备。
        runCatching { kernel.stop() }
        nodeSelector.onProxyState(false, "")
        trafficMonitor.stop()
        runCatching { Workspace.controllerSocket.delete() }
        runCatching { netGuard.cleanup(tunToClean, includeTunDevice = true) }
        stopForegroundService()
        activeTunDevice = ""
    }

    private fun onKernelExit(attempt: Any, code: Int) {
        if (kernelAttempt !== attempt) return
        val status = _state.value.status
        if (status != ProxyStatus.RUNNING && status != ProxyStatus.STARTING) {
            LogBus.debug(TAG, "内核退出事件被忽略（当前状态 $status）")
            return
        }
        scope.launch {
            mutex.withLock {
                if (kernelAttempt !== attempt || _state.value.status != ProxyStatus.RUNNING) return@withLock
                kernelAttempt = null
                LogBus.error(TAG, "mihomo 内核异常退出（exit=$code），开始自动回滚网络规则")
                runCatching { netGuard.cleanup(activeTunDevice.ifBlank { AppSettings.state.value.tunDevice }) }
                runCatching { kernel.stop() }
                nodeSelector.onProxyState(false, "")
                trafficMonitor.stop()
                stopForegroundService()
                _state.update {
                    it.copy(
                        status = ProxyStatus.ERROR,
                        statusText = "错误",
                        error = "mihomo 内核异常退出（exit=$code），已自动回滚 iptables 与策略路由",
                        detail = "",
                        kernelPid = null,
                        tunDevice = null,
                        startedAt = 0L,
                    )
                }
            }
        }
    }

    private fun terminateOrphanKernel() {
        val result = rootShell.exec("pidof mihomo 2>/dev/null", timeoutMillis = 8_000, echo = false, logStdout = false)
        val pids = result.stdoutText().split(' ', '\n').mapNotNull { it.trim().toIntOrNull() }
        if (pids.isEmpty()) return
        LogBus.warn(TAG, "发现上次运行残留的 mihomo 进程：$pids，正在结束")
        pids.forEach { pid ->
            val exe = rootShell.exec("readlink /proc/$pid/exe", tag = TAG, echo = false, logStdout = false).stdoutText()
            if (exe.removeSuffix(" (deleted)") == Workspace.binFile.canonicalPath) {
                runCatching { rootShell.terminatePid(pid, TAG, waitMillis = 3_000) }
            }
        }
    }

    // ------------------------------------------------------ foreground service

    private fun startForegroundService() {
        runCatching {
            val intent = Intent(context, ProxyForegroundService::class.java)
                .setAction(ProxyForegroundService.ACTION_START)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
            LogBus.debug(TAG, "前台保活服务已启动")
        }.onFailure {
            LogBus.warn(TAG, "前台保活服务启动失败（代理仍会运行，但可能在后台被系统回收）：${it.message}")
        }
    }

    private fun stopForegroundService() {
        runCatching { context.stopService(Intent(context, ProxyForegroundService::class.java)) }
    }
}
