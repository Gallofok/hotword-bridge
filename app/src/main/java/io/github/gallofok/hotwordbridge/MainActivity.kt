package io.github.gallofok.hotwordbridge

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    companion object {
        @Volatile var isForeground = false
        private const val REQUEST_PERMISSIONS = 110
    }

    private val preferences by lazy { getSharedPreferences("hotword_prefs", MODE_PRIVATE) }
    private lateinit var statusView: TextView
    private var startAfterPermission = false

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

        text("HotwordBridge · 0.2", 26f)
        text("免费离线语音唤醒 · sherpa-onnx · 不需要 AccessKey / .ppn / Root", 15f)
        text("唤醒词：HEY CHAT GPT（也可尝试逐字母读 G P T）", 16f)
        button("开始监听") { startListening() }
        button("停止监听") {
            stopService(Intent(this, ListeningService::class.java))
            preferences.edit().putBoolean("running", false).apply()
            refreshStatus()
        }
        button("手动打开 ChatGPT") {
            try { startActivity(ChatGptLauncher.intent(this)) }
            catch (error: Exception) { toast("启动失败：${error.message}") }
        }
        statusView = TextView(this).apply {
            textSize = 15f
            setPadding(0, dp(20), 0, dp(16))
        }
        layout.addView(statusView)
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
        if (::statusView.isInitialized) refreshStatus()
    }
    override fun onPause() {
        isForeground = false
        super.onPause()
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
        val status = if (preferences.getBoolean("running", false)) "正在监听" else "未监听"
        val event = preferences.getString("last_event", "").orEmpty()
        val error = preferences.getString("last_error", "").orEmpty()
        statusView.text = "状态：$status\n引擎：sherpa-onnx（英文模型已包含在 APK）" +
            (if (event.isNotEmpty()) "\n最近触发：$event" else "") +
            (if (error.isNotEmpty()) "\n错误：$error" else "")
    }

    private fun dp(value: Int) = (resources.displayMetrics.density * value).toInt()
    private fun toast(value: String) = Toast.makeText(this, value, Toast.LENGTH_LONG).show()
}
