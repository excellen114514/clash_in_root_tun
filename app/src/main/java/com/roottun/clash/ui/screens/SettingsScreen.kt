package com.roottun.clash.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.roottun.clash.BuildConfig
import com.roottun.clash.core.AppSettings
import com.roottun.clash.core.Dns
import com.roottun.clash.core.ProxyCore
import com.roottun.clash.core.ShizukuManager
import com.roottun.clash.core.SubscriptionFetcher
import com.roottun.clash.ui.theme.LocalCardStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val seedPresets = listOf(
    "青碧" to 0xFF006A6A, "靛蓝" to 0xFF3355A2, "紫罗兰" to 0xFF6B4E9B,
    "赤霞" to 0xFF9B3F4E, "松柏" to 0xFF2F6B4F, "鎏金" to 0xFF8A6A22,
)

private val corners = listOf(
    AppSettings.CornerStyle.DEFAULT to "默认",
    AppSettings.CornerStyle.ROUND to "圆润",
    AppSettings.CornerStyle.SHARP to "方正",
)

private val cardStyles = listOf(
    AppSettings.CardStyle.FILLED to "填充",
    AppSettings.CardStyle.TONAL to "色调",
    AppSettings.CardStyle.OUTLINED to "描边",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(core: ProxyCore) {
    val settings by AppSettings.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var tab by rememberSaveable { mutableIntStateOf(0) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("设置") }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            PrimaryTabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("风格化") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("配置功能") })
            }
            AnimatedContent(
                targetState = tab,
                modifier = Modifier.fillMaxSize(),
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "设置分区",
            ) { page ->
                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                        .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    if (page == 0) StyleTab(settings) else ConfigTab(core, settings, snackbar)
                }
            }
        }
    }
}

