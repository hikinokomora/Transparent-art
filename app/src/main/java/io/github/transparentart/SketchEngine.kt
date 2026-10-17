package io.github.transparentart

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Photo → tracing guide, in plain Kotlin so it can also run in JVM tests.
 *
 * Pipeline:
 *  1. CIELAB, so borders between colours of equal brightness are still found.
 *  2. Auto levels + auto gamma, so dark or washed-out photos behave like good ones.
 *  3. Edge-preserving smoothing (guided filter) to drop noise and fine texture.
 *  4. Colour gradient from the structure tensor, thinned by non-maximum suppression.
 *  5. Surround suppression: edges inside busy texture (fur, grass, wood) fade out.
 *  6. Canny-style hysteresis with adaptive thresholds, short fragments removed.
 *  7. Thin dark details (eyes, lips, whiskers) from a one-sided difference of Gaussians.
 *  8. A few flat shading zones for the darker part of the tonal range
 *     (also applied on top of the neural lines, see [finish]).
 *
 * Results are tuned for images whose longest side is about 1000–1300 px.
 */
class SketchEngine(argb: IntArray, val width: Int, val height: Int) {
    private val size = width * height
    private val scale = max(width, height) / 1000f

    private val lum = FloatArray(size)
    private val chromaA = FloatArray(size)
    private val chromaB = FloatArray(size)
    private val tone: FloatArray
    private val darkDog: FloatArray

    private var edgeKey = -1
    private var edgeCache: FloatArray? = null

    init {
        toLab(argb)
        normalize(lum)

        val s = scale
        val narrow = blur(lum, s)
        val wide = blur(lum, s * 2.5f)
        darkDog = FloatArray(size) { wide[it] - narrow[it] }

        tone = blur(guided(lum, lum, max(2, (14 * scale).roundToInt()), 0.02f), 2.5f * scale)
    }

    /** Classic outlines plus shading, in the ink colour. */
    @Synchronized
    fun render(params: SketchParams): IntArray = finish(classicLines(params), params)

    /**
     * Adds the shading zones to [lines] (ink darkness 0..1, left untouched) and
     * returns ARGB pixels in the ink colour on a transparent background.
     */
    fun finish(lines: FloatArray, params: SketchParams): IntArray {
        val levels = params.shadeLevels
        val rgb = params.inkColor and 0x00FFFFFF
        return IntArray(size) { i ->
            var ink = lines[i]
            if (levels >= 2) {
                val darkness = ((0.8f - tone[i]) / 0.8f).coerceIn(0f, 1f)
                val step = min(floor(darkness * levels), levels - 1f) / (levels - 1)
                ink = max(ink, step * params.shadeStrength)
            }
            ((ink.coerceIn(0f, 1f) * 255f).roundToInt() shl 24) or rgb
        }
    }

    private fun classicLines(params: SketchParams): FloatArray {
        val detail = params.detail.coerceIn(0f, 1f)
        val sensitivity = params.sensitivity.coerceIn(0f, 1f)
        val minLength = lerp(40f, 10f, detail) * scale

        // Outlines.
        val strength = edgeStrength(detail)
        val high = max(quantileOfPositive(strength, 1f - lerp(0.05f, 0.22f, sensitivity)), 0.02f)
        val low = high * 0.4f
        val weak = BooleanArray(size) { strength[it] >= low }
        val seeds = BooleanArray(size) { strength[it] >= high }
        val edges = connectedFilter(weak, seeds, minLength)

        var ink = dilate(edges, lerp(0.8f, 3.2f, params.thickness) * scale)
        ink = blur(ink, 0.7f)
        for (i in 0 until size) ink[i] = min(ink[i] * 1.3f, 1f)

        // Thin dark details.
        val t = lerp(0.06f, 0.02f, sensitivity)
        val dark = FloatArray(size) { ((darkDog[it] - t) / t).coerceIn(0f, 1f) }
        val darkKeep = connectedFilter(BooleanArray(size) { dark[it] > 0.25f }, null, minLength * 1.5f)
        for (i in 0 until size) {
            if (darkKeep[i]) ink[i] = max(ink[i], dark[i] * 0.9f)
        }

        return ink
    }

