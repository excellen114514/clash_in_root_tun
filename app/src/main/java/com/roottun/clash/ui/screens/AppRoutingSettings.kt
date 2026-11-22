package com.roottun.clash.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.roottun.clash.core.AppRoute
import com.roottun.clash.core.AppRoutingMode
import com.roottun.clash.core.AppRoutingPolicy
import com.roottun.clash.core.ProxyCore
import com.roottun.clash.core.ShizukuManager
import com.roottun.clash.ui.theme.AppIcons
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class AppFilter(val label: String) {
    ALL("全部"),
    USER("用户应用"),
    SYSTEM("系统应用"),
}

@Composable
internal fun AppRoutingSettings(core: ProxyCore, showShizuku: Boolean = false) {
    val state by core.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var opened by rememberSaveable { mutableStateOf(false) }
    var summary by rememberSaveable { mutableStateOf("") }
    val mode = AppRoutingPolicy.savedMode(context)
    val rootList = AppRoutingPolicy.rootListEnabled(context)
    val shizukuList = showShizuku && AppRoutingPolicy.shizukuListEnabled(context)

    SettingsSection("应用分流") {
        Text("默认关闭。可在应用列表中选择白名单或黑名单模式，并为每个应用单独指定直连/代理。", style = MaterialTheme.typography.bodyMedium)
        Text("白名单：仅勾选应用经代理，其余直连。黑名单：勾选应用直连，其余按订阅规则。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            "当前模式：${mode.label}" +
                (if (rootList) " · root 应用列表已开启" else "") +
                (if (shizukuList) " · Shizuku 应用列表已开启" else ""),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
        if (showShizuku) {
            Text(
                "Shizuku 可补充应用列表并创建测试 TUN；它不会接管本机应用，本机透明代理仍需 root。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FilledTonalButton(onClick = { opened = true }, enabled = !state.busy) { Text("选择应用") }
        if (summary.isNotEmpty()) Text(summary, style = MaterialTheme.typography.bodySmall)
    }
    if (opened) {
        AppRoutingEditor(
            core = core,
            showShizuku = showShizuku,
            onClose = { opened = false },
            onSaved = {
                summary = it
                opened = false
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppRoutingEditor(
    core: ProxyCore,
    showShizuku: Boolean,
    onClose: () -> Unit,
    onSaved: (String) -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val state by core.state.collectAsStateWithLifecycle()
    val shizukuState by ShizukuManager.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var useRoot by rememberSaveable { mutableStateOf(AppRoutingPolicy.rootListEnabled(context)) }
    var useShizuku by rememberSaveable {
        mutableStateOf(showShizuku && AppRoutingPolicy.shizukuListEnabled(context))
    }
    var snapshot by remember { mutableStateOf<AppRoutingPolicy.Snapshot?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var seeded by rememberSaveable { mutableStateOf(false) }
    var modeName by rememberSaveable { mutableStateOf(AppRoutingMode.OFF.name) }
    var draftRoutes by rememberSaveable { mutableStateOf(hashMapOf<String, String>()) }
    var draftSelected by rememberSaveable { mutableStateOf(hashSetOf<String>()) }
    var query by rememberSaveable { mutableStateOf("") }
    var filterName by rememberSaveable { mutableStateOf(AppFilter.ALL.name) }
    var saving by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableStateOf(0) }

    val mode = runCatching { AppRoutingMode.valueOf(modeName) }.getOrDefault(AppRoutingMode.OFF)
    val filter = runCatching { AppFilter.valueOf(filterName) }.getOrDefault(AppFilter.ALL)

    LaunchedEffect(useRoot, useShizuku, reloadKey, shizukuState.permission, shizukuState.bound) {
        loading = true
        error = ""
        try {
            val loaded = withContext(Dispatchers.IO) {
                AppRoutingPolicy.editor(context, core.rootShell, useRoot, useShizuku)
            }
            snapshot = loaded
            if (!seeded) {
                modeName = loaded.mode.name
                draftRoutes = HashMap(loaded.routes.mapValues { it.value.name })
                draftSelected = HashSet(loaded.selected)
                seeded = true
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "读取应用清单失败"
        } finally {
            loading = false
        }
    }

    val apps = snapshot?.apps.orEmpty()
    val validIdentities = remember(snapshot) {
        snapshot?.apps.orEmpty().filter { it.selectable }.mapTo(mutableSetOf()) { it.identity }
    }
    val visible = remember(apps, query, filter) {
        val needle = query.trim()
        apps.filter { app ->
            (filter == AppFilter.ALL || app.system == (filter == AppFilter.SYSTEM)) &&
                (needle.isEmpty() || app.label.contains(needle, true) || app.pkg.contains(needle, true))
        }
    }
    val selectedCount = draftSelected.count { it in validIdentities }

    fun switchMode(target: AppRoutingMode) {
        if (mode == AppRoutingMode.OFF && draftSelected.isEmpty()) {
            val wanted = if (target == AppRoutingMode.WHITELIST) AppRoute.PROXY.name else AppRoute.DIRECT.name
            draftSelected = draftRoutes.filterValues { it == wanted }.keys.filter { it in validIdentities }.toHashSet()
        }
        modeName = target.name
    }

    Dialog(onDismissRequest = { if (!saving) onClose() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                TopAppBar(
                    title = { Text("选择应用") },
                    actions = {
                        TextButton(onClick = onClose, enabled = !saving) { Text("取消") }
                        TextButton(
                            enabled = !saving && !loading && !state.busy && snapshot != null,
                            onClick = {
                                if (saving) return@TextButton
                                saving = true
                                error = ""
                                scope.launch {
                                    try {
                                        val valid = validIdentities
                                        val routes = draftRoutes.mapNotNull { (identity, route) ->
                                            if (identity in valid) identity to AppRoute.valueOf(route) else null
                                        }.toMap()
                                        val selected = draftSelected.filterTo(mutableSetOf()) { it in valid }
                                        withContext(Dispatchers.IO) {
                                            AppRoutingPolicy.save(context, core.rootShell, useRoot, useShizuku, mode, routes, selected)
                                        }
                                        val running = core.state.value.running
                                        if (running) core.restart()
                                        onSaved(
                                            "已保存：模式 ${mode.label}，勾选 $selectedCount 个应用。" +
                                                if (running) "正在重启内核。" else "下次启动生效。",
                                        )
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        error = e.message ?: "保存失败"
                                    } finally {
                                        saving = false
                                    }
                                }
                            },
                        ) { Text(if (saving) "保存中…" else if (state.running) "保存并重启" else "保存") }
                    },
                )
            },
        ) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("工作模式", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppRoutingMode.entries.forEach { target ->
                        FilterChip(
                            selected = mode == target,
                            onClick = { switchMode(target) },
                            enabled = !saving,
                            label = { Text(target.label) },
                        )
                    }
                }
                Text(
                    when (mode) {
                        AppRoutingMode.OFF -> "关闭：按每个应用的「跟随订阅 / 直连 / 代理」生效。"
                        AppRoutingMode.WHITELIST -> if (selectedCount == 0) "白名单为空：所有应用都将直连。" else "仅勾选的 $selectedCount 个应用经代理，其余直连。"
                        AppRoutingMode.BLACKLIST -> if (selectedCount == 0) "黑名单为空：所有应用按订阅规则。" else "勾选的 $selectedCount 个应用直连，其余按订阅规则。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SettingSwitchRow(
                    title = "通过 root 读取应用列表",
                    subtitle = "默认关闭。系统拒绝或包可见性限制时，用 root 补充应用名称、UID 和图标；不上传数据。",
                    checked = useRoot,
                    enabled = !saving,
                ) { useRoot = !useRoot }
                if (showShizuku && shizukuState.installed) {
                    SettingSwitchRow(
                        title = "通过 Shizuku 读取应用列表",
                        subtitle = "Shizuku 仅用于应用列表/测试 TUN；不会接管本机应用，本机透明代理仍需 root。",
                        checked = useShizuku,
                        enabled = !saving,
                    ) {
                        useShizuku = !useShizuku
                        if (useShizuku && !shizukuState.permission) ShizukuManager.requestPermission()
                    }
                    when {
                        !shizukuState.running -> TextButton(
                            onClick = { ShizukuManager.openManager() },
                            enabled = !saving,
                        ) { Text("打开 Shizuku 并启动服务") }

                        !shizukuState.permission -> FilledTonalButton(
                            onClick = { ShizukuManager.requestPermission() },
                            enabled = !saving,
                        ) { Text("授权 Shizuku") }

                        else -> Text(
                            "Shizuku 已授权（uid=${shizukuState.uid ?: "?"}，" +
                                "${if (shizukuState.isRoot) "root" else "shell"}）" +
                                if (shizukuState.bound) "，应用列表服务已连接" else "，正在连接应用列表服务…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    shizukuState.error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
                TextButton(
                    onClick = { reloadKey++ },
                    enabled = !saving && !loading,
                ) { Text("重新读取应用列表") }

                if (error.isNotEmpty()) {
                    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    // 包可见性受限时，用户可能还没打开 root 开关；这里给出直接入口。
                    if (snapshot == null && !loading && !saving) {
                        if (!useRoot) {
                            FilledTonalButton(onClick = { useRoot = true }) { Text("使用 root 读取应用列表") }
                        }
                        if (showShizuku && shizukuState.installed && !useShizuku) {
                            FilledTonalButton(onClick = {
                                useShizuku = true
                                if (!shizukuState.permission) ShizukuManager.requestPermission()
                            }) { Text("使用 Shizuku 读取应用列表") }
                        }
                        if (useRoot || useShizuku) {
                            TextButton(onClick = { reloadKey++ }) { Text("重试读取") }
                        }
                    }
                }
                if (loading) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("正在读取应用列表…", style = MaterialTheme.typography.bodySmall)
                    return@Column
                }
                val current = snapshot
                if (current == null) {
                    Text("没有可用的应用列表。", style = MaterialTheme.typography.bodyMedium)
                    return@Column
                }
                current.rootNotice?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (it.contains("失败")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (showShizuku) {
                    current.shizukuNotice?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (it.contains("失败")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (current.stale > 0) {
                    Text(
                        "有 ${current.stale} 条旧策略的安装身份已失效，保存时会移除。",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AppFilter.entries.forEach { target ->
                        FilterChip(
                            selected = filter == target,
                            onClick = { filterName = target.name },
                            label = { Text(target.label) },
                        )
                    }
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    label = { Text("搜索名称或包名") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "已选 $selectedCount · 显示 ${visible.size} / ${apps.size} · 系统应用 ${apps.count { it.system }}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
                    item {
                        Text(
                            "系统应用与用户应用均可分流；共享 UID、系统代发流量和 TUN 内部 DNS 不在此设置范围内。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (visible.isEmpty()) {
                        item { Text("没有符合条件的应用", Modifier.padding(vertical = 12.dp)) }
                    }
                    items(visible, key = { it.pkg }) { app ->
                        AppRow(
                            app = app,
                            mode = mode,
                            route = draftRoutes[app.identity]?.let { runCatching { AppRoute.valueOf(it) }.getOrNull() } ?: AppRoute.FOLLOW,
                            selected = app.identity in draftSelected,
                            saving = saving,
                            onSelectedChange = { checked ->
                                draftSelected = HashSet(draftSelected).apply {
                                    if (checked) add(app.identity) else remove(app.identity)
                                }
                            },
                            onRouteChange = { route ->
                                draftRoutes = HashMap(draftRoutes).apply { put(app.identity, route.name) }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AppRow(
    app: AppRoutingPolicy.App,
    mode: AppRoutingMode,
    route: AppRoute,
    selected: Boolean,
    saving: Boolean,
    onSelectedChange: (Boolean) -> Unit,
    onRouteChange: (AppRoute) -> Unit,
) {
    val selectable = app.selectable && !saving
    Row(
        modifier = Modifier.fillMaxWidth()
            .then(
                if (mode != AppRoutingMode.OFF && selectable) {
                    Modifier.toggleable(value = selected, role = Role.Checkbox, onValueChange = onSelectedChange)
                } else {
                    Modifier
                },
            )
            .heightIn(min = 64.dp)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppIcon(app)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    app.label,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (app.system) Badge("系统")
                if (app.fromRoot) Badge("Root")
                if (app.fromShizuku) Badge("Shizuku")
            }
            Text(
                app.pkg,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            when {
                !app.selectable && app.shared.isNotEmpty() ->
                    Text("共享 UID：不能单独分流", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                !app.selectable ->
                    Text("无法确认安装身份，不能分流", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                mode == AppRoutingMode.OFF -> Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    AppRoute.entries.forEach { target ->
                        FilterChip(
                            selected = route == target,
                            onClick = { onRouteChange(target) },
                            enabled = !saving,
                            label = { Text(target.label) },
                        )
                    }
                }
            }
        }
        if (mode != AppRoutingMode.OFF && app.selectable) {
            Checkbox(checked = selected, onCheckedChange = null, enabled = !saving)
        }
    }
    HorizontalDivider()
}

@Composable
private fun AppIcon(app: AppRoutingPolicy.App) {
    Box(
        modifier = Modifier
            .size(42.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        val icon = app.icon
        if (icon != null) {
            Image(bitmap = icon, contentDescription = null, modifier = Modifier.fillMaxSize())
        } else {
            Icon(
                AppIcons.Apps,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

@Composable
private fun Badge(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(4.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
        )
    }
}
