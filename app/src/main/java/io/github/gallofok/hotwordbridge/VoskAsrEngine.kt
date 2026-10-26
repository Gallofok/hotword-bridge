package io.github.gallofok.hotwordbridge

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Full-vocabulary offline recognition for comparison with sherpa KWS.
 * Text is stored only in app-private preferences for on-screen diagnostics.
 * The on-device WAV data is never uploaded or kept after decoding.
 */
class VoskAsrEngine(
    context: Context,
    private val prefs: SharedPreferences,
    private val onStage: (String) -> Unit
) : AutoCloseable {

    private val model: Model
    private val recognizer: Recognizer
    private var lastPartial = ""
    private var utterances = 0

    init {
        onStage("Vosk 模型检查／首次使用时自动解压（约 50 MB）")
        val modelDir = extractModel(context)
        onStage("Vosk 正在加载英语模型（可能需要数秒）")
        model = Model(modelDir.absolutePath)
        try {
            recognizer = Recognizer(model, 16000.0f)
        } catch (error: Throwable) {
            model.close()
            throw error
        }
        prefs.edit()
            .putString("vosk_partial", "")
            .putString("vosk_final", "")
            .putString("vosk_log", "")
            .putInt("vosk_utterances", 0)
            .apply()
    }

    fun accept(buffer: ShortArray, count: Int): String? {
        val ended = recognizer.acceptWaveForm(buffer, count)
        val text = try {
            if (ended) {
                JSONObject(recognizer.result).optString("text")
            } else {
                JSONObject(recognizer.partialResult).optString("partial")
            }
        } catch (_: Exception) {
            ""
        }.trim()
        if (ended && text.isNotEmpty()) {
            utterances += 1
            val old = prefs.getString("vosk_log", "").orEmpty()
            val updated = (if (old.isBlank()) text else "$old\n$text")
                .takeLast(900)
            prefs.edit()
                .putInt("vosk_utterances", utterances)
                .putString("vosk_final", text)
                .putString("vosk_partial", "")
                .putString("vosk_log", updated)
                .apply()
            lastPartial = ""
        } else if (!ended && text != lastPartial) {
            lastPartial = text
            prefs.edit().putString("vosk_partial", text).apply()
        }

        // Match exact words, not arbitrary substrings; avoid triggering on "chat" alone.
        val normalized = text.lowercase()
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
        if (Regex("""\bhello\s+world\b""").containsMatchIn(normalized)) {
            return "VOSK_HELLO_WORLD_TEST"
        }
        if (Regex("""\bhey\s+chat\s+(?:g\s*p\s*t|gee\s+pee\s+tee)\b""")
                .containsMatchIn(normalized)) {
            return "VOSK_HEY_CHATGPT"
        }
        return null
    }

    override fun close() {
        recognizer.close()
        model.close()
    }

    private fun extractModel(context: Context): File {
        val target = File(context.filesDir, "vosk-small-en-us-0.15")
        val marker = File(target, ".install-complete")
        if (marker.isFile &&
            File(target, "am/final.mdl").isFile &&
            File(target, "conf/model.conf").isFile) {
            return target
        }
        if (target.exists()) target.deleteRecursively()
        check(target.mkdirs()) { "无法创建 Vosk 模型目录" }

        // Hardcoded trusted source archive, SHA256 verified in the GitHub build.
        val prefix = "vosk-model-small-en-us-0.15/"
        var extractedBytes = 0L
        try {
            context.assets.open("vosk-model.zip").use { raw ->
                ZipInputStream(raw.buffered()).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        val name = entry.name
                        if (name == prefix.dropLast(1)) {
                            zip.closeEntry()
                            continue
                        }
                        check(name.startsWith(prefix)) {
                            "Vosk 压缩包目录结构不符合预期"
                        }
                        val relative = name.removePrefix(prefix)
                        if (relative.isEmpty()) {
                            zip.closeEntry()
                            continue
                        }
                        val file = File(target, relative)
                        val canonicalRoot = target.canonicalPath + File.separator
                        check(file.canonicalPath.startsWith(canonicalRoot)) {
                            "Vosk 模型包含不安全的路径"
                        }
                        if (entry.isDirectory) {
                            check(file.isDirectory || file.mkdirs())
                        } else {
                            check(file.parentFile?.isDirectory == true || file.parentFile?.mkdirs() == true)
                            file.outputStream().buffered().use { output ->
                                val bytes = ByteArray(8192)
                                while (true) {
                                    val n = zip.read(bytes)
                                    if (n < 0) break
                                    output.write(bytes, 0, n)
                                    extractedBytes += n
                                    check(extractedBytes < 250L * 1024 * 1024) {
                                        "Vosk 模型意外超过 250 MB"
                                    }
                                }
                            }
                        }
                        zip.closeEntry()
                    }
                }
            }
            check(File(target, "am/final.mdl").isFile &&
                    File(target, "conf/model.conf").isFile) {
                "Vosk 模型解压不完整"
            }
            marker.writeText("vosk-model-small-en-us-0.15\n")
        } catch (error: Throwable) {
            target.deleteRecursively()
            throw error
        }
        return target
    }
}
