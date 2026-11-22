package com.roottun.clash.core

import java.util.concurrent.TimeUnit

/**
 * Shizuku UserService：运行在 Shizuku 的 root/shell 身份下。
 *
 * 仅用于应用列表等 shell 级查询；TUN/路由仍由 root 后端负责。
 * 由 Shizuku 以本 APK 中的类启动，不在 AndroidManifest 中注册为普通 Service。
 */
class ShizukuUserService : IShizukuService.Stub() {

    override fun exec(command: String): String {
        val result = runCommand(command)
        return (result.stdout + result.stderr).joinToString("\n")
    }

    override fun listPackages(userId: Int): List<String> =
        runCommand("pm list packages -U --user $userId").stdout

    override fun listSystemPackages(userId: Int): List<String> =
        runCommand("pm list packages -s --user $userId").stdout

    private data class CommandResult(val stdout: List<String>, val stderr: List<String>)

    private fun runCommand(command: String): CommandResult {
        val process = ProcessBuilder("sh", "-c", command)
            .redirectErrorStream(false)
            .start()
        val stdout = mutableListOf<String>()
        val stderr = mutableListOf<String>()
        val outThread = pump(process.inputStream, stdout)
        val errThread = pump(process.errorStream, stderr)
        val finished = process.waitFor(20, TimeUnit.SECONDS)
        if (!finished) {
            runCatching { process.destroyForcibly() }
        }
        outThread.join(1_500)
        errThread.join(1_500)
        return CommandResult(stdout.toList(), stderr.toList())
    }

    private fun pump(stream: java.io.InputStream, sink: MutableList<String>): Thread {
        val thread = Thread({
            runCatching {
                stream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        synchronized(sink) { sink.add(line) }
                    }
                }
            }
        }, "shizuku-user-service")
        thread.isDaemon = true
        thread.start()
        return thread
    }
}
