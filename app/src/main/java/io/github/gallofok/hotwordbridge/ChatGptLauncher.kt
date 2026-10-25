package io.github.gallofok.hotwordbridge

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri

object ChatGptLauncher {
    private const val CHATGPT_PACKAGE = "com.openai.chatgpt"

    // This launches the official app, not a guaranteed deep link to Voice mode.
    // Enable "Start with Voice" in ChatGPT settings if offered.
    fun intent(context: Context): Intent {
        val launcher = context.packageManager.getLaunchIntentForPackage(CHATGPT_PACKAGE)
            ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://chatgpt.com"))
        return launcher.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }

    fun pendingIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context, 0, intent(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
}
