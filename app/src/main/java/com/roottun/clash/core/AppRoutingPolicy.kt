package com.roottun.clash.core

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Process
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import java.io.File

/**
 * 应用分流清单与策略存储。
 *
 * - 优先通过 PackageManager 读取当前用户的完整应用列表（清单已声明 QUERY_ALL_PACKAGES）。
 * - 系统因包可见性/权限拒绝时，用户可显式开启「root 应用列表」；root 只用于读取包名、UID 和
 *   已安装 APK 的图标，不上传、不修改任何应用。
 * - 工作模式：关闭时按单应用三态设置；白名单只让勾选应用走代理；黑名单让勾选应用直连。
 * - 持久化键包含包名与安装身份，避免重装/共享 UID 静默继承策略。
 */
object AppRoutingPolicy {

    private const val TAG = "APP-ROUTING"
    private const val PREFS = "app_routing_v1"
    private const val KEY_MODE = "@mode"
    private const val KEY_ROOT_LIST = "@root_list"
    private const val ROUTE_PREFIX = "route:"
    private const val SELECTED_PREFIX = "selected:"
    private const val MAX_ROOT_ICON_APPS = 24
    private const val ICON_PX = 96

    /** 一个可参与分流的应用。图标在 IO 线程加载，供 LazyColumn 直接绘制。 */
    data class App(
        val pkg: String,
        val label: String,
        val uid: Int,
        val installedAt: Long,
        val shared: List<String>,
        val system: Boolean,
        val fromRoot: Boolean,
        val icon: ImageBitmap?,
    ) {
        val selectable: Boolean
            get() = shared.isEmpty() && ResolvedAppRouting.PACKAGE.matches(pkg) && (installedAt > 0 || fromRoot)

        internal val identity: String
            get() = if (installedAt > 0) "$pkg@$installedAt" else "$pkg@root"
    }

    data class Snapshot(
        val apps: List<App>,
        val mode: AppRoutingMode,
        val routes: Map<String, AppRoute>,
        val selected: Set<String>,
        val rootListEnabled: Boolean,
        val usedRoot: Boolean,
        val rootNotice: String?,
        val stale: Int,
    )

