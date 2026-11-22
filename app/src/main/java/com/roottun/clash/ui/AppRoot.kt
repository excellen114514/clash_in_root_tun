package com.roottun.clash.ui

import kotlinx.coroutines.delay
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.roottun.clash.core.AppSettings
import com.roottun.clash.core.ProxyCore
import com.roottun.clash.ui.screens.HomeScreen
import com.roottun.clash.ui.screens.LogConsoleScreen
import com.roottun.clash.ui.screens.NodesScreen
import com.roottun.clash.ui.screens.SettingsScreen
import com.roottun.clash.ui.screens.SubscriptionsScreen
import com.roottun.clash.ui.theme.AppIcons

enum class AppDestination(val label: String) {
    HOME("状态"),
    SUBSCRIPTIONS("订阅"),
    NODES("节点"),
    LOGS("日志"),
    SETTINGS("设置"),
}

private fun AppDestination.icon(): ImageVector = when (this) {
    AppDestination.HOME -> Icons.Filled.Home
    AppDestination.SUBSCRIPTIONS -> Icons.AutoMirrored.Filled.List
    AppDestination.NODES -> AppIcons.Nodes
    AppDestination.LOGS -> Icons.Filled.Info
    AppDestination.SETTINGS -> Icons.Filled.Settings
}

@Composable
fun AppRoot(core: ProxyCore) {
    var destination by rememberSaveable { mutableStateOf(AppDestination.HOME) }
    val pages = rememberSaveableStateHolder()
    val proxyState by core.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var commandSubmitted by remember(core) { mutableStateOf(false) }
    val renderedStatus = proxyState.status
    val commandsEnabled = !commandSubmitted && !proxyState.busy

    // 工作线程报告启停状态之前，先关闭各页共享的操作入口。
    fun submitProxyCommand(command: () -> Unit) {
        val actual = core.state.value
        if (commandSubmitted || actual.busy || actual.status != renderedStatus) return
        commandSubmitted = true
        command()
    }
    LaunchedEffect(commandSubmitted) {
        if (commandSubmitted) {
            delay(450)
            commandSubmitted = false
        }
    }

    BackHandler(enabled = destination != AppDestination.HOME) { destination = AppDestination.HOME }
    LaunchedEffect(destination, core) {
        if (destination == AppDestination.NODES && !core.nodes.value.loading) core.refreshNodes()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                AppDestination.entries.forEach { item ->
                    NavigationBarItem(
                        selected = destination == item,
                        onClick = { destination = item },
                        icon = { Icon(item.icon(), contentDescription = null) },
                        label = { Text(item.label, maxLines = 1) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            AnimatedContent(
                targetState = destination,
                modifier = Modifier.fillMaxSize(),
                transitionSpec = {
                    val direction = if (targetState.ordinal > initialState.ordinal) 1 else -1
                    (fadeIn(tween(220)) + androidx.compose.animation.slideInHorizontally(tween(220)) { direction * it / 16 }) togetherWith
                        (fadeOut(tween(160)) + androidx.compose.animation.slideOutHorizontally(tween(160)) { -direction * it / 16 })
                },
                label = "分区切换",
            ) { page ->
            pages.SaveableStateProvider(page.name) {
                when (page) {
                    AppDestination.HOME -> {
                        val subscriptions by core.subscriptions.subscriptions.collectAsStateWithLifecycle()
                        val traffic by core.traffic.collectAsStateWithLifecycle()
                        val settings by AppSettings.state.collectAsStateWithLifecycle()
                        val running = proxyState.running
                        HomeScreen(
                            state = proxyState,
                            subscriptions = subscriptions,
                            traffic = traffic,
                            showTrafficInNotification = settings.showTrafficNotification,
                            commandsEnabled = commandsEnabled,
                            onToggle = { submitProxyCommand { if (running) core.stop() else core.start() } },
                            onRestart = { submitProxyCommand { core.restart() } },
                            onRefreshSubscription = { core.refreshActiveSubscription() },
                            onOpenLogs = { destination = AppDestination.LOGS },
                            onRetryRoot = { core.refreshRootStatus(force = true) },
                            onToggleTrafficNotification = {
                                AppSettings.update { it.copy(showTrafficNotification = !it.showTrafficNotification) }
                            },
                        )
                    }
                    AppDestination.SUBSCRIPTIONS -> SubscriptionsScreen(
                        core = core,
                        snackbarHostState = snackbar,
                        canActivate = commandsEnabled,
                        onActivate = { id ->
                            if (!commandSubmitted && !core.state.value.busy && core.subscriptions.activeSubscription()?.id != id) {
                                val activate = {
                                    core.subscriptions.setActive(id)
                                    core.onActiveSubscriptionChanged()
                                }
                                if (core.state.value.running) submitProxyCommand(activate) else activate()
                            }
                        },
                    )
                    AppDestination.NODES -> {
                        val nodes by core.nodes.collectAsStateWithLifecycle()
                        NodesScreen(
                            proxyState = proxyState,
                            nodes = nodes,
                            commandsEnabled = commandsEnabled,
                            onRefresh = { if (!core.nodes.value.loading) core.refreshNodes() },
                            onSelect = { group, member -> core.selectNode(group, member) },
                            onTestGroup = { group -> core.testGroupLatency(group) },
                            onTestNode = { name -> core.testNodeLatency(name) },
                            onStartProxy = { submitProxyCommand { core.start() } },
                        )
                    }
                    AppDestination.LOGS -> LogConsoleScreen(snackbarHostState = snackbar)
                    AppDestination.SETTINGS -> SettingsScreen(core)
                }
            }
            }
        }
    }
}
