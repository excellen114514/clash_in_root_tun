package com.roottun.clash.core

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.roottun.clash.MainActivity
import com.roottun.clash.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * 仅用于「保活」的前台服务：让应用进程（以及它通过 su 拉起的 mihomo 子进程）
 * 在界面进入后台后不被系统立即回收。它不创建任何 VPN，也不持有任何网络连接。
 */
class ProxyForegroundService : Service() {

    companion object {
        const val ACTION_START = "com.roottun.clash.action.PROXY_START"
        const val ACTION_STOP = "com.roottun.clash.action.PROXY_STOP"

        private const val CHANNEL_ID = "root_tun_clash_status"
        private const val CHANNEL_NAME = "代理运行状态"
        private const val NOTIFICATION_ID = 0x1001

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, ProxyForegroundService::class.java)) }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var trafficJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelfSafely()
            return START_NOT_STICKY
        }
        return try {
            ensureChannel()
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            }
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
            observeTraffic()
            LogBus.debug("SERVICE", "前台服务已进入前台")
            START_STICKY
        } catch (t: Throwable) {
            LogBus.warn("SERVICE", "startForeground 失败：${t.javaClass.simpleName}: ${t.message}")
            stopSelfSafely()
            START_NOT_STICKY
        }
    }

    override fun onDestroy() {
        trafficJob?.cancel()
        trafficJob = null
        scope.cancel()
        LogBus.debug("SERVICE", "前台服务已销毁")
        runCatching {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        }
        super.onDestroy()
    }

    /** 设置开关或流量变化时刷新通知；关闭开关后恢复默认文案。 */
    private fun observeTraffic() {
        if (trafficJob?.isActive == true) return
        val core = ProxyCore.get(this)
        trafficJob = scope.launch {
            combine(AppSettings.state, core.traffic) { settings, traffic ->
                settings.showTrafficNotification to traffic
            }.collect { (showTraffic, traffic) ->
                val manager = getSystemService(NotificationManager::class.java) ?: return@collect
                runCatching {
                    manager.notify(NOTIFICATION_ID, buildNotification(showTraffic, traffic))
                }.onFailure {
                    LogBus.debug("SERVICE", "刷新流量通知失败：${it.message}")
                }
            }
        }
    }

    private fun stopSelfSafely() {
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "显示 Root TUN 代理内核是否在运行"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(
        showTraffic: Boolean = false,
        traffic: TrafficSnapshot = TrafficSnapshot(),
    ): android.app.Notification {
        val text = if (showTraffic) {
            if (traffic.running) {
                "↑ ${formatBytes(traffic.uploadTotal)}  ↓ ${formatBytes(traffic.downloadTotal)}"
            } else {
                "正在读取流量…"
            }
        } else {
            "mihomo 内核已通过 su 启动，TUN 流量接管中"
        }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_shield)
            .setContentTitle("Root TUN 代理运行中")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        if (showTraffic && traffic.running) {
            builder.setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "上传 ${formatBytes(traffic.uploadTotal)}（${formatRate(traffic.uploadRate)}）\n" +
                        "下载 ${formatBytes(traffic.downloadTotal)}（${formatRate(traffic.downloadRate)}）",
                ),
            )
        }
        return builder.build()
    }
}
