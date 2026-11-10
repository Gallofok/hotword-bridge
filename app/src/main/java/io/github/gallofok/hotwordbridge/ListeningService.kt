package io.github.gallofok.hotwordbridge

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import kotlin.concurrent.thread
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Foreground microphone service. Feeds 80 ms chunks of 16 kHz audio to
 * [WakeWordDetector] and opens ChatGPT when the score passes the threshold.
 * Audio is processed in memory only; nothing is saved or uploaded.
 */
class ListeningService : Service() {
    companion object {
        const val ACTION_START = "io.github.gallofok.hotwordbridge.START"
        const val ACTION_STOP = "io.github.gallofok.hotwordbridge.STOP"
        const val PREFS = "hotword_prefs"
        const val DEFAULT_THRESHOLD = 0.7f
        private const val ONGOING_ID = 1001
        private const val WAKE_ID = 1002
        private const val LISTENING_CHANNEL = "listening_channel"
        private const val WAKE_CHANNEL = "wake_channel"
        private const val MODEL_DIR = "oww"
        /** After a wake, ignore further triggers for this long. */
        private const val COOLDOWN_MS = 6000L
    }

    @Volatile private var listening = false
    @Volatile private var workerStarted = false
    private val mainThread = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }
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
            ).apply { description = "Microphone is listening locally for your wake word" }
        )
        notifications.createNotificationChannel(
            NotificationChannel(
                WAKE_CHANNEL, "HotwordBridge wake events",
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "Tap to open ChatGPT when wake word is detected" }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            listening = false
            stopSelf()
            return START_NOT_STICKY
        }
        if (workerStarted) return START_NOT_STICKY
        workerStarted = true
        prefs.edit()
            .putLong("session_started_at", System.currentTimeMillis())
            .remove("last_event")
            .remove("last_error")
            .putString("diag_stage", "正在加载模型")
            .putLong("samples_total", 0L)
            .putInt("level_percent", 0)
            .putInt("rms_db", -99)
            .putFloat("score_now", 0f)
            .putFloat("score_recent_max", 0f)
            .putInt("wake_count", 0)
            .putInt("mic_silenced", -1)
            .putString("mic_route", "未获取")
            .apply()
        try {
            val notification = Notification.Builder(this, LISTENING_CHANNEL)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("HotwordBridge 正在监听")
                .setContentText("本地离线识别 Hey ChatGPT")
                .setContentIntent(
                    PendingIntent.getActivity(
                        this, 10, Intent(this, MainActivity::class.java),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                )
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
                    ONGOING_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(ONGOING_ID, notification)
            }
            listening = true
            prefs.edit().putBoolean("running", true).apply()
            thread(name = "hotword-listener", start = true) { listenLoop() }
        } catch (error: Throwable) {
            prefs.edit()
                .putBoolean("running", false)
                .putString("last_error", error.message ?: error.javaClass.simpleName)
                .apply()
            listening = false
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun listenLoop() {
        var detector: WakeWordDetector? = null
        var recorder: AudioRecord? = null
        var failure: String? = null
        var stopAfterWake = false
        try {
            detector = WakeWordDetector(
                assets.open("$MODEL_DIR/melspectrogram.onnx").use { it.readBytes() },
                assets.open("$MODEL_DIR/embedding_model.onnx").use { it.readBytes() },
                assets.open("$MODEL_DIR/hey_chatgpt.onnx").use { it.readBytes() }
            )
            if (!listening) return
            check(
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
            ) { "麦克风权限未获授权" }
            val rate = WakeWordDetector.SAMPLE_RATE
            val minBytes = AudioRecord.getMinBufferSize(
                rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            check(minBytes > 0) { "AudioRecord 缓冲区不可用：$minBytes" }
            val source = if (prefs.getString("audio_source", "VOICE_RECOGNITION") == "MIC") {
                MediaRecorder.AudioSource.MIC
            } else {
                MediaRecorder.AudioSource.VOICE_RECOGNITION
            }
            recorder = AudioRecord(
                source, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBytes * 2, WakeWordDetector.CHUNK * 8)
            )
            check(recorder.state == AudioRecord.STATE_INITIALIZED) { "麦克风初始化失败" }
            recorder.startRecording()
            check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                "麦克风未开始录音"
            }
            prefs.edit().putString("diag_stage", "正在监听").apply()

            val threshold = prefs.getFloat("threshold", DEFAULT_THRESHOLD)
            val keepListening = prefs.getBoolean("keep_listening", false)
            val chunk = ShortArray(WakeWordDetector.CHUNK)
            // (time, score) for the last 5 seconds, numbers only
            val recentScores = ArrayDeque<Pair<Long, Float>>()
            var totalSamples = 0L
            var lastReport = 0L
            var lastWake = 0L
            var wakeCount = 0

            while (listening) {
                var filled = 0
                while (filled < chunk.size && listening) {
                    val count = recorder.read(chunk, filled, chunk.size - filled)
                    if (count < 0) error("录音出错：$count")
                    filled += count
                }
                if (filled < chunk.size) break
                totalSamples += filled.toLong()

                var sumSquare = 0.0
                for (sample in chunk) {
                    val value = sample / 32768.0
                    sumSquare += value * value
                }
                val rms = sqrt(sumSquare / chunk.size)
                val dbfs = if (rms <= 0.000001) -99.0 else 20.0 * log10(rms)

                val score = detector.accept(chunk) ?: 0f
                val now = SystemClock.elapsedRealtime()
                recentScores.addLast(now to score)
                while (recentScores.isNotEmpty() && now - recentScores.first().first > 5000) {
                    recentScores.removeFirst()
                }

                var woke = false
                if (score >= threshold && now - lastWake > COOLDOWN_MS) {
                    lastWake = now
                    wakeCount += 1
                    woke = true
                    val scoreText = String.format(java.util.Locale.US, "%.2f", score)
                    if (keepListening) {
                        detector.reset()
                        mainThread.post { onWakeWord(scoreText, stopAfter = false) }
                    } else {
                        stopAfterWake = true
                        listening = false
                        mainThread.post { onWakeWord(scoreText, stopAfter = true) }
                    }
                }

                if (now - lastReport >= 700 || woke) {
                    lastReport = now
                    val silenced = if (Build.VERSION.SDK_INT >= 29) {
                        try {
                            when (recorder.activeRecordingConfiguration?.isClientSilenced) {
                                true -> 1
                                false -> 0
                                null -> -1
                            }
                        } catch (_: RuntimeException) { -1 }
                    } else -1
                    val route = try {
                        recorder.routedDevice?.productName?.toString() ?: "未知"
                    } catch (_: RuntimeException) { "未知" }
                    prefs.edit()
                        .putLong("samples_total", totalSamples)
                        .putInt("rms_db", dbfs.roundToInt())
                        .putInt("level_percent", ((dbfs + 60.0) * 2.0).roundToInt().coerceIn(0, 100))
                        .putFloat("score_now", score)
                        .putFloat("score_recent_max", recentScores.maxOf { it.second })
                        .putInt("wake_count", wakeCount)
                        .putInt("mic_silenced", silenced)
                        .putString("mic_route", route)
                        .apply()
                }
            }
        } catch (problem: Throwable) {
            failure = problem.message ?: problem.javaClass.simpleName
        } finally {
            listening = false
            try { recorder?.stop() } catch (_: Throwable) { }
            try { recorder?.release() } catch (_: Throwable) { }
            try { detector?.close() } catch (_: Throwable) { }
            prefs.edit().putBoolean("running", false).apply()
            val error = failure
            val wakeHandlesStop = stopAfterWake
            mainThread.post {
                if (error != null) {
                    prefs.edit()
                        .putString("diag_stage", "出错并停止")
                        .putString("last_error", error)
                        .apply()
                }
                // After a wake in single-shot mode, onWakeWord stops the service itself.
                if (!wakeHandlesStop) stopSelf()
            }
        }
    }

    /** Runs on the main thread. */
    private fun onWakeWord(scoreText: String, stopAfter: Boolean) {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        prefs.edit()
            .putString("last_event", "$time 识别到 Hey ChatGPT（分数 $scoreText）")
            .putString("diag_stage", if (stopAfter) "识别成功，已停止" else "识别成功，继续监听")
            .apply()

        // Android only lets an app open another app's screen when it is visible
        // itself, or when the user has granted "display over other apps".
        val mode = prefs.getString("voice_launch_mode", ChatGptLauncher.MODE_AUTO)
            ?: ChatGptLauncher.MODE_AUTO
        val mayLaunch = MainActivity.isForeground || Settings.canDrawOverlays(this)
        var opened = false
        if (mayLaunch) {
            try {
                val report = ChatGptLauncher.launch(this, mode)
                prefs.edit().putString("voice_launch_result", report).apply()
                opened = true
            } catch (error: Exception) {
                prefs.edit().putString(
                    "voice_launch_result", "启动失败：" + (error.message ?: "未知错误")
                ).apply()
            }
        } else {
            prefs.edit().putString(
                "voice_launch_result", "后台命中但没有悬浮窗权限：已发通知，需手动点击"
            ).apply()
        }

        if (!opened) {
            try {
                notifications.notify(
                    WAKE_ID,
                    Notification.Builder(this, WAKE_CHANNEL)
                        .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                        .setContentTitle("已听到 Hey ChatGPT")
                        .setContentText("点击打开 ChatGPT 语音对话")
                        .setContentIntent(ChatGptLauncher.pendingIntent(this, mode))
                        .setAutoCancel(true)
                        .setCategory(Notification.CATEGORY_REMINDER)
                        .build()
                )
            } catch (error: SecurityException) {
                prefs.edit().putString("last_error", "无法显示提醒，请在系统设置中允许通知").apply()
            }
        }
        if (stopAfter) stopSelf()
    }

    override fun onDestroy() {
        listening = false
        prefs.edit().putBoolean("running", false).apply()
        super.onDestroy()
    }
}
