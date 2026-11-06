package com.johun.rummiassist.core

/** 인식 결과 → 추천 수. 판에 이미 있는 세트는 가능한 한 그대로 두고, 바뀌는 세트만 알려 준다. */
object Advisor {

    enum class Status(val label: String) { NEW("새 세트"), EXTENDED("붙이기"), REARRANGED("재배치"), MOVED("자리 이동") }

    /**
     * screen: 화면의 실제 타일(손패/판), target: 옮겨 놓을 판 위 자리(null = 제자리, 또는 놓을 빈 곳이 없음)
     * moved: 판 타일인데 다른 자리로 옮겨야 함
     */
    class PlanTile(val tile: SetTile, val screen: DetectedTile?, val target: Rect? = null, val parkFor: PlanTile? = null) {
        val moved get() = !tile.fromHand && target != null
        /** 순환을 풀려고 잠시 내려놓는 빈 칸(이 타일의 첫 이동 parkFor 가 이 칸으로 간다) */
        var via: Rect? = null
        val isPark get() = parkFor != null
    }
    /** noRoom: 판에 이 세트를 놓을 빈 자리를 못 찾음(화살표 없음), step: 속한 단계 번호 */
    class PlanSet(val number: Int, val status: Status, val tiles: List<PlanTile>, val noRoom: Boolean = false, val step: Int = 1)

    /**
     * 실행 단계. 1단계부터 순서대로, 각 단계를 끝까지 하면 그 시점의 판이 규칙에 맞는다(valid).
     * 같은 원래 세트에서 타일을 가져가는 세트들은 한 단계에 묶인다(일부만 하면 원래 세트가 2장 이하로 깨진다).
     */
    class Step(val number: Int, val sets: List<PlanSet>, val valid: Boolean, private val order: List<PlanTile>? = null) {
        /** 판에 자리가 없어 실행할 수 없는 단계 */
        val blocked get() = sets.any { it.noRoom }
        val handTiles get() = sets.sumOf { s -> s.tiles.count { it.tile.fromHand } }
        /**
         * 이 단계의 이동을 할 순서: 세트 하나씩 끝내며, 세트 안에선 판 타일을 먼저 옮기고 손패를 놓는다.
         * (자리 부족 단계는 비어 있음)
         */
        val moves: List<PlanTile> get() = if (blocked) emptyList()
            else order ?: sets.flatMap { s -> s.tiles.filter { it.target != null }.sortedBy { if (it.tile.fromHand) 1 else 0 } }
    }

    class Advice(
        val recognized: Recognizer.Result,
        val sets: List<PlanSet>,
        val keptSets: Int,
        val handPlayed: Int,
        val handValue: Int,
        val message: String,
        val warnings: List<String>,
        /** 조커를 안 쓰기로 했을 때, 썼다면 얼마나 더 낼 수 있는지 */
        val jokerHint: String? = null,
        val steps: List<Step> = emptyList(),
    ) {
        /** 전체 이동 순서(단계 순 → 단계 안 순서). 앞에서부터 하면 단계가 끝날 때마다 판이 유효. */
        val moves: List<PlanTile> get() = steps.flatMap { it.moves }
    }

