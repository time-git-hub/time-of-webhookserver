package com.time.webhook

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import java.net.NetworkInterface

class MonitorService : Service() {
    private lateinit var httpsServer: HttpsServer
    private var feishuNotifier: FeishuNotifier? = null
    private lateinit var config: Config
    private var lastIpv4 = ""
    private var lastIpv6 = ""
    private lateinit var wakeLock: PowerManager.WakeLock
    private var ipCheckThread: Thread? = null

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "monitor_service"
        private const val NOTIFICATION_ID = 1
        private const val TAG = "MonitorService"
        private const val WAKELOCK_TAG = "MonitorService:WakeLock"
        @Volatile
        private var instance: MonitorService? = null

        fun isHttpsServerRunning(): Boolean {
            return instance?.let { service ->
                service.run {
                    ::httpsServer.isInitialized && httpsServer.isHttpsServerRunning()
                }
            } ?: false
        }
    }

    override fun onCreate() {
        super.onCreate()
        config = Config.getInstance(this)
        createNotificationChannel()
        startForegroundService()
        acquireWakeLock()
        instance = this
        config.webhookUrl?.let { webhook ->
            if (webhook.isNotEmpty()) {
                try {
                    feishuNotifier = FeishuNotifier(webhook, config.serverPort)
                    Log.i(TAG, "飞书通知器初始化成功")
                } catch (e: Exception) {
                    Log.w(TAG, "飞书通知器初始化失败，但不影响主服务", e)
                }
            }
        }

        httpsServer = HttpsServer(this, config.serverPort)
        try {
            httpsServer.start()
            startIpCheckLoop()
            Log.i(TAG, "服务启动成功，端口: ${config.serverPort},用户名：${config.username}")
        } catch (e: Exception) {
            Log.e(TAG, "服务启动失败，将在5秒后重试", e)
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    httpsServer = HttpsServer(this, config.serverPort)
                    httpsServer.start()
                    startIpCheckLoop()
                } catch (e: Exception) {
                    Log.e(TAG, "重试失败", e)
                }
            }, 5000)
        }
    }

    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                WAKELOCK_TAG
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
            Log.i(TAG, "WakeLock acquired successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire WakeLock", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "system info",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "running"
                setShowBadge(false)
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun startForegroundService() {
        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("system info")
            .setContentText("Running")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun startIpCheckLoop() {
        ipCheckThread = Thread {
            while (!Thread.currentThread().isInterrupted) {
                try {
                    checkAndNotifyIpChange()
                    Thread.sleep(60000) // 每分钟检查一次
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "IP检查失败", e)
                    Thread.sleep(5000) // 出错后等待5秒再试
                }
            }
        }.apply { start() }
    }

    private fun checkAndNotifyIpChange() {
        val currentIpv4 = getIpAddress(false)
        val currentIpv6 = getIpAddress(true)

        if (currentIpv4 != lastIpv4 || currentIpv6 != lastIpv6) {
            // 只在飞书通知器存在时发送通知
            feishuNotifier?.let {
                try {
                    it.sendIpUpdate(currentIpv4, currentIpv6)

                } catch (e: Exception) {
                    Log.w(TAG, "发送飞书通知失败，但不影响服务运行", e)
                }
            }

            // 无论是否有飞书通知器，都更新IP记录
            lastIpv4 = currentIpv4
            lastIpv6 = currentIpv6

        }
    }

    private fun getIpAddress(ipv6: Boolean): String {
        try {
            NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { networkInterface ->
                if (!networkInterface.isLoopback && networkInterface.isUp) {
                    networkInterface.inetAddresses.toList()
                        .filter { address ->
                            !address.isLoopbackAddress &&
                                    (ipv6 == address.hostAddress?.contains(':')) &&
                                    (!ipv6 || // IPv4 不需要额外过滤
                                            (!address.isLinkLocalAddress && // 过滤本地链路地址
                                                    !address.isSiteLocalAddress && // 过滤站点本地地址
                                                    !address.hostAddress.startsWith("fc") && // 过滤ULA地址
                                                    !address.hostAddress.startsWith("fd")))
                        }
                        .firstOrNull()?.hostAddress?.let { addr ->

                            return if (ipv6 && addr.contains('%')) {
                                addr.substring(0, addr.indexOf('%'))
                            } else {
                                addr
                            }
                        }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "获取IP地址失败", e)
        }
        return if (ipv6) "::" else "0.0.0.0"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!::httpsServer.isInitialized || !httpsServer.isAlive) {
            try {
                httpsServer = HttpsServer(this, config.serverPort)
                httpsServer.start()
                Log.i(TAG, "HTTPS服务重启成功，端口: ${config.serverPort}")
            } catch (e: Exception) {
                Log.e(TAG, "HTTPS服务启动失败", e)
            }
        }

        // 检查IP检查线程
        if (ipCheckThread?.isAlive != true) {
            startIpCheckLoop()
        }

        Log.i(TAG, "服务状态 - HTTPS服务: ${if (::httpsServer.isInitialized && httpsServer.isAlive) "运行中" else "未运行"}")
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        try {
            if (::wakeLock.isInitialized && wakeLock.isHeld) {
                wakeLock.release()
                Log.i(TAG, "WakeLock released")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing WakeLock", e)
        }
        ipCheckThread?.interrupt()
        if (::httpsServer.isInitialized) {
            httpsServer.stop()
        }
        Log.i(TAG, "服务已停止")

        // 在服务被杀死时尝试重启
        val intent = Intent(this, MonitorService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}