package com.roottun.clash.core

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class KernelUpdateState(
    val ready: Boolean = false,
    val automatic: Boolean = true,
    val busy: Boolean = false,
    val current: String = MihomoRelease.BUNDLED,
    val pending: String? = null,
    val rollback: Boolean = false,
    val checkedAt: Long = 0,
    val result: String = "正在读取内核更新设置…",
)

internal object MihomoUpdates {
    private const val INTERVAL = 24 * 60 * 60 * 1000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val checking = AtomicBoolean(false)
    private val mutableState = MutableStateFlow(KernelUpdateState())
    val state = mutableState.asStateFlow()
    lateinit var store: MihomoKernelStore
        private set
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        store = MihomoKernelStore(context.applicationContext)
        prefs = context.getSharedPreferences("mihomo-release-updates", Context.MODE_PRIVATE)
        scope.launch {
            try {
                synchronized(store.lock) {
                    val slots = store.slots()
                    store.prune(slots)
                    File(store.directory, "download.gz.part").delete()
                    File(store.directory, "candidate.part").delete()
                    mutableState.update { it.copy(ready = true, automatic = prefs.getBoolean("automatic", true),
                        checkedAt = prefs.getLong("checked", 0), result = prefs.getString("result", null) ?: "尚未检查更新",
                        current = slots.current, pending = slots.pending?.tag, rollback = slots.rollback) }
                }
                check(automatic = true)
            } catch (e: Exception) {
                mutableState.update { it.copy(result = "内核更新不可用：${e.message}") }
                LogBus.warn("MIHOMO", mutableState.value.result)
            }
        }
    }

    fun setAutomatic(enabled: Boolean) {
        prefs.edit().putBoolean("automatic", enabled).apply()
        mutableState.update { it.copy(automatic = enabled) }
    }

    fun check(automatic: Boolean = false) {
        val now = System.currentTimeMillis()
        val state = mutableState.value
        if (!state.ready || state.rollback || automatic && (!state.automatic || now - state.checkedAt in 0 until INTERVAL)) return
        if (!checking.compareAndSet(false, true)) return
        mutableState.update { it.copy(busy = true, checkedAt = now, result = "正在检查 GitHub 官方稳定版…") }
        prefs.edit().putLong("checked", now).apply()
        scope.launch {
            try {
                val abi = store.abi()
                val release = OfficialMihomoRelease.latest()
                val tag = release.getString("tag_name")
                if (MihomoRelease.compare(tag, store.slots().newest) <= 0) {
                    report("没有更新（官方稳定版 $tag）")
                } else if (!automatic || mutableState.value.automatic) {
                    val asset = OfficialMihomoRelease.select(release, abi)
                    mutableState.update { it.copy(result = "正在下载并校验 $tag…") }
                    val (file, ref) = OfficialMihomoRelease.download(asset, tag, abi, store.directory)
                    try {
                        synchronized(store.lock) {
                            if (automatic && !mutableState.value.automatic) report("自动更新已关闭，丢弃本次下载")
                            else {
                                val slots = store.publish(file, ref)
                                refreshSlots(slots)
                                if (slots.pending == ref) report("$tag 校验通过，下次启动内核时尝试切换")
                                else report("$tag 校验通过，但当前内核状态已变更，未安排切换")
                            }
                        }
                    } finally { file.delete() }
                } else report("自动更新已关闭")
            } catch (e: Exception) {
                report("更新失败：${e.localizedMessage ?: e.javaClass.simpleName}")
            } finally {
                mutableState.update { it.copy(busy = false) }
                checking.set(false)
            }
        }
    }

    fun useBundled() {
        if (!checking.compareAndSet(false, true)) return
        mutableState.update { it.copy(busy = true) }
        scope.launch {
            try {
                synchronized(store.lock) {
                    val next = store.slots().copy(pending = null, rollback = true)
                    store.save(next)
                    setAutomatic(false)
                    refreshSlots(next)
                    report("已关闭自动更新，下次启动内核时使用内置版")
                }
            } catch (e: Exception) {
                report("无法安排回退：${e.message}")
            } finally {
                mutableState.update { it.copy(busy = false) }
                checking.set(false)
            }
        }
    }

    fun refreshSlots(slots: KernelSlots) {
        mutableState.update { it.copy(current = slots.current, pending = slots.pending?.tag, rollback = slots.rollback) }
    }

    fun report(message: String) {
        prefs.edit().putString("result", message).apply()
        mutableState.update { it.copy(result = message) }
        LogBus.info("MIHOMO", message)
    }
}
