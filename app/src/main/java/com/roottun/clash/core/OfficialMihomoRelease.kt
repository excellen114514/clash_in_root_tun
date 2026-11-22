package com.roottun.clash.core

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.URL
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import javax.net.ssl.HttpsURLConnection

internal object OfficialMihomoRelease {
    private const val API = "https://api.github.com/repos/MetaCubeX/mihomo/releases/latest"
    private val assetHosts = setOf("release-assets.githubusercontent.com", "objects.githubusercontent.com")
    data class Asset(val url: String, val hash: String, val size: Long)

    fun latest(): JSONObject = fetch(API, asset = false) { input ->
        val bytes = ByteArrayOutputStream()
        MihomoRelease.copyLimited(input, bytes, 2L * 1024 * 1024)
        JSONObject(bytes.toString("UTF-8")).also {
            require(!it.getBoolean("draft") && !it.getBoolean("prerelease") && MihomoRelease.validTag(it.getString("tag_name"))) {
                "官方接口没有返回正式稳定版"
            }
        }
    }

    fun select(release: JSONObject, abi: String): Asset {
        val tag = release.getString("tag_name")
        val name = MihomoRelease.assetName(tag, abi)
        val assets = release.getJSONArray("assets")
        val asset = (0 until assets.length()).asSequence().map { assets.getJSONObject(it) }
            .filter { it.getString("name") == name }.singleOrNull() ?: error("官方发布缺少唯一的 $name")
        val url = "https://github.com/MetaCubeX/mihomo/releases/download/$tag/$name"
        require(asset.getString("browser_download_url") == url && asset.getString("state") == "uploaded") { "官方资产地址或状态异常" }
        val hash = MihomoRelease.releaseDigest(asset.optString("digest"))
        val size = asset.getLong("size")
        require(size in 1..MihomoRelease.MAX_ARCHIVE) { "官方资产大小不在允许范围内" }
        return Asset(url, hash, size)
    }

    fun download(asset: Asset, tag: String, abi: String, directory: File): Pair<File, KernelRef> {
        // 固定临时文件名：进程被杀后再次更新会复用清理，不会累积半成品。仅单个下载任务进入这里。
        val archive = File(directory, "download.gz.part")
        val candidate = File(directory, "candidate.part")
        var complete = false
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            fetch(asset.url, asset = true) { input ->
                archive.outputStream().use { require(MihomoRelease.copyLimited(input, it, asset.size, digest) == asset.size) { "下载不完整" } }
            }
            require(MihomoRelease.hex(digest.digest()) == asset.hash) { "压缩包 SHA-256 与官方发布不符" }
            val raw = MessageDigest.getInstance("SHA-256")
            MihomoKernelStore.writeSynced(candidate) { output ->
                archive.inputStream().buffered().use { file -> GZIPInputStream(file).use {
                    MihomoRelease.copyLimited(it, output, MihomoRelease.MAX_ELF, raw)
                } }
            }
            require(MihomoRelease.elfAbi(candidate) == abi) { "候选 ELF 与安装 ABI 不符" }
            val ref = KernelRef(tag, MihomoRelease.hex(raw.digest()))
            complete = true
            return candidate to ref
        } finally {
            archive.delete()
            if (!complete) candidate.delete()
        }
    }

    private fun <T> fetch(address: String, asset: Boolean, read: (InputStream) -> T): T {
        var url = URL(address)
        repeat(5) {
            val connection = url.openConnection() as HttpsURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.connectTimeout = 12_000
                connection.readTimeout = 20_000
                connection.setRequestProperty("User-Agent", "RootTunClash-MihomoUpdater")
                connection.setRequestProperty("Accept-Encoding", "identity")
                connection.setRequestProperty("Accept", if (asset) "application/octet-stream" else "application/vnd.github+json")
                when (val status = connection.responseCode) {
                    200 -> return connection.inputStream.use(read)
                    301, 302, 303, 307, 308 -> {
                        val next = URL(url, connection.getHeaderField("Location") ?: error("缺少重定向地址"))
                        require(asset && next.protocol == "https" && next.host in assetHosts && next.port in setOf(-1, 443) && next.userInfo == null) {
                            "已拒绝非官方发布资产重定向"
                        }
                        url = next
                    }
                    else -> error("GitHub 响应 HTTP $status")
                }
            } finally { connection.disconnect() }
        }
        error("GitHub 资产重定向次数过多")
    }
}
