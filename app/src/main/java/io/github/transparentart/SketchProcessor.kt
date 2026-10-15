package io.github.transparentart

import android.graphics.Bitmap
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.roundToInt

/** All sliders are normalised to 0..1 so the UI does not need to know the maths. */
data class SketchParams(
    val detail: Float = 0.5f,
    val sensitivity: Float = 0.5f,
    val thickness: Float = 0.3f,
    /** 0 = no shading, otherwise the number of tones (2..5). */
    val shadeLevels: Int = 3,
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
 * Turns a photo into a tracing guide: outlines from a difference of Gaussians
 * plus a few flat tones for shading. The result is the ink colour on a
 * transparent background, so it can sit directly on top of the camera feed.
 */
class SketchProcessor(source: Bitmap) {
    private val width = source.width
    private val height = source.height
    private val gray: FloatArray
    private val tone: FloatArray
    private val blurCache = LinkedHashMap<Int, FloatArray>()

    init {
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        gray = FloatArray(pixels.size) { i ->
            val c = pixels[i]
            val a = (c ushr 24) / 255f
            val r = (c shr 16 and 0xFF) / 255f
            val g = (c shr 8 and 0xFF) / 255f
            val b = (c and 0xFF) / 255f
            // Transparent areas become white paper.
            (0.299f * r + 0.587f * g + 0.114f * b) * a + (1f - a)
        }
        tone = gaussianBlur(gray, width, height, 3f)
    }

    fun render(params: SketchParams): Bitmap {
        val sigma = lerp(2.4f, 0.6f, params.detail)
        val surround = sigma * lerp(1.4f, 3.2f, params.thickness)
        val threshold = lerp(0.045f, 0.004f, params.sensitivity)
        val ramp = threshold * 1.5f

        val inner = blurred(sigma)
        val outer = blurred(surround)
        val levels = params.shadeLevels
        val rgb = params.inkColor and 0x00FFFFFF

        val out = IntArray(width * height)
        for (i in out.indices) {
            // Positive where the pixel is darker than its neighbourhood: the dark side of an edge.
            val dog = outer[i] - inner[i]
            var ink = ((dog - threshold) / ramp).coerceIn(0f, 1f)

            if (levels >= 2) {
                val step = floor(tone[i] * levels).coerceAtMost(levels - 1f)
                val lightness = step / (levels - 1)
                val shade = (1f - lightness) * params.shadeStrength
                if (shade > ink) ink = shade
            }

            out[i] = ((ink * 255f).roundToInt() shl 24) or rgb
        }
        return Bitmap.createBitmap(out, width, height, Bitmap.Config.ARGB_8888)
    }

    /** Blurs are the slow part, and sliders revisit the same radii, so keep a few around. */
    private fun blurred(sigma: Float): FloatArray {
        val key = (sigma * 20f).roundToInt()
        synchronized(blurCache) {
            blurCache[key]?.let { return it }
        }
        val result = gaussianBlur(gray, width, height, key / 20f)
        synchronized(blurCache) {
            if (blurCache.size >= 6) blurCache.remove(blurCache.keys.first())
            blurCache[key] = result
        }
        return result
    }
}

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t.coerceIn(0f, 1f)

/** Separable Gaussian blur with clamped edges. */
internal fun gaussianBlur(src: FloatArray, w: Int, h: Int, sigma: Float): FloatArray {
    val radius = ceil(sigma * 3f).toInt().coerceAtLeast(1)
    val kernel = FloatArray(radius * 2 + 1) {
        val x = (it - radius).toFloat()
        exp(-x * x / (2f * sigma * sigma))
    }
    val sum = kernel.sum()
    for (i in kernel.indices) kernel[i] /= sum

    val tmp = FloatArray(src.size)
    for (y in 0 until h) {
        val row = y * w
        for (x in 0 until w) {
            var acc = 0f
            for (k in -radius..radius) {
                val xx = (x + k).coerceIn(0, w - 1)
                acc += src[row + xx] * kernel[k + radius]
            }
            tmp[row + x] = acc
        }
    }

    val out = FloatArray(src.size)
    for (y in 0 until h) {
        for (x in 0 until w) {
            var acc = 0f
            for (k in -radius..radius) {
                val yy = (y + k).coerceIn(0, h - 1)
                acc += tmp[yy * w + x] * kernel[k + radius]
            }
            out[y * w + x] = acc
        }
    }
    return out
}