    /** Thinned, texture-suppressed colour gradient. Depends only on [detail], so it is cached. */
    private fun edgeStrength(detail: Float): FloatArray {
        val key = (detail * 100).roundToInt()
        edgeCache?.let { if (key == edgeKey) return it }

        val radius = max(1, (lerp(9f, 2f, detail) * scale).roundToInt())
        val eps = lerp(0.02f, 0.004f, detail)
        val channels = listOf(
            guided(lum, lum, radius, eps) to 1f,
            guided(lum, chromaA, radius, eps) to 1.5f,
            guided(lum, chromaB, radius, eps) to 1.5f,
        )

        // Structure tensor summed over channels.
        val jxx = FloatArray(size)
        val jyy = FloatArray(size)
        val jxy = FloatArray(size)
        for ((c, weight) in channels) {
            for (y in 0 until height) {
                val ym = max(y - 1, 0) * width
                val y0 = y * width
                val yp = min(y + 1, height - 1) * width
                for (x in 0 until width) {
                    val xm = max(x - 1, 0)
                    val xp = min(x + 1, width - 1)
                    val gx = ((c[ym + xp] + 2 * c[y0 + xp] + c[yp + xp]) -
                        (c[ym + xm] + 2 * c[y0 + xm] + c[yp + xm])) / 8f
                    val gy = ((c[yp + xm] + 2 * c[yp + x] + c[yp + xp]) -
                        (c[ym + xm] + 2 * c[ym + x] + c[ym + xp])) / 8f
                    val i = y0 + x
                    jxx[i] += weight * gx * gx
                    jyy[i] += weight * gy * gy
                    jxy[i] += weight * gx * gy
                }
            }
        }

        val magnitude = FloatArray(size)
        val direction = ByteArray(size)
        for (i in 0 until size) {
            val d = jxx[i] - jyy[i]
            val root = sqrt(d * d + 4 * jxy[i] * jxy[i])
            magnitude[i] = sqrt(max((jxx[i] + jyy[i] + root) / 2, 0f))
            val theta = 0.5 * atan2(2.0 * jxy[i], d.toDouble())
            var deg = theta * 180 / PI
            if (deg < 0) deg += 180
            direction[i] = ((deg / 45).roundToInt() % 4).toByte()
        }

        // Non-maximum suppression across the edge direction.
        val dyOff = intArrayOf(0, 1, 1, 1)
        val dxOff = intArrayOf(1, 1, 0, -1)
        val thin = FloatArray(size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = y * width + x
                val m = magnitude[i]
                if (m <= 0f) continue
                val k = direction[i].toInt()
                val y1 = (y + dyOff[k]).coerceIn(0, height - 1)
                val x1 = (x + dxOff[k]).coerceIn(0, width - 1)
                val y2 = (y - dyOff[k]).coerceIn(0, height - 1)
                val x2 = (x - dxOff[k]).coerceIn(0, width - 1)
                if (m >= magnitude[y1 * width + x1] && m >= magnitude[y2 * width + x2]) thin[i] = m
            }
        }

        // Surround suppression: a lone contour has low edge density around it, texture has high.
        val density = blur(thin, 6 * scale)
        val beta = lerp(5f, 1.5f, detail)
        for (i in 0 until size) {
            if (thin[i] > 0f) thin[i] = max(thin[i] - beta * density[i], 0f)
        }

