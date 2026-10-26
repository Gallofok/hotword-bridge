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
    // Only 5s of *numeric volume levels* are held in memory, never raw audio.
    private val recentLevels = ArrayDeque<Pair<Long, Double>>()
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
        recentLevels.clear()
        val selectedEngine = prefs.getString("engine", "VOSK") ?: "VOSK"
        prefs.edit().putString("active_engine", selectedEngine)
            .putLong("session_started_at", System.currentTimeMillis())
            .remove("last_event")
            .remove("personal_last_score")
            .remove("vosk_candidate")
            .remove("vosk_partial")
            .remove("vosk_final")
            .remove("vosk_log")
            .putInt("vosk_utterances", 0)
            .putString("diag_stage", "正在加载离线模型")
            .putLong("samples_total", 0L).putLong("read_calls", 0L)
            .putLong("decode_steps", 0L).putInt("level_percent", 0)
            .putInt("rms_db", -99).putInt("five_sec_max_db", -99)
            .putInt("five_sec_voice_frames", 0).putInt("five_sec_total_frames", 0)
            .putInt("mic_silenced", -1).putString("mic_route", "未获取")
            .remove("last_error").apply()
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
            thread(name = "hotword-$selectedEngine", start = true) {
                when (selectedEngine) {
                    "VOSK" -> recognizeVoskLoop()
                    "PERSONAL" -> recognizePersonalLoop()
                    else -> recognizeLoop()
                }
            }
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
                val audioSource = if (prefs.getString("audio_source", "MIC") == "VOICE_RECOGNITION") {
                    MediaRecorder.AudioSource.VOICE_RECOGNITION
                } else {
                    MediaRecorder.AudioSource.MIC
                }
                recorder = AudioRecord(
                    audioSource,
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
                    recentLevels.addLast(now to dbfs)
                    while (recentLevels.isNotEmpty() && now - recentLevels.first().first > 5000) {
                        recentLevels.removeFirst()
                    }
                    if (now - lastReportTime >= 700 || !listening) {
                        lastReportTime = now
                        val max5Sec = (recentLevels.maxOfOrNull { it.second } ?: -99.0)
                            .roundToInt()
                        val voiceFrames5Sec = recentLevels.count { it.second > -45.0 }
                        // Android may deliver real-looking read counts but silent audio
                        // when another app has priority for microphone input.
                        val frameworkSilenced = if (Build.VERSION.SDK_INT >= 29) {
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
                        prefs.edit().putString("diag_stage", "实时采样中")
                            .putInt("five_sec_max_db", max5Sec)
                            .putInt("five_sec_voice_frames", voiceFrames5Sec)
                            .putInt("five_sec_total_frames", recentLevels.size)
                            .putInt("mic_silenced", frameworkSilenced)
                            .putString("mic_route", route)
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

    /**
     * Vosk ASR comparison mode.
     * Uses the same 16 kHz mono PCM capture, meter and Android audio source as KWS,
     * but a full speech recognizer instead of token-sequence keyword spotting.
     * Vosk performs its own utterance endpointing; feed quiet frames too.
     */
    private fun recognizeVoskLoop() {
        var engine: VoskAsrEngine? = null
        var recorder: AudioRecord? = null
        var detected = ""
        var failure: String? = null
        try {
            engine = VoskAsrEngine(this, prefs,
                onStage = { stage ->
                    prefs.edit().putString("diag_stage", stage).apply()
                },
                limitedGrammar = prefs.getBoolean("vosk_limited_grammar", false)
            )
            if (!listening) return
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED) {
                error("麦克风权限未获授权")
            }
            val minBytes = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            require(minBytes > 0) { "麦克风 buffer 无效：$minBytes" }
            val audioSource = if (prefs.getString("audio_source", "MIC") == "VOICE_RECOGNITION") {
                MediaRecorder.AudioSource.VOICE_RECOGNITION
            } else {
                MediaRecorder.AudioSource.MIC
            }
            recorder = AudioRecord(
                audioSource, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBytes * 2, 6400)
            )
            check(recorder.state == AudioRecord.STATE_INITIALIZED) {
                "Vosk 麦克风初始化失败"
            }
            recorder.startRecording()
            check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                "麦克风未开始录音"
            }
            prefs.edit().putString("diag_stage", "Vosk 实时转写中").apply()
            val buffer = ShortArray(1600)
            while (listening) {
                val count = recorder.read(buffer, 0, buffer.size)
                if (count < 0) error("录音出错：$count")
                if (count == 0) continue
                totalSamples += count.toLong()
                audioReadCalls += 1
                var sumSquare = 0.0
                for (i in 0 until count) {
                    val value = buffer[i] / 32768.0
                    sumSquare += value * value
                }
                val rms = sqrt(sumSquare / count)
                val dbfs = if (rms <= 0.000001) -99.0 else 20.0 * log10(rms)
                val hit = engine.accept(buffer, count)
                decodeSteps += 1
                if (hit != null) {
                    detected = hit
                    listening = false
                }
                val now = SystemClock.elapsedRealtime()
                recentLevels.addLast(now to dbfs)
                while (recentLevels.isNotEmpty() && now - recentLevels.first().first > 5000) {
                    recentLevels.removeFirst()
                }
                if (now - lastReportTime >= 650 || !listening) {
                    lastReportTime = now
                    val frameworkSilenced = if (Build.VERSION.SDK_INT >= 29) {
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
                        .putString("diag_stage", "Vosk 实时转写中")
                        .putLong("samples_total", totalSamples)
                        .putLong("read_calls", audioReadCalls)
                        .putLong("decode_steps", decodeSteps)
                        .putInt("rms_db", dbfs.roundToInt())
                        .putInt("level_percent", ((dbfs + 60.0) * 2.0).roundToInt().coerceIn(0, 100))
                        .putInt("five_sec_max_db", recentLevels.maxOfOrNull { it.second }
                            ?.roundToInt() ?: -99)
                        .putInt("five_sec_voice_frames", recentLevels.count { it.second > -45.0 })
                        .putInt("five_sec_total_frames", recentLevels.size)
                        .putInt("mic_silenced", frameworkSilenced)
                        .putString("mic_route", route)
                        .apply()
                }
            }
        } catch (problem: Throwable) {
            failure = problem.message ?: problem.javaClass.simpleName
        } finally {
            listening = false
            try { recorder?.stop() } catch (_: Throwable) {}
            try { recorder?.release() } catch (_: Throwable) {}
            try { engine?.close() } catch (_: Throwable) {}
            prefs.edit().putBoolean("running", false).apply()
            val lastHit = detected
            val lastError = failure
            mainThread.post {
                if (lastHit.isNotEmpty()) {
                    onWakeWord(lastHit)
                } else {
                    if (lastError != null) {
                        prefs.edit()
                            .putString("diag_stage", "Vosk 运行出错")
                            .putString("last_error", lastError)
                            .apply()
                    }
                    stopSelf()
                }
            }
        }
    }

    /**
     * Independent personal template listener. Endpointed voice is processed
     * locally with the training-time spectral features + DTW.
     * No Vosk, no network, no cloud audio.
     */
    private fun recognizePersonalLoop() {
        var recorder: AudioRecord? = null
        var matched = ""
        var failure: String? = null
        try {
            prefs.edit().putString("diag_stage", "加载个人声学模板").apply()
            check(PersonalWake.ready(this)) { "没有个人训练模型，请返回训练并点击应用" }
            val model = PersonalWake.load(this)
            if (!listening) return
            check(checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED) { "麦克风权限未获授权" }
            val minBytes = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            check(minBytes > 0) { "PCM 缓冲区无效" }
            // All v0.8 enrollment recordings use VOICE_RECOGNITION regardless
            // of the UI selector. Force the same capture mode on PERSONAL
            // listening, so existing private samples remain compatible.
            recorder = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minBytes * 2, 6400))
            check(recorder.state == AudioRecord.STATE_INITIALIZED) { "麦克风未初始化" }
            recorder.startRecording()
            check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                "麦克风未开始录音"
            }
            prefs.edit().putString("diag_stage", "个人模板监听中：等待完整语音").apply()
            val chunk = ShortArray(1600)
            val history = ArrayDeque<ShortArray>()
            val speech = ArrayList<Short>()
            var speaking = false
            var silence = 0
            var lastMatchAt = 0L

            while (listening) {
                val count = recorder.read(chunk, 0, chunk.size)
                if (count < 0) error("PCM 读取失败：" + count)
                if (count == 0) continue
                totalSamples += count.toLong()
                audioReadCalls += 1
                var sum = 0.0
                for (i in 0 until count) {
                    val v = chunk[i] / 32768.0
                    sum += v * v
                }
                val rms = sqrt(sum / count)
                val db = if (rms <= 0.000001) -99.0 else 20.0 * log10(rms)
                val voice = db > -51.0
                val copy = chunk.copyOf(count)
                decodeSteps++
                val now = SystemClock.elapsedRealtime()

                if (!speaking) {
                    history.addLast(copy)
                    while (history.size > 4) history.removeFirst()
                    if (voice && now - lastMatchAt > 900) {
                        speaking = true
                        silence = 0
                        speech.clear()
                        for (part in history) for (s in part) speech.add(s)
                        history.clear()
                    }
                } else {
                    for (s in copy) speech.add(s)
                    if (voice) silence = 0 else silence++
                    val ended = silence >= 7 || speech.size >= SAMPLE_RATE * 42 / 10
                    if (ended) {
                        speaking = false
                        history.clear()
                        val audio = speech.toShortArray()
                        speech.clear()
                        if (audio.size >= SAMPLE_RATE / 2) {
                            prefs.edit().putString("diag_stage", "个人模板：正在比较音频特征").apply()
                            try {
                                val score = PersonalWake.test(model, audio)
                                val report = String.format(
                                    java.util.Locale.US,
                                    "正例距离 %.2f / 负例距离 %.2f / 阈值 %.2f → %s",
                                    score.positive, score.negative, model.threshold,
                                    if (score.match) "命中" else "不匹配"
                                )
                                prefs.edit().putString("personal_last_score", report).apply()
                                if (score.match) {
                                    matched = "PERSONAL_HEY_CHATGPT"
                                    listening = false
                                }
                            } catch (e: IllegalArgumentException) {
                                prefs.edit().putString("personal_last_score",
                                    "样本太短，未匹配").apply()
                            }
                            lastMatchAt = SystemClock.elapsedRealtime()
                        }
                    }
                }

                recentLevels.addLast(now to db)
                while (recentLevels.isNotEmpty() && now - recentLevels.first().first > 5000) {
                    recentLevels.removeFirst()
                }
                if (now - lastReportTime >= 650 || !listening) {
                    lastReportTime = now
                    val frameworkSilenced = if (Build.VERSION.SDK_INT >= 29) {
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
                        .putString("diag_stage", if (speaking) "个人模板：正在收集语音片段"
                            else "个人模板：等待下一句话")
                        .putLong("samples_total", totalSamples)
                        .putLong("read_calls", audioReadCalls)
                        .putLong("decode_steps", decodeSteps)
                        .putInt("rms_db", db.roundToInt())
                        .putInt("level_percent", ((db + 60) * 2).roundToInt().coerceIn(0,100))
                        .putInt("five_sec_max_db", recentLevels.maxOfOrNull { it.second }
                            ?.roundToInt() ?: -99)
                        .putInt("five_sec_voice_frames", recentLevels.count { it.second > -45 })
                        .putInt("five_sec_total_frames", recentLevels.size)
                        .putInt("mic_silenced", frameworkSilenced)
                        .putString("mic_route", route)
                        .apply()
                }
            }
        } catch (error: Throwable) {
            failure = error.message ?: error.javaClass.simpleName
        } finally {
            listening = false
            try { recorder?.stop() } catch (_: Throwable) {}
            try { recorder?.release() } catch (_: Throwable) {}
            prefs.edit().putBoolean("running", false).apply()
            val finalHit = matched
            val finalError = failure
            mainThread.post {
                if (finalHit.isNotEmpty()) {
                    onWakeWord(finalHit)
                } else {
                    if (finalError != null) {
                        prefs.edit().putString("diag_stage", "个人模板引擎出错")
                            .putString("last_error", finalError).apply()
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

        // Android restricts background Activity launches. When the app is
        // backgrounded, issue a user-tappable PendingIntent, not startActivity().
        val launchMode = prefs.getString(
            "voice_launch_mode", ChatGptLauncher.MODE_AUTO
        ) ?: ChatGptLauncher.MODE_AUTO
        val opened = if (MainActivity.isForeground) {
            try {
                val report = ChatGptLauncher.launch(this, launchMode)
                prefs.edit().putString("voice_launch_result", report).apply()
                true
            } catch (error: Exception) {
                prefs.edit().putString("voice_launch_result",
                    "启动失败：" + (error.message ?: "未知错误")).apply()
                false
            }
        } else false

        if (!opened) {
            if (!MainActivity.isForeground) {
                prefs.edit().putString("voice_launch_result",
                    "后台命中：已发送语音启动通知（需手动点击）").apply()
            }
            try {
                notifications.notify(
                    WAKE_ID,
                    Notification.Builder(this, WAKE_CHANNEL)
                        .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                        .setContentTitle("已听到 Hey ChatGPT")
                        .setContentText("点击打开 ChatGPT 语音对话")
                        .setContentIntent(ChatGptLauncher.pendingIntent(this, launchMode))
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
