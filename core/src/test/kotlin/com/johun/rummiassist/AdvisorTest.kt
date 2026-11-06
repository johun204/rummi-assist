package com.johun.rummiassist

import com.johun.rummiassist.core.Advisor
import com.johun.rummiassist.core.Recognizer
import com.johun.rummiassist.core.Solver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvisorTest {
    private val expectedBoardSets = mapOf("s1.png" to 1, "s2.png" to 7, "s3.png" to 7, "s4.png" to 3)

    @Test fun advise() {
        val rec = Recognizer()
        for (name in TRUTH.keys) {
            val img = loadImg(name)
            val r = rec.recognize(img.px, img.w, img.h)
            assertEquals(name, expectedBoardSets[name], r.boardSets.size)
            for (meld in listOf(true, false)) {
                val t0 = System.currentTimeMillis()
                val a = Advisor.advise(r, meld)
                println("== $name meld=$meld ${System.currentTimeMillis() - t0}ms: ${a.message} kept=${a.keptSets} warn=${a.warnings}")
                for (s in a.sets) {
                    println("   #${s.number} ${s.status}: " + s.tiles.joinToString(" ") { "${it.tile}${if (it.moved) "→" else ""}${if (it.screen == null) "?" else ""}" })
                    assertTrue(Solver.isValidSet(s.tiles.map { it.tile.kind }))
                    assertTrue("unmapped", s.tiles.all { it.screen != null })
                }
                assertTrue(a.warnings.isEmpty())
                // 조커 아끼기: 제안에 손패 조커가 없어야 한다.
                val noJ = Advisor.advise(r, meld, useHandJokers = false)
                println("   noJoker: ${noJ.message} hint=${noJ.jokerHint}")
                assertTrue(noJ.sets.none { s -> s.tiles.any { it.tile.joker && it.tile.fromHand } })
                for (s in noJ.sets) assertTrue(Solver.isValidSet(s.tiles.map { it.tile.kind }))
            }
        }
    }

    /** 화면에 없는 타일(같은 타일 3장 인식 등)이 섞이면 아무것도 제안하지 않는다. */
    @Test fun noSuggestionWhenInconsistent() {
        val img = loadImg("s1.png")
        val r = Recognizer().recognize(img.px, img.w, img.h)
        val k4 = r.hand.first { it.kind.toString() == "K4" }
        val bad = Recognizer.Result(r.tiles + k4.copy(glyph = k4.glyph.copy(left = k4.glyph.left + 1)), r.boardSets, r.rackTop, r.width, r.height)
        val a = Advisor.advise(bad, true)
        assertTrue(a.sets.isEmpty())
    }
}
