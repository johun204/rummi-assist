package com.johun.rummiassist

import com.johun.rummiassist.core.Recognizer
import org.junit.Assert.assertEquals
import org.junit.Test

class RecognizerTest {
    @Test fun recognizesAllScreenshots() = check(1.0)

    /** 다른 해상도 기기(작은 화면, 큰 화면) 대응. */
    @Test fun recognizesScaled() { check(0.85); check(0.7); check(1.4) }

    private fun check(f: Double) {
        val rec = Recognizer()
        for ((name, truth) in TRUTH) {
            val img = if (f == 1.0) loadImg(name) else scaled(loadImg(name), f)
            val t0 = System.currentTimeMillis()
            val r = rec.recognize(img.px, img.w, img.h)
            val ms = System.currentTimeMillis() - t0
            fun str(l: List<com.johun.rummiassist.core.DetectedTile>) =
                readingOrder(l, { it.glyph.cy }, { it.glyph.left }, { it.glyph.height }).joinToString(" ") { it.kind.toString() }
            println("$name x$f ${ms}ms worst=${"%.4f".format(r.tiles.maxOf { it.score })}")
            assertEquals("$name x$f board", truth.first, str(r.board))
            assertEquals("$name x$f hand", truth.second, str(r.hand))
        }
    }
}
