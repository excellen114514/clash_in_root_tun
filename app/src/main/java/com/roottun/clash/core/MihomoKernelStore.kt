package com.roottun.clash.core

import android.content.Context
import android.os.Build
import android.system.Os
import android.system.OsConstants
import java.io.ByteArrayOutputStream
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

internal data class KernelRef(val tag: String, val hash: String) {
    init {
        require(MihomoRelease.validTag(tag) && MihomoRelease.hashPattern.matches(hash)) { "内核记录无效" }
    }
}

internal data class KernelSlots(
    val active: KernelRef? = null,
    val pending: KernelRef? = null,
    val rollback: Boolean = false,
    val previous: KernelRef? = null,
    val rejected: String? = null,
) {
    val current: String get() = active?.tag ?: MihomoRelease.BUNDLED
    val newest: String get() = listOfNotNull(current, pending?.tag, previous?.tag, rejected, MihomoRelease.BUNDLED)
        .maxWith(MihomoRelease::compare)

    fun bootOrder(): List<KernelRef?> {
        val newerThanBundled = listOfNotNull(active, previous).distinct()
            .filter { MihomoRelease.compare(it.tag, MihomoRelease.BUNDLED) > 0 }
        if (rollback) return listOf(null) + newerThanBundled
        val candidate = pending?.takeIf { MihomoRelease.compare(it.tag, current) > 0 &&
            MihomoRelease.compare(it.tag, MihomoRelease.BUNDLED) > 0 }
        return if (active == null) listOfNotNull(candidate) + listOf(null) + newerThanBundled
            else (listOfNotNull(candidate) + newerThanBundled).distinct() + listOf(null)
    }

    fun afterBoot(installed: KernelRef?, attempted: KernelSlots, failures: Set<KernelRef>): KernelSlots {
        // Quarantine a failed update only after another release proved usable with this startup.
        val failedTags = listOfNotNull(rejected, attempted.pending?.takeIf { it in failures }?.tag)
            .maxWithOrNull(MihomoRelease::compare)
        return copy(
            active = installed,
            pending = pending?.takeUnless { it == installed || it in failures },
            rollback = rollback && !attempted.rollback,
            previous = if (active == installed) previous?.takeUnless { it in failures }
                else listOfNotNull(active, previous).firstOrNull { it != installed && it !in failures },
            rejected = failedTags,
        )
    }
}

/** 文件按解压后的摘要命名；先持久化文件，再原子写入引用。网络请求不持有切换锁。 */
internal class MihomoKernelStore(private val context: Context) {
    val lock = Any()
    val bundled get() = File(context.applicationInfo.nativeLibraryDir, "libmihomo.so")
    val directory: File get() = File(context.noBackupFilesDir, "mihomo-releases").also {
        check(it.isDirectory || it.mkdirs() || it.isDirectory) { "无法创建内核更新目录" }
    }
    private val manifest get() = File(directory, "slots.json")
    private var bootRefs: List<KernelRef> = emptyList()

    fun beginBoot(): KernelSlots = synchronized(lock) {
        slots().also { bootRefs = it.bootOrder().filterNotNull() }
    }

    fun endBoot() = synchronized(lock) {
        bootRefs = emptyList()
        prune(slots())
    }
    fun blob(ref: KernelRef) = File(directory, "core-${ref.hash}")

    fun abi(): String = MihomoRelease.elfAbi(bundled).also {
        require(it in Build.SUPPORTED_ABIS) { "APK 内核与设备 ABI 不符：$it" }
    }

    fun slots(): KernelSlots = synchronized(lock) {
        val json = try {
            manifest.inputStream().use { input ->
                val text = ByteArrayOutputStream()
                MihomoRelease.copyLimited(input, text, 4096)
                JSONObject(text.toString("UTF-8"))
            }
        } catch (_: FileNotFoundException) {
            return@synchronized KernelSlots()
        } catch (e: Exception) {
            LogBus.warn("MIHOMO", "无法读取更新记录，将使用内置内核：${e.message}")
            return@synchronized KernelSlots()
        }
        fun ref(key: String): KernelRef? = json.optJSONObject(key)?.let {
            runCatching { KernelRef(it.getString("tag"), it.getString("sha256")) }.getOrNull()
        }
        KernelSlots(ref("active"), ref("pending"), json.optBoolean("rollback", false), ref("previous"),
            json.optString("rejected").takeIf(MihomoRelease::validTag))
    }

    fun save(slots: KernelSlots) = synchronized(lock) {
        fun KernelRef.json() = JSONObject().put("tag", tag).put("sha256", hash)
        val bytes = JSONObject().put("active", slots.active?.json()).put("pending", slots.pending?.json())
            .put("rollback", slots.rollback).put("previous", slots.previous?.json())
            .put("rejected", slots.rejected).toString().toByteArray(Charsets.UTF_8)
        val temp = File(directory, "slots.json.part")
        try {
            writeSynced(temp) { it.write(bytes) }
            atomicReplace(temp, manifest)
        } finally { temp.delete() }
        prune(slots)
    }

    fun prune(slots: KernelSlots) = synchronized(lock) {
        val keep = (listOfNotNull(slots.active, slots.pending, slots.previous) + bootRefs).map { "core-${it.hash}" }.toSet()
        directory.listFiles()?.forEach {
            if (it.name.startsWith("core-") && MihomoRelease.hashPattern.matches(it.name.removePrefix("core-")) && it.name !in keep) it.delete()
        }
    }

    fun publish(candidate: File, ref: KernelRef): KernelSlots = synchronized(lock) {
        val before = slots()
        if (before.rollback || MihomoRelease.compare(ref.tag, before.newest) <= 0) return@synchronized before
        atomicReplace(candidate, blob(ref))
        before.copy(pending = ref).also { save(it) }
    }

    companion object {
        fun atomicReplace(source: File, target: File) {
            Files.move(source.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            // 先落盘目录项，再提交指向它的记录。
            val fd = Os.open(target.parent!!, OsConstants.O_RDONLY, 0)
            try { Os.fsync(fd) } finally { Os.close(fd) }
        }

        fun executable(file: File) {
            Os.chmod(file.path, 0x140) // 0500；应用 UID 在某些 Android 版本上不能直接执行私有目录文件。
            check(Os.stat(file.path).st_mode and 0x1ff == 0x140) { "无法设置内核为仅所有者可读、可执行" }
        }

        fun writeSynced(file: File, write: (FileOutputStream) -> Unit) = FileOutputStream(file).use {
            write(it)
            it.fd.sync()
        }
    }
}
