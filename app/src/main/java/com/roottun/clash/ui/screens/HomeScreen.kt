package com.roottun.clash.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.roottun.clash.core.ProxyState
import com.roottun.clash.core.ProxyStatus
import com.roottun.clash.core.Subscription
import com.roottun.clash.core.TrafficSnapshot
import com.roottun.clash.core.formatBytes
import com.roottun.clash.core.formatRate
import com.roottun.clash.ui.theme.AppIcons
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: ProxyState,
    subscriptions: List<Subscription>,
    traffic: TrafficSnapshot,
    showTrafficInNotification: Boolean,
    commandsEnabled: Boolean,
    onToggle: () -> Unit,
    onRestart: () -> Unit,
    onRefreshSubscription: () -> Unit,
    onOpenLogs: () -> Unit,
    onRetryRoot: () -> Unit,
    onToggleTrafficNotification: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Root TUN Clash") })
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            StatusCard(
                state = state,
                commandsEnabled = commandsEnabled,
                onToggle = onToggle,
                onRefreshSubscription = onRefreshSubscription,
                onRetryRoot = onRetryRoot,
            )

            TrafficCard(
                traffic = traffic,
                showInNotification = showTrafficInNotification,
                running = state.running,
                onToggleNotification = onToggleTrafficNotification,
            )

            SubscriptionSummaryCard(
                state = state,
                subscriptions = subscriptions,
            )

            QuickActionsCard(
                commandsEnabled = commandsEnabled,
                onRestart = onRestart,
                onOpenLogs = onOpenLogs,
            )

            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun StatusCard(
    state: ProxyState,
    commandsEnabled: Boolean,
    onToggle: () -> Unit,
    onRefreshSubscription: () -> Unit,
    onRetryRoot: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    val accent by animateColorAsState(
        targetValue = when (state.status) {
            ProxyStatus.RUNNING -> colorScheme.primary
            ProxyStatus.ERROR -> colorScheme.error
            ProxyStatus.STARTING, ProxyStatus.STOPPING -> colorScheme.tertiary
            ProxyStatus.STOPPED -> colorScheme.onSurfaceVariant
        },
        label = "代理状态色",
    )
    val icon = when (state.status) {
        ProxyStatus.RUNNING -> Icons.Filled.CheckCircle
        ProxyStatus.ERROR -> Icons.Filled.Warning
        ProxyStatus.STARTING, ProxyStatus.STOPPING -> Icons.Filled.PlayArrow
        ProxyStatus.STOPPED -> Icons.Filled.PlayArrow
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val checked = state.running || state.status == ProxyStatus.STARTING
            Row(
                modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
                    .toggleable(value = checked, enabled = commandsEnabled, role = Role.Switch, onValueChange = { onToggle() })
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(accent.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, contentDescription = null, tint = accent)
                }
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    androidx.compose.animation.AnimatedContent(targetState = state.statusText, label = "启停状态") { title ->
                        Text(title, style = MaterialTheme.typography.headlineSmall)
                    }
                    Text(
                        text = state.detail.ifBlank {
                            when (state.status) {
                                ProxyStatus.RUNNING -> "mihomo 正在接管 TUN 流量"
                                ProxyStatus.STOPPED -> "打开开关以启动代理"
                                ProxyStatus.ERROR -> "请查看日志控制台中的错误详情"
                                else -> "正在处理…"
                            }
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Switch(checked = checked, enabled = commandsEnabled, onCheckedChange = null)
            }

            AnimatedVisibility(visible = state.busy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            state.error?.let { message ->
                Surface(
                    color = colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            "启动/运行错误",
                            style = MaterialTheme.typography.labelLarge,
                            color = colorScheme.onErrorContainer,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            message,
                            style = MaterialTheme.typography.bodySmall,
                            color = colorScheme.onErrorContainer,
                        )
                    }
                }
            }

            InfoRow("Root 权限", when (state.rootGranted) {
                true -> "已授权（uid=0）"
                false -> "未授权"
                null -> "检测中…"
            })
            InfoRow("TUN 设备", state.tunDevice ?: "—")
            InfoRow("内核 PID", state.kernelPid?.toString() ?: "—")
            InfoRow("本地监听", if (state.running) "无（仅 TUN + unix socket）" else "—")
            InfoRow("内核版本", state.kernelVersion ?: "—")
            if (state.running && state.startedAt > 0) {
                UptimeRow(startedAt = state.startedAt)
            }

            if (state.rootGranted == false) {
                Surface(
                    color = colorScheme.secondaryContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            "未获得 Root 权限",
                            style = MaterialTheme.typography.labelLarge,
                            color = colorScheme.onSecondaryContainer,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "MuMu：设置 → 其他设置 → 开启 Root 权限并重启模拟器；首次启动代理时请在授权弹窗点击「允许」并勾选「永久记住选择」。",
                            style = MaterialTheme.typography.bodySmall,
                            color = colorScheme.onSecondaryContainer,
                        )
                        TextButton(onClick = onRetryRoot) { Text("重新检测 Root 权限") }
                    }
                }
            }

            OutlinedButton(onClick = onRefreshSubscription, enabled = commandsEnabled) {
                Text("刷新订阅")
            }
        }
    }
}

