package com.roottun.clash.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MihomoReleaseTest {
    @Test fun stableTagsAndAbiMustMatchExactly() {
        assertTrue(MihomoRelease.compare("v1.20.0", "v1.9.99") > 0)
        assertEquals(0, MihomoRelease.compare("v2.0.0", "v2.0.0"))
        listOf("v1.20.0-rc.1", "latest", "v01.2.3", "v2.0.0/asset").forEach { assertFalse(MihomoRelease.validTag(it)) }
        assertEquals("mihomo-android-arm64-v8-v1.20.0.gz", MihomoRelease.assetName("v1.20.0", "arm64-v8a"))
        assertEquals("mihomo-android-amd64-v1.20.0.gz", MihomoRelease.assetName("v1.20.0", "x86_64"))
        assertThrows(IllegalStateException::class.java) { MihomoRelease.assetName("v1.20.0", "armeabi-v7a") }
    }

    @Test fun requireAnExplicitSha256ReleaseDigest() {
        val hash = "ab".repeat(32)
        assertEquals(hash, MihomoRelease.releaseDigest("sha256:${hash.uppercase()}"))
        listOf("", hash, "sha1:$hash", "sha256:${hash}ab", "sha256:${"z".repeat(64)}").forEach {
            assertThrows(IllegalStateException::class.java) { MihomoRelease.releaseDigest(it) }
        }
    }

    @Test fun boundedCopyNeverWritesPastLimit() {
        val source = ByteArray(65537)
        val sink = ByteArrayOutputStream()
        assertThrows(IllegalArgumentException::class.java) { MihomoRelease.copyLimited(source.inputStream(), sink, 65536) }
        assertTrue(sink.size() <= 65536)
        assertEquals(65537L, MihomoRelease.copyLimited(source.inputStream(), ByteArrayOutputStream(), 65537))
    }

    @Test fun requireExecutableSegmentAndDetectSameLengthChanges() {
        val bytes = ByteBuffer.allocate(120).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0, 0x464c457f); put(4, 2); put(5, 1); put(6, 1); putShort(16, 3); putShort(18, 183)
            putInt(20, 1); putLong(24, 64); putLong(32, 64); putShort(52, 64); putShort(54, 56); putShort(56, 1)
            putInt(64, 1); putInt(68, 5); putLong(96, 120); putLong(104, 120)
        }
        val file: File = Files.createTempFile("mihomo-elf", ".bin").toFile()
        try {
            file.writeBytes(bytes.array())
            assertEquals("arm64-v8a", MihomoRelease.elfAbi(file))
            val first = MihomoRelease.sha256(file)
            bytes.putShort(18, 62); file.writeBytes(bytes.array())
            assertEquals("x86_64", MihomoRelease.elfAbi(file))
            assertNotEquals(first, MihomoRelease.sha256(file))
            bytes.putInt(68, 4); file.writeBytes(bytes.array())
            assertThrows(IllegalStateException::class.java) { MihomoRelease.elfAbi(file) }
            bytes.putInt(68, 5); bytes.putLong(32, Long.MAX_VALUE); file.writeBytes(bytes.array())
            assertThrows(IllegalArgumentException::class.java) { MihomoRelease.elfAbi(file) }
        } finally { file.delete() }
    }
}
