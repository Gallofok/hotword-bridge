package io.github.gallofok.hotwordbridge

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

/**
 * Experimental launch paths for the *official* ChatGPT Android application.
 *
 * The explicit AssistantActivity is UNDOCUMENTED and may disappear or stop
 * being exported in a future ChatGPT release. This class checks component
 * export/permission before use and has a supported-app-launch fallback.
 *
 * startActivity() returning without throwing does NOT prove Voice connected.
 * The diagnostic UI explicitly asks the user to verify the actual screen.
 */
object ChatGptLauncher {
    const val MODE_AUTO = "VOICE_AUTO"
    const val MODE_DIRECT = "VOICE_DIRECT"
    const val MODE_DEEPLINK = "VOICE_LINK"
    const val MODE_APP = "APP_ONLY"

    private const val PACKAGE = "com.openai.chatgpt"
    private val VOICE_COMPONENT = ComponentName(
        PACKAGE, "com.openai.voice.assistant.AssistantActivity"
    )

    fun modeLabel(mode: String): String = when (mode) {
        MODE_DIRECT -> "直接语音 Activity（实验）"
        MODE_DEEPLINK -> "语音链接（实验）"
        MODE_APP -> "只打开 ChatGPT"
        else -> "语音优先：自动选择"
    }

    fun isDirectAvailable(context: Context): Boolean = try {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getActivityInfo(VOICE_COMPONENT, 0)
        info.exported && info.permission.isNullOrEmpty()
    } catch (_: Exception) {
        false
    }

    private fun directIntent() = Intent().apply {
        component = VOICE_COMPONENT
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun deeplinkIntent() = Intent(
        Intent.ACTION_VIEW, Uri.parse("https://chat.com/?mode=voice")
    ).apply {
        setPackage(PACKAGE)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun appIntent(context: Context): Intent {
        val launcher = context.packageManager.getLaunchIntentForPackage(PACKAGE)
            ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://chatgpt.com"))
        return launcher.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        )
    }

    fun launch(context: Context, mode: String = MODE_AUTO): String {
        val candidates = when (mode) {
            MODE_DIRECT -> listOf("直接语音 Activity" to directIntent())
            MODE_DEEPLINK -> listOf("语音 Deep Link" to deeplinkIntent())
            MODE_APP -> listOf("ChatGPT 主界面" to appIntent(context))
            else -> buildList {
                if (isDirectAvailable(context)) {
                    add("直接语音 Activity" to directIntent())
                }
                add("语音 Deep Link" to deeplinkIntent())
                add("ChatGPT 主界面（回退）" to appIntent(context))
            }
        }

        var lastError: Exception? = null
        for ((label, intent) in candidates) {
            try {
                if (intent.component == VOICE_COMPONENT && !isDirectAvailable(context)) {
                    throw SecurityException("ChatGPT 语音 Activity 不存在或未对外开放")
                }
                context.startActivity(intent)
                return "已请求：" + label + "（需观察是否真的进入语音对话）"
            } catch (error: Exception) {
                lastError = error
            }
        }
        throw IllegalStateException(
            "ChatGPT 启动失败：" + (lastError?.message ?: "未知错误"), lastError
        )
    }

    /**
     * The user taps a notification, so Android is allowed to start an Activity.
     * A PendingIntent launches one best-effort target directly; it has no
     * callback to confirm success or fall back after Activity launch.
     */
    fun pendingIntent(context: Context, mode: String = MODE_AUTO): PendingIntent {
        val selected = when (mode) {
            MODE_DIRECT -> if (isDirectAvailable(context)) directIntent() else deeplinkIntent()
            MODE_DEEPLINK -> deeplinkIntent()
            MODE_APP -> appIntent(context)
            else -> if (isDirectAvailable(context)) directIntent() else deeplinkIntent()
        }
        val reachable = try {
            selected.resolveActivity(context.packageManager) != null
        } catch (_: Exception) { false }
        val intent = if (reachable) selected else appIntent(context)
        return PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
