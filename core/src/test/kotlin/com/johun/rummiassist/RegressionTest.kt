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
            for (m in a.moves) println("     move ${m.tile}${if (m.isPark) " (임시)" else ""} ${m.screen?.rect?.let { "${it.cx},${it.cy}" }} -> ${m.target!!.cx},${m.target!!.cy}")
            assertEquals(null, layoutProblem(a))
            // 손패 없이 기존 세트를 옮기기만 하는 단계는 없어야 한다(규칙상 한 장 이상 내야 함)
            assertTrue(a.steps.filter { !it.blocked }.all { it.handTiles > 0 })
            // 버그 당시 36번(기존 세트 전부 이동) → 꼭 필요한 이동만
            assertTrue("이동이 너무 많음: ${a.moves.size}", a.moves.size <= if (joker) 15 else 9)
            // 목록에 있는 세트는 모두 실제로 무언가 움직이거나 손패를 낸다
            assertTrue(a.sets.all { s -> s.tiles.any { it.target != null || it.tile.fromHand } })
        }
    }

    /** 2026-11-07 제보: 손패 B1 B2 를 판의 B3 B4 B5 왼쪽에 붙일 수 있는데 '자리 부족'으로 아무것도 못 내게 안내 */
    private fun screen2(): Recognizer.Result {
        val col = intArrayOf(367, 482, 596, 711, 826, 940, 1055, 1169, 1284, 1398, 1513, 1627)
        fun row(cy: Int, vararg t: Pair<Int, String>) = t.map { (c, s) -> tile(s, col[c], cy) }
        val sets = listOf(
            row(220, 0 to "J", 1 to "K11", 2 to "B11"),
            row(220, 4 to "K8", 5 to "O8", 6 to "R8"),
            row(220, 8 to "R4", 9 to "R5", 10 to "R6", 11 to "R7"),
            row(395, 0 to "K11", 1 to "O11", 2 to "B11"),
            row(395, 4 to "O3", 5 to "K3", 6 to "R3", 7 to "B3"),
            row(395, 9 to "K4", 10 to "K5", 11 to "K6"),
            row(568, 0 to "O6", 1 to "O7", 2 to "O8"),
            row(568, 5 to "B5", 6 to "B6", 7 to "B7", 8 to "B8", 9 to "B9"),
            row(740, 0 to "O10", 1 to "O11", 2 to "O12", 3 to "O13"),
            row(740, 5 to "K8", 6 to "R8", 7 to "B8"),
            row(740, 9 to "B3", 10 to "B4", 11 to "B5"),
        )
        val hand = listOf("K5", "K7", "K13", "K13", "B1", "B2", "B9", "B12", "B12", "O1").mapIndexed { i, s -> tile(s, 447 + i * 119, 1005, true) } +
            listOf("O10", "R4", "R7", "R9", "R11", "R13", "J").mapIndexed { i, s -> tile(s, 447 + i * 119, 1160, true) }
        return Recognizer.Result(sets.flatten() + hand, sets, 930, 1972, 1248)
    }

    @Test fun fullBoardStillPlays() {
        val r = screen2()
        val a = Advisor.advise(r, true, false)
        println("full board: ${a.message} 이동 ${a.moves.size}개, 단계 ${a.steps.map { "${it.number}(손패${it.handTiles},이동${it.moves.size},blocked=${it.blocked})" }}")
        for (s in a.sets) println("   #${s.number} ${s.status}${if (s.relaxed) " (자동 축소)" else ""}: " + s.tiles.joinToString(" ") { "${it.tile}${if (it.target != null) "→" else ""}" })
        assertTrue("자리 부족으로 막힘", a.steps.none { it.blocked })
        assertEquals(a.handPlayed, a.steps.sumOf { it.handTiles })
        // B1 B2 는 B3 B4 B5 왼쪽(같은 줄)에 붙는다
        val b12 = a.sets.first { s -> s.tiles.any { it.tile.kind.toString() == "B1" } }
        assertTrue(b12.tiles.filter { !it.tile.fromHand }.all { it.target == null })
        assertEquals(null, layoutProblem(a))
    }

    /** 2026-11-07 제보: 한 장 옮기자 게임이 판을 줄였고, 앱이 새 계획을 세워 엉뚱한 곳으로 안내 → 같은 계획을 새 좌표로 이어 가야 한다 */
    @Test fun planSurvivesRescale() {
        val r = screen2()
        val a = Advisor.advise(r, true, false)
        // 1단계 첫 이동(B1 을 B3 왼쪽에)을 한 뒤 게임이 판을 0.85배로 줄이고 오른쪽 아래로 옮긴 화면
        val first = a.moves.first()
        val s = 0.85; val tx = 120.0; val ty = 40.0
        fun f(rc: Rect): Rect {
            val cx = s * rc.cx + tx; val cy = s * rc.cy + ty
            val hw = rc.width * s / 2; val hh = rc.height * s / 2
            return Rect((cx - hw).toInt(), (cy - hh).toInt(), (cx + hw).toInt(), (cy + hh).toInt())
        }
        val board = r.board.map { it.copy(rect = f(it.rect), glyph = f(it.glyph)) } +
            DetectedTile(first.tile.kind, f(first.target!!), f(first.target!!), false, 0.0)
        val hand = r.hand.filter { it !== first.screen }
        val r2 = Recognizer.Result(board + hand, emptyList(), 930, 1972, 1248)
        val b = com.johun.rummiassist.core.Tracker.follow(a, r2)
        assertTrue("변환을 못 찾음", b != null && b !== a)
        val prog = com.johun.rummiassist.core.Tracker.track(b!!, r2)
        println("rescale: done=${prog.done.size} deviated=${prog.deviated}")
        assertTrue(!prog.deviated)
        assertEquals(1, prog.done.size)
        // 다음 이동은 원래 계획의 두 번째 이동(새 좌표)
        assertEquals(a.moves[1].tile, b.moves.first { it !in prog.done }.tile)
    }

    /** 2026-11-07 제보 3: 판이 빡빡해 '뽑기 권장'인데도 화살표가 그대로 보였고, 첫 이동이 멀쩡한 런(R10 R11 R12) 가운데를 빼 단독 2개를 만듦 */
    private fun screen3(): Recognizer.Result {
        fun t(s: String, c: Int, cy: Int, hand: Boolean = false): DetectedTile {
            val w = 86; val h = 124; val cx = if (hand) c else 355 + (c * 91.5).toInt()
            return DetectedTile(k(s), Rect(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2), Rect(cx - 16, cy - 40, cx + 16, cy), hand, 0.0)
        }
        fun row(cy: Int, vararg x: Pair<Int, String>) = x.map { (c, s) -> t(s, c, cy) }
        val sets = listOf(
            row(205, 3 to "O1", 4 to "O2", 5 to "O3", 6 to "O4"), row(205, 8 to "B3", 9 to "R3", 10 to "K3"), row(205, 12 to "K12", 13 to "B12", 14 to "O12"),
            row(343, 0 to "R10", 1 to "R11", 2 to "R12"), row(343, 4 to "K9", 5 to "B9", 6 to "R9"), row(343, 8 to "R4", 9 to "R5", 10 to "R6", 11 to "R7", 12 to "R8"),
            row(482, 1 to "R10", 2 to "O10", 3 to "K10"), row(482, 5 to "B7", 6 to "B8", 7 to "B9", 8 to "B10", 9 to "B11"), row(482, 11 to "K1", 12 to "B1", 13 to "O1", 14 to "R1"),
            row(620, 1 to "R13", 2 to "O13", 3 to "B13"), row(620, 5 to "R13", 6 to "O13", 7 to "K13"), row(620, 9 to "O5", 10 to "O6", 11 to "O7", 12 to "O8"),
        )
        val hand = listOf("K3" to 447, "K10" to 565, "O10" to 805, "O11" to 925, "R4" to 1045).map { (s, x) -> t(s, x, 1005, true) }
        return Recognizer.Result(sets.flatten() + hand, sets, 950, 1972, 1248)
    }

    @Test fun tightBoard() {
        val r = screen3()
        for (budget in listOf(6, 1000)) {
            val a = Advisor.advise(r, true, false, moveBudget = budget)
            println("tight budget=$budget: ${a.message} overTime=${a.overTime} 이동 ${a.moves.size}")
            for (m in a.moves) println("     ${m.tile}${if (m.isPark) "(임시)" else ""}")
            // 무효 타일 수 흐름
        }
        val a = Advisor.advise(r, true, false)
        // 새 세트를 만들 땐 손패 타일부터 놓아, 멀쩡한 판 세트를 최대한 늦게 깬다
        val first = a.moves.first()
        assertTrue("첫 이동이 판 타일: ${first.tile}", first.tile.fromHand)
    }

    /** 2026-11-08 제보: 판 오른쪽 끝의 K6 K7 K8 에 손패 K9 만 붙이면 되는데, 세트를 통째로 옮긴 뒤 붙이라고 안내 */
    @Test fun attachAtBoardEdge() {
        fun row(cy: Int, vararg x: Pair<Int, String>) = x.map { (c, s) -> tile(s, 367 + c * 115, cy) }
        val sets = listOf(
            row(220, 0 to "K11", 1 to "O11", 2 to "B11", 3 to "R11"),
            row(395, 4 to "B2", 5 to "B3", 6 to "B4", 7 to "B5"),
            row(740, 9 to "K6", 10 to "K7", 11 to "K8"),
        )
        val hand = listOf("K6", "K9", "B4", "B7", "O3", "O8", "O10", "R3", "R13", "R13").mapIndexed { i, s -> tile(s, 447 + i * 119, 1005, true) } +
            listOf(tile("O6", 447, 1160, true))
        val r = Recognizer.Result(sets.flatten() + hand, sets, 930, 1972, 1248)
        val a = Advisor.advise(r, true, false, moveBudget = 6)
        println("edge: ${a.message} 이동 ${a.moves.map { "${it.tile}" }}")
        val k9 = a.sets.first { s -> s.tiles.any { it.tile.kind.toString() == "K9" } }
        // K6 K7 K8 은 그대로, K9 한 장만 오른쪽 끝에 붙인다
        assertTrue("판 타일을 옮김: ${k9.tiles.filter { it.target != null && !it.tile.fromHand }.map { it.tile }}",
            k9.tiles.filter { !it.tile.fromHand }.all { it.target == null })
    }

    /** 남은 시간에 따라 보여 줄 단계: 하던 단계는 끝까지, 그 뒤는 시간 안에 들어가는 것만 */
    @Test fun stepsWithinTime() {
        val a = Advisor.advise(screen2(), true, false)
        val sizes = a.steps.map { it.moves.size }
        println("steps moves=$sizes")
        val all = a.steps.map { it.number }.toSet()
        assertEquals(all, Advisor.stepsWithin(a, emptySet(), 1000.0))
        // 첫 단계만 들어갈 시간
        assertEquals(setOf(1), Advisor.stepsWithin(a, emptySet(), sizes[0] * 2.3 + 0.1))
        // 시간이 거의 없어도 이미 시작한 단계는 끝까지
        val started = setOf(a.steps[0].moves.first())
        assertEquals(setOf(1), Advisor.stepsWithin(a, started, 0.5))
        // 첫 단계를 다 했고 시간이 없으면 더 보여 줄 게 없다(= 여기서 턴 끝내기)
        val done1 = a.steps[0].moves.toSet()
        assertEquals(setOf(1), Advisor.stepsWithin(a, done1, 1.0))
    }
}
