package com.johun.rummiassist

import com.johun.rummiassist.core.Recognizer
import org.junit.Test

class DumpTest {
    @Test fun dump() {
        val spec = System.getenv("DUMP") ?: return // 예: s2.png:0.7
        val (name, f) = spec.split(':').let { it[0] to it.getOrElse(1) { "1.0" }.toDouble() }
        val rec = Recognizer()
        val img = if (f == 1.0) loadImg(name) else scaled(loadImg(name), f)
        val gs = rec.findGlyphs(img.px, img.w, img.h)
        println("== $name x$f glyphs=${gs.size} rackTop=${rec.findRackTop(img.px, img.w, img.h)}")
        for (g in readingOrder(gs, { it.rect.cy }, { it.rect.left }, { it.rect.height }))
            println("  ${g.rect} ${g.color} a=${"%.2f".format(g.aspect)} v=${g.vivid} -> ${rec.classify(g)}")
    }
}
