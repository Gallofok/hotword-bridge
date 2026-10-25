package io.github.gallofok.hotwordbridge

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File

class MainActivity : Activity() {
    companion object {
        @Volatile var isForeground: Boolean = false
        private const val REQUEST_PERMISSIONS = 110
        private const val REQUEST_MODEL = 111
        private const val PREFS = "hotword_prefs"
    }

    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }
    private lateinit var keyInput: EditText
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

        fun heading(text: String, size: Float) {
            layout.addView(TextView(this).apply {
                this.text = text
                textSize = size
                setPadding(0, dp(9), 0, dp(9))
            })
        }
        fun button(title: String, click: () -> Unit) {
            layout.addView(Button(this).apply {
                text = title
                setOnClickListener { click() }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            })
        }

        heading("HotwordBridge", 26f)
        heading("离线检测唤醒词；不需要 Root。请先安装官方 ChatGPT App。", 15f)
        heading("Picovoice AccessKey（仅存储在本机）", 16f)

        keyInput = EditText(this).apply {
            setSingleLine()
            hint = "输入 AccessKey"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs.getString("access_key", ""))
        }
        layout.addView(keyInput)
        button("保存 AccessKey") {
            saveKey()
            toast("AccessKey 已保存到手机")
        }
        button("导入 Hey ChatGPT Android .ppn 模型") { importModel() }
        button("使用内置 Porcupine 唤醒词（测试）") {
            prefs.edit().putString("keyword_mode", "built_in").apply()
            refreshStatus()
            toast("测试模式：请说 Porcupine")
        }

        heading("开始之前，请允许麦克风及通知权限。", 14f)
        button("开始监听") { startListening() }
        button("停止监听") {
            stopService(Intent(this, ListeningService::class.java))
            prefs.edit().putBoolean("running", false).apply()
            refreshStatus()
        }
        button("手动打开 ChatGPT") { openChatGpt() }
        statusView = TextView(this).apply {
            textSize = 15f
            gravity = Gravity.START
            setPadding(0, dp(18), 0, dp(16))
        }
        layout.addView(statusView)
        heading(
            "说明：如果本应用处于后台/锁屏，识别唤醒词后将显示一条可点击的通知。" +
                "Android 不允许普通后台应用可靠地自动弹出 ChatGPT。" +
                "每次识别后会停止麦克风监听，避免与 ChatGPT 语音争用麦克风。",
            13f
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

    private fun saveKey() {
        prefs.edit().putString("access_key", keyInput.text.toString().trim()).apply()
    }

    private fun importModel() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQUEST_MODEL)
    }

    @Deprecated("Uses the platform document picker; compatible with minSdk 26")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_MODEL || resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
        try {
            val cursor = contentResolver.query(
                uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
            )
            val filename = cursor?.use {
                if (it.moveToFirst()) it.getString(0) else ""
            } ?: ""
            require(filename.endsWith(".ppn", ignoreCase = true)) {
                "请选择以 .ppn 结尾的 Android 唤醒词模型"
            }
            val target = File(filesDir, "wakeword.ppn")
            contentResolver.openInputStream(uri).use { input ->
                require(input != null) { "无法读取文件" }
                target.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            prefs.edit().putString("keyword_mode", "custom").apply()
            toast("模型已导入：$filename")
            refreshStatus()
        } catch (error: Exception) {
            toast("导入失败：${error.message}")
        }
    }

    private fun startListening() {
        saveKey()
        if (prefs.getString("access_key", "").isNullOrBlank()) {
            toast("请先填写 Picovoice AccessKey")
            return
        }
        if (prefs.getString("keyword_mode", "custom") == "custom" &&
            !File(filesDir, "wakeword.ppn").isFile
        ) {
            toast("请导入 Android .ppn，或者切换到内置测试模式")
            return
        }
        val wanted = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            wanted.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            wanted.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (wanted.isNotEmpty()) {
            startAfterPermission = true
            requestPermissions(wanted.toTypedArray(), REQUEST_PERMISSIONS)
        } else {
            reallyStartListening()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_PERMISSIONS || !startAfterPermission) return
        startAfterPermission = false
        val audioOk = checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        val notificationOk = Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (audioOk && notificationOk) reallyStartListening()
        else toast("后台提示需要麦克风及通知权限；请在设置里允许后重试")
    }

    private fun reallyStartListening() {
        try {
            val intent = Intent(this, ListeningService::class.java)
                .setAction(ListeningService.ACTION_START)
            startForegroundService(intent)
            statusView.text = "正在启动监听…"
        } catch (error: Exception) {
            toast("无法启动监听：${error.message}")
        }
    }

    private fun openChatGpt() {
        try {
            startActivity(ChatGptLauncher.intent(this))
        } catch (error: Exception) {
            toast("打开 ChatGPT 失败：${error.message}")
        }
    }

    private fun refreshStatus() {
        val custom = prefs.getString("keyword_mode", "custom") == "custom"
        val mode = if (custom) "Hey ChatGPT 自定义模型" else "内置 Porcupine 测试词"
        val ready = if (File(filesDir, "wakeword.ppn").isFile) "已导入" else "尚未导入"
        val service = if (prefs.getBoolean("running", false)) "正在监听" else "未监听"
        val event = prefs.getString("last_event", "").orEmpty()
        val error = prefs.getString("last_error", "").orEmpty()
        statusView.text = "状态：$service\n唤醒模式：$mode\n自定义模型：$ready" +
            if (error.isNotEmpty()) "\n错误：$error" else if (event.isNotEmpty()) "\n最近：$event" else ""
    }

    private fun dp(value: Int): Int = (resources.displayMetrics.density * value).toInt()
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
