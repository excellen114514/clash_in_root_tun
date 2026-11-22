package com.roottun.clash.core

import android.content.Context
import android.net.LinkAddress
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import java.lang.reflect.Array as ReflectArray
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Shizuku UserService：运行在 Shizuku 的 root/shell 身份下。
 *
 * 应用列表：执行 shell 级 pm 查询。
 * TUN：采用 Shizzi / HotspotXray 同类路径，通过隐藏的
 * android.net.TestNetworkManager.createTunInterface 创建 TestNetwork TUN。
 * 该 TUN 面向 tethering/test network，不是 VpnService；本机应用透明代理仍需 root。
 * 由 Shizuku 以本 APK 中的类启动，不在 AndroidManifest 中注册为普通 Service。
 */
class ShizukuUserService : IShizukuService.Stub() {

    private var tunHandle: Any? = null
    private var tunFd: ParcelFileDescriptor? = null
    private var tunName: String? = null

    // TestNetworkManager 构造时会创建 Handler，必须在有 Looper 的线程上获取。
    private val tunThread by lazy { HandlerThread("ShizukuTun").apply { start() } }
    private val tunHandler by lazy { Handler(tunThread.looper) }
    private fun <T> withTunLooper(block: () -> T): T {
        val latch = CountDownLatch(1)
        var result: Result<T>? = null
        if (!tunHandler.post {
                result = runCatching(block)
                latch.countDown()
            }
        ) {
            error("无法启动 Shizuku TUN 线程")
        }
        if (!latch.await(15, TimeUnit.SECONDS)) error("Shizuku TUN 线程超时")
        return result?.getOrThrow() ?: error("Shizuku TUN 线程没有返回结果")
    }

    private val systemContext: Context by lazy {
        val activityThread = Class.forName("android.app.ActivityThread")
        val systemMain = activityThread.getMethod("systemMain").invoke(null)
        activityThread.getMethod("getSystemContext").invoke(systemMain) as Context
    }

    private val shellContext: Context by lazy {
        runCatching {
            val ctx = systemContext.createPackageContext("com.android.shell", 0)
            // TestNetworkManager 的部分 AppOps 检查依赖 opPackageName；把上下文归因到 shell。
            runCatching {
                val sourceField = ctx.javaClass.getDeclaredField("mAttributionSource")
                sourceField.isAccessible = true
                val current = sourceField.get(ctx)
                val rebased = current.javaClass
                    .getMethod("withPackageName", String::class.java)
                    .invoke(current, "com.android.shell")
                sourceField.set(ctx, rebased)
            }
            ctx
        }.getOrDefault(systemContext)
    }

    override fun exec(command: String): String {
        val result = runCommand(command)
        return (result.stdout + result.stderr).joinToString("\n")
    }

    override fun listPackages(userId: Int): List<String> =
        runCommand("pm list packages -U --user $userId").stdout

    override fun listSystemPackages(userId: Int): List<String> =
        runCommand("pm list packages -s --user $userId").stdout

    override fun probeTun(): String {
        if (Build.VERSION.SDK_INT < 30) return "Android ${Build.VERSION.RELEASE} 不支持 TestNetworkManager（需要 API 30+）"
        return withTunLooper {
            val managerClass = runCatching { Class.forName("android.net.TestNetworkManager") }.getOrNull()
                ?: return@withTunLooper "未找到 android.net.TestNetworkManager"
            val serviceAttempt = runCatching { shellContext.getSystemService("test_network") }
            val manager = serviceAttempt.getOrNull()
                ?: runCatching { systemContext.getSystemService("test_network") }.getOrNull()
                ?: return@withTunLooper "test_network 系统服务不可用（该 ROM 可能精简了 TestNetworkManager" +
                (serviceAttempt.exceptionOrNull()?.let { "；${rootMessage(it)}" } ?: "") + "）"
            val create = managerClass.declaredMethods.any { it.name == "createTunInterface" }
            if (!create) return@withTunLooper "TestNetworkManager 缺少 createTunInterface"
            val tetherClass = runCatching { Class.forName("android.net.TetheringManager") }.getOrNull()
            val prefer = tetherClass?.declaredMethods?.any { it.name == "setPreferTestNetworks" } == true
            val tetherService = runCatching { shellContext.getSystemService("tethering") }.getOrNull() != null
            "TestNetworkManager 可用（API ${Build.VERSION.SDK_INT}，uid=${android.os.Process.myUid()}）；" +
                "setPreferTestNetworks=${if (prefer && tetherService) "可用" else "不可用"}"
        }
    }

