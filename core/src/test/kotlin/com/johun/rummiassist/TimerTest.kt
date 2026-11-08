package com.johun.rummiassist

import com.johun.rummiassist.core.TimerReader
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** 아바타 위 남은 시간 숫자 읽기. tm_* = 타이머(큰 숫자), pl_* = 같은 글꼴의 장수 숫자(작음) */
class TimerTest {
    private val dir = File(javaClass.getResource("/timer")!!.toURI())
    private fun samples() = dir.listFiles()!!.filter { it.name.endsWith(".png") }.sortedBy { it.name }.map { f ->
        val label = f.name.split('_')[1]
        val img = loadImg("timer/${f.name}")
        Triple(f.name, label, img)
    }
    private fun glyphs(img: Img) = TimerReader.digits(img.px, img.w, img.h, 0, 0, img.w, img.h, 15, 70)

    @Test fun generate() {
        if (System.getenv("GEN") == null) return
        val sums = HashMap<Int, FloatArray>(); val asp = HashMap<Int, Float>(); val n = HashMap<Int, Int>()
        for ((name, label, img) in samples()) {
            val gs = glyphs(img)
            check(gs.size == label.length) { "$name: ${gs.size} glyphs vs '$label'" }
            for ((g, ch) in gs.zip(label.toList())) {
                val d = ch - '0'
                val s = sums.getOrPut(d) { FloatArray(g.grid.size) }
                for (i in s.indices) s[i] += g.grid[i]
                asp[d] = (asp[d] ?: 0f) + g.aspect; n[d] = (n[d] ?: 0) + 1
            }
        }
        val lines = sums.keys.sorted().map { d ->
            val c = n.getValue(d)
            "$d ${"%.3f".format(asp.getValue(d) / c)} " + sums.getValue(d).joinToString("") { ('a' + Math.round(it / c * 15)).toString() }
        }
        println("digits: ${n.toSortedMap()}")
        val f = File("src/main/kotlin/com/johun/rummiassist/core/TimerTemplates.kt")
        val src = f.readText()
        val start = src.indexOf("DATA = \"\"\"") + 10
        f.writeText(src.substring(0, start) + "\n" + lines.joinToString("\n") + "\n" + src.substring(src.indexOf("\"\"\"", start)))
    }

    @Test fun readsAllSamples() {
        for ((name, label, img) in samples()) {
            val v = TimerReader.readDigits(img.px, img.w, img.h, 0, 0, img.w, img.h, 15, 70)
            assertEquals(name, label.toInt(), v)
        }
    }

    /** 실제 화면 크기에서: 캡처 s1(타이머 없음)에 타이머 조각을 원래 자리에 붙여 넣고 전체 화면 읽기 */
    @Test fun readsFullScreen() {
        val base = loadImg("s1.png")
        assertEquals(null, TimerReader.read(base.px, base.w, base.h))
        for ((name, label, img) in samples().filter { it.first.startsWith("tm_") }) {
            val s = Img(base.px.clone(), base.w, base.h)
            for (y in 0 until img.h) for (x in 0 until img.w) s.px[(1045 + y) * s.w + 100 + x] = img.px[y * img.w + x]
            assertEquals(name, label.toInt(), TimerReader.read(s.px, s.w, s.h))
            // 다른 해상도
            val sc = scaled(s, 0.75)
            assertEquals("$name x0.75", label.toInt(), TimerReader.read(sc.px, sc.w, sc.h))
        }
    }
}