    /**
     * @param useHandJokers false 면 손패 조커는 없는 것으로 계산(판 위 조커는 규칙상 계속 써야 함)
     * 제안은 화면에 실제로 있는 판 타일 + 손패만으로 만든다. 인식이 앞뒤가 안 맞으면(같은 타일 3장 등) 아무것도 제안하지 않는다.
     */
    fun advise(rec: Recognizer.Result, initialMeldDone: Boolean, useHandJokers: Boolean = true): Advice {
        val warnings = ArrayList<String>()
        val boardTiles = rec.board
        val handTiles = rec.hand
        val all = HashMap<TileKind, Int>()
        for (t in rec.tiles) all[t.kind] = (all[t.kind] ?: 0) + 1
        for ((k, n) in all) if (!k.isJoker && n > 2) warnings += "$k 가 ${n}장으로 인식됨(최대 2장)"
        if ((all[TileKind.JOKER] ?: 0) > 2) warnings += "조커가 3장 이상으로 인식됨"
        val origSets = rec.boardSets
        if (warnings.isNotEmpty())
            return Advice(rec, emptyList(), origSets.size, 0, 0, "인식이 불안정해요 — 화면을 그대로 두세요", warnings)

        val validOrig = origSets.filter { s -> Solver.isValidSet(s.map { it.kind }) }
        for (s in origSets - validOrig.toSet()) warnings += "판 세트 인식 이상: ${s.joinToString(" ") { it.kind.toString() }}"

        fun result(sol: Solution?, msg: String, hint: String? = null): Advice {
            if (sol == null || sol.handTiles == 0) return Advice(rec, emptyList(), origSets.size, 0, 0, msg, warnings, hint)
            val a = present(rec, origSets, sol, msg, warnings, hint, initialMeldDone)
            // 화면에 없는 타일이 하나라도 들어간 제안은 보여 주지 않는다.
            if (a.sets.any { s -> s.tiles.any { it.screen == null } })
                return Advice(rec, emptyList(), origSets.size, 0, 0, "인식이 불안정해요 — 화면을 그대로 두세요", warnings)
            return a
        }

        val (hc, hjAll) = counts(handTiles)
        val hj = if (useHandJokers) hjAll else 0
        if (!initialMeldDone) {
            // 첫 등록: 손패만으로 30점 이상. 먼저 장수 최대, 부족하면 점수 최대.
            fun meld(jokers: Int): Solution? {
                val empty = Solver.emptyCounts()
                var sol = Solver.solve(empty, hc, 0, jokers, wCount = 1000, wValue = 1, jokerValue = true)
                if (sol != null && sol.handValue < 30) sol = Solver.solve(empty, hc, 0, jokers, wCount = 1, wValue = 1000, jokerValue = true)
                return sol?.takeIf { it.handValue >= 30 }
            }
            val sol = meld(hj)
            val hint = if (hj < hjAll) meld(hjAll)?.let { j ->
                if (sol == null) "조커를 쓰면 첫 등록 가능 (${j.handTiles}장, ${j.handValue}점)"
                else if (j.handTiles > sol.handTiles) "조커를 쓰면 ${j.handTiles - sol.handTiles}장 더 낼 수 있어요" else null
            } else null
            if (sol == null) return result(null, "첫 등록(30점) 불가 — 타일을 뽑으세요", hint)
            return result(sol, "첫 등록: 손패 ${sol.handTiles}장, ${sol.handValue}점", hint)
        }

        val (bc, bj) = counts(boardTiles)
        val sol = Solver.solve(bc, hc, bj, hj)
            ?: return result(null, "판을 유효한 세트로 해석할 수 없어요(인식 오류 가능)")
        val hint = if (hj < hjAll) Solver.solve(bc, hc, bj, hjAll)?.let { j ->
            if (j.handTiles > sol.handTiles) "조커를 쓰면 ${j.handTiles - sol.handTiles}장 더 낼 수 있어요" else null
        } else null
        if (sol.handTiles == 0) return result(null, "낼 수 있는 조합이 없어요 — 타일을 뽑으세요", hint)
        val best = result(preserve(sol, validOrig), "최대 ${sol.handTiles}장 내기 (합 ${sol.handValue})", hint)
        if (best.steps.none { it.blocked }) return best
        // 최적 배치가 판에 다 들어가지 않으면, 덜 내는 다른 배치(조커 빼기, 손패 한 종류씩 빼기)가
        // 실제로 '할 수 있는' 장수가 더 많은지 비교해 고른다.
        fun doable(a: Advice) = a.steps.filter { !it.blocked }.sumOf { it.handTiles }
        var pick = best
        val tries = ArrayList<Pair<Array<IntArray>, Int>>()
        if (hj > 0) tries += hc to 0
        for (c in 0..3) for (v in 1..13) if (hc[c][v] > 0) tries += Array(4) { hc[it].clone() }.also { it[c][v]-- } to hj
        for ((h2, j2) in tries) {
            if (doable(pick) >= sol.handTiles) break
            val alt = Solver.solve(bc, h2, bj, j2) ?: continue
            if (alt.handTiles <= doable(pick)) continue
            val a2 = result(preserve(alt, validOrig), "${alt.handTiles}장 내기 (합 ${alt.handValue}) — 최대 ${sol.handTiles}장은 판에 자리가 부족", hint)
            if (doable(a2) > doable(pick)) pick = a2
        }
        return pick
    }

