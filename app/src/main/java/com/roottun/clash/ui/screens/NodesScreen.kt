package com.roottun.clash.ui.screens

import android.os.SystemClock
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.roottun.clash.core.NodeGroup
import com.roottun.clash.core.NodeMember
import com.roottun.clash.core.NodesUiState
import com.roottun.clash.core.ProxyState
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NodesScreen(
    proxyState: ProxyState,
    nodes: NodesUiState,
    commandsEnabled: Boolean,
    onRefresh: () -> Unit,
    onSelect: (NodeGroup, NodeMember) -> Unit,
    onTestGroup: (NodeGroup) -> Unit,
    onTestNode: (String) -> Unit,
    onStartProxy: () -> Unit,
) {
    val list = rememberLazyListState()
    var expanded by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var pendingGroup by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingMember by rememberSaveable { mutableStateOf<String?>(null) }
    var errorAtRequest by rememberSaveable { mutableStateOf<String?>(null) }
    var deadline by rememberSaveable { mutableLongStateOf(0L) }
    val confirmed = pendingGroup?.let { name -> nodes.groups.firstOrNull { it.name == name }?.now }
    LaunchedEffect(pendingGroup, pendingMember, confirmed, nodes.error, proxyState.running) {
        if (pendingGroup != null) {
            if (proxyState.running && confirmed != pendingMember && nodes.error == errorAtRequest) {
                // 控制器不返回操作句柄；即使连续失败产生相同错误，也允许在读超时后重试。
                delay((deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
            }
            pendingGroup = null
            pendingMember = null
        }
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("节点") }, actions = {
            IconButton(onClick = onRefresh, enabled = proxyState.running && !nodes.loading && pendingGroup == null) {
                Icon(Icons.Filled.Refresh, contentDescription = "刷新节点列表")
            }
        })
    }) { padding ->
        if (!proxyState.running || nodes.groups.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding).padding(24.dp), contentAlignment = Alignment.Center) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    when {
                        proxyState.busy || (proxyState.running && nodes.loading) -> {
                            CircularProgressIndicator()
                            Text(if (proxyState.busy) proxyState.statusText else "正在读取节点…")
                        }
                        !proxyState.running -> {
                            Text("代理未运行", style = MaterialTheme.typography.titleLarge)
                            Text("启动代理后即可查看策略组、切换节点和测速。", style = MaterialTheme.typography.bodyMedium)
                            FilledTonalButton(onClick = onStartProxy, enabled = commandsEnabled) { Text("启动代理") }
                        }
                        else -> {
                            Text(if (nodes.error == null) "没有可用的策略组" else "读取节点失败", style = MaterialTheme.typography.titleLarge)
                            nodes.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                            FilledTonalButton(onClick = onRefresh) { Text("重新读取") }
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                state = list, modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                item(key = "intro") {
                    Text("展开策略组查看节点。手动选择组可切换节点，其他组由内核自动选择。",
                        modifier = Modifier.padding(bottom = 4.dp), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (nodes.loading) item(key = "progress") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                nodes.error?.let { error -> item(key = "error") { Text(error, color = MaterialTheme.colorScheme.error) } }
                nodes.groups.forEach { group ->
                    val open = group.name in expanded
                    item(key = "group:${group.name}", contentType = "group") {
                        GroupHeader(group, open, nodes,
                            onExpand = {
                                expanded = ArrayList(expanded).apply { if (!remove(group.name)) add(group.name) }
                            }, onTest = { onTestGroup(group) })
                    }
                    if (open) {
                        items(count = group.members.size, key = { index -> "member:${group.name.length}:${group.name}:$index" }, contentType = { "member" }) { index ->
                            val member = group.members[index]
                            MemberRow(
                                member = member, selected = member.name == group.now,
                                selectable = group.selectable && pendingGroup == null && !nodes.loading,
                                pending = pendingGroup == group.name && pendingMember == member.name,
                                delayMs = nodes.delays[member.name], testing = member.name in nodes.testing,
                                onSelect = {
                                    if (pendingGroup == null && group.selectable && group.now != member.name && !nodes.loading) {
                                        errorAtRequest = nodes.error
                                        deadline = SystemClock.elapsedRealtime() + 11_000L
                                        pendingMember = member.name
                                        pendingGroup = group.name
                                        onSelect(group, member)
                                    }
                                }, onTest = { onTestNode(member.name) }, modifier = Modifier.animateItem(),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupHeader(group: NodeGroup, expanded: Boolean, nodes: NodesUiState, onExpand: () -> Unit, onTest: () -> Unit) {
    val angle by animateFloatAsState(if (expanded) 180f else 0f, label = "展开指示")
    val testing = group.members.any { it.name in nodes.testing }
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onExpand)
                .semantics { stateDescription = if (expanded) "已展开" else "已收起" }
                .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(group.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${typeLabel(group.type)} · ${group.members.size} 个节点", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                Text("当前：${group.now ?: "未选择"}", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            group.now?.let { LatencyBadge(nodes.delays[it], it in nodes.testing) }
            TextButton(onClick = onTest, enabled = !testing && group.members.isNotEmpty()) { Text("测速") }
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, modifier = Modifier.rotate(angle))
        }
    }
}

@Composable
private fun MemberRow(
    member: NodeMember, selected: Boolean, selectable: Boolean, pending: Boolean,
    delayMs: Int?, testing: Boolean, onSelect: () -> Unit, onTest: () -> Unit, modifier: Modifier,
) {
    val color by animateColorAsState(if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow, label = "节点选中背景")
    Row(modifier = modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(color)
        .selectable(selected = selected, enabled = selectable, role = Role.RadioButton, onClick = onSelect)
        .heightIn(min = 52.dp).padding(start = 12.dp, end = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = null, enabled = selectable || selected)
        Column(Modifier.weight(1f)) {
            Text(member.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (pending) "正在切换…" else member.type, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LatencyBadge(delayMs, testing)
        IconButton(onClick = onTest, enabled = !testing) { Icon(Icons.Filled.PlayArrow, contentDescription = "为${member.name}测速") }
    }
}

@Composable
private fun LatencyBadge(delayMs: Int?, testing: Boolean) {
    when {
        testing -> CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        delayMs != null -> Text(if (delayMs > 0) "$delayMs ms" else "超时", style = MaterialTheme.typography.labelSmall,
            color = when (delayMs) {
                in 1..299 -> MaterialTheme.colorScheme.primary
                in 300..799 -> MaterialTheme.colorScheme.tertiary
                else -> MaterialTheme.colorScheme.error
            })
    }
}

private fun typeLabel(type: String): String = when (type.lowercase()) {
    "selector" -> "手动选择"
    "urltest" -> "自动测速"
    "fallback" -> "故障转移"
    "loadbalance" -> "负载均衡"
    "relay" -> "链式代理"
    "direct" -> "直连"
    "reject" -> "拒绝"
    "compatible" -> "兼容"
    else -> type
}