    override fun createTun(): String = runCatching { createTunInternal() }
        .getOrElse { "创建测试 TUN 失败：${it.javaClass.simpleName}: ${it.message}" }

    private fun createTunInternal(): String {
        if (tunFd != null) return "TUN 已存在：${tunName}（fd=${tunFd?.fd}）"
        if (Build.VERSION.SDK_INT < 30) error("TestNetworkManager 需要 Android 11+")
        return withTunLooper {
            val managerClass = Class.forName("android.net.TestNetworkManager")
            val manager = runCatching { shellContext.getSystemService("test_network") }
                .getOrElse { throw IllegalStateException("test_network 系统服务不可用：${rootMessage(it)}", it) }
                ?: runCatching { systemContext.getSystemService("test_network") }.getOrNull()
                ?: error("test_network 系统服务不可用（该 ROM 可能精简了 TestNetworkManager）")

            val addressConstructor = LinkAddress::class.java.getDeclaredConstructor(
                InetAddress::class.java,
                Int::class.javaPrimitiveType,
            )
            addressConstructor.isAccessible = true
            val addresses = listOf(
                addressConstructor.newInstance(InetAddress.getByName("192.0.2.2"), 24),
                addressConstructor.newInstance(InetAddress.getByName("2001:db8::2"), 64),
            )

            val handle = invokeCreateTun(managerClass, manager, addresses)
                ?: error("createTunInterface 没有可用重载（Collection/Array）")
            val name = runCatching {
                handle.javaClass.getMethod("getInterfaceName").invoke(handle) as String
            }.getOrNull() ?: "testtun"
            val fd = runCatching {
                handle.javaClass.getMethod("getFileDescriptor").invoke(handle) as ParcelFileDescriptor
            }.getOrNull() ?: error("createTunInterface 未返回 ParcelFileDescriptor")

            tunHandle = handle
            tunFd = fd
            tunName = name
            "TUN 已创建：$name（fd=${fd.fd}）。这是 TestNetwork TUN，不是 VpnService。"
        }
    }

    override fun getTunFd(): ParcelFileDescriptor? = tunFd

    override fun destroyTun(): String {
        val name = tunName
        runCatching { tunFd?.close() }
        tunFd = null
        tunHandle = null
        tunName = null
        return if (name == null) "没有已创建的测试 TUN" else "已销毁测试 TUN：$name"
    }

    private fun invokeCreateTun(
        managerClass: Class<*>,
        manager: Any,
        addresses: List<LinkAddress>,
    ): Any? {
        val collection = runCatching {
            managerClass.getMethod("createTunInterface", Collection::class.java)
                .invoke(manager, addresses)
        }.getOrNull()
        if (collection != null) return collection

        val arrayType = Class.forName("[Landroid.net.LinkAddress;")
        val array = ReflectArray.newInstance(LinkAddress::class.java, addresses.size)
        addresses.forEachIndexed { index, address -> ReflectArray.set(array, index, address) }
        return runCatching {
            managerClass.getMethod("createTunInterface", arrayType).invoke(manager, array)
        }.getOrNull()
    }

    private fun rootMessage(failure: Throwable): String {
        var cause = failure
        while (cause.cause != null && cause.cause !== cause) cause = cause.cause!!
        return "${cause.javaClass.simpleName}: ${cause.message}"
    }

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
