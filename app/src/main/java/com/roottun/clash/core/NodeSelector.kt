package com.roottun.clash.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

data class NodeMember(
    val name: String,
    val type: String,
)

data class NodeGroup(
    val name: String,
    val type: String,
    val now: String?,
    val members: List<NodeMember>,
    val selectable: Boolean,
)

data class NodesUiState(
    val running: Boolean = false,
    val loading: Boolean = false,
    val groups: List<NodeGroup> = emptyList(),
    val delays: Map<String, Int> = emptyMap(),
    val testing: Set<String> = emptySet(),
    val error: String? = null,
)

/**
 * 「自由选择节点」状态机：通过 mihomo 控制器读取策略组、切换节点、测速。
 */
class NodeSelector(private val scope: CoroutineScope) {

    companion object {
        private const val TAG = "NODES"
        private const val MAX_CONCURRENT_TESTS = 8
    }

    private val _state = MutableStateFlow(NodesUiState())
    val state: StateFlow<NodesUiState> = _state.asStateFlow()

    private val testSemaphore = Semaphore(MAX_CONCURRENT_TESTS)

    @Volatile
    private var running = false

    @Volatile
    private var controllerSocketPath = ""

    fun onProxyState(isRunning: Boolean, socketPath: String) {
        val changed = running != isRunning || controllerSocketPath != socketPath
        running = isRunning
        controllerSocketPath = socketPath
        if (!isRunning) {
            _state.value = NodesUiState(running = false)
            return
        }
        if (changed || _state.value.groups.isEmpty()) {
            refresh()
        } else {
            _state.update { it.copy(running = true) }
        }
    }

    fun refresh() {
        if (!running || controllerSocketPath.isBlank()) {
            _state.update {
                it.copy(running = running, loading = false, error = null)
            }
            return
        }
        val socketPath = controllerSocketPath
        scope.launch(Dispatchers.IO) {
            _state.update { it.copy(running = true, loading = true, error = null) }
            MihomoApi(socketPath).fetchProxies()
                .onSuccess { snapshot ->
                    val groups = snapshot.groups
                        .map { group ->
                            NodeGroup(
                                name = group.name,
                                type = group.type,
                                now = group.now,
                                members = group.members.map { member ->
                                    NodeMember(member, snapshot.types[member] ?: "未知")
                                },
                                selectable = group.type.equals("Selector", ignoreCase = true),
                            )
                        }
                        .sortedWith(compareBy({ groupPriority(it.type) }, { it.name }))
                    _state.update { it.copy(loading = false, groups = groups, error = null) }
                    LogBus.info(TAG, "已读取 ${groups.size} 个策略组、${snapshot.types.size} 个节点/分组")
                }
                .onFailure { throwable ->
                    val reason = throwable.message ?: throwable.javaClass.simpleName
                    LogBus.warn(TAG, "读取节点列表失败：$reason")
                    _state.update { it.copy(loading = false, error = reason) }
                }
        }
    }

    fun select(group: NodeGroup, member: NodeMember) {
        if (!running || controllerSocketPath.isBlank()) return
        if (!group.selectable) {
            LogBus.warn(TAG, "「${group.name}」是 ${group.type} 策略组，由 mihomo 自动选择，不能手动指定")
            return
        }
        val socketPath = controllerSocketPath
        scope.launch(Dispatchers.IO) {
            MihomoApi(socketPath).select(group.name, member.name)
                .onSuccess {
                    LogBus.info(TAG, "策略组「${group.name}」已切换为「${member.name}」")
                    _state.update { current ->
                        current.copy(
                            groups = current.groups.map {
                                if (it.name == group.name) it.copy(now = member.name) else it
                            },
                            error = null,
                        )
                    }
                }
                .onFailure { throwable ->
                    val reason = throwable.message ?: throwable.javaClass.simpleName
                    LogBus.error(TAG, "切换「${group.name}」→「${member.name}」失败：$reason")
                    _state.update { it.copy(error = "切换节点失败：$reason") }
                }
        }
    }

    fun testGroup(group: NodeGroup) {
        testNodes(group.members.map { it.name })
    }

    fun testNode(name: String) {
        testNodes(listOf(name))
    }

    private fun testNodes(names: List<String>) {
        if (!running || controllerSocketPath.isBlank()) return
        val targets = names.distinct().filter { it.isNotBlank() }
        if (targets.isEmpty()) return
        val socketPath = controllerSocketPath
        scope.launch(Dispatchers.IO) {
            targets.forEach { name ->
                launch {
                    testSemaphore.withPermit {
                        _state.update { it.copy(testing = it.testing + name) }
                        val result = MihomoApi(socketPath).delay(name)
                        _state.update { current ->
                            current.copy(
                                testing = current.testing - name,
                                delays = current.delays + (name to result.getOrDefault(-1)),
                            )
                        }
                        result.onFailure { throwable ->
                            LogBus.debug(TAG, "测速「$name」失败：${throwable.message}")
                        }
                    }
                }
            }
        }
    }

    private fun groupPriority(type: String): Int = when (type.lowercase()) {
        "selector" -> 0
        "urltest" -> 1
        "fallback" -> 2
        "loadbalance" -> 3
        "relay" -> 4
        else -> 5
    }
}