@Composable
private fun UptimeRow(startedAt: Long) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(startedAt) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    InfoRow("运行时长", formatDuration(now - startedAt))
}

private fun formatDuration(millis: Long): String {
    val seconds = (millis / 1000).coerceAtLeast(0)
    return when {
        seconds < 60 -> "${seconds} 秒"
        seconds < 3600 -> "${seconds / 60} 分 ${seconds % 60} 秒"
        else -> "${seconds / 3600} 时 ${(seconds % 3600) / 60} 分"
    }
}

@Composable
private fun TrafficCard(
    traffic: TrafficSnapshot,
    showInNotification: Boolean,
    running: Boolean,
    onToggleNotification: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("流量统计", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(
                    if (running) "本次运行" else "未运行",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                TrafficStat(
                    label = "上传",
                    total = traffic.uploadTotal,
                    rate = traffic.uploadRate,
                    icon = AppIcons.Upload,
                    modifier = Modifier.weight(1f),
                )
                TrafficStat(
                    label = "下载",
                    total = traffic.downloadTotal,
                    rate = traffic.downloadRate,
                    icon = AppIcons.Download,
                    modifier = Modifier.weight(1f),
                )
            }
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("在通知栏显示流量", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "代理运行时，在前台通知中显示累计流量与速率。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(checked = showInNotification, onCheckedChange = { onToggleNotification() })
            }
            if (!running) {
                Text(
                    "启动代理后开始统计本次运行流量。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun TrafficStat(label: String, total: Long, rate: Long, icon: ImageVector, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(formatBytes(total), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium)
        Text(
            formatRate(rate),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SubscriptionSummaryCard(state: ProxyState, subscriptions: List<Subscription>) {
    val active = subscriptions.firstOrNull { it.active }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("当前订阅", style = MaterialTheme.typography.titleMedium)
            InfoRow("名称", state.activeSubName ?: active?.name ?: "未配置（启动时使用 DIRECT 自检）")
            InfoRow("来源", active?.sourceLabel ?: "—")
            InfoRow("节点数", active?.nodeCount?.toString() ?: "—")
            InfoRow("更新时间", active?.fetchedAtText ?: "—")
            if (active == null) {
                Text(
                    "提示：前往「订阅」页添加 Clash/mihomo 订阅后重启内核即可生效。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (!active.enabled) {
                Text(
                    "该订阅当前被禁用，但仍是活动订阅。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun QuickActionsCard(commandsEnabled: Boolean, onRestart: () -> Unit, onOpenLogs: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("快捷操作", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onRestart, enabled = commandsEnabled) { Text("重启内核") }
                TextButton(onClick = onOpenLogs) { Text("打开日志控制台") }
            }
        }
    }
}

@Composable
internal fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            value,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.End,
            fontWeight = FontWeight.Medium,
        )
    }
}