    /** 같은 손패 사용량을 유지하면서 원래 판 세트를 최대한 그대로 남기도록 다시 나눈다. */
    private fun preserve(sol: Solution, orig: List<List<DetectedTile>>): Solution {
        val tiles = sol.sets.flatten()
        val rb = Solver.emptyCounts(); var rbj = 0
        val rh = Solver.emptyCounts(); var rhj = 0
        for (t in tiles) when {
            t.joker && t.fromHand -> rhj++
            t.joker -> rbj++
            t.fromHand -> rh[t.color.ordinal][t.value]++
            else -> rb[t.color.ordinal][t.value]++
        }
        val kept = ArrayList<List<SetTile>>()
        for (s in orig.sortedByDescending { it.size }) {
            val kinds = s.map { it.kind }
            val nb = Array(4) { rb[it].clone() }; var nbj = rbj
            var ok = true
            for (k in kinds) if (k.isJoker) { if (--nbj < 0) ok = false } else if (--nb[k.color!!.ordinal][k.value] < 0) ok = false
            if (!ok) continue
            if (Solver.solve(nb, rh, nbj, rhj, requireAll = true) == null) continue
            for (c in 0..3) rb[c] = nb[c]
            rbj = nbj
            kept += representAsSetTiles(kinds)
        }
        val rest = Solver.solve(rb, rh, rbj, rhj, requireAll = true) ?: return sol
        return Solution(kept + rest.sets)
    }

    /** 원래 세트를 SetTile 로(조커가 대신하는 숫자 복원). */
    private fun representAsSetTiles(kinds: List<TileKind>): List<SetTile> {
        val real = kinds.filter { !it.isJoker }
        val isGroup = real.all { it.value == real[0].value } && real.map { it.color }.toSet().size == real.size && kinds.size <= 4
        if (isGroup) {
            val missing = TileColor.entries.filter { c -> real.none { it.color == c } }.iterator()
            return kinds.map { if (it.isJoker) SetTile(missing.next(), real[0].value, true, false) else SetTile(it.color!!, it.value, false, false) }
        }
        val i0 = kinds.indexOfFirst { !it.isJoker }
        val start = kinds[i0].value - i0
        val col = kinds[i0].color!!
        return kinds.mapIndexed { i, k -> SetTile(col, start + i, k.isJoker, false) }
    }

