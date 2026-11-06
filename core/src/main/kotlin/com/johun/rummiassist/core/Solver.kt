package com.johun.rummiassist.core

/** 결과 세트 안의 타일 1장. 조커는 joker=true 이고 color/value 는 조커가 대신하는 타일. */
data class SetTile(val color: TileColor, val value: Int, val joker: Boolean, val fromHand: Boolean) {
    val kind get() = if (joker) TileKind.JOKER else TileKind.of(color, value)
    override fun toString() = (if (joker) "J(${color.code}$value)" else "${color.code}$value") + if (fromHand) "*" else ""
}

class Solution(val sets: List<List<SetTile>>) {
    val handTiles get() = sets.sumOf { s -> s.count { it.fromHand } }
    val handValue get() = sets.sumOf { s -> s.filter { it.fromHand }.sumOf { it.value } }
}

/**
 * 루미큐브 배치 DP (van Rijn et al., "The Complexity of Rummikub Problems" 의 숫자 순서 DP).
 *
 * 숫자 1→13 순서로 보며, 색마다 "진행 중인 런 2개의 길이(0,1,2,3+)" 를 상태로 둔다
 * (같은 타일은 2장뿐이라 한 색에 동시에 진행되는 런은 최대 2개). 각 숫자에서 색별로
 * 몇 장을 쓰고(판 타일은 반드시 사용, 손패는 선택), 그중 몇 장을 그룹(같은 숫자 다른 색 3~4장)에 넣고
 * 나머지를 어느 런에 이어 붙일지 고른다. 조커는 아무 (색, 숫자) 자리에나 들어가는 타일로 취급한다.
 * 색 하나씩 처리하는 하위 단계로 나눠 그룹 조건은 (2장 쓴 색 수, 1장 쓴 색 수) 만 들고 다닌다.
 */
object Solver {
    private val PA = IntArray(10); private val PB = IntArray(10)
    private val PAIR = Array(4) { IntArray(4) }
    init {
        var i = 0
        for (a in 0..3) for (b in a..3) { PA[i] = a; PB[i] = b; PAIR[a][b] = i; PAIR[b][a] = i; i++ }
    }

