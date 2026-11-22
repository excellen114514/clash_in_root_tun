package com.roottun.clash.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.roottun.clash.core.LogBus
import com.roottun.clash.core.ProxyCore
import com.roottun.clash.core.Subscription
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubscriptionsScreen(
    core: ProxyCore,
    snackbarHostState: SnackbarHostState,
    canActivate: Boolean,
    onActivate: (String) -> Unit,
) {
    val subscriptions by core.subscriptions.subscriptions.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var showAddDialog by rememberSaveable { mutableStateOf(false) }
    val refreshLock = remember { Mutex() }
    var refreshingIds by remember { mutableStateOf(emptySet<String>()) }
    var pendingDelete by remember { mutableStateOf<Subscription?>(null) }

    fun notify(message: String) {
        scope.launch { snackbarHostState.showSnackbar(message) }
    }

    fun refreshSubscriptions(requested: List<Subscription>) {
        val targets = requested.filterNot { it.id in refreshingIds }
        if (targets.isEmpty()) return
        val ids = targets.map { it.id }.toSet()
        refreshingIds = refreshingIds + ids
        scope.launch {
            try {
                var updated = 0
                targets.forEach { sub ->
                    refreshLock.withLock { core.subscriptions.refresh(sub.id) }.onSuccess { count ->
                        updated++
                        if (targets.size == 1) notify("「${sub.name}」已更新：$count 个节点")
                    }.onFailure { notify("「${sub.name}」更新失败：${it.message}") }
                }
                if (targets.size > 1) notify("更新完成：$updated / ${targets.size} 个订阅成功")
            } finally {
                refreshingIds = refreshingIds - ids
            }
        }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) {
            LogBus.warn("SUBSCRIPTION", "已取消本地 YAML 文件选择")
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val content = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        String(stream.readBytes(), Charsets.UTF_8)
                    }
                }.getOrNull()
            }
            if (content.isNullOrBlank()) {
                LogBus.error("SUBSCRIPTION", "读取本地文件失败或内容为空：$uri")
                notify("导入失败：文件为空或不可读")
                return@launch
            }
            val displayName = uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':')
                ?: "本地订阅"
            core.subscriptions.addLocalYaml(displayName, content)
                .onSuccess { notify("已导入「${it.name}」：${it.nodeCount} 个节点") }
                .onFailure { notify("导入失败：${it.message}") }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("订阅") },
                actions = {
                    IconButton(
                        enabled = refreshingIds.isEmpty() && subscriptions.any { it.enabled },
                        onClick = { refreshSubscriptions(subscriptions.filter { it.enabled }) },
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新全部订阅")
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAddDialog = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("添加订阅") },
            )
        },
    ) { padding ->
        if (subscriptions.isEmpty()) {
            EmptySubscriptions(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                onAddClick = { showAddDialog = true },
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .selectableGroup(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Text(
                        "点按卡片选择活动订阅。代理运行时，切换订阅将重启内核。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(items = subscriptions, key = { it.id }) { subscription ->
                    SubscriptionCard(
                        subscription = subscription,
                        refreshing = subscription.id in refreshingIds,
                        canActivate = canActivate,
                        onActivate = { onActivate(subscription.id) },
                        onToggleEnabled = {
                            core.subscriptions.subscriptions.value.firstOrNull { it.id == subscription.id }?.let { latest ->
                                core.subscriptions.setEnabled(latest.id, !latest.enabled)
                            }
                        },
                        onRefresh = { refreshSubscriptions(listOf(subscription)) },
                        onDelete = { pendingDelete = subscription },
                    )
                }
            }
        }
    }

    if (showAddDialog) {
        AddSubscriptionDialog(
            onDismiss = { showAddDialog = false },
            onAddUrl = { name, url ->
                val subscription = core.subscriptions.addUrlSubscription(name, url)
                showAddDialog = false
                notify("已添加「${subscription.name}」，正在拉取…")
                refreshSubscriptions(listOf(subscription))
            },
            onPickLocalFile = {
                showAddDialog = false
                filePicker.launch(arrayOf("application/yaml", "text/yaml", "text/plain", "*/*"))
            },
        )
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除订阅？") },
            text = { Text("「${target.name}」及其本地缓存将被删除，此操作不可撤销。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        core.subscriptions.remove(target.id)
                        pendingDelete = null
                        notify("已删除「${target.name}」")
                    },
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun SubscriptionCard(
    subscription: Subscription,
    refreshing: Boolean,
    canActivate: Boolean,
    onActivate: () -> Unit,
    onToggleEnabled: () -> Unit,
    onRefresh: () -> Unit,
    onDelete: () -> Unit,
) {
    val container by animateColorAsState(
        if (subscription.active) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        label = "活动订阅背景",
    )
    Card(
        colors = CardDefaults.cardColors(containerColor = container),
        modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
            .selectable(selected = subscription.active, enabled = canActivate, role = Role.RadioButton, onClick = onActivate),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = subscription.active, onClick = null, enabled = canActivate)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = subscription.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${subscription.sourceLabel} · ${subscription.nodeCount} 节点 · ${subscription.fetchedAtText}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onRefresh, enabled = !refreshing) {
                    if (refreshing) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Filled.Refresh, contentDescription = "更新${subscription.name}")
                    }
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "删除订阅")
                }
            }

            if (subscription.url.isNotBlank()) {
                val host = remember(subscription.url) { Uri.parse(subscription.url).host }
                Text(
                    text = host ?: "远程订阅",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            subscription.lastError?.let { error ->
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "最近错误：$error",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
            ) {
                Switch(
                    checked = subscription.enabled,
                    onCheckedChange = { onToggleEnabled() },
                    modifier = Modifier.semantics { contentDescription = "启用${subscription.name}" },
                )
            }
        }
    }
}

@Composable
private fun EmptySubscriptions(modifier: Modifier, onAddClick: () -> Unit) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("还没有订阅", style = MaterialTheme.typography.titleLarge)
            Text(
                "支持 Clash/mihomo YAML、base64 分享链接列表，以及本地 YAML 文件（SAF）。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FilledTonalButton(onClick = onAddClick) { Text("添加第一个订阅") }
        }
    }
}

@Composable
private fun AddSubscriptionDialog(
    onDismiss: () -> Unit,
    onAddUrl: (name: String, url: String) -> Unit,
    onPickLocalFile: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf("") }
    val urlValid = url.trim().startsWith("http://", true) || url.trim().startsWith("https://", true)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加订阅") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "方式一：填写订阅 URL（自动识别 YAML / base64）",
                    style = MaterialTheme.typography.labelLarge,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称（可选）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("订阅 URL") },
                    placeholder = { Text("https://example.com/sub") },
                    singleLine = true,
                    isError = url.isNotBlank() && !urlValid,
                    supportingText = {
                        if (url.isNotBlank() && !urlValid) {
                            Text("必须以 http:// 或 https:// 开头")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                HorizontalDivider()
                Text(
                    "方式二：选择本地 YAML 文件",
                    style = MaterialTheme.typography.labelLarge,
                )
                OutlinedButton(onClick = onPickLocalFile) { Text("通过 SAF 选择文件") }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onAddUrl(name, url.trim()) },
                enabled = urlValid,
            ) { Text("添加并拉取") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
