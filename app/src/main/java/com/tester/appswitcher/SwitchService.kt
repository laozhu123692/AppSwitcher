package com.tester.appswitcher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * 承载轮换循环的前台服务。
 *
 * - startForeground() 常驻通知，通知里带「停止」按钮
 * - 循环由 SwitchController 用 Handler 排程（不使用 Thread.sleep）
 * - 起循环前先校验：悬浮窗权限已给 且 悬浮面板已显示；无障碍服务必须已连接
 */
class SwitchService : Service(), SwitchController.Listener {

    private var tearingDown = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
        SwitchController.addListener(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Log.i(TAG, "收到停止命令")
                SwitchController.stop(SwitchController.State.STOPPED, getString(R.string.status_stopped))
                teardown()
            }

            ACTION_PAUSE -> SwitchController.pause()

            ACTION_RESUME -> SwitchController.resume()

            else -> startLoop()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** 用户从最近任务里划掉 App 时不自杀，交给系统与前台服务保活 */
    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.i(TAG, "任务被移除，前台服务继续运行")
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        SwitchController.removeListener(this)
        if (SwitchController.isActive) {
            SwitchController.stop(SwitchController.State.STOPPED, getString(R.string.status_stopped))
        }
        super.onDestroy()
    }

    // -------------------------------------------------------------- 启动循环

    private fun startLoop() {
        val prefs = Prefs(this)

        if (!prefs.hasBothApps) {
            toastAndQuit(R.string.err_pick_apps)
            return
        }
        val a = prefs.packageA
        val b = prefs.packageB
        if (a.isNullOrEmpty() || b.isNullOrEmpty()) {
            toastAndQuit(R.string.err_pick_apps)
            return
        }
        if (a == b) {
            toastAndQuit(R.string.err_same_app)
            return
        }
        if (!AppLoader.isInstalled(this, a) || !AppLoader.isInstalled(this, b)) {
            toastAndQuit(R.string.err_app_missing)
            return
        }

        // 背景启动 Activity（BAL）豁免的第一重：悬浮窗权限 + 悬浮面板正在显示
        if (!Settings.canDrawOverlays(this)) {
            toastAndQuit(R.string.err_overlay_permission)
            return
        }
        if (!FloatingPanel.isVisible) {
            toastAndQuit(R.string.err_overlay_not_shown)
            return
        }
        // 第二重：无障碍服务已绑定
        if (!SwitchAccessibilityService.isRunning) {
            toastAndQuit(R.string.err_accessibility_required)
            return
        }

        startForegroundSafely()

        Log.i(
            TAG,
            "启动轮换：A=$a B=$b interval=${prefs.intervalMs}ms stopMode=${prefs.stopMode} " +
                "rounds=${prefs.stopRounds} minutes=${prefs.stopMinutes} " +
                "overlay=${Settings.canDrawOverlays(this)} panel=${FloatingPanel.isVisible} a11y=${SwitchAccessibilityService.isRunning}"
        )

        SwitchController.start(
            this,
            a,
            b,
            prefs.intervalMs,
            prefs.stopMode,
            prefs.stopRounds,
            prefs.stopMinutes
        )
        updateNotification()
    }

    private fun toastAndQuit(resId: Int) {
        Toast.makeText(this, resId, Toast.LENGTH_LONG).show()
        Log.w(TAG, "拒绝启动：" + getString(resId))
        tearDownOnly()
    }

    private fun startForegroundSafely() {
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                } else {
                    0
                }
            )
        } catch (t: Throwable) {
            Log.e(TAG, "startForeground 失败: ${t.message}")
            Toast.makeText(this, R.string.err_foreground_failed, Toast.LENGTH_LONG).show()
        }
    }

    /** 只收掉前台服务，不动 SwitchController 的最终状态 */
    private fun tearDownOnly() {
        tearingDown = true
        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        } catch (t: Throwable) {
            Log.w(TAG, "stopForeground 失败: ${t.message}")
        }
        stopSelf()
    }

    /** 收掉前台服务并结束循环 */
    private fun teardown() {
        tearDownOnly()
    }

    // ---------------------------------------------------------- 通知与回调

    override fun onSwitchStateChanged() {
        when (SwitchController.state) {
            SwitchController.State.FINISHED, SwitchController.State.STOPPED -> {
                if (!tearingDown) {
                    toastStateMessage()
                    teardown()
                }
            }

            else -> updateNotification()
        }
    }

    private fun toastStateMessage() {
        val text = SwitchController.statusText
        if (text.isNotEmpty()) {
            Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateNotification() {
        if (tearingDown) return
        if (SwitchController.state == SwitchController.State.IDLE) return
        try {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification())
        } catch (t: Throwable) {
            Log.w(TAG, "更新通知失败: ${t.message}")
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, SwitchService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val pauseIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, SwitchService::class.java).setAction(ACTION_PAUSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_switch)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(SwitchController.notificationText())
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, getString(R.string.action_pause), pauseIntent)
            .addAction(0, getString(R.string.action_stop), stopIntent)

        if (SwitchController.state == SwitchController.State.PAUSED) {
            val resumeIntent = PendingIntent.getService(
                this,
                3,
                Intent(this, SwitchService::class.java).setAction(ACTION_RESUME),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(0, getString(R.string.action_resume), resumeIntent)
        }
        return builder.build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        channel.description = getString(R.string.notif_channel_desc)
        channel.setShowBadge(false)
        nm.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "SwitchService"

        const val ACTION_START = "com.tester.appswitcher.action.START"
        const val ACTION_STOP = "com.tester.appswitcher.action.STOP"
        const val ACTION_PAUSE = "com.tester.appswitcher.action.PAUSE"
        const val ACTION_RESUME = "com.tester.appswitcher.action.RESUME"

        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "app_switcher_running"

        /** 启动轮换循环（前台服务） */
        fun start(context: Context) {
            val intent = Intent(context, SwitchService::class.java).setAction(ACTION_START)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (t: Throwable) {
                Log.e(TAG, "启动前台服务失败: ${t.message}")
                Toast.makeText(context, R.string.err_start_service, Toast.LENGTH_LONG).show()
            }
        }

        /** 停止轮换循环 */
        fun stop(context: Context) {
            try {
                context.startService(Intent(context, SwitchService::class.java).setAction(ACTION_STOP))
            } catch (t: Throwable) {
                Log.w(TAG, "发送停止命令失败，直接结束循环: ${t.message}")
                SwitchController.stop(
                    SwitchController.State.STOPPED,
                    context.getString(R.string.status_stopped)
                )
            }
        }

        fun pause(context: Context) {
            try {
                context.startService(Intent(context, SwitchService::class.java).setAction(ACTION_PAUSE))
            } catch (t: Throwable) {
                SwitchController.pause()
            }
        }

        fun resume(context: Context) {
            try {
                context.startService(Intent(context, SwitchService::class.java).setAction(ACTION_RESUME))
            } catch (t: Throwable) {
                SwitchController.resume()
            }
        }
    }
}