    /**
     * @param board 판 타일 수 [색][숫자 1..13] (반드시 모두 사용)
     * @param hand 손패 타일 수 [색][숫자] (선택 사용, requireAll 이면 모두 사용)
     * @param wCount 손패 1장 사용 점수, wValue 손패 숫자 합 점수
     * @param jokerValue true 면 조커 점수에 대신하는 숫자를 반영(첫 등록 30점 계산용)
     * @return 최고 점수 배치, 판 타일을 모두 쓰는 배치가 없으면 null
     */
    fun solve(
        board: Array<IntArray>, hand: Array<IntArray>, boardJokers: Int, handJokers: Int,
        wCount: Long = 1000, wValue: Long = 1, jokerValue: Boolean = false, requireAll: Boolean = false,
    ): Solution? {
        val jTot = boardJokers + handJokers
        if (jTot > 2) return null
        val jMin = if (requireAll) jTot else boardJokers
        // steps[v*4+c] : newKey -> (prevKey shl 8 | option)
        val steps = arrayOfNulls<HashMap<Int, Long>>(14 * 4)
        val groupSteps = arrayOfNulls<HashMap<Int, Long>>(14) // 숫자별 그룹 확인 단계
        var cur = HashMap<Int, Long>() // key -> score
        cur[0] = 0L
        for (v in 1..13) {
            for (c in 0..3) {
                val bc = board[c][v]; val hc = hand[c][v]
                if (bc > 2) return null
                val next = HashMap<Int, Long>(cur.size * 2)
                val par = HashMap<Int, Long>(cur.size * 2)
                val rMax = minOf(2, bc + hc)
                val rMin = if (requireAll) bc + hc else bc
                if (rMin > 2) return null
                for ((key, score) in cur) {
                    val pairs = key % 10000
                    val pi = (pairs / POW[c]) % 10
                    val a = PA[pi]; val b = PB[pi]
                    val j = (key / 10000) % 3
                    val d = (key / 30000) % 5
                    val s = key / 150000
                    for (r in rMin..rMax) for (jj in 0..minOf(2 - r, jTot - j)) {
                        val k = r + jj
                        val handReal = r - bc
                        val gain = handReal * (wCount + wValue * v) + jj * (wCount + if (jokerValue) wValue * v else 0)
                        for (g in 0..k) {
                            val m = k - g
                            val nd = d + if (g == 2) 1 else 0
                            val ns = s + if (g == 1) 1 else 0
                            for (asg in 0..3) {
                                val na: Int; val nb: Int
                                when (asg) {
                                    0 -> { if (m != 0) continue; na = end(a); nb = end(b) }
                                    1 -> { if (m != 1) continue; na = ext(a); nb = end(b) }
                                    2 -> { if (m != 1 || a == b) continue; na = end(a); nb = ext(b) }
                                    else -> { if (m != 2) continue; na = ext(a); nb = ext(b) }
                                }
                                if (na < 0 || nb < 0) continue
                                val npairs = pairs + (PAIR[na][nb] - pi) * POW[c]
                                val nkey = npairs + 10000 * (j + jj) + 30000 * nd + 150000 * ns
                                val nscore = score + gain
                                val old = next[nkey]
                                if (old == null || nscore > old) {
                                    next[nkey] = nscore
                                    par[nkey] = (key.toLong() shl 8) or (r.toLong() or (jj.toLong() shl 2) or (g.toLong() shl 4) or (asg.toLong() shl 6))
                                }
                            }
                        }
                    }
                }
                steps[v * 4 + c] = par
                cur = next
            }
            // 이 숫자의 그룹 조건 확인 후 (d,s) 초기화.
            val next = HashMap<Int, Long>(cur.size)
            val par = HashMap<Int, Long>(cur.size)
            for ((key, score) in cur) {
                val d = (key / 30000) % 5
                val s = key / 150000
                if (!groupsOk(d, s)) continue
                val nkey = key % 30000
                val old = next[nkey]
                if (old == null || score > old) { next[nkey] = score; par[nkey] = (key.toLong() shl 8) or (d.toLong() shl 4) or s.toLong() }
            }
            groupSteps[v] = par
            cur = next
        }
        // 끝: 모든 런이 닫혀(길이 0 또는 3+) 있어야 한다.
        var bestKey = -1; var best = Long.MIN_VALUE
        for ((key, score) in cur) {
            var ok = true
            var p = key % 10000
            for (c in 0..3) { val pi = p % 10; p /= 10; if (end(PA[pi]) < 0 || end(PB[pi]) < 0) ok = false }
            val j = (key / 10000) % 3
            if (ok && j >= jMin && score > best) { best = score; bestKey = key }
        }
        if (bestKey < 0) return null
        return rebuild(bestKey, steps, groupSteps, board, hand, boardJokers)
    }

    /** 판 타일 전부(+조커)를 빠짐없이 유효한 세트로 나눌 수 있는지. */
    fun feasible(board: Array<IntArray>, jokers: Int): Boolean =
        solve(board, emptyCounts(), jokers, 0, requireAll = true) != null

    fun emptyCounts() = Array(4) { IntArray(14) }

