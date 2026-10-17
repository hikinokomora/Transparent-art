package io.github.transparentart

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import java.io.Closeable
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.roundToInt

/** Line drawing styles of the Informative Drawings model. */
enum class NeuralStyle(val asset: String) {
    CONTOUR("models/informative_drawings_contour.onnx"),
    SKETCH("models/informative_drawings_sketch.onnx"),
}

/**
 * Runs the Informative Drawings generator (Chan, Durand, Isola, CVPR 2022, MIT License)
 * on the device. The network is slow at full resolution, so it sees a reduced copy of the
 * photo and the drawing is scaled back up afterwards.
 */
class NeuralSketcher(private val context: Context) : Closeable {
    // Created on first use, so a device where the native library fails only loses this mode.
    private val env by lazy { OrtEnvironment.getEnvironment() }
    private val sessions = HashMap<NeuralStyle, OrtSession>()

    private fun session(style: NeuralStyle): OrtSession = sessions.getOrPut(style) {
        val bytes = context.assets.open(style.asset).use { it.readBytes() }
        val options = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(1, 4))
        }
        env.createSession(bytes, options)
    }

    /**
     * Returns ink darkness 0..1 for every pixel of [source] (row-major, source size).
     */
    @Synchronized
    fun draw(source: Bitmap, style: NeuralStyle, workSide: Int = WORK_SIDE): FloatArray {
        val s = workSide.toFloat() / max(source.width, source.height)
        // The network downsamples twice, so its input must be a multiple of 4.
        val w = ((source.width * s / 4).roundToInt() * 4).coerceAtLeast(4)
        val h = ((source.height * s / 4).roundToInt() * 4).coerceAtLeast(4)
        val small = Bitmap.createScaledBitmap(source, w, h, true)
        val pixels = IntArray(w * h)
        small.getPixels(pixels, 0, w, 0, 0, w, h)
        if (small !== source) small.recycle()

        val plane = w * h
        val input = FloatArray(3 * plane)
        for (i in 0 until plane) {
            val c = pixels[i]
            val a = (c ushr 24) / 255f
            // Composite transparent areas onto white paper.
            input[i] = ((c shr 16 and 0xFF) / 255f) * a + (1f - a)
            input[plane + i] = ((c shr 8 and 0xFF) / 255f) * a + (1f - a)
            input[2 * plane + i] = ((c and 0xFF) / 255f) * a + (1f - a)
        }

        val session = session(style)
        val drawing = FloatArray(plane)
        OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, 3, h.toLong(), w.toLong())).use { tensor ->
            session.run(mapOf(session.inputNames.first() to tensor)).use { result ->
                (result[0] as OnnxTensor).floatBuffer.get(drawing)
            }
        }
        // The model draws dark lines on white; turn that into ink amount.
        for (i in 0 until plane) drawing[i] = (1f - drawing[i]).coerceIn(0f, 1f)
        return upscale(drawing, w, h, source.width, source.height)
    }

    @Synchronized
    override fun close() {
        sessions.values.forEach { it.close() }
        sessions.clear()
    }

    companion object {
        /** Longest side the network works at: a balance between line detail and speed on phones. */
        const val WORK_SIDE = 640
    }
}

/** Bilinear resize of a single-channel image. */
private fun upscale(src: FloatArray, sw: Int, sh: Int, dw: Int, dh: Int): FloatArray {
    val out = FloatArray(dw * dh)
    val sx = sw.toFloat() / dw
    val sy = sh.toFloat() / dh
    for (y in 0 until dh) {
        val fy = ((y + 0.5f) * sy - 0.5f).coerceIn(0f, sh - 1f)
        val y0 = fy.toInt()
        val y1 = minOf(y0 + 1, sh - 1)
        val ty = fy - y0
        for (x in 0 until dw) {
            val fx = ((x + 0.5f) * sx - 0.5f).coerceIn(0f, sw - 1f)
            val x0 = fx.toInt()
            val x1 = minOf(x0 + 1, sw - 1)
            val tx = fx - x0
            val top = src[y0 * sw + x0] * (1 - tx) + src[y0 * sw + x1] * tx
            val bottom = src[y1 * sw + x0] * (1 - tx) + src[y1 * sw + x1] * tx
            out[y * dw + x] = top * (1 - ty) + bottom * ty
        }
    }
    return out
}
