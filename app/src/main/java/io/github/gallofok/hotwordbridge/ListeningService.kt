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
import android.widget.Toast
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sqrt
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.getFeatureConfig
import kotlin.concurrent.thread

class ListeningService : Service() {
    companion object {
        const val ACTION_START = "io.github.gallofok.hotwordbridge.START"
        const val ACTION_STOP = "io.github.gallofok.hotwordbridge.STOP"
        private const val ONGOING_ID = 1001
        private const val WAKE_ID = 1002
        private const val LISTENING_CHANNEL = "listening_channel"
        private const val WAKE_CHANNEL = "wake_channel"
        private const val SAMPLE_RATE = 16000
    }

    @Volatile private var listening = false
    @Volatile private var workerStarted = false
    private val mainThread = Handler(Looper.getMainLooper())
    private var totalSamples = 0L
    private var audioReadCalls = 0L
    private var decodeSteps = 0L
    private var lastReportTime = 0L
    private val prefs by lazy { getSharedPreferences("hotword_prefs", MODE_PRIVATE) }
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
        totalSamples = 0L
        audioReadCalls = 0L
        decodeSteps = 0L
        lastReportTime = 0L
        prefs.edit().putString("diag_stage", "正在加载离线模型")
            .putLong("samples_total", 0L).putLong("read_calls", 0L)
            .putLong("decode_steps", 0L).putInt("level_percent", 0)
            .putInt("rms_db", -99).remove("last_error").apply()
        try {
            val notification = Notification.Builder(this, LISTENING_CHANNEL)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("HotwordBridge 正在监听")
                .setContentText("本地离线识别 Hey ChatGPT")
                .setContentIntent(PendingIntent.getActivity(
                    this, 10, Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                ))
                .setOngoing(true)
                .addAction(
                    android.R.drawable.ic_media_pause, "停止监听",
                    PendingIntent.getService(
                        this, 11, Intent(this, ListeningService::class.java).setAction(ACTION_STOP),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                )
                .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    ONGOING_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(ONGOING_ID, notification)
            }
            listening = true
            prefs.edit().putBoolean("running", true).remove("last_error").apply()
            thread(name = "sherpa-kws", start = true) { recognizeLoop() }
        } catch (error: Throwable) {
            prefs.edit()
                .putBoolean("running", false)
                .putString("last_error", error.message ?: error.javaClass.simpleName).apply()
            listening = false
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun recognizeLoop() {
        var engine: KeywordSpotter? = null
        var recorder: AudioRecord? = null
        var detected = ""
        var failureMessage: String? = null
        try {
            val dir = "kws"
            val modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = "$dir/encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
                    decoder = "$dir/decoder-epoch-12-avg-2-chunk-16-left-64.onnx",
                    joiner = "$dir/joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
                ),
                tokens = "$dir/tokens.txt",
                modelType = "zipformer2",
                numThreads = 2
            )
            engine = KeywordSpotter(
                assetManager = assets,
                config = KeywordSpotterConfig(
                    featConfig = getFeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                    modelConfig = modelConfig,
                    keywordsFile = "$dir/keywords.txt",
                    keywordsScore = 1.5f,
                    keywordsThreshold = 0.25f
                )
            )
            prefs.edit().putString("diag_stage", "模型加载成功；准备麦克风").apply()
            val stream = engine.createStream()
            try {
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
                    PackageManager.PERMISSION_GRANTED) {
                    error("麦克风权限未获授权")
                }
                val minBytes = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
                )
                require(minBytes > 0) { "AudioRecord 缓冲区不可用：$minBytes" }
                recorder = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBytes * 2, 6400)
                )
                check(recorder.state == AudioRecord.STATE_INITIALIZED) {
                    "麦克风初始化失败"
                }
                recorder.startRecording()
                check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    "麦克风未开始录音"
                }
                prefs.edit().putString("diag_stage", "麦克风已启动，正在读取 PCM 数据").apply()
                val buffer = ShortArray(1600)
                while (listening) {
                    val count = recorder.read(buffer, 0, buffer.size)
                    if (count < 0) error("录音出错：$count")
                    if (count == 0) continue
                    // Only aggregate levels and counts; never save or upload audio.
                    totalSamples += count.toLong()
                    audioReadCalls += 1
                    var sumSquare = 0.0
                    for (i in 0 until count) {
                        val normalized = buffer[i] / 32768.0
                        sumSquare += normalized * normalized
                    }
                    val rms = sqrt(sumSquare / count)
                    val dbfs = if (rms <= 0.000001) -99.0 else 20.0 * log10(rms)
                    val levelPercent = ((dbfs + 60.0) * 2.0).roundToInt().coerceIn(0, 100)

                    val samples = FloatArray(count) { buffer[it] / 32768.0f }
                    stream.acceptWaveform(samples, SAMPLE_RATE)
                    while (listening && engine.isReady(stream)) {
                        engine.decode(stream)
                        decodeSteps += 1
                        val keyword = engine.getResult(stream).keyword
                        if (keyword.isNotBlank()) {
                            detected = keyword
                            listening = false
                            break
                        }
                    }
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastReportTime >= 700 || !listening) {
                        lastReportTime = now
                        prefs.edit().putString("diag_stage", "实时采样中")
                            .putLong("samples_total", totalSamples)
                            .putLong("read_calls", audioReadCalls)
                            .putLong("decode_steps", decodeSteps)
                            .putInt("rms_db", dbfs.roundToInt())
                            .putInt("level_percent", levelPercent)
                            .apply()
                    }
                }
            } finally {
                try { stream.release() } catch (_: Throwable) { }
            }
        } catch (problem: Throwable) {
            failureMessage = problem.message ?: problem.javaClass.simpleName
        } finally {
            listening = false
            try { recorder?.stop() } catch (_: Throwable) { }
            try { recorder?.release() } catch (_: Throwable) { }
            try { engine?.release() } catch (_: Throwable) { }
            prefs.edit().putBoolean("running", false).apply()
            val wake = detected
            val failure = failureMessage
            mainThread.post {
                if (wake.isNotEmpty()) {
                    onWakeWord(wake)
                } else {
                    if (failure != null) {
                        prefs.edit()
                            .putString("diag_stage", "出错并停止")
                            .putString("last_error", failure).apply()
                    }
                    stopSelf()
                }
            }
        }
    }

    private fun onWakeWord(keyword: String) {
        prefs.edit()
            .putString("last_event", "$keyword（麦克风已释放）")
            .putString("diag_stage", "识别成功，已停止")
            .remove("last_error").apply()

        // The calibration keyword proves the audio + recognizer path.
        // It intentionally never launches ChatGPT.
        if (keyword.contains("HELLO_WORLD")) {
            if (MainActivity.isForeground) {
                Toast.makeText(
                    this, "Hello World 测试成功：麦克风和识别器正常",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                notifications.notify(
                    WAKE_ID,
                    Notification.Builder(this, WAKE_CHANNEL)
                        .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                        .setContentTitle("HotwordBridge 采样测试成功")
                        .setContentText("已识别 Hello World；麦克风和模型正常")
                        .setContentIntent(PendingIntent.getActivity(
                            this, 12, Intent(this, MainActivity::class.java),
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        ))
                        .setAutoCancel(true)
                        .build()
                )
            }
            stopSelf()
            return
        }

        // Android restricts background Activity launches; do not circumvent it.
        val opened = if (MainActivity.isForeground) {
            try { startActivity(ChatGptLauncher.intent(this)); true }
            catch (_: Exception) { false }
        } else false

        if (!opened) {
            try {
                notifications.notify(
                    WAKE_ID,
                    Notification.Builder(this, WAKE_CHANNEL)
                        .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                        .setContentTitle("已听到 Hey ChatGPT")
                        .setContentText("点击打开 ChatGPT 语音对话")
                        .setContentIntent(ChatGptLauncher.pendingIntent(this))
                        .setAutoCancel(true)
                        .setCategory(Notification.CATEGORY_REMINDER)
                        .build()
                )
            } catch (error: SecurityException) {
                prefs.edit().putString(
                    "last_error", "无法显示提醒，请在系统设置中允许通知"
                ).apply()
            }
        }
        stopSelf()
    }

    override fun onDestroy() {
        listening = false
        prefs.edit().putBoolean("running", false).apply()
        super.onDestroy()
    }
}