    private fun rebuild(
        finalKey: Int, steps: Array<HashMap<Int, Long>?>, groupSteps: Array<HashMap<Int, Long>?>, board: Array<IntArray>, hand: Array<IntArray>, boardJokers: Int,
    ): Solution {
        // 뒤에서부터 선택지를 모은다.
        val opt = Array(14) { IntArray(4) }
        var key = finalKey
        for (v in 13 downTo 1) {
            key = (groupSteps[v]!![key]!! shr 8).toInt()
            for (c in 3 downTo 0) {
                val p = steps[v * 4 + c]!![key]!!
                opt[v][c] = (p and 0xFF).toInt()
                key = (p shr 8).toInt()
            }
        }
        val sets = ArrayList<List<SetTile>>()
        val slots = Array(4) { arrayOf(ArrayList<SetTile>(), ArrayList<SetTile>()) }
        var jokersLeftOnBoard = boardJokers
        for (v in 1..13) {
            val groupTiles = Array(4) { ArrayList<SetTile>() }
            for (c in 0..3) {
                val o = opt[v][c]
                val r = o and 3; val jj = (o shr 2) and 3; val g = (o shr 4) and 3; val asg = (o shr 6) and 3
                val col = TileColor.entries[c]
                val tiles = ArrayList<SetTile>()
                val bc = minOf(board[c][v], r)
                repeat(bc) { tiles += SetTile(col, v, false, false) }
                repeat(r - bc) { tiles += SetTile(col, v, false, true) }
                repeat(jj) {
                    val fromBoard = jokersLeftOnBoard > 0
                    if (fromBoard) jokersLeftOnBoard--
                    tiles += SetTile(col, v, true, !fromBoard)
                }
                repeat(g) { groupTiles[c] += tiles.removeAt(tiles.size - 1) }
                // DP 와 같은 기준(길이 3 이상은 3)으로 정렬된 두 칸
                val sl = slots[c]
                if (cap(sl[0].size) > cap(sl[1].size)) { val t = sl[0]; sl[0] = sl[1]; sl[1] = t }
                val extA = asg == 1 || asg == 3
                val extB = asg == 2 || asg == 3
                if (extA) sl[0] += tiles.removeAt(0) else if (sl[0].isNotEmpty()) { sets += sl[0].toList(); sl[0].clear() }
                if (extB) sl[1] += tiles.removeAt(0) else if (sl[1].isNotEmpty()) { sets += sl[1].toList(); sl[1].clear() }
            }
            sets += splitGroups(groupTiles)
        }
        for (sl in slots) for (s in sl) if (s.isNotEmpty()) sets += s.toList()
        return Solution(sets)
    }

    private fun splitGroups(g: Array<ArrayList<SetTile>>): List<List<SetTile>> {
        val twos = (0..3).filter { g[it].size == 2 }
        val ones = (0..3).filter { g[it].size == 1 }
        if (twos.isEmpty() && ones.isEmpty()) return emptyList()
        if (twos.isEmpty()) return listOf(ones.map { g[it][0] })
        val d = twos.size
        val x = (0..ones.size).first { d + it in 3..4 && d + ones.size - it in 3..4 }
        val g1 = twos.map { g[it][0] } + ones.take(x).map { g[it][0] }
        val g2 = twos.map { g[it][1] } + ones.drop(x).map { g[it][0] }
        return listOf(g1, g2)
    }

    private fun groupsOk(d: Int, s: Int): Boolean {
        if (d == 0) return s == 0 || s == 3 || s == 4
        for (x in 0..s) if (d + x in 3..4 && d + s - x in 3..4) return true
        return false
    }

    private fun cap(n: Int) = if (n > 3) 3 else n
    private fun end(x: Int) = if (x == 0 || x == 3) 0 else -1
    private fun ext(x: Int) = if (x >= 3) 3 else x + 1
    private val POW = intArrayOf(1, 10, 100, 1000)

    /** 화면 순서대로 놓인 세트가 규칙에 맞는지(런: 같은 색 연속 3장+, 그룹: 같은 숫자 다른 색 3~4장). */
    fun isValidSet(kinds: List<TileKind>): Boolean {
        if (kinds.size < 3) return false
        val real = kinds.filter { !it.isJoker }
        if (real.isEmpty()) return true
        // 그룹
        if (kinds.size <= 4 && real.all { it.value == real[0].value } && real.map { it.color }.toSet().size == real.size) return true
        // 런: 첫 실제 타일 위치로 시작 숫자를 정하고 자리마다 숫자가 맞는지
        if (real.any { it.color != real[0].color }) return false
        val i0 = kinds.indexOfFirst { !it.isJoker }
        val start = kinds[i0].value - i0
        if (start < 1 || start + kinds.size - 1 > 13) return false
        return kinds.withIndex().all { (i, k) -> k.isJoker || k.value == start + i }
    }
}
