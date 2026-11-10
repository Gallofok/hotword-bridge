package io.github.gallofok.hotwordbridge

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.nio.FloatBuffer

/**
 * Streaming openWakeWord detector. Pure Kotlin + ONNX Runtime, no Android classes,
 * so the same code runs in JVM unit tests.
 *
 * It mirrors training/oww/stream_ref.py step by step. Audio arrives in chunks of
 * [CHUNK] samples (80 ms at 16 kHz). For every chunk:
 *  1. mel: the last 1760 samples (chunk + 480 of history) give exactly 8 new frames
 *  2. embedding: the last 76 mel frames give one 96-value vector
 *  3. score: the classifier looks at the last [window] embeddings
 */
class WakeWordDetector(
    melModel: ByteArray,
    embeddingModel: ByteArray,
    classifierModel: ByteArray
) : AutoCloseable {
    companion object {
        const val SAMPLE_RATE = 16000
        const val CHUNK = 1280
        private const val HISTORY = 480
        private const val MEL_BINS = 32
        private const val MEL_WINDOW = 76
        private const val EMBEDDING = 96
        private const val FRAMES_PER_CHUNK = 8
    }

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val mel = open(melModel)
    private val embedding = open(embeddingModel)
    private val classifier = open(classifierModel)
    private val melInput = mel.inputNames.first()
    private val embeddingInput = embedding.inputNames.first()
    private val classifierInput = classifier.inputNames.first()

    /** Number of embeddings the classifier needs (80 ms each). */
    val window: Int = run {
        val shape = (classifier.inputInfo.getValue(classifierInput).info as TensorInfo).shape
        if (shape.size > 1 && shape[1] > 0) shape[1].toInt() else 16
    }

    private val raw = FloatArray(HISTORY + CHUNK)
    private val mels = FloatArray(MEL_WINDOW * MEL_BINS)
    private var melFrames = 0
    private val embeddings = FloatArray(window * EMBEDDING)
    private var embeddingCount = 0

    private fun open(model: ByteArray): OrtSession {
        val options = OrtSession.SessionOptions()
        options.setIntraOpNumThreads(1)
        return env.createSession(model, options)
    }

    /** Forget all audio heard so far. */
    fun reset() {
        raw.fill(0f)
        mels.fill(0f)
        embeddings.fill(0f)
        melFrames = 0
        embeddingCount = 0
    }

    /**
     * Feed exactly [CHUNK] samples of 16 kHz mono PCM.
     * Returns the wake score (0..1), or null while the buffers are still filling.
     */
    fun accept(chunk: ShortArray): Float? {
        require(chunk.size == CHUNK) { "expected $CHUNK samples, got ${chunk.size}" }
        // Keep the last 480 samples of the previous call in front of the new chunk.
        System.arraycopy(raw, CHUNK, raw, 0, HISTORY)
        for (i in 0 until CHUNK) raw[HISTORY + i] = chunk[i].toFloat()

        // 1. mel frames, scaled as openWakeWord does (x / 10 + 2)
        val newMel = run(mel, melInput, raw, longArrayOf(1, raw.size.toLong()))
        val frames = newMel.size / MEL_BINS
        check(frames == FRAMES_PER_CHUNK) { "mel model returned $frames frames" }
        System.arraycopy(mels, frames * MEL_BINS, mels, 0, (MEL_WINDOW - frames) * MEL_BINS)
        val melOffset = (MEL_WINDOW - frames) * MEL_BINS
        for (i in newMel.indices) mels[melOffset + i] = newMel[i] / 10f + 2f
        melFrames = minOf(MEL_WINDOW, melFrames + frames)
        if (melFrames < MEL_WINDOW) return null

        // 2. one embedding for the last 76 frames
        val vector = run(
            embedding, embeddingInput, mels,
            longArrayOf(1, MEL_WINDOW.toLong(), MEL_BINS.toLong(), 1)
        )
        check(vector.size == EMBEDDING) { "embedding model returned ${vector.size} values" }
        System.arraycopy(embeddings, EMBEDDING, embeddings, 0, (window - 1) * EMBEDDING)
        System.arraycopy(vector, 0, embeddings, (window - 1) * EMBEDDING, EMBEDDING)
        embeddingCount = minOf(window, embeddingCount + 1)
        if (embeddingCount < window) return null

        // 3. classifier score
        return run(
            classifier, classifierInput, embeddings,
            longArrayOf(1, window.toLong(), EMBEDDING.toLong())
        )[0]
    }

    private fun run(session: OrtSession, input: String, data: FloatArray, shape: LongArray): FloatArray {
        OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape).use { tensor ->
            session.run(mapOf(input to tensor)).use { result ->
                val buffer = (result.get(0) as OnnxTensor).floatBuffer
                val out = FloatArray(buffer.remaining())
                buffer.get(out)
                return out
            }
        }
    }

    override fun close() {
        classifier.close()
        embedding.close()
        mel.close()
    }
}
