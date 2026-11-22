package com.roottun.clash.core

import android.content.Context
import java.io.File
import java.nio.file.Files

class MihomoInstaller(private val context: Context, private val root: RootShell) {
    companion object { private const val TAG = "MIHOMO" }
    data class Installation(val version: String, val binaryPath: String, val abi: String, val copied: Boolean)
    class InstallException(message: String) : Exception(message)

    internal fun beginBoot(): KernelSlots {
        check(Workspace.ensureDirs()) { "无法创建内核工作目录" }
        ensureGeodata()
        return MihomoUpdates.store.beginBoot()
    }

    internal fun install(ref: KernelRef?): Installation {
        val store = MihomoUpdates.store
        val source = ref?.let(store::blob) ?: store.bundled
        val tag = ref?.tag ?: MihomoRelease.BUNDLED
        val hash = ref?.hash ?: MihomoRelease.sha256(source)
        return install(source, tag, hash, store.abi())
    }

    internal fun confirmBoot(attempted: KernelSlots, installed: KernelRef?, failures: Set<KernelRef>) {
        val store = MihomoUpdates.store
        synchronized(store.lock) {
            val slots = store.slots()
            val next = slots.afterBoot(installed, attempted, failures)
            if (next != slots) store.save(next)
            MihomoUpdates.refreshSlots(next)
            if (failures.isNotEmpty()) {
                MihomoUpdates.report("${failures.joinToString("、") { it.tag }} 未通过启动验证；已回退至 ${next.current}")
            } else if (next.current != slots.current || attempted.rollback) {
                MihomoUpdates.report("已启动并切换至 ${next.current}")
            }
        }
    }

    internal fun endBoot() = MihomoUpdates.store.endBoot()

    private fun install(source: File, tag: String, hash: String, abi: String): Installation {
        val target = Workspace.binFile
        val matches = target.isFile && runCatching { MihomoRelease.sha256(target) == hash }.getOrDefault(false)
        val trial = if (matches) target else File(target.parentFile, ".mihomo-install.part")
        try {
            if (!matches) {
                Files.deleteIfExists(trial.toPath())
                Files.createFile(trial.toPath())
                MihomoKernelStore.writeSynced(trial) { output -> source.inputStream().use {
                    MihomoRelease.copyLimited(it, output, MihomoRelease.MAX_ELF)
                } }
                require(MihomoRelease.sha256(trial) == hash) { "内核 SHA-256 校验失败" }
            }
            require(MihomoRelease.elfAbi(trial) == abi) { "内核 ELF ABI 不匹配" }
            MihomoKernelStore.executable(trial)
            val path = Workspace.shellQuote(trial.absolutePath)
            val result = root.exec("test -x $path && $path -v 2>&1", timeoutMillis = 25_000, tag = TAG)
            val line = result.stdout.firstOrNull { it.isNotBlank() }.orEmpty().trim()
            require(result.ok && Regex("^Mihomo(?: Meta)?\\s+${Regex.escape(tag)}\\s+android(?:\\s|$)", RegexOption.IGNORE_CASE).containsMatchIn(line)) {
                "内核 -v 自检失败或版本不符（期望 $tag，exit=${result.code}）"
            }
            if (!matches) MihomoKernelStore.atomicReplace(trial, target)
            return Installation(line, target.absolutePath, abi, !matches)
        } finally {
            if (!matches) trial.delete()
        }
    }

    private fun ensureGeodata() {
        copyAssetIfNeeded("geodata/geoip.metadb", File(Workspace.homeDir, "geoip.metadb"), 1_000_000L)
        copyAssetIfNeeded("geodata/geosite.dat", File(Workspace.homeDir, "geosite.dat"), 500_000L)
    }

    private fun copyAssetIfNeeded(assetPath: String, target: File, minSize: Long) {
        if (target.exists() && target.length() > minSize) {
            LogBus.debug(TAG, "地理库已存在：${target.name}（${target.length()} bytes）")
            return
        }
        try {
            context.assets.open(assetPath).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            LogBus.info(TAG, "已释放 ${target.name}（${target.length()} bytes）")
        } catch (t: Exception) {
            LogBus.warn(
                TAG,
                "释放 $assetPath 失败：${t.message}；GEOIP 规则可能不可用，启动时将记录内核报错",
            )
        }
    }
}
