package com.roottun.clash.core

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.security.MessageDigest

internal object MihomoRelease {
    // 与随 APK 打包的两个 libmihomo.so 一起更新。
    const val BUNDLED = "v1.19.34"
    const val MAX_ARCHIVE = 64L * 1024 * 1024
    const val MAX_ELF = 128L * 1024 * 1024
    val hashPattern = Regex("[0-9a-f]{64}")
    private val tagPattern = Regex("v(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})")

    fun validTag(tag: String) = tagPattern.matches(tag)

    fun compare(a: String, b: String): Int {
        val left = requireNotNull(tagPattern.matchEntire(a)).groupValues
        val right = requireNotNull(tagPattern.matchEntire(b)).groupValues
        for (i in 1..3) {
            val order = left[i].toInt().compareTo(right[i].toInt())
            if (order != 0) return order
        }
        return 0
    }

    fun releaseDigest(value: String): String = Regex("sha256:([0-9a-fA-F]{64})").matchEntire(value)
        ?.groupValues?.get(1)?.lowercase() ?: error("官方资产缺少有效的 SHA-256，拒绝更新")

    fun assetName(tag: String, abi: String): String {
        require(validTag(tag)) { "不是稳定版本号" }
        val arch = when (abi) {
            "arm64-v8a" -> "arm64-v8"
            "x86_64" -> "amd64"
            else -> error("不支持的 Android ABI：$abi")
        }
        return "mihomo-android-$arch-$tag.gz"
    }

    /** 验证 64 位小端 ELF、机器类型及位于可执行 LOAD 段中的入口；不执行文件。 */
    fun elfAbi(file: File): String {
        require(Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS) && file.length() in 64L..MAX_ELF) { "ELF 文件无效" }
        RandomAccessFile(file, "r").use { input ->
            val header = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN)
            input.readFully(header.array())
            require(header.getInt(0) == 0x464c457f && header.get(4).toInt() == 2 &&
                header.get(5).toInt() == 1 && header.get(6).toInt() == 1 && header.getInt(20) == 1 &&
                header.getShort(16).toInt() in 2..3 && header.getShort(52).toInt() == 64) { "不是受支持的 ELF 可执行文件" }
            val abi = when (header.getShort(18).toInt()) {
                183 -> "arm64-v8a"
                62 -> "x86_64"
                else -> error("ELF 机器类型不受支持")
            }
            val entry = header.getLong(24)
            val offset = header.getLong(32)
            val count = header.getShort(56).toInt()
            require(entry > 0 && count in 1..1024 && header.getShort(54).toInt() == 56 &&
                offset in 64L..(input.length() - 56L * count)) { "ELF 程序头无效" }
            input.seek(offset)
            val ph = ByteBuffer.allocate(56).order(ByteOrder.LITTLE_ENDIAN)
            repeat(count) {
                input.readFully(ph.array())
                val start = ph.getLong(16)
                val size = ph.getLong(32)
                if (ph.getInt(0) == 1 && ph.getInt(4) and 1 != 0 && start in 0..entry &&
                    entry - start < size && size in 1..ph.getLong(40) && ph.getLong(8) in 0..(input.length() - size)) return abi
            }
            error("ELF 缺少有效的可执行入口")
        }
    }

    fun copyLimited(input: InputStream, output: OutputStream, limit: Long, digest: MessageDigest? = null): Long {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) return total
            require(n <= limit - total) { "文件超出允许大小" }
            output.write(buffer, 0, n)
            digest?.update(buffer, 0, n)
            total += n
        }
    }

    fun sha256(file: File): String {
        require(Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS) && file.length() in 1..MAX_ELF) { "内核文件无效" }
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(64 * 1024).use { input ->
            val buffer = ByteArray(64 * 1024)
            var left = MAX_ELF
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                require(n <= left) { "内核超出允许大小" }
                md.update(buffer, 0, n)
                left -= n
            }
        }
        return hex(md.digest())
    }

    fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
}