    private data class Catalog(
        val apps: List<App>,
        val usedRoot: Boolean,
        val rootNotice: String?,
    )

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun rootListEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ROOT_LIST, false)

    fun savedMode(context: Context): AppRoutingMode = readMode(context)

    private fun readMode(context: Context): AppRoutingMode =
        runCatching { AppRoutingMode.valueOf(prefs(context).getString(KEY_MODE, "OFF") ?: "OFF") }
            .getOrDefault(AppRoutingMode.OFF)

    private fun readRoutes(context: Context): Map<String, AppRoute> = buildMap {
        for ((key, value) in prefs(context).all) {
            if (key.startsWith("@") || key.startsWith(SELECTED_PREFIX)) continue
            val identity = if (key.startsWith(ROUTE_PREFIX)) key.removePrefix(ROUTE_PREFIX) else key
            when (value) {
                "DIRECT" -> put(identity, AppRoute.DIRECT)
                "PROXY" -> put(identity, AppRoute.PROXY)
            }
        }
    }

    private fun readSelected(context: Context): Set<String> =
        prefs(context).all.keys.filter { it.startsWith(SELECTED_PREFIX) }
            .mapTo(mutableSetOf()) { it.removePrefix(SELECTED_PREFIX) }

    private fun keyPackage(key: String): String? {
        if (key.startsWith("@")) return null
        val identity = when {
            key.startsWith(ROUTE_PREFIX) -> key.removePrefix(ROUTE_PREFIX)
            key.startsWith(SELECTED_PREFIX) -> key.removePrefix(SELECTED_PREFIX)
            else -> key
        }
        return identity.substringBefore('@').takeIf { it.isNotBlank() }
    }

    /**
     * 读取可编辑快照。useRoot 由调用方（设置页开关）决定；失败时抛出可读错误。
     */
    internal fun editor(context: Context, root: RootShell, useRoot: Boolean): Snapshot {
        val catalog = catalog(context, root, useRoot, labels = true, icons = true)
        val routes = readRoutes(context)
        val selected = readSelected(context)
        val valid = catalog.apps.filter { it.selectable }.mapTo(mutableSetOf()) { it.identity }
        val validRoutes = routes.filterKeys { it in valid }
        val validSelected = selected.filterTo(mutableSetOf()) { it in valid }
        return Snapshot(
            apps = catalog.apps,
            mode = readMode(context),
            routes = validRoutes,
            selected = validSelected,
            rootListEnabled = rootListEnabled(context),
            usedRoot = catalog.usedRoot,
            rootNotice = catalog.rootNotice,
            stale = (routes.size - validRoutes.size) + (selected.size - validSelected.size),
        )
    }

    internal fun save(
        context: Context,
        root: RootShell,
        useRoot: Boolean,
        mode: AppRoutingMode,
        routes: Map<String, AppRoute>,
        selected: Set<String>,
    ) {
        val valid = catalog(context, root, useRoot, labels = false, icons = false)
            .apps.filter { it.selectable }.mapTo(mutableSetOf()) { it.identity }
        require(valid.containsAll(routes.keys)) { "安装清单已变动，请关闭并重新打开应用列表" }
        require(valid.containsAll(selected)) { "安装清单已变动，请关闭并重新打开应用列表" }
        val edit = prefs(context).edit().clear()
            .putString(KEY_MODE, mode.name)
            .putBoolean(KEY_ROOT_LIST, useRoot)
        routes.forEach { (identity, route) -> if (route != AppRoute.FOLLOW) edit.putString(ROUTE_PREFIX + identity, route.name) }
        selected.forEach { edit.putString(SELECTED_PREFIX + it, "1") }
        check(edit.commit()) { "未能保存应用分流设置" }
        LogBus.info(
            TAG,
            "应用分流已保存：模式=${mode.label}，单应用规则=${routes.size}，勾选=${selected.size}，root 列表=${if (useRoot) "开" else "关"}",
        )
    }

    /** 启动事务每次 resolve 均调用；安装事件只停用旧快照，不自行启动内核。 */
    private var watching = false
    @Synchronized
    private fun watch(context: Context) {
        if (watching) return
        val app = context.applicationContext
        val filter = android.content.IntentFilter().apply {
            addAction(android.content.Intent.ACTION_PACKAGE_ADDED)
            addAction(android.content.Intent.ACTION_PACKAGE_REMOVED)
            addAction(android.content.Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context, intent: android.content.Intent) {
                val pkg = intent.data?.schemeSpecificPart ?: return
                if (prefs(app).all.keys.none { keyPackage(it) == pkg }) return
                val core = ProxyCore.get(app)
                if (core.state.value.status in setOf(ProxyStatus.RUNNING, ProxyStatus.STARTING)) {
                    LogBus.warn(TAG, "已选应用安装状态变动，停止旧会话；请重新确认后启动。停机期间不提供分流。")
                    core.stop()
                }
            }
        }
        androidx.core.content.ContextCompat.registerReceiver(
            app,
            receiver,
            filter,
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        watching = true
    }

    /** 在每次启动/重载时于 IO 线程调用；持久化键只有包名和安装身份，没有可复用的数字 UID。 */
    fun resolve(context: Context, root: RootShell, useRoot: Boolean): ResolvedAppRouting {
        val mode = readMode(context)
        val routes = readRoutes(context)
        val selected = readSelected(context)
        if (mode == AppRoutingMode.OFF && routes.isEmpty()) return ResolvedAppRouting()
        watch(context)
        val catalog = catalog(context, root, useRoot, labels = false, icons = false)
        val selectable = catalog.apps.filter { it.selectable }.associateBy { it.identity }
        if (mode == AppRoutingMode.OFF) {
            val owners = resolveOwners(routes, catalog.apps)
            check(owners.size == routes.size) {
                "有 ${routes.size - owners.size} 条应用分流策略的安装身份已变动或 UID 被共享；为避免误分流已取消启动。请重新打开应用列表确认并保存。"
            }
            return ResolvedAppRouting(owners = owners, mode = mode)
        }
        val selectedOwners = selected.mapNotNull { identity ->
            selectable[identity]?.let {
                AppRouteOwner(it.pkg, it.uid, if (mode == AppRoutingMode.WHITELIST) AppRoute.PROXY else AppRoute.DIRECT)
            }
        }
        check(selectedOwners.size == selected.size) {
            "有 ${selected.size - selectedOwners.size} 条白名单/黑名单策略的安装身份已变动或 UID 被共享；为避免误分流已取消启动。请重新打开应用列表确认并保存。"
        }
        return ResolvedAppRouting(mode = mode, selected = selectedOwners)
    }

    // ------------------------------------------------------------------ 清单

    private fun catalog(context: Context, root: RootShell, useRoot: Boolean, labels: Boolean, icons: Boolean): Catalog {
        val pm = context.packageManager
        val userId = Process.myUid() / 100_000
        val installed = try {
            pm.getInstalledPackages(0)
        } catch (t: Throwable) {
            if (!useRoot) {
                throw IllegalStateException(
                    "系统拒绝提供应用列表：${t.message ?: t.javaClass.simpleName}。请开启「通过 root 读取应用列表」后重试。",
                    t,
                )
            }
            emptyList()
        }
        require(installed.any { it.packageName == context.packageName }) {
            if (useRoot) "系统应用清单不可用，root 列表也无法核对本应用身份" else
                "系统返回的应用清单缺少本应用，可能被包可见性限制。请开启「通过 root 读取应用列表」。"
        }
        if (installed.count { it.applicationInfo?.uid != Process.myUid() } == 0 && !useRoot) {
            throw IllegalStateException(
                "系统只返回了本应用，可能被包可见性限制。请开启「通过 root 读取应用列表」后重试。",
            )
        }

        val pmApps = installed.mapNotNull { it.applicationInfo }
        val installTimes = installed.associate { it.packageName to it.firstInstallTime }
        val pmByName = installed.associateBy { it.packageName }
        val merged = mutableListOf<App>()

        for (info in pmApps) {
            if (!eligible(info, userId)) continue
            val system = info.flags and ApplicationInfo.FLAG_SYSTEM != 0
            merged += App(
                pkg = info.packageName,
                label = if (labels) runCatching { info.loadLabel(pm).toString() }.getOrDefault(info.packageName) else info.packageName,
                uid = info.uid,
                installedAt = installTimes[info.packageName] ?: 0L,
                shared = emptyList(),
                system = system,
                fromRoot = false,
                icon = if (icons) loadIcon(pm, info) else null,
            )
        }

        var rootNotice: String? = null
        var usedRoot = false
        if (useRoot) {
            val rootPackages = runCatching { rootPackages(root, userId) }.getOrNull()
            if (rootPackages == null) {
                rootNotice = "root 读取应用列表失败（su 被拒绝或 pm 命令不可用），当前只显示系统列表。"
                LogBus.warn(TAG, rootNotice)
            } else {
                usedRoot = true
                val systemSet = runCatching { rootSystemPackages(root, userId) }.getOrDefault(emptySet())
                val missing = rootPackages.filterKeys { it !in pmByName && ResolvedAppRouting.PACKAGE.matches(it) }
                    .toList()
                    .sortedWith(compareBy({ it.first in systemSet }, { it.first }))
                var iconBudget = if (icons) MAX_ROOT_ICON_APPS else 0
                for ((pkg, uid) in missing) {
                    if (uid <= 0 || uid == Process.myUid() || !Process.isApplicationUid(uid) || uid / 100_000 != userId) continue
                    if (pmByName[pkg]?.applicationInfo?.let { eligible(it, userId) } == true) continue
                    var label = pkg
                    var icon: ImageBitmap? = null
                    if (iconBudget > 0) {
                        iconBudget--
                        val extracted = loadRootAppInfo(context, root, pkg)
                        if (extracted != null) {
                            label = extracted.first
                            icon = extracted.second
                        }
                    }
                    merged += App(
                        pkg = pkg,
                        label = label,
                        uid = uid,
                        installedAt = 0L,
                        shared = emptyList(),
                        system = pkg in systemSet,
                        fromRoot = true,
                        icon = icon,
                    )
                }
                val added = merged.count { it.fromRoot }
                rootNotice = when {
                    added > 0 -> "已通过 root 补充 $added 个系统列表不可见的应用。"
                    else -> "root 列表已核对，没有发现额外应用。"
                }
                if (icons && iconBudget == 0 && added > MAX_ROOT_ICON_APPS) {
                    rootNotice += " 为控制开销，仅前 $MAX_ROOT_ICON_APPS 个 root 应用加载了图标。"
                }
                LogBus.info(TAG, "root 应用列表：发现 ${rootPackages.size} 个包，补充 $added 个不可见应用")
            }
            File(context.cacheDir, "app-list-icons").deleteRecursively()
        }

        val byUid = merged.groupBy { it.uid }
        val apps = merged.map { app ->
            val siblings = byUid[app.uid].orEmpty().map { it.pkg }.filter { it != app.pkg }
            val shared = siblings.ifEmpty {
                pmByName[app.pkg]?.sharedUserId?.let(::listOf).orEmpty()
            }
            app.copy(shared = shared)
        }.sortedWith(compareBy({ it.label.lowercase() }, { it.pkg }))

        return Catalog(apps, usedRoot, rootNotice)
    }

    internal fun resolveOwners(stored: Map<String, AppRoute>, apps: List<App>): List<AppRouteOwner> =
        apps.filter { it.selectable }.mapNotNull { app -> stored[app.identity]?.let { AppRouteOwner(app.pkg, app.uid, it) } }

    private fun eligible(info: ApplicationInfo, userId: Int): Boolean =
        info.uid != Process.myUid() &&
            Process.isApplicationUid(info.uid) &&
            info.uid / 100_000 == userId

    private fun loadIcon(pm: PackageManager, info: ApplicationInfo): ImageBitmap? = runCatching {
        info.loadIcon(pm).toBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888).asImageBitmap()
    }.getOrNull()

    private fun rootPackages(root: RootShell, userId: Int): Map<String, Int>? {
        val commands = listOf(
            "pm list packages -U --user $userId",
            "cmd package list packages -U --user $userId",
        )
        val regex = Regex("""^package:([A-Za-z0-9_.]+)(?:\s+uid:(\d+))?\s*$""")
        for (command in commands) {
            val result = root.exec(command, timeoutMillis = 30_000, tag = TAG, echo = false, logStdout = false)
            if (!result.ok) continue
            val packages = linkedMapOf<String, Int>()
            for (line in result.stdout) {
                val match = regex.find(line.trim()) ?: continue
                val uid = match.groupValues[2].toIntOrNull() ?: -1
                if (uid > 0) packages[match.groupValues[1]] = uid
            }
            if (packages.isNotEmpty()) return packages
        }
        return null
    }

    private fun rootSystemPackages(root: RootShell, userId: Int): Set<String> {
        val result = root.exec("pm list packages -s --user $userId", timeoutMillis = 20_000, tag = TAG, echo = false, logStdout = false)
        if (!result.ok) return emptySet()
        return result.stdout.mapNotNull { line ->
            line.trim().removePrefix("package:").takeIf { ResolvedAppRouting.PACKAGE.matches(it) }
        }.toSet()
    }

    /** root 应用没有可用的 PackageManager 记录：从已安装 APK 里读取一次名称和图标。 */
    private fun loadRootAppInfo(context: Context, root: RootShell, pkg: String): Pair<String, ImageBitmap?>? {
        val cache = File(context.cacheDir, "app-list-icons")
        if (!cache.isDirectory && !cache.mkdirs()) return null
        val destination = File(cache, "${pkg.hashCode().toUInt().toString(16)}.apk")
        return try {
            val pathResult = root.exec(
                "pm path ${Workspace.shellQuote(pkg)}",
                timeoutMillis = 10_000,
                tag = TAG,
                echo = false,
                logStdout = false,
            )
            val apk = pathResult.stdout.firstOrNull { it.startsWith("package:") }
                ?.removePrefix("package:")?.trim()
                ?: return null
            if (!apk.startsWith("/") || apk.any { it == '\n' || it == '\r' }) return null
            val copy = root.exec(
                "cp ${Workspace.shellQuote(apk)} ${Workspace.shellQuote(destination.absolutePath)}",
                timeoutMillis = 20_000,
                tag = TAG,
                echo = false,
                logStdout = false,
            )
            if (!copy.ok || !destination.isFile || destination.length() <= 0L) return null
            val archive = context.packageManager.getPackageArchiveInfo(destination.absolutePath, 0) ?: return null
            val info = archive.applicationInfo ?: return null
            info.sourceDir = destination.absolutePath
            info.publicSourceDir = destination.absolutePath
            val label = runCatching { info.loadLabel(context.packageManager).toString() }.getOrDefault(pkg)
            val icon = runCatching {
                info.loadIcon(context.packageManager).toBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888).asImageBitmap()
            }.getOrNull()
            label to icon
        } finally {
            runCatching { destination.delete() }
        }
    }
}
