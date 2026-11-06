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
     * repack = true: 모든 세트(그대로 둘 세트 포함)를 긴 것부터 빈 판에 차곡차곡 다시 놓는다.
     * 판이 조각나 자리가 모자랄 때 쓰는 대안(움직이는 타일은 늘지만 완성된 판이 반드시 유효).
     */
    fun place(rec: Recognizer.Result, sets: List<Pending>, repack: Boolean = false): Out {
        val orig = rec.boardSets
        val board = rec.board
        val ref = board.firstOrNull() ?: rec.hand.firstOrNull() ?: return Out(sets.map { it.tiles }, sets.map { s -> s.tiles.map { null } }, sets.map { false })
        val tileW = if (board.isNotEmpty()) ref.rect.width else (ref.rect.width * 0.75).roundToInt()
        val tileH = if (board.isNotEmpty()) ref.rect.height else (ref.rect.height * 0.75).roundToInt()
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

        val occ = HashMap<Row, HashMap<Int, Int>>()    // 최종 배치: 칸 → 주인
        val now = HashMap<Row, HashSet<Int>>()          // 지금 타일이 있는 칸
        for (t in board) cellOf(t)?.let { (r, c) -> now.getOrPut(r) { HashSet() } += c }
        // 이번 수에 쓰이지 않는 판 타일(그대로 둘 세트, 첫 등록 때의 판 전체)은 제자리를 차지한다.
        val used = sets.flatMap { s -> s.tiles.map { it.second } }.toHashSet()
        if (!repack) for ((i, s) in orig.withIndex()) for (t in s) if (t !in used) cellOf(t)?.let { (r, c) -> occ.getOrPut(r) { HashMap() }[c] = -1 - i }

        fun fits(r: Row, c0: Int, n: Int, owner: Int, avoidNow: Boolean): Boolean {
            val range = colRange(r)
            if (c0 < range.first || c0 + n - 1 > range.last) return false
            val o = occ[r] ?: emptyMap<Int, Int>()
            for (c in c0 - 1..c0 + n) { val who = o[c]; if (who != null && who != owner) return false }
            if (avoidNow) { val cur = now[r] ?: emptySet<Int>(); for (c in c0..c0 + n - 1) if (c in cur) return false }
            return true
        }

        val outTiles = ArrayList<List<Pair<SetTile, DetectedTile>>>()
        val outTargets = ArrayList<List<Rect?>>()
        // 남는 판 타일이 많은 세트부터 제자리 배치를 시도
        val order = if (repack) sets.indices.sortedByDescending { sets[it].tiles.size }
        else sets.indices.sortedByDescending { i -> sets[i].tiles.count { (st, d) -> !st.fromHand && sets[i].anchor != null && d in orig[sets[i].anchor!!] } }
        val placed = arrayOfNulls<Pair<Row, Int>>(sets.size)
        val finalTiles = arrayOfNulls<List<Pair<SetTile, DetectedTile>>>(sets.size)
        for (i in order) {
            val s = sets[i]
            val stayCand = { d: DetectedTile, st: SetTile -> !st.fromHand && s.anchor != null && d in orig[s.anchor] }
            var tiles = s.tiles
            // 그룹은 순서가 자유: 남는 타일을 지금 순서대로 앞에, 새 타일을 뒤에
            if (isGroup(tiles.map { it.first })) {
                val (stay, rest) = tiles.partition { (st, d) -> stayCand(d, st) }
                tiles = stay.sortedBy { it.second.rect.cx } + rest
            }
            finalTiles[i] = tiles
            // 제자리: 남는 타일들이 가장 많이 동의하는 (줄, 시작 칸)
            val votes = HashMap<Pair<Row, Int>, Int>()
            tiles.forEachIndexed { idx, (st, d) ->
                if (stayCand(d, st)) cellOf(d)?.let { (r, c) -> votes.merge(r to c - idx, 1, Int::plus) }
            }
            val inPlace = votes.entries.sortedByDescending { it.value }.map { it.key }
                .firstOrNull { (r, c0) -> fits(r, c0, tiles.size, i, avoidNow = false) }
            val spot = (if (repack) null else inPlace)
                ?: rows.firstNotNullOfOrNull { r -> colRange(r).firstOrNull { fits(r, it, tiles.size, i, true) }?.let { r to it } }
                ?: rows.firstNotNullOfOrNull { r -> colRange(r).firstOrNull { fits(r, it, tiles.size, i, false) }?.let { r to it } }
            if (spot != null) {
                placed[i] = spot
                for (k in tiles.indices) occ.getOrPut(spot.first) { HashMap() }[spot.second + k] = i
            }
        }
        for (i in sets.indices) {
            val tiles = finalTiles[i]!!
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

    private fun isGroup(t: List<SetTile>) = t.size <= 4 && t.all { it.value == t[0].value } && t.map { it.color }.toSet().size == t.size
}
