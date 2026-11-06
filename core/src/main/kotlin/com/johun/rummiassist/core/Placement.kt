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
    /** free: 지금도 비어 있고 최종 배치에서도 비는 칸(순환을 풀 때 잠시 내려놓을 자리), 주변이 빈 칸부터 */
    /**
     * relaxed: 판에 빈 자리가 없어 '게임이 자동으로 판을 줄여 자리를 만든다'고 보고 놓은 세트
     * (기존 세트 끝에 붙이거나 줄 끝 너머). 이 세트들의 타일은 게임이 다시 배치하므로 위치 검사에서 뺀다.
     */
    class Out(
        val tiles: List<List<Pair<SetTile, DetectedTile>>>, val targets: List<List<Rect?>>, val placed: List<Boolean>,
        val free: List<Rect> = emptyList(), val relaxed: List<Boolean> = placed.map { false },
    )

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
        for (round in 0 until 6) {
            val now = linked(sets.indices.filter { !out.placed[it] }.toSet())
            if (now.isEmpty()) return out
            if (now == stuck) break
            stuck = now
            out = placeOnce(rec, sets, repack, stuck)
        }
        // 그래도 못 놓은(+함께 해야 하는) 세트는 게임의 자동 축소를 믿고 놓는다
        val left = linked(sets.indices.filter { !out.placed[it] }.toSet())
        return if (left.isEmpty()) out else placeOnce(rec, sets, repack, left, relax = true)
    }

    private fun placeOnce(rec: Recognizer.Result, sets: List<Pending>, repack: Boolean, stuck: Set<Int>, relax: Boolean = false): Out {
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

        /**
         * stayAt: 이 세트의 '제자리에 남는' 타일이 지금 있는 칸.
         * strict 면 그 외의 칸은 지금 비어 있어야 한다. 아니면 지금 타일이 있어도 그 타일이 옮겨 갈 칸이면 허용
         * (이동 순서에서 비운 다음에 놓도록 한다 — 타일 위에 덮어 놓는 일은 없다).
         */
        fun fits(r: Row, c0: Int, n: Int, owner: Int, stayAt: Set<Int> = emptySet(), strict: Boolean = true): Boolean {
            val range = colRange(r)
            if (c0 < range.first || c0 + n - 1 > range.last) return false
            val o = occ[r] ?: emptyMap<Int, Int>()
            for (c in c0 - 1..c0 + n) { val who = o[c]; if (who != null && who != owner) return false }
            if (strict) { val cur = now[r] ?: emptySet<Int>(); for (c in c0..c0 + n - 1) if (c in cur && c !in stayAt) return false }
            return true
        }

        val baseOcc = occ.mapValues { HashMap(it.value) }

        // 지금 각 칸에 있는 판 타일
        val occupant = HashMap<Pair<Row, Int>, DetectedTile>()
        for (t in board) cellOf(t)?.let { occupant[it] = t }

        class Attempt(val placed: Array<Pair<Row, Int>?>, val tiles: Array<List<Pair<SetTile, DetectedTile>>?>) {
            val unplaced get() = sets.indices.count { it !in stuck && placed[it] == null }
            /**
             * 순환 수: 타일 A 가 놓일 칸에 지금 있는 타일 B 는 먼저 비켜야 한다(A→B 의존). 이 의존이 돌고 돌면
             * 어느 쪽도 먼저 옮길 수 없다(덮어 놓기 없이는 불가능) → 그런 배치는 피한다.
             */
            val cycles: Int get() {
                val dest = HashMap<DetectedTile, Pair<Row, Int>>() // 판 타일 → 옮겨 갈 칸
                for (i in sets.indices) {
                    val sp = placed[i] ?: continue
                    tiles[i]!!.forEachIndexed { k, p -> if (!p.first.fromHand) { val c = sp.first to sp.second + k; if (cellOf(p.second) != c) dest[p.second] = c } }
                }
                var n = 0
                for (start in dest.keys) {
                    var cur: DetectedTile? = start
                    var steps = 0
                    while (cur != null && steps++ <= dest.size) {
                        val next = dest[cur]?.let { occupant[it] }
                        if (next === start) { n++; break }
                        cur = next
                    }
                }
                return n
            }
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
                val stayCount = s.tiles.count { (st, d) -> stayCand(d, st) }
                // 원래 자리 바로 옆(같은 줄, 한 칸 이내)으로 세트째 미는 자리는 빈 곳 후보에서 뺀다
                val ownCells = s.tiles.filter { !it.first.fromHand }.mapNotNull { cellOf(it.second) }
                fun freeSpot(n: Int, owner: Int, strict: Boolean): Pair<Row, Int>? = rows.firstNotNullOfOrNull { r ->
                    val own = ownCells.filter { it.first == r }.map { it.second }
                    colRange(r).firstOrNull { c0 ->
                        (own.isEmpty() || c0 + n - 1 < own.min() - 1 || c0 > own.max() + 1 || ownCells.size < 1) &&
                            fits(r, c0, n, owner, emptySet(), strict)
                    }?.let { r to it }
                }
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
                    // 남는 타일이 '전부' 제 칸에 그대로 있을 때만 제자리 배치(일부만 남기고 나머지를 옆으로 미는 일 없음)
                    .filter { it.third == stayCount }
                    .let { cs ->
                        fun ok(strict: Boolean) = cs.firstOrNull { (sp, arr, _) ->
                            val stayAt = arr.withIndex().filter { (k, p) -> stayCand(p.second, p.first) && cellOf(p.second) == (sp.first to sp.second + k) }
                                .map { sp.second + it.index }.toSet()
                            fits(sp.first, sp.second, n, i, stayAt, strict)
                        }
                        ok(true) ?: ok(false)
                    }
                var tiles = inPlace?.second ?: s.tiles
                val spot = inPlace?.first
                    ?: freeSpot(n, i, true) ?: freeSpot(n, i, false)
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
        val best = orders.map { attempt(it) }.minWith(compareBy<Attempt> { it.unplaced }.thenBy { it.cycles }.thenBy { it.moves })
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
        // 최종 배치(best)로 occ 를 다시 채워 빈 칸 목록을 만든다
        val finalOcc = HashMap<Row, HashSet<Int>>()
        for ((r, m) in baseOcc) finalOcc.getOrPut(r) { HashSet() } += m.keys
        for (i in sets.indices) { val sp = placed[i] ?: continue; for (k in 0 until (finalTiles[i] ?: sets[i].tiles).size) finalOcc.getOrPut(sp.first) { HashSet() } += sp.second + k }
        val free = ArrayList<Pair<Rect, Int>>()
        for (r in rows) for (c in colRange(r)) {
            if (c in (now[r] ?: emptySet<Int>()) || c in (finalOcc[r] ?: emptySet<Int>())) continue
            val crowd = (-1..1).count { d -> (c + d) in (now[r] ?: emptySet<Int>()) || (c + d) in (finalOcc[r] ?: emptySet<Int>()) }
            free += rectOf(r, c) to crowd
        }
        val relaxedFlags = BooleanArray(sets.size)
        if (relax) {
            // 자리 무시 배치: 게임이 판을 자동으로 줄여 자리를 만든다고 보고, 판 밖으로 나가도 놓는다.
            // 기존 세트 끝에 붙이는 건 이웃 세트와 겹쳐 보여도 게임이 자리를 만들어 준다(사용자 확인).
            // 다만 같은 원래 세트를 나눠 쓰는 다른 세트의 남는 타일이나, 이미 정한 자리와 겹치면 세트째 줄 끝으로 옮긴다.
            val fixed = HashMap<Row, HashSet<Int>>()
            fun stayCells(i: Int) = sets[i].tiles.filter { (st, d) -> !st.fromHand && sets[i].anchor != null && d in orig[sets[i].anchor!!] }
                .mapNotNull { p -> cellOf(p.second)?.let { p to it } }
            // 남는 타일이 많은 세트부터 자리를 정하고, 정한 자리만 막는다(적게 남는 세트가 비켜 준다).
            fun free(r: Row, cols: List<Int>, own: Set<Int>): Boolean {
                val f = fixed[r] ?: return true
                return cols.all { c -> (c !in f || c in own) } && (cols.min() - 1).let { it !in f || it in own } && (cols.max() + 1).let { it !in f || it in own }
            }
            for (i in stuck.sortedByDescending { stayCells(it).size }) {
                val s = sets[i]
                val stays = stayCells(i)
                var tiles = s.tiles
                var spot: Pair<Row, Int>? = null
                if (stays.isNotEmpty()) {
                    val r = stays.groupBy { it.second.first }.maxBy { it.value.size }.key
                    val inRow = stays.filter { it.second.first == r }.sortedBy { it.second.second }
                    val own = inRow.map { it.second.second }.toSet()
                    val contiguous = own.max() - own.min() + 1 == own.size && inRow.size == stays.size
                    if (contiguous) {
                        if (isGroup(s.tiles.map { it.first })) {
                            val rest = s.tiles.filter { p -> inRow.none { it.first === p } }
                            // 새 타일을 오른쪽에, 안 되면 왼쪽에
                            val right = inRow.map { it.first } + rest
                            val left = rest + inRow.map { it.first }
                            val c0r = own.min(); val c0l = own.min() - rest.size
                            if (free(r, (c0r until c0r + s.tiles.size).toList(), own)) { tiles = right; spot = r to c0r }
                            else if (free(r, (c0l until c0l + s.tiles.size).toList(), own)) { tiles = left; spot = r to c0l }
                        } else {
                            val (p, c) = inRow.first()
                            val c0 = c.second - s.tiles.indexOf(p)
                            if (free(r, (c0 until c0 + s.tiles.size).toList(), own)) spot = r to c0
                        }
                    }
                }
                if (spot == null) {
                    // 세트째 가장 덜 찬 줄의 끝 너머로(남는 타일 없이 전부 옮김) → 원래 자리는 비게 된다
                    tiles = s.tiles
                    val all = { r: Row -> (fixed[r] ?: emptySet()) + (finalOcc[r] ?: emptySet()) + (now[r] ?: emptySet()) }
                    val r = rows.minBy { r -> all(r).maxOrNull() ?: -99 }
                    val last = all(r).maxOrNull()
                    spot = r to (if (last == null) colRange(r).first else last + 2)
                }
                for (k in tiles.indices) fixed.getOrPut(spot.first) { HashSet() } += spot.second + k
                placed[i] = spot
                finalTiles[i] = tiles
                relaxedFlags[i] = true
                outTiles[i] = tiles
                val sp = spot
                outTargets[i] = tiles.mapIndexed { k, (st, d) ->
                    val c = sp.first to sp.second + k
                    if (!st.fromHand && cellOf(d) == c) null else rectOf(c.first, c.second)
                }
            }
        }
        return Out(outTiles, outTargets, placed.map { it != null }, free.sortedBy { it.second }.map { it.first }, relaxedFlags.toList())
    }

    /** 판 타일 크기. 판이 비었으면 손패 타일의 75%로 가정. */
    fun tileSize(rec: Recognizer.Result): Pair<Int, Int> {
        rec.board.firstOrNull()?.let { return it.rect.width to it.rect.height }
        val h = rec.hand.firstOrNull() ?: return 1 to 1
        return (h.rect.width * 0.75).roundToInt() to (h.rect.height * 0.75).roundToInt()
    }

    private fun isGroup(t: List<SetTile>) = t.size <= 4 && t.all { it.value == t[0].value } && t.map { it.color }.toSet().size == t.size
}