    private fun present(
        rec: Recognizer.Result, orig: List<List<DetectedTile>>, sol: Solution, msg: String, warnings: List<String>, hint: String?,
        canMoveBoard: Boolean,
    ): Advice {
        val origOf = HashMap<DetectedTile, Int>()
        orig.forEachIndexed { i, s -> s.forEach { origOf[it] = i } }
        val usedScreen = HashSet<DetectedTile>()
        val pending = ArrayList<Placement.Pending>()
        val keptAnchors = ArrayList<Int>()
        for (set in sol.sets.sortedBy { s -> s.count { it.fromHand } == s.size }) {
            val boardKinds = set.filter { !it.fromHand }.map { it.kind }
            // 겹치는 판 타일이 가장 많은 원래 세트를 기준으로 삼는다.
            val anchor = orig.indices.maxByOrNull { i -> overlap(orig[i].filter { it !in usedScreen }.map { it.kind }, boardKinds) }
                ?.takeIf { boardKinds.isNotEmpty() && overlap(orig[it].map { t -> t.kind }, boardKinds) > 0 }
            val plan = set.map { st ->
                val cand = rec.tiles.filter { it.kind == st.kind && it.inHand == st.fromHand && it !in usedScreen }
                val pick = cand.firstOrNull { anchor != null && origOf[it] == anchor } ?: cand.firstOrNull()
                    ?: return Advice(rec, emptyList(), orig.size, 0, 0, "인식이 불안정해요 — 화면을 그대로 두세요", warnings)
                usedScreen += pick
                st to pick
            }
            val anchorKinds = anchor?.let { orig[it].map { t -> t.kind } }
            if (anchorKinds != null && sameMultiset(anchorKinds, set.map { it.kind })) { keptAnchors += anchor; continue }
            pending += Placement.Pending(plan, anchor)
        }
        // 같은 원래 판 세트의 타일을 나눠 쓰는 세트들(함께 실행해야 함)
        fun linked(ps: List<Placement.Pending>): (Set<Int>) -> Set<Int> = { seed ->
            val groups = components(ps.map { p -> p.tiles.filter { !it.first.fromHand }.mapNotNull { origOf[it.second] }.toSet() })
            groups.filter { g -> g.any { it in seed } }.flatten().toSet()
        }
        var all: List<Placement.Pending> = pending
        var placed = Placement.place(rec, all, linked = linked(all))
        if (placed.placed.any { !it } && canMoveBoard) {
            // 자리가 모자라면 그대로 둘 세트까지 포함해 판 전체를 다시 채워 본다(첫 등록 전엔 판을 못 건드리니 안 함).
            val used = pending.flatMap { p -> p.tiles.map { it.second } }.toHashSet()
            val untouched = orig.indices.filter { a -> orig[a].none { it in used } }
            val withKept = pending + untouched.map { a -> Placement.Pending(keptTiles(orig[a]), a) }
            val re = Placement.place(rec, withKept, repack = true, linked = linked(withKept))
            if (re.placed.count { !it } < placed.placed.count { !it }) { all = withKept; placed = re; keptAnchors.clear() }
        }
        val out = all.indices.map { i ->
            val plan = placed.tiles[i].zip(placed.targets[i]) { p, t -> PlanTile(p.first, p.second, t) }
            val boardKinds = plan.filter { !it.tile.fromHand }.map { it.tile.kind }
            val anchorKinds = all[i].anchor?.let { orig[it].map { t -> t.kind } }
            val status = when {
                boardKinds.isEmpty() -> Status.NEW
                plan.none { it.tile.fromHand } -> Status.MOVED
                anchorKinds != null && plan.none { it.moved } && sameMultiset(anchorKinds, boardKinds) -> Status.EXTENDED
                else -> Status.REARRANGED
            }
            PlanSet(0, status, plan, noRoom = !placed.placed[i])
        }
        // 자리를 다시 잡느라 함께 넘겼지만 결국 제자리인 기존 세트는 목록에서 빼고 '그대로 둘 세트'로 센다
        val (stillKept, changed) = normalizeSwaps(rec, out).partition { p -> p.tiles.none { it.tile.fromHand } && p.tiles.all { it.target == null } && !p.noRoom }
        val steps = makeSteps(rec, orig, changed, oneStep = !canMoveBoard, parking = placed.free)
        return Advice(rec, steps.flatMap { it.sets }, keptAnchors.size + stillKept.size, sol.handTiles, sol.handValue, msg, warnings, hint, steps)
    }

