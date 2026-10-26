package io.github.gallofok.hotwordbridge

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.getFeatureConfig
import kotlin.concurrent.thread

/**
 * Uses an upstream REAL human voice sample, as 16 kHz mono little-endian PCM.
 * This bypasses the microphone but tests the Android-native ONNX runtime,
 * bundled model and 100 ms audio streaming. It never opens ChatGPT.
 */
object KwsSelfTest {
    private const val SAMPLE_RATE = 16000
    private const val EXPECTED = "LIGHT UP"

    fun run(context: Context, report: (Boolean, String) -> Unit) {
        val app = context.applicationContext
        thread(name = "kws-recording-selftest") {
            var detector: KeywordSpotter? = null
            var passed = false
            var message = "测试未完成"
            try {
                val directory = "kws"
                detector = KeywordSpotter(
                    assetManager = app.assets,
                    config = KeywordSpotterConfig(
                        featConfig = getFeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                        modelConfig = OnlineModelConfig(
                            transducer = OnlineTransducerModelConfig(
                                encoder = "$directory/encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
                                decoder = "$directory/decoder-epoch-12-avg-2-chunk-16-left-64.onnx",
                                joiner = "$directory/joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
                            ),
                            tokens = "$directory/tokens.txt",
                            modelType = "zipformer2",
                            numThreads = 2
                        ),
                        keywordsFile = "$directory/official_keywords.txt",
                        keywordsScore = 1.5f,
                        keywordsThreshold = 0.25f
                    )
                )
                val kws = detector
                val stream = kws.createStream()
                try {
                    val pcm = app.assets.open("$directory/official-light-up.pcm")
                        .use { it.readBytes() }
                    check(pcm.size > SAMPLE_RATE && pcm.size % 2 == 0) {
                        "内置录音无效（不是 16-bit PCM）"
                    }

                    var found = ""
                    fun feed(samples: FloatArray) {
                        stream.acceptWaveform(samples, SAMPLE_RATE)
                        while (kws.isReady(stream)) {
                            kws.decode(stream)
                            val result = kws.getResult(stream).keyword
                            if (result.isNotBlank()) {
                                found = result
                                return
                            }
                        }
                    }

                    var offset = 0
                    while (offset < pcm.size && found.isBlank()) {
                        val count = minOf(1600, (pcm.size - offset) / 2)
                        if (count <= 0) break
                        val samples = FloatArray(count) { i ->
                            val k = offset + i * 2
                            val value = ((pcm[k + 1].toInt() shl 8) or
                                (pcm[k].toInt() and 0xff)).toShort().toInt()
                            value / 32768.0f
                        }
                        feed(samples)
                        offset += count * 2
                    }
                    if (found.isBlank()) feed(FloatArray(12800))
                    passed = found.contains(EXPECTED)
                    message = if (passed) {
                        "标准真人录音自测通过：识别到 LIGHT UP（无需麦克风）"
                    } else {
                        "标准真人录音自测失败：预期 LIGHT UP，实际结果：" +
                            found.ifBlank { "无" }
                    }
                } finally {
                    try { stream.release() } catch (_: Throwable) {}
                }
            } catch (problem: Throwable) {
                message = "标准录音自测出错：" +
                    (problem.message ?: problem.javaClass.simpleName)
            } finally {
                try { detector?.release() } catch (_: Throwable) {}
                val ok = passed
                val resultText = message
                Handler(Looper.getMainLooper()).post { report(ok, resultText) }
            }
        }
    }
}
