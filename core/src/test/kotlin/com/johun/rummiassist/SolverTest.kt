package com.johun.rummiassist

import com.johun.rummiassist.core.Solver
import com.johun.rummiassist.core.TileColor
import com.johun.rummiassist.core.TileKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SolverTest {
    private fun counts(s: String): Pair<Array<IntArray>, Int> {
        val a = Solver.emptyCounts(); var j = 0
        for (t in s.split(' ').filter { it.isNotBlank() }) {
            if (t == "J") { j++; continue }
            val c = TileColor.entries.first { it.code == t[0] }
            a[c.ordinal][t.drop(1).toInt()]++
        }
        return a to j
    }

    private fun checkValid(board: String, hand: String, sol: com.johun.rummiassist.core.Solution) {
        for (set in sol.sets) assertTrue("invalid $set", Solver.isValidSet(set.map { it.kind }))
        val (b, bj) = counts(board)
        val used = Solver.emptyCounts(); var uj = 0
        for (t in sol.sets.flatten().filter { !it.fromHand }) if (t.joker) uj++ else used[t.color.ordinal][t.value]++
        assertTrue("board tiles not conserved", b.indices.all { c -> b[c].contentEquals(used[c]) } && bj == uj)
        val (h, hj) = counts(hand)
        val uh = Solver.emptyCounts(); var uhj = 0
        for (t in sol.sets.flatten().filter { it.fromHand }) if (t.joker) uhj++ else uh[t.color.ordinal][t.value]++
        assertTrue("used more than hand", (0..3).all { c -> (1..13).all { v -> uh[c][v] <= h[c][v] } } && uhj <= hj)
    }

    private fun solve(board: String, hand: String): com.johun.rummiassist.core.Solution? {
        val (b, bj) = counts(board); val (h, hj) = counts(hand)
        return Solver.solve(b, h, bj, hj)
    }

    @Test fun screenshotPositions() {
        for ((name, t) in TRUTH) {
            val t0 = System.currentTimeMillis()
            val sol = solve(t.first, t.second)!!
            println("$name ${System.currentTimeMillis() - t0}ms hand=${sol.handTiles} sets=${sol.sets}")
            checkValid(t.first, t.second, sol)
        }
    }

    @Test fun simpleCases() {
        assertEquals(3, solve("", "K1 K2 K3 R5")!!.handTiles)
        assertEquals(1, solve("K1 K2 K3", "K4")!!.handTiles)
        assertEquals(0, solve("K1 K2 K3", "K5")!!.handTiles)
        assertEquals(0, solve("K12 K13 K1", "K2")?.handTiles ?: 0) // 13-1 연결 불가 → 판 자체가 무효
        // 판 재배치: R5 R6 R7 / B5 O5 K5 에 R5 를 내면 → R5 R6 R7 런 유지 + 4색 그룹은 불가(R5 중복), 대신 런 쪼개기 필요 없음
        assertEquals(2, solve("R3 R4 R5 R6 R7", "R5 R8")!!.handTiles) // R3-R5 + R5-R8 로 분리
        assertEquals(1, solve("K7 K8 K9", "J")!!.handTiles)
        assertEquals(4, solve("", "R10 B10 O10 J")!!.handTiles)
    }

    /** 작은 무작위 국면에서 전수 탐색과 최적값 비교. */
    @Test fun matchesBruteForce() {
        val rnd = Random(7)
        repeat(300) {
            val pool = ArrayList<TileKind>()
            for (c in TileColor.entries) for (v in 1..6) repeat(2) { pool += TileKind.of(c, v) }
            pool += TileKind.JOKER; pool += TileKind.JOKER
            pool.shuffle(rnd)
            val hand = pool.take(rnd.nextInt(3, 10))
            val handStr = hand.joinToString(" ")
            val sol = solve("", handStr)!!
            checkValid("", handStr, sol)
            assertEquals("hand=$handStr", brute(hand), sol.handTiles)
        }
    }

    /** 손패에서 고를 수 있는 최대 장수: 부분집합을 큰 것부터 유효 세트로 분할 가능한지 확인. */
    private fun brute(hand: List<TileKind>): Int {
        val n = hand.size
        var best = 0
        for (mask in 0 until (1 shl n)) {
            val cnt = Integer.bitCount(mask)
            if (cnt <= best) continue
            if (partition(hand.filterIndexed { i, _ -> mask shr i and 1 == 1 })) best = cnt
        }
        return best
    }

    private fun partition(t: List<TileKind>): Boolean {
        if (t.isEmpty()) return true
        val first = t[0]; val rest = t.drop(1)
        val m = rest.size
        for (mask in 0 until (1 shl m)) {
            val pick = rest.filterIndexed { i, _ -> mask shr i and 1 == 1 }
            if (pick.size < 2) continue
            val set = listOf(first) + pick
            if (!anyOrderValid(set)) continue
            if (partition(rest.filterIndexed { i, _ -> mask shr i and 1 == 0 })) return true
        }
        return false
    }

    private fun anyOrderValid(set: List<TileKind>): Boolean {
        val real = set.filter { !it.isJoker }.sortedBy { it.value }
        val jokers = set.size - real.size
        if (Solver.isValidSet(real + List(jokers) { TileKind.JOKER })) return true
        // 런: 시작값 전부 시도
        if (real.any { it.color != real.firstOrNull()?.color }) return false
        for (start in 1..13) {
            val vals = (start until start + set.size)
            if (vals.last > 13) break
            if (real.map { it.value } == real.map { it.value }.filter { it in vals } && real.map { it.value }.toSet().size == real.size) return true
        }
        return false
    }
}
