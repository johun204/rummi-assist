package com.johun.rummiassist

import com.johun.rummiassist.core.Recognizer
import org.junit.Test
import java.io.File

/** GEN=1 일 때 s1/s2/s4 캡처(여러 배율)와 정답으로 Templates.kt 의 DATA 를 다시 만든다(s3 은 검증용으로 남김). */
class TemplateGenTest {
    @Test fun generate() {
        if (System.getenv("GEN") == null) return
        val rec = Recognizer(emptyList())
        val sums = HashMap<String, FloatArray>()
        val asp = HashMap<String, Float>()
        val n = HashMap<String, Int>()
        for (name in listOf("s1.png", "s2.png", "s4.png")) for (f in listOf(1.0, 0.75, 0.6, 1.3)) {
            val img = if (f == 1.0) loadImg(name) else scaled(loadImg(name), f)
            val rack = rec.findRackTop(img.px, img.w, img.h)
            val gs0 = rec.findGlyphs(img.px, img.w, img.h)
            val med = gs0.map { it.rect.height }.sorted()[gs0.size / 2]
            val gs = gs0.filter { it.rect.height > med * 0.6 }
            val (b, hnd) = TRUTH.getValue(name)
            val order = readingOrder(gs.filter { it.rect.cy <= rack }, { it.rect.cy }, { it.rect.left }, { it.rect.height }) +
                readingOrder(gs.filter { it.rect.cy > rack }, { it.rect.cy }, { it.rect.left }, { it.rect.height })
            val labels = "$b $hnd".split(' ')
            check(order.size == labels.size) { "$name: ${order.size} glyphs vs ${labels.size} labels" }
            for ((g, l) in order.zip(labels)) {
                val key = if (l == "J") "J" else l.drop(1)
                val s = sums.getOrPut(key) { FloatArray(g.grid.size) }
                for (i in s.indices) s[i] += g.grid[i]
                asp[key] = (asp[key] ?: 0f) + g.aspect
                n[key] = (n[key] ?: 0) + 1
            }
        }
        val lines = sums.keys.sortedBy { it.toIntOrNull() ?: 99 }.map { k ->
            val c = n.getValue(k)
            val grid = sums.getValue(k).joinToString("") { ('a' + Math.round(it / c * 15)).toString() }
            "$k ${"%.3f".format(asp.getValue(k) / c)} $grid"
        }
        println("labels: ${n.toSortedMap()}")
        val f = File("src/main/kotlin/com/johun/rummiassist/core/Templates.kt")
        val src = f.readText()
        val start = src.indexOf("DATA = \"\"\"") + 10
        val end = src.indexOf("\"\"\"", start)
        f.writeText(src.substring(0, start) + "\n" + lines.joinToString("\n") + "\n" + src.substring(end))
    }
}
