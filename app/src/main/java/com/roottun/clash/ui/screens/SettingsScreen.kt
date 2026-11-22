package com.roottun.clash.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import com.roottun.clash.core.ProxyCore
import com.roottun.clash.core.ShizukuManager
import com.roottun.clash.core.SubscriptionFetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val seedPresets = listOf(
    "青碧" to 0xFF006A6A, "靛蓝" to 0xFF3355A2, "紫罗兰" to 0xFF6B4E9B,
    "赤霞" to 0xFF9B3F4E, "松柏" to 0xFF2F6B4F, "鎏金" to 0xFF8A6A22,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(core: ProxyCore) {
    val settings by AppSettings.state.collectAsStateWithLifecycle()
    Scaffold(topBar = { TopAppBar(title = { Text("设置") }) }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            AppearanceSettings(settings)
            AppRoutingSettings(core)
            ShizukuTunSettings()
            KernelUpdateSettings()
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
                Text("下次启动内核时生效。若小请求正常但大文件上传卡住，可尝试 1280。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            SettingsSection("关于") {
                InfoRow("版本", BuildConfig.VERSION_NAME)
                Text(
                    "本应用不创建 VpnService：TUN 设备与路由规则全部通过 root shell 由 mihomo 内核建立，" +
                        "因此系统不会出现任何 VPN 连接。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("代理内核：mihomo", style = MaterialTheme.typography.bodyMedium)
                Text("开发者：ExceSama、GPT、DeepSeek", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun AppearanceSettings(settings: AppSettings.State) {
    SettingsSection("外观") {
        SettingSwitchRow("动态取色", "Android 12 及以上可使用壁纸配色，关闭后使用下方基准色。", settings.dynamicColor) {
            AppSettings.update { it.copy(dynamicColor = !it.dynamicColor) }
        }
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
        Text("主题基准色", style = MaterialTheme.typography.titleSmall)
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()).selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            seedPresets.forEach { (name, value) ->
                val selected = settings.seedArgb == value
                Column(
                    modifier = Modifier.clip(MaterialTheme.shapes.small)
                        .selectable(selected = selected, role = Role.RadioButton,
                            onClick = { AppSettings.update { it.copy(seedArgb = value) } })
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Box(Modifier.size(32.dp).background(Color(value), CircleShape), contentAlignment = Alignment.Center) {
                        if (selected) Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                    Text(name, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun ShizukuTunSettings() {
    val shizuku by ShizukuManager.state.collectAsStateWithLifecycle()
    if (!shizuku.installed) return

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

    SettingsSection("Shizuku TestNetwork TUN（实验）") {
        Text(
            "通过 Shizuku UserService 调用隐藏的 TestNetworkManager.createTunInterface，" +
                "与 Shizzi / HotspotXray 同类路径。它创建的是热点/网络共享测试 TUN，" +
                "不会接管本机应用；本项目本地透明代理仍需 root。",
            style = MaterialTheme.typography.bodySmall,
        )
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
}

@Composable
internal fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, modifier = Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary)
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
