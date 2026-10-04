package com.opencode.android.service

import com.opencode.android.R
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import com.opencode.android.MainActivity

class OpenCodeKeepAliveService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var taskStartTimeMs: Long = 0L
    private var currentStep: String = getString(R.string.svc_001)
    private var currentSessionId: String = ""
    private var isTaskRunning = false

    private val timerRunnable = object : Runnable {
        override fun run() {
            if (isTaskRunning) {
                updateProgressNotification()
                handler.postDelayed(this, 1000L) // 每秒刷新运行时长
            }
        }
    }

    companion object {
        const val CHANNEL_ID_PROGRESS = "opencode_channel_progress"
        const val CHANNEL_ID_ALERT = "opencode_channel_alert"
        const val NOTIFICATION_ID_PROGRESS = 1001
        const val NOTIFICATION_ID_APPROVAL = 1002
        const val NOTIFICATION_ID_COMPLETED = 1003
        // v1.6 P0 任务通知
        const val NOTIFICATION_ID_TASK_FAILED = 1004
        const val NOTIFICATION_ID_WAITING_INPUT = 1005

        private const val ACTION_START = "com.opencode.android.action.START_TASK"
        private const val ACTION_UPDATE = "com.opencode.android.action.UPDATE_TASK"
        private const val ACTION_STOP = "com.opencode.android.action.STOP_TASK"
        private const val ACTION_APPROVAL = "com.opencode.android.action.NOTIFY_APPROVAL"

        private const val EXTRA_STEP = "extra_step"
        private const val EXTRA_TOOL = "extra_tool"
        private const val EXTRA_SESSION = "extra_session"
        // v1.6 P0 任务通知：深链跳转用
        const val EXTRA_OPEN_SESSION = "extra_open_session"

        /**
         * v1.6 P0: 通知内容脱敏——避免泄露代码、Token、Secret。
         */
        fun sanitizeForNotification(text: String, maxLen: Int = 80): String {
            var s = text
            // 脱敏常见密钥模式
            s = s.replace(Regex("(?i)(api[_-]?key|token|secret|password|passwd|sk-)\\s*[:=]\\s*\\S+"), "$1=***")
            s = s.replace(Regex("sk-[A-Za-z0-9-_]{8,}"), "sk-***")
            s = s.replace(Regex("ghp_[A-Za-z0-9]{8,}"), "ghp_***")
            return s.take(maxLen).trim().ifBlank { getString(R.string.svc_002) }
        }

        private fun sessionDeepLinkIntent(context: Context, sessionId: String): PendingIntent {
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                if (sessionId.isNotBlank()) putExtra(EXTRA_OPEN_SESSION, sessionId)
            }
            return PendingIntent.getActivity(
                context, sessionId.hashCode(),
                intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        /**
         * v1.6 P0 任务通知：任务完成（任务名、耗时、修改文件数，点击直达会话）
         */
        fun notifyTaskCompleted(
            context: Context,
            taskName: String,
            durationMs: Long,
            fileCount: Int,
            sessionId: String
        ) {
            vibrateStatic(context, longArrayOf(0, 120, 80, 120))
            val mins = durationMs / 60000
            val secs = (durationMs % 60000) / 1000
            val duration = if (mins > 0) getString(R.string.svc_003, mins, secs) else getString(R.string.svc_004, secs)
            val safeName = sanitizeForNotification(taskName, 40)
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val n = NotificationCompat.Builder(context, CHANNEL_ID_ALERT)
                .setSmallIcon(android.R.drawable.checkbox_on_background)
                .setContentTitle(getString(R.string.svc_005, safeName))
                .setContentText(getString(R.string.svc_006, duration, fileCount))
                .setStyle(NotificationCompat.BigTextStyle().bigText(
                    getString(R.string.svc_007, safeName, duration, fileCount)
                ))
                .setAutoCancel(true)
                .setContentIntent(sessionDeepLinkIntent(context, sessionId))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
            nm.notify(NOTIFICATION_ID_COMPLETED, n)
        }

        /**
         * v1.6 P0 任务通知：任务失败（错误摘要 + 进入会话按钮）
         */
        fun notifyTaskFailed(context: Context, errorSummary: String, sessionId: String) {
            vibrateStatic(context, longArrayOf(0, 200, 100, 200))
            val safeErr = sanitizeForNotification(errorSummary, 100)
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val n = NotificationCompat.Builder(context, CHANNEL_ID_ALERT)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(getString(R.string.svc_008))
                .setContentText(safeErr)
                .setStyle(NotificationCompat.BigTextStyle().bigText(
                    getString(R.string.svc_009, safeErr)
                ))
                .setAutoCancel(true)
                .setContentIntent(sessionDeepLinkIntent(context, sessionId))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
            nm.notify(NOTIFICATION_ID_TASK_FAILED, n)
        }

        /**
         * v1.6 P0 任务通知：AI 等待用户输入（高优先级）
         */
        fun notifyWaitingInput(context: Context, promptSummary: String, sessionId: String) {
            vibrateStatic(context, longArrayOf(0, 250, 100, 250, 100, 250))
            val safePrompt = sanitizeForNotification(promptSummary, 100)
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val n = NotificationCompat.Builder(context, CHANNEL_ID_ALERT)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(getString(R.string.svc_010))
                .setContentText(safePrompt)
                .setStyle(NotificationCompat.BigTextStyle().bigText(
                    getString(R.string.svc_011, safePrompt)
                ))
                .setAutoCancel(true)
                .setContentIntent(sessionDeepLinkIntent(context, sessionId))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
            nm.notify(NOTIFICATION_ID_WAITING_INPUT, n)
        }

        private fun vibrateStatic(context: Context, pattern: LongArray) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                    vm?.defaultVibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1))
                } else {
                    @Suppress("DEPRECATION")
                    val v = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        v?.vibrate(VibrationEffect.createWaveform(pattern, -1))
                    } else {
                        @Suppress("DEPRECATION")
                        v?.vibrate(pattern, -1)
                    }
                }
            } catch (_: Exception) {}
        }

        fun startTaskProgress(context: Context, stepDescription: String, sessionId: String = "") {
            val intent = Intent(context, OpenCodeKeepAliveService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_STEP, stepDescription)
                putExtra(EXTRA_SESSION, sessionId)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e("OpenCodeKeepAlive", "startForegroundService failed: ${e.message}", e)
            }
        }

        fun updateProgress(context: Context, stepDescription: String) {
            val intent = Intent(context, OpenCodeKeepAliveService::class.java).apply {
                action = ACTION_UPDATE
                putExtra(EXTRA_STEP, stepDescription)
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                Log.w("OpenCodeKeepAlive", "service call failed: ${e.message}", e)
            }
        }

        fun stopTaskProgress(context: Context) {
            val intent = Intent(context, OpenCodeKeepAliveService::class.java).apply {
                action = ACTION_STOP
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                Log.w("OpenCodeKeepAlive", "service call failed: ${e.message}", e)
            }
        }

        fun notifyApprovalRequired(context: Context, toolDescription: String) {
            val intent = Intent(context, OpenCodeKeepAliveService::class.java).apply {
                action = ACTION_APPROVAL
                putExtra(EXTRA_TOOL, toolDescription)
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                Log.w("OpenCodeKeepAlive", "service call failed: ${e.message}", e)
            }
        }

        @SuppressLint("BatteryLife")
        fun requestIgnoreBatteryOptimization(context: Context) {
            try {
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                if (powerManager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    if (!powerManager.isIgnoringBatteryOptimizations(context.packageName)) {
                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:${context.packageName}")
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        context.startActivity(intent)
                        return
                    }
                }
            } catch (e: Exception) {
                // fallback to general battery saver settings
                try {
                    val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                } catch (ignored: Exception) {}
            }
        }

        fun requestIgnoreBatteryOptimizations(context: Context) {
            requestIgnoreBatteryOptimization(context)
        }

        fun isIgnoringBatteryOptimizations(context: Context): Boolean {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                powerManager.isIgnoringBatteryOptimizations(context.packageName)
            } else {
                true
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                currentStep = intent.getStringExtra(EXTRA_STEP) ?: getString(R.string.svc_012)
                currentSessionId = intent.getStringExtra(EXTRA_SESSION) ?: ""
                taskStartTimeMs = System.currentTimeMillis()
                isTaskRunning = true
                startForegroundWithServiceType()
                handler.removeCallbacks(timerRunnable)
                handler.post(timerRunnable)
            }
            ACTION_UPDATE -> {
                val step = intent.getStringExtra(EXTRA_STEP)
                if (!step.isNullOrBlank()) {
                    currentStep = step
                    updateProgressNotification()
                }
            }
            ACTION_STOP -> {
                isTaskRunning = false
                handler.removeCallbacks(timerRunnable)
                triggerCompletionFeedback()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
                stopSelf()
            }
            ACTION_APPROVAL -> {
                val toolName = intent.getStringExtra(EXTRA_TOOL) ?: getString(R.string.svc_013)
                triggerApprovalNotificationAndVibration(toolName)
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundWithServiceType() {
        val notification = buildProgressNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID_PROGRESS,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID_PROGRESS, notification)
        }
    }

    private fun updateProgressNotification() {
        if (!isTaskRunning) return
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID_PROGRESS, buildProgressNotification())
    }

    private fun buildProgressNotification(): android.app.Notification {
        val elapsedSec = ((System.currentTimeMillis() - taskStartTimeMs) / 1000).coerceAtLeast(0)
        val minutes = elapsedSec / 60
        val seconds = elapsedSec % 60
        val durationFormatted = String.format("%02d:%02d", minutes, seconds)

        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(getString(R.string.svc_014, durationFormatted))
            .setContentText(currentStep)
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                getString(R.string.svc_015, durationFormatted) +
                (if (currentSessionId.isNotBlank()) getString(R.string.svc_016, currentSessionId.take(8)) else "") +
                getString(R.string.svc_017, currentStep)
            ))
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun triggerCompletionFeedback() {
        // 任务结束：震动提醒与完成通知
        vibrate(longArrayOf(0, 120, 80, 120))

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            1,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val completedNotification = NotificationCompat.Builder(this, CHANNEL_ID_ALERT)
            .setSmallIcon(android.R.drawable.checkbox_on_background)
            .setContentTitle(getString(R.string.svc_018))
            .setContentText(getString(R.string.svc_019))
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        notificationManager.notify(NOTIFICATION_ID_COMPLETED, completedNotification)
    }

    private fun triggerApprovalNotificationAndVibration(toolName: String) {
        // 工具审批：强震动与高优先级弹窗通知
        vibrate(longArrayOf(0, 250, 100, 250, 100, 300))

        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            2,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val approvalNotification = NotificationCompat.Builder(this, CHANNEL_ID_ALERT)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(getString(R.string.svc_020))
            .setContentText(getString(R.string.svc_021, toolName))
            .setStyle(NotificationCompat.BigTextStyle().bigText(getString(R.string.svc_022, toolName)))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID_APPROVAL, approvalNotification)
    }

    private fun vibrate(pattern: LongArray) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(pattern, -1)
                }
            }
        } catch (e: Exception) {
            // ignore if device has no vibrator
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val progressChannel = NotificationChannel(
                CHANNEL_ID_PROGRESS,
                getString(R.string.svc_023),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.svc_024)
                enableVibration(false)
            }

            val alertChannel = NotificationChannel(
                CHANNEL_ID_ALERT,
                getString(R.string.svc_025),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.svc_026)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 250, 100, 250)
            }

            notificationManager.createNotificationChannel(progressChannel)
            notificationManager.createNotificationChannel(alertChannel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        isTaskRunning = false
        handler.removeCallbacks(timerRunnable)
    }
}
