package io.github.gallofok.hotwordbridge

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import org.json.JSONArray
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
    private val onStage: (String) -> Unit,
    private val limitedGrammar: Boolean = false
) : AutoCloseable {

    private val model: Model
    private val recognizer: Recognizer
    private var lastPartial = ""
    private var utterances = 0
    private var activeGrammar = false

    init {
        onStage("Vosk 模型检查／首次使用时自动解压（约 50 MB）")
        val modelDir = extractModel(context)
        onStage("Vosk 正在加载英语模型（可能需要数秒）")
        model = Model(modelDir.absolutePath)
        try {
            // A small grammar is an OPT-IN experiment, never the default.
            // [unk] keeps a path for speech outside our target phrases.
            // We retain a few hard negatives to make false positives observable.
            val grammar = JSONArray(listOf(
                "hey chat g p t",
                "hey chat gpt",
                "hey chat gee pee tee",
                "hello world",
                "hey google",
                "hey check your pity",
                "[unk]"
            )).toString()
            var fallback = ""
            recognizer = if (limitedGrammar) {
                try {
                    val constrained = Recognizer(model, 16000.0f, grammar)
                    activeGrammar = true
                    constrained
                } catch (error: Exception) {
                    fallback = "限定词表不可用，已回退完整转写：" +
                        (error.message ?: error.javaClass.simpleName)
                    Recognizer(model, 16000.0f)
                }
            } else Recognizer(model, 16000.0f)
            prefs.edit()
                .putString("vosk_effective_mode",
                    if (activeGrammar) "受限词表实验" else "完整英语转写")
                .putString("vosk_grammar_warning", fallback)
                .apply()
        } catch (error: Throwable) {
            model.close()
            throw error
        }
        prefs.edit()
            .putString("vosk_partial", "")
            .putString("vosk_final", "")
            .putString("vosk_log", "")
            .putString("vosk_candidate", "")
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

        // Read-only hint about likely phonetic confusions. It never triggers.
        // Avoid broad matching: "chat" by itself must not launch ChatGPT.
        val normalized = text.lowercase()
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
        if (normalized.startsWith("hey ") &&
            Regex("""\b(chat|check|chet|church|treachery)\b""")
                .containsMatchIn(normalized)) {
            prefs.edit().putString("vosk_candidate",
                text + if (ended) "（完整句）" else "（实时草稿）").apply()
        }
        // Constrained grammars may hallucinate targets on unrelated speech.
        // Only consider a completed utterance for wake in this experiment.
        if (activeGrammar && !ended) return null
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
