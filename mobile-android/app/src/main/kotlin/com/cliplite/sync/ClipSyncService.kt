package com.cliplite.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.cliplite.sync.engine.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 前台服务：常驻通知保活 + 后台轮询剪贴板。
 *
 * 目标：用户在微信等 App 复制文字/图片后，即使 ClipLiteSync 未打开（退到后台），
 * 也能自动读到剪贴板并上行同步到 PC 端（仅上行；下行仍由用户开 App 自行复制）。
 *
 * 注意：Android 10+ 后台读剪贴板受平台限制，本服务在前台服务状态下轮询
 * ClipboardManager，部分国产 ROM（如 MIUI）可能返回空/旧值——这是平台约束，
 * 若实测拿不到内容，需改用无障碍服务（AccessibilityService）监听剪贴板变化。
 */
class ClipSyncService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null

    private val engine
        get() = (application as ClipSyncApp).container.engine

    override fun onCreate() {
        super.onCreate()
        AppLog.i("service", "onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())
        AppLog.i("service", "onStartCommand -> startForeground")

        // 拉起引擎连接（幂等，若已在跑则复用）
        engine.start()

        // 后台轮询剪贴板：每 2s 读一次，有变化且非回环则上行
        if (pollJob?.isActive != true) {
            pollJob = scope.launch {
                while (isActive) {
                    try {
                        engine.uploadClipboardOnce()
                    } catch (e: Exception) {
                        AppLog.e("service", "轮询上行异常: ${e.javaClass.simpleName}: ${e.message}")
                    }
                    delay(POLL_INTERVAL_MS)
                }
            }
        }
        // START_STICKY：被系统杀掉后尝试重建（国产 ROM 不保证）
        return START_STICKY
    }

    override fun onDestroy() {
        AppLog.i("service", "onDestroy")
        pollJob?.cancel()
        // 引擎是否停止由 Activity 生命周期决定；服务销毁时不停引擎，
        // 避免与前台 Activity 的 onResume/onPause 争抢连接状态。
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "剪贴板同步", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "后台保持剪贴板同步到其他设备" }
            nm.createNotificationChannel(ch)
        }
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("ClipLiteSync 同步中")
            .setContentText("后台监听剪贴板，复制即同步到其他设备")
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "clipsync_background"
        private const val NOTIF_ID = 1001
        private const val POLL_INTERVAL_MS = 2_000L

        /** 显式启动前台服务（避免隐式启动异常） */
        fun start(context: Context) {
            val i = Intent(context, ClipSyncService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ClipSyncService::class.java))
        }
    }
}
