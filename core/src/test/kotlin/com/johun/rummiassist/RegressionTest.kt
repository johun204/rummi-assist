package com.johun.rummiassist

import com.johun.rummiassist.core.Advisor
import com.johun.rummiassist.core.DetectedTile
import com.johun.rummiassist.core.Rect
import com.johun.rummiassist.core.Recognizer
import com.johun.rummiassist.core.TileColor
import com.johun.rummiassist.core.TileKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 사용자가 보낸 실제 게임 화면(2026-11-06) 재현: 이미 완성된 세트를 통째로 옮기라고 하던 버그. */
class RegressionTest {
    private fun k(s: String) = if (s == "J") TileKind.JOKER else TileKind.of(TileColor.entries.first { it.code == s[0] }, s.drop(1).toInt())

    private fun tile(s: String, cx: Int, cy: Int, hand: Boolean = false): DetectedTile {
        val w = 109; val h = 160
        val top = cy - h / 2
        return DetectedTile(k(s), Rect(cx - w / 2, top, cx - w / 2 + w, top + h), Rect(cx - 20, top + 30, cx + 20, top + 82), hand, 0.0)
    }

    /** 판 1972x1248, 타일 간격 114.5, 줄 y = 220/395/568/740, 랙 윗변 930 */
    private fun screen(): Recognizer.Result {
        val col = intArrayOf(367, 482, 596, 711, 825, 940, 1055, 1169, 1284, 1398, 1512, 1627)
        fun row(cy: Int, vararg t: Pair<Int, String>) = t.map { (c, s) -> tile(s, col[c], cy) }
        val sets = listOf(
            row(220, 0 to "K6", 1 to "K7", 2 to "K8", 3 to "K9"),
            row(220, 5 to "R11", 6 to "R12", 7 to "R13"),
            row(220, 9 to "B10"),
            row(220, 11 to "O10"),
            row(395, 0 to "B1", 1 to "B2", 2 to "B3", 3 to "B4"),
            row(395, 5 to "B4", 6 to "B5", 7 to "B6", 8 to "B7"),
            row(568, 0 to "K8", 1 to "R8", 2 to "O8"),
            row(568, 5 to "O1", 6 to "R1", 7 to "K1"),
            row(568, 9 to "B7", 10 to "R7", 11 to "O7"),
            row(740, 0 to "R1", 1 to "J", 2 to "R3", 3 to "R4", 4 to "R5"),
            row(740, 6 to "K10"),
            row(740, 8 to "R4", 9 to "R5"),
            row(740, 11 to "R3"),
        )
        val hand = listOf("B9" to 447, "O12" to 565, "K3" to 685, "J" to 805, "R10" to 925).map { (s, x) -> tile(s, x, 1005, true) } +
            listOf("B9" to 565, "R11" to 1165).map { (s, x) -> tile(s, x, 1160, true) }
        return Recognizer.Result(sets.flatten() + hand, sets, 930, 1972, 1248)
    }

    @Test fun noPointlessRelocation() {
        val r = screen()
        for (joker in listOf(false, true)) {
            val a = Advisor.advise(r, true, joker)
            println("joker=$joker: ${a.message} 이동 ${a.moves.size}개, 단계 ${a.steps.map { "${it.number}(손패${it.handTiles},이동${it.moves.size},valid=${it.valid})" }}")
            for (s in a.sets) println("   #${s.number} ${s.status} step${s.step}: " + s.tiles.joinToString(" ") { "${it.tile}${if (it.target != null) "→" else ""}" })
            assertEquals(null, layoutProblem(a))
            // 손패 없이 기존 세트를 옮기기만 하는 단계는 없어야 한다(규칙상 한 장 이상 내야 함)
            assertTrue(a.steps.filter { !it.blocked }.all { it.handTiles > 0 })
            // 버그 당시 36번(기존 세트 전부 이동) → 꼭 필요한 이동만
            assertTrue("이동이 너무 많음: ${a.moves.size}", a.moves.size <= if (joker) 14 else 6)
            // 목록에 있는 세트는 모두 실제로 무언가 움직이거나 손패를 낸다
            assertTrue(a.sets.all { s -> s.tiles.any { it.target != null || it.tile.fromHand } })
        }
    }
}
