package com.johun.rummiassist.core

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 바뀌는 세트를 판 어디에 놓을지 정한다(화살표 목적지).
 *
 * 판을 타일 간격 격자로 본다. 기존 판 줄은 그대로 줄이 되고, 빈 공간에는 줄 간격만큼 떨어진 새 줄을 만든다.
 * - 그대로 둘 세트는 제자리를 차지한다.
 * - 판 타일이 남는 세트(붙이기/재배치)는 남는 타일 자리에 맞춰 그 줄에 놓는다(붙이기면 양 끝에 추가).
 * - 그게 안 되거나 손패만으로 만든 새 세트는 빈 곳에 놓는다. 지금 아무 타일도 없는 칸을 우선한다.
 * 세트끼리는 같은 줄에서 최소 한 칸 띄운다(붙어 있으면 한 세트로 읽힌다).
 */
internal object Placement {

    class Pending(val tiles: List<Pair<SetTile, DetectedTile>>, val anchor: Int?)

    private class Row(val y: Int, val phase: Double)

    /** tiles: 세트별 타일(그룹은 놓을 순서로 재정렬), targets: 타일별 목적지(null = 제자리) */
    class Out(val tiles: List<List<Pair<SetTile, DetectedTile>>>, val targets: List<List<Rect?>>, val placed: List<Boolean>)

    /**
     * repack = true: 그대로 둘 세트도 '움직일 수 있는' 것으로 넘겨받는다. 다만 제자리에 둘 수 있으면 그대로 두고,
     * 새 세트가 들어갈 자리를 막는 세트만 옮긴다(판이 조각나 자리가 모자랄 때의 대안).
     */
    /**
     * 자리를 못 찾은 세트는 실행할 수 없고, 같은 원래 판 세트를 나눠 쓰는 세트들(linked)도 함께 실행할 수 없다.
     * 그 세트들의 판 타일은 실제로 제자리에 남으므로 그 칸을 막고 다시 배치한다(더 안 바뀔 때까지).
     */
    fun place(rec: Recognizer.Result, sets: List<Pending>, repack: Boolean = false, linked: (Set<Int>) -> Set<Int> = { it }): Out {
        var stuck = emptySet<Int>()
        var out = placeOnce(rec, sets, repack, stuck)
        repeat(6) {
            val now = linked(sets.indices.filter { !out.placed[it] }.toSet())
            if (now == stuck || now.isEmpty()) return out
            stuck = now
            out = placeOnce(rec, sets, repack, stuck)
        }
        return out
    }

