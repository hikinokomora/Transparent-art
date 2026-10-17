package io.github.transparentart

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.pow

enum class SketchMode { NEURAL, CLASSIC }

/** All sliders are normalised to 0..1 so the UI does not need to know the maths. */
data class SketchParams(
    val mode: SketchMode = SketchMode.NEURAL,
    // Neural mode.
    val style: NeuralStyle = NeuralStyle.CONTOUR,
    val cleanliness: Float = 0.4f,
    val darkness: Float = 0.5f,
    // Classic mode.
    val detail: Float = 0.5f,
    val sensitivity: Float = 0.5f,
    val thickness: Float = 0.3f,
    // Both modes.
    /** 0 = no shading, otherwise the number of tones (2..5). */
    val shadeLevels: Int = 0,
    val shadeStrength: Float = 0.3f,
    val inkColor: Int = INK_COLORS.first(),
)

val INK_COLORS = listOf(
    0xFF000000.toInt(),
    0xFFE53935.toInt(),
    0xFF1E88E5.toInt(),
    0xFF43A047.toInt(),
)

/**
 * Turns a photo into a tracing guide: the ink colour on a transparent
 * background, so it can sit directly on top of the camera feed.
 * Lines come either from the neural network ([NeuralSketcher]) or from the
 * classic filter ([SketchEngine]); shading is shared.
 */
class SketchProcessor(private val source: Bitmap, private val neural: NeuralSketcher) {
    private val width = source.width
    private val height = source.height
    private val engine: SketchEngine
    private val neuralLines = HashMap<NeuralStyle, NeuralLines>()

    init {
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        engine = SketchEngine(pixels, width, height)
    }

    fun render(params: SketchParams): Bitmap {
        val pixels = when (params.mode) {
            SketchMode.CLASSIC -> engine.render(params)
            SketchMode.NEURAL -> engine.finish(adjust(linesFor(params.style), params), params)
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    /** The network is the slow part, so its raw output is kept per style. */
    private fun linesFor(style: NeuralStyle): NeuralLines = synchronized(neuralLines) {
        neuralLines.getOrPut(style) { NeuralLines(neural.draw(source, style)) }
    }

    /**
     * Brings the darkest strokes to full ink, then cleanliness drops faint strokes
     * and line strength makes the remaining ones bolder.
     */
    private fun adjust(lines: NeuralLines, params: SketchParams): FloatArray {
        val floor = lerp(0.02f, 0.45f, params.cleanliness)
        val gain = lerp(1f, 2.2f, params.darkness)
        val gamma = lerp(1.2f, 0.6f, params.darkness)
        val range = 1f - floor
        val raw = lines.ink
        return FloatArray(raw.size) {
            val v = ((raw[it] / lines.peak - floor) / range).coerceIn(0f, 1f)
            (v * gain).coerceAtMost(1f).pow(gamma)
        }
    }
}

/** Raw network ink and its 99.5th percentile, which counts as full darkness. */
private class NeuralLines(val ink: FloatArray) {
    val peak: Float = run {
        val bins = 1024
        val histogram = IntArray(bins)
        for (v in ink) histogram[(v.coerceIn(0f, 1f) * (bins - 1)).toInt()]++
        val target = ink.size * 0.995f
        var acc = 0
        var bin = bins - 1
        for (b in 0 until bins) {
            acc += histogram[b]
            if (acc >= target) {
                bin = b
                break
            }
        }
        max(bin / (bins - 1f), 0.05f)
    }
}

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t.coerceIn(0f, 1f)
