package io.github.gallofok.hotwordbridge

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runs the real models on the JVM. The fixtures are synthetic speech from the
 * training run (no real voice). Expected scores come from the Python reference
 * training/oww/stream_ref.py, so this test checks that the Kotlin port computes
 * the same numbers.
 */
class WakeWordDetectorTest {
    private val assets = File("src/main/assets/oww")
    private val fixtures = File("src/test/resources/oww")

    // file -> highest score from stream_ref.py (3 s silence, clip, 1 s silence)
    private val expected = mapOf(
        "negative/synthetic-1.wav" to 0.0018f,
        "negative/synthetic-2.wav" to 0.0423f,
        "negative/synthetic-3.wav" to 0.0009f,
        "negative/synthetic-4.wav" to 0.0012f,
        "positive/synthetic-1.wav" to 0.9494f,
        "positive/synthetic-2.wav" to 0.9504f,
        "positive/synthetic-3.wav" to 0.9496f,
        "positive/synthetic-4.wav" to 0.9506f
    )

    private fun detector() = WakeWordDetector(
        File(assets, "melspectrogram.onnx").readBytes(),
        File(assets, "embedding_model.onnx").readBytes(),
        File(assets, "hey_chatgpt.onnx").readBytes()
    )

    /** Minimal reader for 16 kHz mono 16-bit PCM WAV files. */
    private fun readWav(file: File): ShortArray {
        val bytes = file.readBytes()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var position = 12
        while (position + 8 <= bytes.size) {
            val id = String(bytes, position, 4, Charsets.US_ASCII)
            val size = buffer.getInt(position + 4)
            if (id == "fmt ") {
                assertEquals("mono", 1, buffer.getShort(position + 10).toInt())
                assertEquals("16 kHz", 16000, buffer.getInt(position + 12))
                assertEquals("16 bit", 16, buffer.getShort(position + 22).toInt())
            }
            if (id == "data") {
                val count = minOf(size, bytes.size - position - 8) / 2
                return ShortArray(count) { buffer.getShort(position + 8 + it * 2) }
            }
            position += 8 + size + (size and 1)
        }
        error("no data chunk in ${file.name}")
    }

    private fun maxScore(detector: WakeWordDetector, clip: ShortArray): Float {
        detector.reset()
        val audio = ShortArray(3 * 16000) + clip + ShortArray(16000)
        var best = 0f
        var offset = 0
        while (offset + WakeWordDetector.CHUNK <= audio.size) {
            val score = detector.accept(audio.copyOfRange(offset, offset + WakeWordDetector.CHUNK))
            if (score != null && score > best) best = score
            offset += WakeWordDetector.CHUNK
        }
        return best
    }

    @Test
    fun scoresMatchPythonReference() {
        detector().use { detector ->
            for ((name, want) in expected) {
                val got = maxScore(detector, readWav(File(fixtures, name)))
                assertTrue("$name: expected $want, got $got", abs(got - want) < 0.02f)
            }
        }
    }

    @Test
    fun wakePhraseTriggersAndOtherSpeechDoesNot() {
        detector().use { detector ->
            for (name in expected.keys) {
                val score = maxScore(detector, readWav(File(fixtures, name)))
                if (name.startsWith("positive/")) assertTrue("$name scored $score", score >= 0.7f)
                else assertTrue("$name scored $score", score < 0.3f)
            }
        }
    }

    @Test
    fun returnsNullUntilBuffersAreFull() {
        detector().use { detector ->
            assertTrue(detector.window > 0)
            assertNull(detector.accept(ShortArray(WakeWordDetector.CHUNK)))
            var calls = 1
            while (detector.accept(ShortArray(WakeWordDetector.CHUNK)) == null) {
                calls += 1
                assertTrue("still filling after $calls chunks", calls < 100)
            }
            // 76 mel frames need 10 chunks; then one embedding per chunk
            assertEquals(9 + detector.window, calls + 1)
        }
    }

    @Test
    fun silenceDoesNotTrigger() {
        detector().use { detector ->
            assertTrue(maxScore(detector, ShortArray(5 * 16000)) < 0.3f)
        }
    }
}