    private fun placeOnce(rec: Recognizer.Result, sets: List<Pending>, repack: Boolean, stuck: Set<Int>): Out {
        val orig = rec.boardSets
        val board = rec.board
        if (rec.tiles.isEmpty()) return Out(sets.map { it.tiles }, sets.map { s -> s.tiles.map { null } }, sets.map { false })
        val (tileW, tileH) = tileSize(rec)
        // 타일 간격: 이웃 간격 중앙값으로 대략 잡고, 같은 줄 타일 쌍 전체(칸 수로 나눔)로 정밀하게 다듬는다.
        val p0 = orig.flatMap { s -> s.sortedBy { it.rect.cx }.zipWithNext { a, b -> (b.rect.cx - a.rect.cx).toDouble() } }
            .sorted().let { if (it.isEmpty()) tileW * 1.045 else it[it.size / 2] }
        var sumD = 0.0; var sumK = 0
        for (a in board) for (b in board) {
            if (b.rect.cx <= a.rect.cx || abs(a.rect.cy - b.rect.cy) > tileH / 3) continue
            val k = ((b.rect.cx - a.rect.cx) / p0).roundToInt()
            if (k >= 1) { sumD += b.rect.cx - a.rect.cx; sumK += k }
        }
        val pitch = if (sumK > 0) sumD / sumK else p0
        val rowPitch = tileH * 1.04

        val w = rec.width; val h = rec.height
        val xMin = minOf(w * 0.14, board.minOfOrNull { it.rect.left }?.toDouble() ?: Double.MAX_VALUE)
        val xMax = maxOf(w * 0.85, board.maxOfOrNull { it.rect.right }?.toDouble() ?: 0.0)
        // 판 영역: 첨부 캡처 기준 가로 14~85%, 세로 10%~랙 위 (기존 판 타일이 더 바깥에 있으면 거기까지)
        val yMin = minOf(h * 0.10, board.minOfOrNull { it.rect.top }?.toDouble() ?: Double.MAX_VALUE)
        val yMax = rec.rackTop - tileH * 0.1

        // 줄: 기존 판 줄 + 그 줄에서 줄 간격 배수만큼 떨어진 빈 줄
        val existing = ArrayList<Row>()
        // 줄 위치와 칸 위상(x 기준점)은 그 줄 타일 전체의 평균
        val rowTiles = ArrayList<MutableList<DetectedTile>>()
        for (t in board.sortedBy { it.rect.cy }) {
            val r = rowTiles.firstOrNull { abs(it[0].rect.cy - t.rect.cy) < tileH / 2 }
            if (r != null) r += t else rowTiles += mutableListOf(t)
        }
        for (r in rowTiles) {
            val ph0 = ((r[0].rect.cx % pitch) + pitch) % pitch
            val ph = r.map { t -> val k = ((t.rect.cx - ph0) / pitch).roundToInt(); t.rect.cx - k * pitch }.average()
            existing += Row(r.map { it.rect.cy }.average().roundToInt(), ph)
        }
        val rows = ArrayList(existing)
        val seeds = existing.ifEmpty { listOf(Row((yMin + tileH / 2.0).roundToInt(), ((xMin + pitch / 2) % pitch))) }
        for (k in 1..40) for (s in seeds) for (sign in intArrayOf(-1, 1)) {
            val y = (s.y + sign * k * rowPitch).roundToInt()
            if (y - tileH / 2 < yMin || y + tileH / 2 > yMax) continue
            if (rows.any { abs(it.y - y) < rowPitch * 0.98 }) continue
            rows += Row(y, s.phase)
        }
        if (existing.isEmpty() && seeds[0].y + tileH / 2 <= yMax) rows += seeds[0]
        rows.sortBy { it.y }

        fun colX(r: Row, c: Int) = r.phase + c * pitch
        fun colRange(r: Row): IntRange {
            val lo = kotlin.math.ceil((xMin + tileW / 2.0 - r.phase) / pitch).toInt()
            val hi = kotlin.math.floor((xMax - tileW / 2.0 - r.phase) / pitch).toInt()
            return lo..hi
        }
        fun cellOf(t: DetectedTile): Pair<Row, Int>? {
            val r = rows.minByOrNull { abs(it.y - t.rect.cy) } ?: return null
            if (abs(r.y - t.rect.cy) > tileH / 2) return null
            return r to ((t.rect.cx - r.phase) / pitch).roundToInt()
        }
        fun rectOf(r: Row, c: Int): Rect {
            val cx = colX(r, c).roundToInt()
            return Rect(cx - tileW / 2, r.y - tileH / 2, cx - tileW / 2 + tileW, r.y - tileH / 2 + tileH)
        }

        val occ = HashMap<Row, HashMap<Int, Int>>()    // 최종 배치: 칸 → 주인 (attempt 마다 baseOcc 로 초기화)
        val now = HashMap<Row, HashSet<Int>>()          // 지금 타일이 있는 칸
        for (t in board) cellOf(t)?.let { (r, c) -> now.getOrPut(r) { HashSet() } += c }
        // 이번 수에 쓰이지 않는 판 타일(그대로 둘 세트, 첫 등록 때의 판 전체)은 제자리를 차지한다.
        val used = sets.filterIndexed { i, _ -> i !in stuck }.flatMap { s -> s.tiles.map { it.second } }.toHashSet()
        val stuckTiles = stuck.flatMap { i -> sets[i].tiles.filter { !it.first.fromHand }.map { it.second } }.toHashSet()
        for ((i, s) in orig.withIndex()) for (t in s)
            if ((!repack && t !in used) || t in stuckTiles) cellOf(t)?.let { (r, c) -> occ.getOrPut(r) { HashMap() }[c] = -1 - i }

        fun fits(r: Row, c0: Int, n: Int, owner: Int, avoidNow: Boolean): Boolean {
            val range = colRange(r)
            if (c0 < range.first || c0 + n - 1 > range.last) return false
            val o = occ[r] ?: emptyMap<Int, Int>()
            for (c in c0 - 1..c0 + n) { val who = o[c]; if (who != null && who != owner) return false }
            if (avoidNow) { val cur = now[r] ?: emptySet<Int>(); for (c in c0..c0 + n - 1) if (c in cur) return false }
            return true
        }

        val baseOcc = occ.mapValues { HashMap(it.value) }

        class Attempt(val placed: Array<Pair<Row, Int>?>, val tiles: Array<List<Pair<SetTile, DetectedTile>>?>) {
            val unplaced get() = sets.indices.count { it !in stuck && placed[it] == null }
            val moves get() = sets.indices.sumOf { i ->
                val sp = placed[i] ?: return@sumOf 0
                tiles[i]!!.withIndex().count { (k, p) -> p.first.fromHand || cellOf(p.second) != (sp.first to sp.second + k) }
            }
        }

        /** 주어진 순서로 세트를 하나씩 놓아 본다. */
        fun attempt(order: List<Int>): Attempt {
            occ.clear(); for ((r, m) in baseOcc) occ[r] = HashMap(m)
            val placed = arrayOfNulls<Pair<Row, Int>>(sets.size)
            val finalTiles = arrayOfNulls<List<Pair<SetTile, DetectedTile>>>(sets.size)
            for (i in order) {
                if (i in stuck) continue
                val s = sets[i]
                val stayCand = { d: DetectedTile, st: SetTile -> !st.fromHand && s.anchor != null && d in orig[s.anchor] }
                val n = s.tiles.size
                val group = isGroup(s.tiles.map { it.first })
                // 제자리 후보 (줄, 시작 칸, 그 자리에서의 타일 순서, 제자리에 남는 타일 수)
                val cands = ArrayList<Triple<Pair<Row, Int>, List<Pair<SetTile, DetectedTile>>, Int>>()
                if (group) {
                    // 그룹은 순서가 자유: 남는 타일 하나를 기준으로 가능한 모든 시작 칸에서, 그 범위 안의 남는 타일은 제 칸에 둔다
                    val stays = s.tiles.filter { (st, d) -> stayCand(d, st) }
                    val starts = HashSet<Pair<Row, Int>>()
                    for ((_, d) in stays) cellOf(d)?.let { (r, c) -> for (k in 0 until n) starts += r to c - k }
                    for ((r, c0) in starts) {
                        val slot = arrayOfNulls<Pair<SetTile, DetectedTile>>(n)
                        val rest = ArrayList<Pair<SetTile, DetectedTile>>()
                        for (p in s.tiles) {
                            val cell = if (stayCand(p.second, p.first)) cellOf(p.second) else null
                            val k = if (cell != null && cell.first == r) cell.second - c0 else -1
                            if (k in 0 until n && slot[k] == null) slot[k] = p else rest += p
                        }
                        val kept = slot.count { it != null }
                        val it = rest.iterator()
                        for (k in 0 until n) if (slot[k] == null) slot[k] = it.next()
                        cands += Triple(r to c0, slot.map { x -> x!! }, kept)
                    }
                } else {
                    val votes = HashMap<Pair<Row, Int>, Int>()
                    s.tiles.forEachIndexed { idx, (st, d) ->
                        if (stayCand(d, st)) cellOf(d)?.let { (r, c) -> votes.merge(r to c - idx, 1, Int::plus) }
                    }
                    for ((k, v) in votes) cands += Triple(k, s.tiles, v)
                }
                // 순서가 실행마다 달라지지 않게: 제자리 타일 수 → 줄 위치 → 칸 순으로 고정
                val inPlace = cands.sortedWith(compareByDescending<Triple<Pair<Row, Int>, List<Pair<SetTile, DetectedTile>>, Int>> { it.third }
                    .thenBy { it.first.first.y }.thenBy { it.first.second })
                    .firstOrNull { (sp, _, _) -> fits(sp.first, sp.second, n, i, avoidNow = false) }
                var tiles = inPlace?.second ?: s.tiles
                val spot = inPlace?.first
                    ?: rows.firstNotNullOfOrNull { r -> colRange(r).firstOrNull { fits(r, it, n, i, true) }?.let { r to it } }
                    ?: rows.firstNotNullOfOrNull { r -> colRange(r).firstOrNull { fits(r, it, n, i, false) }?.let { r to it } }
                if (inPlace == null && group) tiles = s.tiles
                finalTiles[i] = tiles
                if (spot != null) {
                    placed[i] = spot
                    for (k in 0 until n) occ.getOrPut(spot.first) { HashMap() }[spot.second + k] = i
                }
            }
            return Attempt(placed, finalTiles)
        }

        // 놓는 순서에 따라 결과가 달라진다: 몇 가지 순서로 해 보고, 못 놓은 세트가 적고 → 움직임이 적은 것을 고른다.
        fun stays(i: Int) = sets[i].tiles.count { (st, d) -> !st.fromHand && sets[i].anchor != null && d in orig[sets[i].anchor!!] }
        val idx = sets.indices.toList()
        val orders = listOf(
            idx.sortedWith(compareByDescending<Int> { stays(it) }.thenByDescending { sets[it].tiles.size }),
            idx.sortedByDescending { sets[it].tiles.size },
            idx.sortedWith(compareByDescending<Int> { sets[it].tiles.count { p -> p.first.fromHand } }.thenByDescending { sets[it].tiles.size }),
            idx.sortedBy { stays(it) },
        ).let { base ->
            // 몇 가지 정해진 순서 + 고정 시드 무작위 순서(실행마다 같은 결과)
            val rnd = kotlin.random.Random(42)
            base + List(if (sets.size > 1) 24 else 0) { idx.shuffled(rnd) }
        }.distinct()
        val best = orders.map { attempt(it) }.minWith(compareBy<Attempt> { it.unplaced }.thenBy { it.moves })
        val placed = best.placed
        val finalTiles = best.tiles
        val outTiles = ArrayList<List<Pair<SetTile, DetectedTile>>>()
        val outTargets = ArrayList<List<Rect?>>()
        for (i in sets.indices) {
            val tiles = finalTiles[i] ?: sets[i].tiles
            val spot = placed[i]
            outTiles += tiles
            outTargets += tiles.mapIndexed { k, (st, d) ->
                if (spot == null) return@mapIndexed null
                val (r, c) = spot.first to spot.second + k
                if (!st.fromHand && cellOf(d) == (r to c)) null else rectOf(r, c)
            }
        }
        return Out(outTiles, outTargets, placed.map { it != null })
    }

    /** 판 타일 크기. 판이 비었으면 손패 타일의 75%로 가정. */
    fun tileSize(rec: Recognizer.Result): Pair<Int, Int> {
        rec.board.firstOrNull()?.let { return it.rect.width to it.rect.height }
        val h = rec.hand.firstOrNull() ?: return 1 to 1
        return (h.rect.width * 0.75).roundToInt() to (h.rect.height * 0.75).roundToInt()
    }

    private fun isGroup(t: List<SetTile>) = t.size <= 4 && t.all { it.value == t[0].value } && t.map { it.color }.toSet().size == t.size
}