@Composable
private fun StyleTab(settings: AppSettings.State) {
    SettingsSection("外观") {
        SettingSwitchRow(
            "动态取色",
            "Android 12 及以上使用壁纸配色；关闭后使用下方基准色。",
            settings.dynamicColor,
        ) { AppSettings.update { it.copy(dynamicColor = !it.dynamicColor) } }
        Text("深色模式", style = MaterialTheme.typography.titleSmall)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppSettings.DarkMode.entries.forEach { mode ->
                val label = when (mode) {
                    AppSettings.DarkMode.SYSTEM -> "跟随系统"
                    AppSettings.DarkMode.LIGHT -> "浅色"
                    AppSettings.DarkMode.DARK -> "深色"
                }
                FilterChip(
                    selected = settings.darkMode == mode,
                    onClick = { AppSettings.update { it.copy(darkMode = mode) } },
                    label = { Text(label) },
                )
            }
        }
        Text("色彩对比度", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                AppSettings.Contrast.STANDARD to "标准",
                AppSettings.Contrast.HIGH to "高对比",
            ).forEach { (value, label) ->
                FilterChip(
                    selected = settings.contrast == value,
                    onClick = { AppSettings.update { it.copy(contrast = value) } },
                    label = { Text(label) },
                )
            }
        }
    }

    SettingsSection("主题基准色") {
        Text(
            "仅关闭动态取色时生效；所有颜色角色由 SPEC_2025 引擎生成。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()).selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            seedPresets.forEach { (name, value) ->
                val selected = settings.seedArgb == value
                Column(
                    modifier = Modifier.clip(MaterialTheme.shapes.small)
                        .selectable(
                            selected = selected,
                            role = Role.RadioButton,
                            onClick = { AppSettings.update { it.copy(seedArgb = value) } },
                        )
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Box(
                        Modifier.size(32.dp).background(Color(value), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (selected) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                    Text(name, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }

    SettingsSection("形状与卡片") {
        Text("圆角", style = MaterialTheme.typography.titleSmall)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            corners.forEach { (value, label) ->
                FilterChip(
                    selected = settings.cornerStyle == value,
                    onClick = { AppSettings.update { it.copy(cornerStyle = value) } },
                    label = { Text(label) },
                )
            }
        }
        Text("卡片样式", style = MaterialTheme.typography.titleSmall)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            cardStyles.forEach { (value, label) ->
                FilterChip(
                    selected = settings.cardStyle == value,
                    onClick = { AppSettings.update { it.copy(cardStyle = value) } },
                    label = { Text(label) },
                )
            }
        }
        Text(
            "圆角与卡片样式会立即应用到设置页与首页卡片，不影响分流与内核参数。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ConfigTab(core: ProxyCore, settings: AppSettings.State, snackbar: SnackbarHostState) {
    val scope = rememberCoroutineScope()
    var taps by remember { mutableIntStateOf(0) }

    AppRoutingSettings(core, showShizuku = settings.shizukuUnlocked)
    DnsSettings(settings)
    SettingsSection("网络与 TUN") {
        SettingSwitchRow("手工策略路由", "auto-route 未建立策略路由时，使用独立路由表。", settings.manualPolicyRouting) {
            AppSettings.update { it.copy(manualPolicyRouting = !it.manualPolicyRouting) }
        }
        SettingSwitchRow("应用自身流量绕过 TUN", "订阅拉取和控制请求不经过代理内核。", settings.bypassOwnTraffic) {
            AppSettings.update { it.copy(bypassOwnTraffic = !it.bypassOwnTraffic) }
        }
        SettingSwitchRow("阻断其他应用访问本机代理端口", "限制其他应用连接本机的代理与 DNS 端口。", settings.blockLocalProxyPorts) {
            AppSettings.update { it.copy(blockLocalProxyPorts = !it.blockLocalProxyPorts) }
        }
        SettingSwitchRow("TUN strict-route", "启用严格路由。部分设备可能不兼容。", settings.strictRoute) {
            AppSettings.update { it.copy(strictRoute = !it.strictRoute) }
        }
        Text("TUN 协议栈", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("gvisor", "system").forEach { stack ->
                FilterChip(
                    selected = settings.tunStack == stack,
                    onClick = { AppSettings.update { it.copy(tunStack = stack) } },
                    label = { Text(stack) },
                )
            }
        }
        Text("TUN MTU 上限", style = MaterialTheme.typography.titleSmall)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(1280, 1400, 1500).forEach { mtu ->
                FilterChip(
                    selected = settings.tunMtu == mtu,
                    onClick = { AppSettings.update { it.copy(tunMtu = mtu) } },
                    label = { Text(mtu.toString()) },
                )
            }
        }
        Text(
            "下次启动内核时生效。若小请求正常但大文件上传卡住，可尝试 1280。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    SettingsSection("订阅请求") {
        var input by rememberSaveable(settings.subscriptionUserAgent) { mutableStateOf(settings.subscriptionUserAgent) }
        Text(
            "服务端可能按 User-Agent 返回不同配置。修改后请重新拉取订阅。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = input, onValueChange = { input = it }, label = { Text("User-Agent") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(
                enabled = input != settings.subscriptionUserAgent,
                onClick = {
                    input = input.trim().ifBlank { SubscriptionFetcher.DEFAULT_USER_AGENT }
                    AppSettings.update { it.copy(subscriptionUserAgent = input) }
                },
            ) { Text("保存") }
            TextButton(onClick = {
                input = SubscriptionFetcher.DEFAULT_USER_AGENT
                AppSettings.update { it.copy(subscriptionUserAgent = SubscriptionFetcher.DEFAULT_USER_AGENT) }
            }) { Text("恢复默认") }
        }
    }
    KernelUpdateSettings()
    if (settings.shizukuUnlocked) {
        ShizukuTunSettings(onHide = {
            AppSettings.update { it.copy(shizukuUnlocked = false) }
            scope.launch { snackbar.showSnackbar("已隐藏实验功能") }
        })
    }
    SettingsSection(
        title = "关于",
        onClick = {
            if (settings.shizukuUnlocked) return@SettingsSection
            taps += 1
            if (taps >= 5) {
                AppSettings.update { it.copy(shizukuUnlocked = true) }
                taps = 0
                scope.launch { snackbar.showSnackbar("已显示实验功能") }
            }
        },
    ) {
        InfoRow("版本", BuildConfig.VERSION_NAME)
        Text(
            "本应用不创建 VpnService：TUN 设备与路由规则全部通过 root shell 由 mihomo 内核建立，" +
                "因此系统不会出现任何 VPN 连接。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text("代理内核：mihomo", style = MaterialTheme.typography.bodyMedium)
        Text("开发者：ExceSama、GPT、DeepSeek", style = MaterialTheme.typography.bodyMedium)
        if (!settings.shizukuUnlocked && taps in 1..4) {
            Text(
                "再点击 ${5 - taps} 次显示实验功能",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun DnsSettings(settings: AppSettings.State) {
    var draft by rememberSaveable(settings.customDns) { mutableStateOf(settings.customDns) }
    val entries = draft.split(',', '\n', '，').map { it.trim() }.filter { it.isNotEmpty() }
    val invalid = entries.filterNot { Dns.isValidEntry(it) }
    val changed = draft.trim() != settings.customDns

    SettingsSection("DNS") {
        Text(
            "当前：${Dns.summary(settings)}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                AppSettings.DnsPreset.CN to "国内优化",
                AppSettings.DnsPreset.GOOGLE to "Google",
                AppSettings.DnsPreset.CLOUDFLARE to "Cloudflare",
                AppSettings.DnsPreset.CUSTOM to "自定义",
            ).forEach { (preset, label) ->
                FilterChip(
                    selected = settings.dnsPreset == preset,
                    onClick = { AppSettings.update { it.copy(dnsPreset = preset) } },
                    label = { Text(label) },
                )
            }
        }
        if (settings.dnsPreset == AppSettings.DnsPreset.CUSTOM) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                label = { Text("自定义 DNS") },
                placeholder = { Text("https://dns.google/dns-query, 8.8.8.8") },
                supportingText = {
                    Text(
                        if (invalid.isEmpty()) {
                            "每行或用逗号分隔；支持 IP、https://（DoH）、tls://（DoT）"
                        } else {
                            "无法识别：${invalid.take(3).joinToString("、")}"
                        },
                        color = if (invalid.isEmpty()) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                },
                isError = invalid.isNotEmpty(),
                minLines = 3,
                maxLines = 6,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    enabled = changed && invalid.isEmpty() && entries.isNotEmpty(),
                    onClick = {
                        AppSettings.update { it.copy(customDns = draft.trim()) }
                        draft = draft.trim()
                    },
                ) { Text("保存") }
                TextButton(onClick = {
                    draft = ""
                    AppSettings.update { it.copy(customDns = "") }
                }) { Text("清空") }
            }
        }
        val effective = Dns.nameservers(settings.copy(customDns = draft, dnsPreset = settings.dnsPreset))
        Text(
            "生效顺序：${effective.joinToString(" → ")}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "TUN 内 any:53 由内核劫持，系统 DNS 设置无需修改；节点域名始终用国内 DNS 解析，" +
                "避免自定义 DoH 在隧道建立前不可达。下次启动内核时生效。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ShizukuTunSettings(onHide: () -> Unit) {
    val shizuku by ShizukuManager.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var busy by rememberSaveable { mutableStateOf(false) }
    var message by rememberSaveable { mutableStateOf("") }

    fun run(action: suspend () -> String?) {
        if (busy) return
        busy = true
        scope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { action() } }
                .getOrElse { "操作失败：${it.message}" }
            message = result ?: "Shizuku UserService 不可用；请确认 Shizuku 已运行并已授权。"
            busy = false
        }
    }

    SettingsSection("Shizuku 实验功能") {
        Text(
            "通过 Shizuku UserService 调用隐藏的 TestNetworkManager.createTunInterface，" +
                "与 Shizzi / HotspotXray 同类路径。创建的是热点/网络共享测试 TUN，" +
                "不接管本机应用；本机透明代理仍需 root。",
            style = MaterialTheme.typography.bodySmall,
        )
        if (!shizuku.installed) {
            Text("未检测到 Shizuku。", style = MaterialTheme.typography.bodySmall)
        } else {
            Text(
                when {
                    !shizuku.running -> "Shizuku 未运行"
                    !shizuku.permission -> "Shizuku 已运行，尚未授权"
                    else -> "Shizuku 已授权（uid=${shizuku.uid ?: "?"}，${if (shizuku.isRoot) "root" else "shell"}）"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            when {
                !shizuku.running -> FilledTonalButton(onClick = { ShizukuManager.openManager() }) {
                    Text("打开 Shizuku 并启动服务")
                }
                !shizuku.permission -> FilledTonalButton(onClick = { ShizukuManager.requestPermission() }) {
                    Text("授权 Shizuku")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    enabled = shizuku.permission && !busy,
                    onClick = { run { ShizukuManager.probeTun() } },
                ) { Text("检测能力") }
                FilledTonalButton(
                    enabled = shizuku.permission && !busy,
                    onClick = { run { ShizukuManager.createTun() } },
                ) { Text("创建测试 TUN") }
                TextButton(
                    enabled = shizuku.permission && !busy,
                    onClick = { run { ShizukuManager.destroyTun() } },
                ) { Text("销毁") }
            }
            if (busy) Text("正在调用 Shizuku…", style = MaterialTheme.typography.bodySmall)
            if (message.isNotEmpty()) {
                Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            shizuku.error?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
        TextButton(onClick = onHide) { Text("隐藏实验功能") }
    }
}

@Composable
internal fun SettingsSection(
    title: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val cardStyle = LocalCardStyle.current
    val colors = when (cardStyle) {
        AppSettings.CardStyle.FILLED -> CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        )
        AppSettings.CardStyle.TONAL -> CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        )
        AppSettings.CardStyle.OUTLINED -> CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        )
    }
    Card(
        modifier = modifier.fillMaxWidth().then(
            if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
        ),
        colors = colors,
        border = if (cardStyle == AppSettings.CardStyle.OUTLINED) {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        } else {
            null
        },
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                title,
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            content()
        }
    }
}

@Composable
internal fun SettingSwitchRow(title: String, subtitle: String, checked: Boolean, enabled: Boolean = true, onToggle: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = { onToggle() })
            .heightIn(min = 56.dp).padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, enabled = enabled, onCheckedChange = null)
    }
}
