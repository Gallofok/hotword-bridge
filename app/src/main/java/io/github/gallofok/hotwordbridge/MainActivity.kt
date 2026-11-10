package io.github.gallofok.hotwordbridge

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
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
        // label -> score threshold; a lower threshold wakes more easily
        private val SENSITIVITY = listOf("高" to 0.5f, "中" to 0.7f, "低" to 0.85f)
    }

    private val preferences by lazy { getSharedPreferences(ListeningService.PREFS, MODE_PRIVATE) }
    private lateinit var statusView: TextView
    private lateinit var levelBar: ProgressBar
    private var startAfterPermission = false
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
        fun whenStopped(action: () -> Unit) {
            if (preferences.getBoolean("running", false)) toast("请先停止监听") else action()
        }

        text("HotwordBridge 1.0", 26f)
        text("说 Hey ChatGPT 打开 ChatGPT 语音。识别完全在本机进行，声音不保存、不上传。", 15f)

        button("开始监听") { startListening() }
        button("停止监听") {
            stopService(Intent(this, ListeningService::class.java))
            preferences.edit().putBoolean("running", false).apply()
            refreshStatus()
        }

        text("实时输入音量（说话时进度条应上升）", 16f)
        levelBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
        }
        layout.addView(
            levelBar,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(20))
        )
        statusView = TextView(this).apply {
            textSize = 15f
            setPadding(0, dp(20), 0, dp(16))
        }
        layout.addView(statusView)

        text("设置", 21f)
        button("允许在后台直接打开 ChatGPT（悬浮窗权限）") {
            if (Settings.canDrawOverlays(this)) {
                toast("已授权：应用在后台时也会直接打开 ChatGPT")
            } else {
                try {
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                    )
                    toast("请打开“允许显示在其他应用的上层”，然后返回")
                } catch (error: Exception) {
                    toast("无法打开设置页：${error.message}")
                }
            }
        }
        button("切换灵敏度：高 / 中 / 低（需停止监听）") {
            whenStopped {
                val current = preferences.getFloat("threshold", ListeningService.DEFAULT_THRESHOLD)
                val index = SENSITIVITY.indexOfFirst { it.second == current }
                val next = SENSITIVITY[(index + 1) % SENSITIVITY.size]
                preferences.edit().putFloat("threshold", next.second).apply()
                refreshStatus()
                toast("灵敏度：${next.first}（阈值 ${next.second}）")
            }
        }
        button("切换：唤醒一次后停止 / 唤醒后继续监听（实验，需停止监听）") {
            whenStopped {
                val keep = !preferences.getBoolean("keep_listening", false)
                preferences.edit().putBoolean("keep_listening", keep).apply()
                refreshStatus()
                toast(
                    if (keep) "唤醒后继续监听：ChatGPT 使用麦克风期间本应用会被系统静音"
                    else "唤醒一次后停止，需要再次点击开始监听"
                )
            }
        }
        button("切换 ChatGPT 启动方式（自动 / 直接语音 / 语音链接 / 首页）") {
            val modes = listOf(
                ChatGptLauncher.MODE_AUTO,
                ChatGptLauncher.MODE_DIRECT,
                ChatGptLauncher.MODE_DEEPLINK,
                ChatGptLauncher.MODE_APP
            )
            val current = preferences.getString("voice_launch_mode", ChatGptLauncher.MODE_AUTO)
                ?: ChatGptLauncher.MODE_AUTO
            val next = modes[(modes.indexOf(current).coerceAtLeast(0) + 1) % modes.size]
            preferences.edit().putString("voice_launch_mode", next).apply()
            refreshStatus()
            toast("已选择：" + ChatGptLauncher.modeLabel(next))
        }
        button("测试进入 ChatGPT Voice（无需唤醒词）") {
            val mode = preferences.getString("voice_launch_mode", ChatGptLauncher.MODE_AUTO)
                ?: ChatGptLauncher.MODE_AUTO
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
        button("切换麦克风输入源（需停止监听）") {
            whenStopped {
                val old = preferences.getString("audio_source", "VOICE_RECOGNITION")
                val next = if (old == "MIC") "VOICE_RECOGNITION" else "MIC"
                preferences.edit().putString("audio_source", next).apply()
                refreshStatus()
                toast("下次开始监听将使用：$next")
            }
        }
        button("复制诊断数据") {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(
                ClipData.newPlainText("HotwordBridge Diagnostics", statusView.text)
            )
            toast("诊断数据已复制")
        }
        text(
            "首次使用请允许麦克风和通知权限。开始监听后可以退到后台；授予悬浮窗权限后，" +
                "在其他应用里说 Hey ChatGPT 也会直接打开 ChatGPT，否则只会收到一条可点击的通知。\n\n" +
                "锁屏状态下不会自动打开。系统的省电策略可能会停止后台监听。\n\n" +
                "唤醒模型是针对一位使用者的声音训练的，其他人说可能无法唤醒。",
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

    private fun startListening() {
        val needed = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
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
        if (requestCode != REQUEST_PERMISSIONS || !startAfterPermission) return
        startAfterPermission = false
        val microphone = checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (microphone) reallyStartListening()
        else toast("必须允许麦克风权限才能监听")
    }

    private fun reallyStartListening() {
        try {
            startForegroundService(
                Intent(this, ListeningService::class.java).setAction(ListeningService.ACTION_START)
            )
            statusView.text = "状态：正在启动模型和麦克风…"
        } catch (error: Exception) {
            toast("启动监听失败：${error.message}")
        }
    }

    private fun refreshStatus() {
        val active = preferences.getBoolean("running", false)
        val stage = preferences.getString("diag_stage", "尚未启动").orEmpty()
        val samples = preferences.getLong("samples_total", 0L)
        val db = preferences.getInt("rms_db", -99)
        val level = preferences.getInt("level_percent", 0)
        val scoreNow = preferences.getFloat("score_now", 0f)
        val scoreMax = preferences.getFloat("score_recent_max", 0f)
        val wakeCount = preferences.getInt("wake_count", 0)
        val threshold = preferences.getFloat("threshold", ListeningService.DEFAULT_THRESHOLD)
        val sensitivity = SENSITIVITY.firstOrNull { it.second == threshold }?.first ?: "自定义"
        val keep = preferences.getBoolean("keep_listening", false)
        val overlay = Settings.canDrawOverlays(this)
        val silenced = when (preferences.getInt("mic_silenced", -1)) {
            1 -> "是（其他应用正在使用麦克风）"
            0 -> "否"
            else -> "未知"
        }
        val route = preferences.getString("mic_route", "未知")
        val source = preferences.getString("audio_source", "VOICE_RECOGNITION")
        val voiceMode = preferences.getString("voice_launch_mode", ChatGptLauncher.MODE_AUTO)
            ?: ChatGptLauncher.MODE_AUTO
        val launchResult = preferences.getString("voice_launch_result", "尚未测试").orEmpty()
        val directAvailable = if (ChatGptLauncher.isDirectAvailable(this)) "是" else "否"
        val event = preferences.getString("last_event", "").orEmpty()
        val error = preferences.getString("last_error", "").orEmpty()
        val sessionStart = preferences.getLong("session_started_at", 0L)
        val session = if (sessionStart > 0L) {
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                .format(java.util.Date(sessionStart))
        } else "尚未开始"

        if (::levelBar.isInitialized) levelBar.progress = if (active) level else 0
        val seconds = String.format(java.util.Locale.US, "%.0f", samples / 16000.0)
        fun two(value: Float) = String.format(java.util.Locale.US, "%.2f", value)
        statusView.text = "运行：" + (if (active) "正在监听" else "未监听") + "\n" +
            "阶段：$stage\n" +
            "本次开始：$session · 已监听 $seconds 秒\n" +
            "唤醒分数：当前 ${two(scoreNow)} · 最近 5 秒最高 ${two(scoreMax)} · 阈值 ${two(threshold)}\n" +
            "本次唤醒次数：$wakeCount\n" +
            (if (event.isNotEmpty()) "最近唤醒：$event\n" else "") +
            "灵敏度：$sensitivity\n" +
            "唤醒后：" + (if (keep) "继续监听（实验）" else "停止监听") + "\n" +
            "后台直接打开 ChatGPT：" + (if (overlay) "已授权" else "未授权（只发通知）") + "\n" +
            "启动方式：" + ChatGptLauncher.modeLabel(voiceMode) + "\n" +
            "ChatGPT 语音 Activity 可访问：$directAvailable\n" +
            "上次启动尝试：$launchResult\n" +
            "麦克风输入源：$source · 录音设备：$route\n" +
            "当前音量：$db dBFS · 被系统静音：$silenced\n" +
            (if (error.isNotEmpty()) "错误：$error" else "")
    }

    private fun dp(value: Int) = (resources.displayMetrics.density * value).toInt()
    private fun toast(value: String) = Toast.makeText(this, value, Toast.LENGTH_LONG).show()
}
