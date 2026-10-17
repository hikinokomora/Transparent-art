package io.github.transparentart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SketchEngineTest {

    @Test
    fun colourBordersOfEqualBrightnessBecomeLines() {
        // Red square on a green background of nearly the same luminance.
        val w = 400
        val h = 300
        val pixels = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            if (x in 100 until 300 && y in 75 until 225) 0xFFD74646.toInt() else 0xFF3CA03C.toInt()
        }
        val ink = SketchEngine(pixels, w, h).render(SketchParams(shadeLevels = 0))
        fun alpha(x: Int, y: Int) = ink[y * w + x] ushr 24

        assertTrue("left border is drawn", (96..104).any { alpha(it, 150) > 128 })
        assertTrue("top border is drawn", (71..79).any { alpha(200, it) > 128 })
        assertEquals("flat inside stays empty", 0, alpha(200, 150))
        assertEquals("flat outside stays empty", 0, alpha(30, 30))
    }
}
