package io.github.gallofok.hotwordbridge

import ai.picovoice.porcupine.Porcupine
import ai.picovoice.porcupine.PorcupineManager
import ai.picovoice.porcupine.PorcupineManagerCallback
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.io.File

class ListeningService : Service() {
    companion object {
        const val ACTION_START = "io.github.gallofok.hotwordbridge.START"
        const val ACTION_STOP = "io.github.gallofok.hotwordbridge.STOP"
        private const val PREFS = "hotword_prefs"
        private const val ONGOING_ID = 1001
        private const val WAKE_ID = 1002
        private const val LISTENING_CHANNEL = "listening_channel"
        private const val WAKE_CHANNEL = "wake_channel"
    }

    private var manager: PorcupineManager? = null
    private var running = false
    private val handler = Handler(Looper.getMainLooper())
    private val preferences by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }
    private val notifications by lazy {
        getSystemService(NOTIFICATION_SERVICE) as NotificationManager
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notifications.createNotificationChannel(
            NotificationChannel(
                LISTENING_CHANNEL, "HotwordBridge microphone",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Ongoing offline hotword recognition" }
        )
        notifications.createNotificationChannel(
            NotificationChannel(
                WAKE_CHANNEL, "HotwordBridge wake events",
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "Tap to open ChatGPT after a detected wake word" }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (running || manager != null) return START_NOT_STICKY

        try {
            val foregroundNotification = Notification.Builder(this, LISTENING_CHANNEL)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("HotwordBridge 正在监听")
                .setContentText("离线唤醒检测 • 点击返回应用")
                .setContentIntent(PendingIntent.getActivity(
                    this, 10, Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                ))
                .setOngoing(true)
                .addAction(
                    android.R.drawable.ic_media_pause, "停止监听",
                    PendingIntent.getService(
                        this, 11,
                        Intent(this, ListeningService::class.java).setAction(ACTION_STOP),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                )
                .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    ONGOING_ID, foregroundNotification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(ONGOING_ID, foregroundNotification)
            }

            val key = preferences.getString("access_key", "").orEmpty()
            require(key.isNotEmpty()) { "缺少 AccessKey" }
            val builder = PorcupineManager.Builder().setAccessKey(key)
            if (preferences.getString("keyword_mode", "custom") == "built_in") {
                builder.setKeyword(Porcupine.BuiltInKeyword.PORCUPINE)
            } else {
                val model = File(filesDir, "wakeword.ppn")
                require(model.isFile) { "缺少 wakeword.ppn" }
                builder.setKeywordPath(model.absolutePath)
            }
            manager = builder.build(this, PorcupineManagerCallback { _ ->
                handler.post { onWakeWord() }
            })
            manager?.start()
            running = true
            preferences.edit().putBoolean("running", true)
                .remove("last_error").apply()
        } catch (error: Exception) {
            preferences.edit().putBoolean("running", false)
                .putString("last_error", error.message ?: error.javaClass.simpleName).apply()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun onWakeWord() {
        if (!running) return
        running = false
        releaseManager()
        preferences.edit()
            .putBoolean("running", false)
            .putString("last_event", "识别成功：已停止监听以释放麦克风")
            .apply()

        // Launch from a visible Activity only. Android limits activity starts
        // while backgrounded or locked; show a user-tappable notification then.
        val opened = if (MainActivity.isForeground) {
            try {
                startActivity(ChatGptLauncher.intent(this))
                true
            } catch (_: Exception) {
                false
            }
        } else false

        if (!opened) {
            try {
                notifications.notify(
                    WAKE_ID,
                    Notification.Builder(this, WAKE_CHANNEL)
                        .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                        .setContentTitle("已听到唤醒词")
                        .setContentText("点击打开 ChatGPT 继续语音对话")
                        .setContentIntent(ChatGptLauncher.pendingIntent(this))
                        .setAutoCancel(true)
                        .setCategory(Notification.CATEGORY_REMINDER)
                        .build()
                )
            } catch (error: SecurityException) {
                preferences.edit()
                    .putString("last_error", "无法显示通知，请开启通知权限")
                    .apply()
            }
        }
        stopSelf()
    }

    private fun releaseManager() {
        val existing = manager ?: return
        manager = null
        try { existing.stop() } catch (_: Exception) { }
        try { existing.delete() } catch (_: Exception) { }
    }

    override fun onDestroy() {
        running = false
        releaseManager()
        preferences.edit().putBoolean("running", false).apply()
        super.onDestroy()
    }
}
