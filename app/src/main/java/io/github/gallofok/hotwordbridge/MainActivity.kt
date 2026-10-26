package io.github.gallofok.hotwordbridge

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    companion object {
        @Volatile var isForeground = false
        private const val REQUEST_PERMISSIONS = 110
        private const val REQUEST_TRAIN_RECORD = 111
    }

    private val preferences by lazy { getSharedPreferences("hotword_prefs", MODE_PRIVATE) }
    private lateinit var statusView: TextView
    private lateinit var levelBar: ProgressBar
    private var startAfterPermission = false
    private var pendingClip = -1
    private var capturingClip = false
    private var trainingModel = false
    private var confirmErase = false
    private lateinit var trainStatusView: TextView
    private val uiHandler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            if (::statusView.isInitialized) refreshStatus()
            uiHandler.postDelayed(this, 650)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(22)
            setPadding(p, dp(24), p, dp(36))
        }
        scroll.addView(layout)
        setContentView(scroll)

        fun text(value: String, size: Float) {
            layout.addView(TextView(this).apply {
                this.text = value
                textSize = size
                setPadding(0, dp(9), 0, dp(9))
            })
        }
        fun button(label: String, action: () -> Unit) {
            layout.addView(Button(this).apply {
                this.text = label
                setOnClickListener { action() }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            })
        }

        text("HotwordBridge · 0.8 个人本地训练版", 26f)
        text("Vosk 语音转文字 / sherpa-onnx 关键词检测。全程离线，无需 AccessKey。", 15f)
        text("初次使用 Vosk 会解压英语模型，请保留手机足够空间。先说 Hello World，再尝试 Hey Chat G P T。", 16f)
        button("切换识别引擎：Vosk / sherpa / 个人模板（需停止监听）") {
            if (preferences.getBoolean("running", false)) {
                toast("请先停止监听")
            } else {
                val old = preferences.getString("engine", "VOSK")
                val modes = listOf("VOSK", "SHERPA", "PERSONAL")
                val next = modes[(modes.indexOf(old) + 1).coerceAtLeast(0) % modes.size]
                if (next == "PERSONAL" && !PersonalWake.ready(this)) {
                    toast("请先完成个人录音和本地训练")
                } else {
                    preferences.edit().putString("engine", next).apply()
                }
                refreshStatus()
                toast("下次启动使用：$next")
            }
        }
        button("切换麦克风输入源（需停止监听）") {
            if (preferences.getBoolean("running", false)) {
                toast("请先点击停止监听")
            } else {
                val old = preferences.getString("audio_source", "MIC")
                val next = if (old == "MIC") "VOICE_RECOGNITION" else "MIC"
                preferences.edit().putString("audio_source", next).apply()
                refreshStatus()
                toast("下次开始监听将使用：$next")
            }
        }
        button("开始监听") { startListening() }
        text("个人唤醒词训练（离线）", 21f)
        text("按提示逐句录制；每次点击录制后有 3.3 秒说话。提示音节：Hey / Chat / G / P / T（英语字母）。建议点击后稍等半秒再说。", 14f)
        trainStatusView = TextView(this).apply {
            textSize = 15f
            setPadding(0, dp(12), 0, dp(12))
        }
        layout.addView(trainStatusView)
        button("录制当前提示句（3.3 秒自动保存）") {
            recordPersonalSample()
        }
        button("重录上一条 / 删除上一条") {
            if (preferences.getBoolean("running", false) || capturingClip || trainingModel) {
                toast("请先停止监听或录制")
            } else {
                val previous = PersonalWake.next(this) - 1
                if (previous >= 0) {
                    PersonalWake.remove(this, previous)
                    toast("已删除第 " + (previous + 1) + " 条，请重新录制")
                    refreshStatus()
                } else toast("尚无录音")
            }
        }
        button("使用本机样本训练个人模型") {
            if (capturingClip || trainingModel || preferences.getBoolean("running", false)) {
                toast("请先结束录制和监听")
            } else {
                trainingModel = true
                preferences.edit().putString("personal_training_result", "训练中：计算声学特征和验证阈值").apply()
                refreshStatus()
                PersonalWake.train(this) { ok, result ->
                    trainingModel = false
                    preferences.edit().putString("personal_training_result", result).apply()
                    refreshStatus()
                    toast(result)
                }
            }
        }
        button("应用已训练的个人唤醒模型") {
            if (preferences.getBoolean("running", false) || capturingClip || trainingModel) {
                toast("请先停止监听、录制或训练")
            } else if (!PersonalWake.ready(this)) {
                toast("请先录满全部样本并训练成功")
            } else {
                preferences.edit().putString("engine", "PERSONAL").apply()
                refreshStatus()
                toast("已应用本地个人模型，点击开始监听测试")
            }
        }
        button("删除所有个人录音和训练结果（再次点击确认）") {
            if (preferences.getBoolean("running", false) || capturingClip || trainingModel) {
                toast("请先停止监听、录制或训练")
            } else if (!confirmErase) {
                confirmErase = true
                toast("再次点击将永久删除本机所有个人训练数据")
            } else {
                PersonalWake.erase(this)
                confirmErase = false
                preferences.edit()
                    .putString("personal_training_result", "录音和模型已删除").apply()
                if (preferences.getString("engine", "VOSK") == "PERSONAL") {
                    preferences.edit().putString("engine", "VOSK").apply()
                }
                refreshStatus()
            }
        }
        button("停止监听") {
            stopService(Intent(this, ListeningService::class.java))
            preferences.edit().putBoolean("running", false).apply()
            refreshStatus()
        }
        button("切换 ChatGPT 启动方式（自动 / 直接语音 / 语音链接 / 首页）") {
            val modes = listOf(
                ChatGptLauncher.MODE_AUTO,
                ChatGptLauncher.MODE_DIRECT,
                ChatGptLauncher.MODE_DEEPLINK,
                ChatGptLauncher.MODE_APP
            )
            val current = preferences.getString(
                "voice_launch_mode", ChatGptLauncher.MODE_AUTO
            ) ?: ChatGptLauncher.MODE_AUTO
            val next = modes[(modes.indexOf(current).coerceAtLeast(0) + 1) % modes.size]
            preferences.edit().putString("voice_launch_mode", next).apply()
            refreshStatus()
            toast("已选择：" + ChatGptLauncher.modeLabel(next))
        }
        button("测试进入 ChatGPT Voice（无需唤醒词）") {
            val mode = preferences.getString(
                "voice_launch_mode", ChatGptLauncher.MODE_AUTO
            ) ?: ChatGptLauncher.MODE_AUTO
            try {
                val result = ChatGptLauncher.launch(this, mode)
                preferences.edit().putString("voice_launch_result", result).apply()
                toast(result)
            } catch (error: Exception) {
                val message = "启动失败：" + (error.message ?: "未知错误")
                preferences.edit().putString("voice_launch_result", message).apply()
                toast(message)
            }
        }
        text("实时输入音量（说话时进度条应上升）", 16f)
        levelBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
        }
        layout.addView(levelBar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(20)
        ))
        statusView = TextView(this).apply {
            textSize = 15f
            setPadding(0, dp(20), 0, dp(16))
        }
        layout.addView(statusView)
        button("运行官方真人录音自测（不使用麦克风）") {
            if (preferences.getBoolean("running", false)) {
                toast("请先停止麦克风监听，再运行标准录音自测")
            } else {
                preferences.edit().putString("self_test", "正在检测官方真人录音").apply()
                refreshStatus()
                KwsSelfTest.run(this) { passed, result ->
                    preferences.edit().putString("self_test", result).apply()
                    refreshStatus()
                    toast(result)
                }
            }
        }
        button("清空本机转写记录") {
            preferences.edit()
                .putString("vosk_partial", "")
                .putString("vosk_final", "")
                .putString("vosk_log", "")
                .putInt("vosk_utterances", 0)
                .apply()
            refreshStatus()
        }
        button("复制诊断数据") {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText(
                "HotwordBridge Diagnostics", statusView.text
            ))
            toast("诊断数据已复制")
        }
        text(
            "首次启动，请允许麦克风与通知权限。监听开始后可以退到后台，但系统可能因省电策略停止服务。\n\n" +
                "当 HotwordBridge 显示在前台时，识别唤醒词会尝试打开 ChatGPT。后台或锁屏下使用可点击通知，" +
                "因为 Android 不保证普通后台应用能直接弹出界面。识别到一次后会停止监听，避免争用 ChatGPT 麦克风。" +
                "\n\nChatGPT 设置 → Voice → Start with Voice（如当前版本提供）。",
            14f
        )
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        isForeground = true
        uiHandler.removeCallbacks(refreshRunnable)
        uiHandler.post(refreshRunnable)
    }
    override fun onPause() {
        uiHandler.removeCallbacks(refreshRunnable)
        isForeground = false
        super.onPause()
    }

    private fun recordPersonalSample() {
        if (capturingClip || trainingModel || preferences.getBoolean("running", false)) {
            toast("请先结束当前监听或训练")
            return
        }
        val index = PersonalWake.next(this)
        if (index >= PersonalWake.prompts.size) {
            toast("录音已满，请点击本地训练")
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED) {
            pendingClip = index
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_TRAIN_RECORD)
            return
        }
        capturingClip = true
        refreshStatus()
        PersonalWake.record(this, index) { success, message ->
            capturingClip = false
            preferences.edit().putString("personal_training_result", message).apply()
            refreshStatus()
            toast(message)
        }
    }

    private fun startListening() {
        val needed = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (needed.isNotEmpty()) {
            startAfterPermission = true
            requestPermissions(needed.toTypedArray(), REQUEST_PERMISSIONS)
        } else {
            reallyStartListening()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_TRAIN_RECORD) {
            val allowed = checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            pendingClip = -1
            if (allowed) recordPersonalSample()
            else toast("需要录音权限才能采集自己的训练样本")
            return
        }
        if (requestCode != REQUEST_PERMISSIONS || !startAfterPermission) return
        startAfterPermission = false
        val microphone = checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        val notification = Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (microphone && notification) reallyStartListening()
        else toast("必须允许麦克风及通知权限才能进行完整测试")
    }

    private fun reallyStartListening() {
        try {
            startForegroundService(Intent(this, ListeningService::class.java)
                .setAction(ListeningService.ACTION_START))
            statusView.text = "状态：正在启动模型和麦克风…"
        } catch (error: Exception) {
            toast("启动监听失败：${error.message}")
        }
    }

    private fun refreshStatus() {
        val active = preferences.getBoolean("running", false)
        val status = if (active) "正在监听" else "未监听"
        val stage = preferences.getString("diag_stage", "尚未启动").orEmpty()
        val samples = preferences.getLong("samples_total", 0L)
        val reads = preferences.getLong("read_calls", 0L)
        val steps = preferences.getLong("decode_steps", 0L)
        val db = preferences.getInt("rms_db", -99)
        val max5sec = preferences.getInt("five_sec_max_db", -99)
        val voiceFrames = preferences.getInt("five_sec_voice_frames", 0)
        val totalFrames = preferences.getInt("five_sec_total_frames", 0)
        val silenced = when (preferences.getInt("mic_silenced", -1)) {
            1 -> "是（Android 正在静音此录音）"
            0 -> "否"
            else -> "未知"
        }
        val route = preferences.getString("mic_route", "未知")
        val source = preferences.getString("audio_source", "MIC")
        val engine = preferences.getString("engine", "VOSK")
        val runningEngine = preferences.getString("active_engine", "无")
        val partial = preferences.getString("vosk_partial", "").orEmpty()
        val finalResult = preferences.getString("vosk_final", "").orEmpty()
        val log = preferences.getString("vosk_log", "").orEmpty()
        val utterances = preferences.getInt("vosk_utterances", 0)
        val level = preferences.getInt("level_percent", 0)
        val selfTest = preferences.getString("self_test", "尚未运行").orEmpty()
        val voiceMode = preferences.getString("voice_launch_mode", ChatGptLauncher.MODE_AUTO)
            ?: ChatGptLauncher.MODE_AUTO
        val voiceLaunchResult = preferences.getString("voice_launch_result", "尚未测试")
            .orEmpty()
        val directAvailable = if (ChatGptLauncher.isDirectAvailable(this)) "是" else "否"
        val event = preferences.getString("last_event", "").orEmpty()
        val personalScore = preferences.getString("personal_last_score", "尚未测试").orEmpty()
        val error = preferences.getString("last_error", "").orEmpty()

        if (::levelBar.isInitialized) levelBar.progress = if (active) level else 0
        val audioMeaning = when {
            samples == 0L -> "尚无麦克风采样"
            max5sec > -40 -> "最近 5 秒内有明显声音信号"
            max5sec > -55 -> "最近 5 秒音量较低，但确实有声"
            else -> "最近 5 秒信号很弱；靠近麦克风持续讲话重测"
        }
        val sampleSeconds = String.format(java.util.Locale.US, "%.1f", samples / 16000.0)
        if (::trainStatusView.isInitialized) {
            val index = PersonalWake.next(this)
            val count = PersonalWake.count(this)
            val current = PersonalWake.prompts.getOrNull(index)
            val description = if (current == null) {
                "全部样本录制完成，可以运行本地训练"
            } else {
                "第 " + (index + 1) + "/" + PersonalWake.prompts.size + " 条：\n" +
                    current.phrase + "\n" + current.hint + "\n" +
                    if (current.group == "positive") {
                        if (current.heldOut) "保留正例测试（不用于训练）"
                        else "正例：真实唤醒发音"
                    } else {
                        if (current.heldOut) "保留负例测试（不用于训练）"
                        else "负例：听到这句不应该唤醒"
                    }
            }
            val state = preferences.getString("personal_training_result", "尚未训练")
            trainStatusView.text = "录音：" + count + "/" + PersonalWake.prompts.size +
                " · 个人模型：" + (if (PersonalWake.ready(this)) "已生成" else "未训练") +
                "\n" + description + "\n训练结果：" + state +
                if (capturingClip) "\n正在录音，请说话…" else ""
        }
        statusView.text = "运行：$status\n阶段：$stage\n" +
            "选定引擎：$engine · 当前服务引擎：$runningEngine\n" +
            "语音启动方式：" + ChatGptLauncher.modeLabel(voiceMode) + "\n" +
            "ChatGPT 语音 Activity 可访问：$directAvailable\n" +
            "上次启动尝试：$voiceLaunchResult\n" +
            "麦克风输入源：$source（可切换）\n录音设备：$route\n" +
            "Android 系统静音：$silenced\n" +
            "已读取音频：$sampleSeconds 秒 · $reads 次读取\n" +
            "当前音量：$db dBFS\n" +
            "最近 5 秒最高音量：$max5sec dBFS\n" +
            "最近 5 秒声音窗口：$voiceFrames / $totalFrames（高于 -45 dBFS）\n" +
            "判断：$audioMeaning\n" +
            "模型解码步数：$steps\n" +
            "个人声学匹配结果：$personalScore\n" +
            "标准录音自测：$selfTest\n" +
            (if (engine == "VOSK") {
                "Vosk 已完成话语：$utterances\n" +
                "Vosk 实时识别：${partial.ifBlank { "（暂无）" }}\n" +
                "Vosk 最近完整句：${finalResult.ifBlank { "（暂无）" }}\n" +
                "Vosk 转写记录（仅本机）：\n${log.ifBlank { "（暂无）" }}\n"
            } else {
                "sherpa 阈值：Hey ChatGPT=0.12 / Hello World=0.20\n"
            }) +
            (if (event.isNotEmpty()) "最近触发：$event\n" else "") +
            (if (error.isNotEmpty()) "错误：$error" else "")
    }

    private fun dp(value: Int) = (resources.displayMetrics.density * value).toInt()
    private fun toast(value: String) = Toast.makeText(this, value, Toast.LENGTH_LONG).show()
}