    /**
     * 같은 종류 타일끼리 자리 바꾸기 정리: 타일 A 의 목적지에 같은 종류의 다른 움직일 판 타일 B 가 이미 있으면,
     * B 가 그 자리에 그대로 있고 A 가 B 의 목적지로 가는 것과 같다(같은 타일이니까). 움직임이 줄고 진행 추적이 헷갈리지 않는다.
     */
    private fun normalizeSwaps(rec: Recognizer.Result, sets: List<PlanSet>): List<PlanSet> {
        val (tw, th) = Placement.tileSize(rec)
        val tiles = sets.map { it.tiles.toMutableList() }
        fun at(r: Rect, d: DetectedTile) = kotlin.math.abs(r.cx - d.rect.cx) < tw * 0.45 && kotlin.math.abs(r.cy - d.rect.cy) < th * 0.45
        var changed = true
        var guard = 0
        while (changed && guard++ < 200) {
            changed = false
            loop@ for (si in tiles.indices) for (ti in tiles[si].indices) {
                val a = tiles[si][ti]
                val t = a.target ?: continue
                for (sj in tiles.indices) for (tj in tiles[sj].indices) {
                    val b = tiles[sj][tj]
                    if (b === a || b.tile.fromHand || b.target == null || b.tile.kind != a.tile.kind) continue
                    val bs = b.screen ?: continue
                    if (!at(t, bs)) continue
                    // B 는 제자리(A 의 역할), A 는 B 의 목적지로(B 의 역할). 목적지가 A 의 지금 자리면 그대로 있으면 됨.
                    val aStays = a.screen != null && !a.tile.fromHand && at(b.target, a.screen)
                    tiles[si][ti] = PlanTile(a.tile, bs, null)
                    tiles[sj][tj] = PlanTile(b.tile.copy(fromHand = a.tile.fromHand), a.screen, if (aStays) null else b.target)
                    tiles[si][ti] = PlanTile(a.tile.copy(fromHand = false), bs, null)
                    changed = true
                    break@loop
                }
            }
        }
        return sets.mapIndexed { i, p -> PlanSet(p.number, p.status, tiles[i], p.noRoom, p.step) }
    }

    /** 원래 판 세트 번호를 공유하는 것끼리 묶은 연결 요소(세트 번호 목록들) */
    private fun components(origSetsOf: List<Set<Int>>): List<List<Int>> {
        val parent = IntArray(origSetsOf.size) { it }
        fun find(x: Int): Int = if (parent[x] == x) x else find(parent[x]).also { parent[x] = it }
        val owner = HashMap<Int, Int>()
        origSetsOf.forEachIndexed { i, os -> for (o in os) { val p = owner[o]; if (p == null) owner[o] = i else parent[find(i)] = find(p) } }
        return origSetsOf.indices.groupBy { find(it) }.values.toList()
    }

