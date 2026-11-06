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
            assertEquals(name, expectedBoardSets[name], Advisor.boardSets(r.board).size)
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
            }
        }
    }
}
