package io.github.transparentart

import android.graphics.Bitmap

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
 * Turns a photo into a tracing guide: the ink colour on a transparent
 * background, so it can sit directly on top of the camera feed.
 * The image processing itself lives in [SketchEngine].
 */
class SketchProcessor(source: Bitmap) {
    private val width = source.width
    private val height = source.height
    private val engine: SketchEngine

    init {
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        engine = SketchEngine(pixels, width, height)
    }

    fun render(params: SketchParams): Bitmap =
        Bitmap.createBitmap(engine.render(params), width, height, Bitmap.Config.ARGB_8888)
}