        edgeKey = key
        edgeCache = thin
        return thin
    }

    private fun toLab(argb: IntArray) {
        val linear = FloatArray(256) {
            val c = it / 255f
            if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
        }
        fun f(t: Float) = if (t > 0.008856f) cbrt(t) else 7.787f * t + 16f / 116f
        for (i in 0 until size) {
            val c = argb[i]
            val a = c ushr 24
            // Composite onto white paper.
            fun channel(v: Int) = linear[(v * a + 255 * (255 - a) + 127) / 255]
            val r = channel(c shr 16 and 0xFF)
            val g = channel(c shr 8 and 0xFF)
            val b = channel(c and 0xFF)
            val fx = f((0.4124f * r + 0.3576f * g + 0.1805f * b) / 0.95047f)
            val fy = f(0.2126f * r + 0.7152f * g + 0.0722f * b)
            val fz = f((0.0193f * r + 0.1192f * g + 0.9505f * b) / 1.08883f)
            lum[i] = 1.16f * fy - 0.16f
            chromaA[i] = 5f * (fx - fy)
            chromaB[i] = 2f * (fy - fz)
        }
    }

    /** Stretches the 1st..99th percentile to 0..1, then applies a gamma that moves the median to 0.5. */
    private fun normalize(values: FloatArray) {
        val bins = 4096
        val histogram = IntArray(bins)
        for (v in values) histogram[(v.coerceIn(0f, 1f) * (bins - 1)).roundToInt()]++
        fun percentile(p: Float): Float {
            val target = p * values.size
            var acc = 0
            for (b in 0 until bins) {
                acc += histogram[b]
                if (acc >= target) return b / (bins - 1f)
            }
            return 1f
        }
        val lo = percentile(0.01f)
        val hi = percentile(0.99f)
        val range = max(hi - lo, 1e-3f)
        val median = ((percentile(0.5f) - lo) / range).coerceIn(0.05f, 0.95f)
        val gamma = (ln(0.5) / ln(median.toDouble())).coerceIn(0.5, 2.0)
        val curve = FloatArray(bins) { (it / (bins - 1.0)).pow(gamma).toFloat() }
        for (i in values.indices) {
            val v = ((values[i] - lo) / range).coerceIn(0f, 1f)
            values[i] = curve[(v * (bins - 1)).roundToInt()]
        }
    }

    /** Self-guided or cross-guided edge-preserving filter (He et al.). */
    private fun guided(guide: FloatArray, src: FloatArray, r: Int, eps: Float): FloatArray {
        val meanI = box(guide, r)
        val meanP = box(src, r)
        val corrIp = box(FloatArray(size) { guide[it] * src[it] }, r)
        val corrII = box(FloatArray(size) { guide[it] * guide[it] }, r)
        val a = FloatArray(size)
        val b = FloatArray(size)
        for (i in 0 until size) {
            val varI = corrII[i] - meanI[i] * meanI[i]
            val cov = corrIp[i] - meanI[i] * meanP[i]
            a[i] = cov / (varI + eps)
            b[i] = meanP[i] - a[i] * meanI[i]
        }
        val meanA = box(a, r)
        val meanB = box(b, r)
        return FloatArray(size) { meanA[it] * guide[it] + meanB[it] }
    }

    /** Mean over a (2r+1)² window with clamped edges, O(1) per pixel. */
    private fun box(src: FloatArray, r: Int): FloatArray {
        val window = 2 * r + 1
        val tmp = FloatArray(size)
        for (y in 0 until height) {
            val row = y * width
            var acc = 0.0
            for (k in -r..r) acc += src[row + k.coerceIn(0, width - 1)]
            for (x in 0 until width) {
                tmp[row + x] = (acc / window).toFloat()
                acc += src[row + min(x + r + 1, width - 1)] - src[row + max(x - r, 0)]
            }
        }
        val out = FloatArray(size)
        for (x in 0 until width) {
            var acc = 0.0
            for (k in -r..r) acc += tmp[k.coerceIn(0, height - 1) * width + x]
            for (y in 0 until height) {
                out[y * width + x] = (acc / window).toFloat()
                acc += tmp[min(y + r + 1, height - 1) * width + x] - tmp[max(y - r, 0) * width + x]
            }
        }
        return out
    }

    /** Exact Gaussian for small radii, three box passes for large ones. */
    private fun blur(src: FloatArray, sigma: Float): FloatArray {
        if (sigma <= 2.5f) return gaussianBlur(src, width, height, sigma)
        val r = ((sqrt(4 * sigma * sigma + 1) - 1) / 2).roundToInt().coerceAtLeast(1)
        return box(box(box(src, r), r), r)
    }

    /** Value below which [q] of the positive entries fall. */
    private fun quantileOfPositive(values: FloatArray, q: Float): Float {
        var top = 0f
        var count = 0
        for (v in values) if (v > 0f) {
            count++
            if (v > top) top = v
        }
        if (count == 0) return 1f
        val bins = 4096
        val histogram = IntArray(bins)
        for (v in values) if (v > 0f) histogram[min((v / top * bins).toInt(), bins - 1)]++
        val target = q * count
        var acc = 0
        for (b in 0 until bins) {
            acc += histogram[b]
            if (acc >= target) return (b + 1f) / bins * top
        }
        return top
    }

    /**
     * Keeps 8-connected components of [mask] that are at least [minSize] pixels
     * and, when [seeds] is given, contain at least one seed pixel.
     */
    private fun connectedFilter(mask: BooleanArray, seeds: BooleanArray?, minSize: Float): BooleanArray {
        val keep = BooleanArray(size)
        val visited = BooleanArray(size)
        val component = IntArray(size)
        for (start in 0 until size) {
            if (!mask[start] || visited[start]) continue
            var count = 0
            var head = 0
            var seeded = seeds == null
            visited[start] = true
            component[count++] = start
            while (head < count) {
                val p = component[head++]
                if (!seeded && seeds!![p]) seeded = true
                val py = p / width
                val px = p - py * width
                for (dy in -1..1) {
                    val ny = py + dy
                    if (ny < 0 || ny >= height) continue
                    for (dx in -1..1) {
                        val nx = px + dx
                        if (nx < 0 || nx >= width) continue
                        val q = ny * width + nx
                        if (mask[q] && !visited[q]) {
                            visited[q] = true
                            component[count++] = q
                        }
                    }
                }
            }
            if (seeded && count >= minSize) {
                for (k in 0 until count) keep[component[k]] = true
            }
        }
        return keep
    }

    /** Thickens one-pixel lines with a disc of the given radius. */
    private fun dilate(mask: BooleanArray, radius: Float): FloatArray {
        val out = FloatArray(size)
        if (radius < 1f) {
            for (i in 0 until size) if (mask[i]) out[i] = 1f
            return out
        }
        val k = ceil(radius).toInt()
        val offsets = ArrayList<Int>()
        for (dy in -k..k) for (dx in -k..k) {
            if (dx * dx + dy * dy <= radius * radius) offsets += (dy shl 16) or (dx and 0xFFFF)
        }
        for (y in 0 until height) for (x in 0 until width) {
            if (!mask[y * width + x]) continue
            for (o in offsets) {
                val ny = y + (o shr 16)
                val nx = x + o.toShort()
                if (ny in 0 until height && nx in 0 until width) out[ny * width + nx] = 1f
            }
        }
        return out
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