    /**
     * 세트들을 실행 단계로 나눈다.
     * 1) 같은 원래 판 세트의 타일을 쓰는 세트끼리 묶음(합집합 찾기).
     * 2) 남은 묶음 중, 지금까지 한 단계 + 이 묶음을 실행한 판이 유효한 것을 '이동 1번당 낼 수 있는 손패'가 많은 순으로 고른다.
     *    하나로 안 되면 둘씩, 그래도 안 되면 남은 것 전부를 한 단계로.
     * oneStep(첫 등록): 30점은 한 턴에 함께 내야 하므로 한 단계.
     */
    private fun makeSteps(
        rec: Recognizer.Result, orig: List<List<DetectedTile>>, sets: List<PlanSet>, oneStep: Boolean, parking: List<Rect> = emptyList(),
    ): List<Step> {
        if (sets.isEmpty()) return emptyList()
        val origOf = HashMap<DetectedTile, Int>()
        orig.forEachIndexed { i, s -> s.forEach { origOf[it] = i } }
        // 자리가 없는 세트가 낀 묶음은 끝까지 실행할 수 없으므로(원래 세트가 깨진 채 남음) 통째로 맨 뒤 '자리 부족' 단계로
        val all = if (oneStep) listOf(sets.indices.toList())
        else components(sets.map { s -> s.tiles.filter { !it.tile.fromHand }.mapNotNull { it.screen?.let { d -> origOf[d] } }.toSet() })
        val blocked = all.filter { c -> c.any { sets[it].noRoom } }
        var comps: MutableList<List<Int>> = all.filter { c -> c.none { sets[it].noRoom } }.toMutableList()
        val (tw, th) = Placement.tileSize(rec)
        val inPlan = sets.flatMap { s -> s.tiles.mapNotNull { it.screen } }.toHashSet()
        /** 옮긴 이동(applied)만 반영한 판의 타일 위치(잠시 내려놓은 타일은 그 빈 칸에) */
        val parked = HashSet<PlanTile>()
        fun positions(applied: Set<PlanTile>): List<BoardCheck.P> {
            val ps = ArrayList<BoardCheck.P>()
            for (t in rec.board) if (t !in inPlan) ps += BoardCheck.P(t.kind, t.rect.cx, t.rect.cy)
            for (st in sets) for (pt in st.tiles) {
                val r = if (pt in applied) pt.target else if (pt in parked) pt.via else if (pt.tile.fromHand) null else pt.screen?.rect
                r?.let { ps += BoardCheck.P(pt.tile.kind, it.cx, it.cy) }
            }
            return ps
        }
        fun movesOf(c: Collection<Int>) = c.flatMap { i -> sets[i].tiles.filter { it.target != null } }
        fun badness(applied: Set<PlanTile>) = BoardCheck.badness(positions(applied), tw, th)
        fun hand(c: List<Int>) = c.sumOf { i -> sets[i].tiles.count { it.tile.fromHand } }
        fun moves(c: List<Int>) = movesOf(c).size.coerceAtLeast(1)
        val order = ArrayList<Pair<List<Int>, Boolean>>()
        val done = HashSet<Int>()
        // 제한 시간이 언제 끝나도 판이 최대한 유효하도록: 실행 후 판의 '무효 타일 수'가 가장 적은 묶음부터,
        // 같으면 이동 1번당 손패를 많이 내는 것, 그다음 이동이 적은 것.
        while (comps.isNotEmpty()) {
            val single = comps.minWith(
                compareBy<List<Int>> { badness(movesOf(done + it).toSet()) }
                    .thenByDescending { hand(it).toDouble() / moves(it) }.thenBy { moves(it) },
            )
            // 하나로는 판이 유효해지지 않으면(다음 단계 타일이 자리를 막거나 붙어 있는 등) 유효해질 때까지
            // 가장 도움이 되는 묶음을 하나씩 더 합친다 → 모든 단계 경계에서 판이 유효
            val chosen = mutableListOf(single)
            fun bad() = badness(movesOf(done + chosen.flatten()).toSet())
            while (bad() > 0) {
                val restC = comps.filter { c -> chosen.none { it === c } }
                if (restC.isEmpty()) break
                chosen += restC.minBy { c -> badness(movesOf(done + chosen.flatten() + c).toSet()) }
            }
            val merged = chosen.flatten()
            done += merged
            order += merged to (badness(movesOf(done).toSet()) == 0)
            comps = comps.filter { c -> chosen.none { it === c } }.toMutableList()
        }
        // 규칙: 한 턴에 손패를 한 장 이상 내야 판 변경이 인정된다 → 손패를 안 내는 단계(기존 타일 정리만)는
        // 혼자 끝낼 수 있는 단계가 아니므로 다음 단계에 합친다(마지막이면 앞 단계에).
        var k = 0
        while (k < order.size) {
            val (idx, _) = order[k]
            if (hand(idx) == 0 && order.size > 1) {
                val into = if (k + 1 < order.size) k + 1 else k - 1
                val merged = (order[into].first + idx)
                order[into] = merged to order[maxOf(into, k)].second
                order.removeAt(k)
                if (into > k) continue else { k = 0; continue }
            }
            k++
        }
        var rest = blocked.flatten()
        // 손패를 내는 단계가 하나도 없으면(전부 자리 부족) 정리만 하는 단계도 혼자 할 수 없다
        if (rest.isNotEmpty() && order.isNotEmpty() && order.all { hand(it.first) == 0 }) { rest = order.flatMap { it.first } + rest; order.clear() }
        if (rest.isNotEmpty()) order += rest to false
        // 단계 안의 이동 순서: 이동 하나마다, 그 이동 뒤 판의 무효 타일 수가 가장 적어지는 것부터
        // (같으면 방금 옮기던 세트를 이어서 → 세트를 하나씩 완성). 중간에 시간이 끝나도 판이 최대한 유효하다.
        val applied = HashSet<PlanTile>()
        val stepMoves = order.map { (idx, ok) ->
            if (!ok && idx.any { sets[it].noRoom }) return@map emptyList<PlanTile>()
            val left = movesOf(idx).toMutableList()
            val seq = ArrayList<PlanTile>()
            var lastSet: PlanSet? = null
            while (left.isNotEmpty()) {
                // 놓을 칸에 아직 안 옮긴 타일이 있으면 그 타일을 먼저 옮긴 뒤에(덮어 놓기 금지)
                val cur = positions(applied)
                fun blockedNow(m: PlanTile): Boolean {
                    val t = m.target!!
                    return cur.any { p -> kotlin.math.abs(p.cx - t.cx) < tw * 0.8 && kotlin.math.abs(p.cy - t.cy) < th * 0.8 }
                }
                val eligible = left.filter { !blockedNow(it) }
                val parkable = left.filter { it !in parked && !it.tile.fromHand }
                if (eligible.isEmpty() && parkable.isNotEmpty()) {
                    // 서로의 자리로 가야 해서(순환) 아무것도 먼저 못 옮김 → 하나를 빈 칸에 잠시 내려놓는다(타일마다 한 번만)
                    val m = parkable.minBy { badness(applied + it) }
                    fun near(a: Rect, b: Rect) = kotlin.math.abs(a.cx - b.cx) < tw * 0.8 && kotlin.math.abs(a.cy - b.cy) < th * 0.8
                    val p = parking.firstOrNull { c -> cur.none { kotlin.math.abs(it.cx - c.cx) < tw * 0.8 && kotlin.math.abs(it.cy - c.cy) < th * 0.8 } && left.none { near(it.target!!, c) } }
                    if (p != null) {
                        val leg = PlanTile(m.tile, m.screen, p, parkFor = m)
                        m.via = p
                        seq += leg; parked += m
                        continue
                    }
                }
                val m = eligible.ifEmpty { left }.minWith(compareBy<PlanTile> { badness(applied + it) }
                    .thenBy { if (lastSet != null && it in lastSet!!.tiles) 0 else 1 }
                    .thenBy { if (it.tile.fromHand) 1 else 0 })
                left.remove(m); seq += m; applied += m
                lastSet = sets.first { m in it.tiles }
            }
            seq
        }
        var n = 0
        return order.mapIndexed { k, (idx, ok) ->
            val ss = idx.sortedBy { sets[it].status.ordinal.let { o -> if (o == 1) -1 else o } }
                .map { i -> val p = sets[i]; PlanSet(++n, p.status, p.tiles, p.noRoom, k + 1) }
            Step(k + 1, ss, ok, stepMoves[k].takeIf { it.isNotEmpty() })
        }
    }

    /** 그대로 둘 판 세트를 (대신하는 숫자를 채운) SetTile 과 화면 타일 쌍으로 */
    private fun keptTiles(set: List<DetectedTile>): List<Pair<SetTile, DetectedTile>> =
        representAsSetTiles(set.map { it.kind }).zip(set)

    private fun overlap(a: List<TileKind>, b: List<TileKind>): Int {
        val m = HashMap<TileKind, Int>()
        for (k in a) m[k] = (m[k] ?: 0) + 1
        var n = 0
        for (k in b) { val c = m[k] ?: 0; if (c > 0) { n++; m[k] = c - 1 } }
        return n
    }

    private fun sameMultiset(a: List<TileKind>, b: List<TileKind>) = a.size == b.size && overlap(a, b) == a.size

    fun counts(tiles: List<DetectedTile>): Pair<Array<IntArray>, Int> {
        val a = Solver.emptyCounts(); var j = 0
        for (t in tiles) if (t.kind.isJoker) j++ else a[t.kind.color!!.ordinal][t.kind.value]++
        return a to j
    }
}
