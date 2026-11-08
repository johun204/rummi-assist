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
    /**
     * noRoom: 놓을 자리를 못 찾음(화살표 없음), step: 속한 단계 번호,
     * relaxed: 빈 자리가 없어 게임의 자동 축소를 믿고 놓은 세트(목적지는 대략적인 위치)
     */
    class PlanSet(
        val number: Int, val status: Status, val tiles: List<PlanTile>, val noRoom: Boolean = false, val step: Int = 1,
        val relaxed: Boolean = false,
    )

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
        /** 제한 시간(이동 예산) 안에 끝나는 단계가 하나도 없음 → 무리하다 실패하면 벌칙이므로 뽑기를 권한다 */
        val overTime: Boolean = false,
    ) {
        /** 전체 이동 순서(단계 순 → 단계 안 순서). 앞에서부터 하면 단계가 끝날 때마다 판이 유효. */
        val moves: List<PlanTile> get() = steps.flatMap { it.moves }
    }

    /**
     * @param useHandJokers false 면 손패 조커는 없는 것으로 계산(판 위 조커는 규칙상 계속 써야 함)
     * 제안은 화면에 실제로 있는 판 타일 + 손패만으로 만든다. 인식이 앞뒤가 안 맞으면(같은 타일 3장 등) 아무것도 제안하지 않는다.
     */
    /**
     * @param moveBudget 한 턴에 할 수 있는 이동 수(제한 시간 / 이동 1번 시간). 최대 장수 계획의 단계를 순서대로 해서
     *   이 안에 끝나는 장수가 적으면, 덜 내는 다른 계획 중 '제한 시간 안에 실제로 내는 장수'가 가장 많은 것을 고른다.
     */
    fun advise(
        rec: Recognizer.Result, initialMeldDone: Boolean, useHandJokers: Boolean = true, moveBudget: Int = Int.MAX_VALUE,
        /** 이번 턴에 이미 손패에서 판으로 낸 타일(첫 등록 도중 다시 계산할 때 이 타일들도 등록 세트에 넣는다) */
        placedThisTurn: List<DetectedTile> = emptyList(),
    ): Advice {
        // '이번 턴에 낸 타일'은 첫 등록 중에만 의미가 있다(턴 경계를 화면만으로 확실히 알 수 없어서, 그 밖엔 쓰지 않음)
        val placed = if (initialMeldDone) emptyList() else placedThisTurn
        val a = adviseInner(rec, initialMeldDone, useHandJokers, moveBudget, placed)
        if (moveBudget == Int.MAX_VALUE || a.moves.isEmpty() || placed.isNotEmpty()) return a
        // 첫 등록은 한 번에 다 해야 하고, 그 밖엔 첫 단계라도 시간 안에 끝나야 한다
        val fits = if (!initialMeldDone) a.moves.size <= moveBudget else tilesWithin(a, moveBudget) > 0
        if (fits) return a
        return Advice(a.recognized, a.sets, a.keptSets, a.handPlayed, a.handValue,
            "제한 시간 안에 끝낼 수 있는 조합이 없어요 — 뽑기를 권해요 (${a.moves.size}번 이동 필요)", a.warnings, a.jokerHint, a.steps, overTime = true)
    }

    private fun adviseInner(
        rec: Recognizer.Result, initialMeldDone: Boolean, useHandJokers: Boolean, moveBudget: Int, placedThisTurn: List<DetectedTile>,
    ): Advice {
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
            if (sol == null || (sol.handTiles == 0 && placedThisTurn.isEmpty())) return Advice(rec, emptyList(), origSets.size, 0, 0, msg, warnings, hint)
            val a = present(rec, origSets, sol, msg, warnings, hint, initialMeldDone)
            // 화면에 없는 타일이 하나라도 들어간 제안은 보여 주지 않는다.
            if (a.sets.any { s -> s.tiles.any { it.screen == null } })
                return Advice(rec, emptyList(), origSets.size, 0, 0, "인식이 불안정해요 — 화면을 그대로 두세요", warnings)
            return a
        }

        val (hc, hjAll) = counts(handTiles)
        val hj = if (useHandJokers) hjAll else 0
        if (!initialMeldDone) {
            // 첫 등록: 손패만으로 30점 이상. 이동(=놓는 장수) 예산 안에서 가장 많이, 예산 안에 30점이 안 되면
            // 30점을 넘기는 가장 적은 장수로. 점수/장수 가중치를 바꿔 가며 후보를 만든다.
            // 이번 턴에 이미 낸 타일은 '반드시 쓰는' 타일로(판의 다른 타일은 첫 등록 전이라 못 씀)
            val (pc, pj) = counts(placedThisTurn)
            fun meldValue(sol: Solution) = sol.sets.flatten().sumOf { it.value }
            fun meld(jokers: Int): Solution? {
                val cands = ArrayList<Solution>()
                Solver.solve(pc, hc, pj, jokers, wCount = 1000, wValue = 1, jokerValue = true)?.let { cands += it }
                for (pen in longArrayOf(0, 4, 8, 11, 14, 20)) Solver.solve(pc, hc, pj, jokers, wCount = -pen, wValue = 1, jokerValue = true)?.let { cands += it }
                val ok = cands.filter { meldValue(it) >= 30 && (it.handTiles > 0 || placedThisTurn.isNotEmpty()) }
                return ok.filter { it.handTiles <= moveBudget }.maxWithOrNull(compareBy<Solution> { it.handTiles }.thenBy { it.handValue })
                    ?: ok.minWithOrNull(compareBy<Solution> { it.handTiles }.thenByDescending { it.handValue })
            }
            val sol = meld(hj)
            val hint = if (hj < hjAll) meld(hjAll)?.let { j ->
                if (sol == null) "조커를 쓰면 첫 등록 가능 (${j.handTiles}장, ${j.handValue}점)"
                else if (j.handTiles > sol.handTiles) "조커를 쓰면 ${j.handTiles - sol.handTiles}장 더 낼 수 있어요" else null
            } else null
            if (sol == null) return result(null, "첫 등록(30점) 불가 — 타일을 뽑으세요", hint)
            return result(sol, "첫 등록: 손패 ${sol.handTiles + placedThisTurn.size}장, ${meldValue(sol)}점", hint)
        }

        val (bc, bj) = counts(boardTiles)
        val sol = Solver.solve(bc, hc, bj, hj)
            ?: return result(null, "판을 유효한 세트로 해석할 수 없어요(인식 오류 가능)")
        val hint = if (hj < hjAll) Solver.solve(bc, hc, bj, hjAll)?.let { j ->
            if (j.handTiles > sol.handTiles) "조커를 쓰면 ${j.handTiles - sol.handTiles}장 더 낼 수 있어요" else null
        } else null
        if (sol.handTiles == 0) return result(null, "낼 수 있는 조합이 없어요 — 타일을 뽑으세요", hint)
        val best = result(preserve(sol, validOrig), "최대 ${sol.handTiles}장 내기 (합 ${sol.handValue})", hint)
        if (best.steps.none { it.blocked }) return withinTime(best, sol, bc, hc, bj, hj, validOrig, hint, moveBudget, ::result)
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

    /**
     * 턴 시작 때 판(start)과 지금 판(now)을 비교해 이번 턴에 새로 판에 놓인 타일을 고른다.
     * 종류별로 늘어난 장수만큼, 턴 시작 때 그 종류 타일 자리에서 가장 먼 것.
     */
    fun placedSince(start: List<DetectedTile>, now: List<DetectedTile>): List<DetectedTile> {
        val out = ArrayList<DetectedTile>()
        for ((k, list) in now.groupBy { it.kind }) {
            val extra = list.size - start.count { it.kind == k }
            if (extra <= 0) continue
            val olds = start.filter { it.kind == k }
            out += list.sortedByDescending { n -> olds.minOfOrNull { o -> kotlin.math.abs(o.rect.cx - n.rect.cx) + kotlin.math.abs(o.rect.cy - n.rect.cy) } ?: Int.MAX_VALUE }.take(extra)
        }
        return out
    }

    /**
     * 남은 시간(초) 안에 할 수 있는 단계 번호들: 이미 시작한(일부 옮긴) 단계는 끝까지 보여 주고,
     * 그 뒤 단계는 남은 이동 수 x 이동 1번 시간이 남은 시간 안에 들어가는 동안만 순서대로.
     */
    fun stepsWithin(a: Advice, done: Set<PlanTile>, seconds: Double, perMove: Double = 2.3): Set<Int> {
        val out = HashSet<Int>()
        var used = 0.0
        for (st in a.steps) {
            if (st.blocked) break
            val left = st.moves.count { it !in done }
            if (left == 0) { out += st.number; continue }
            val started = left < st.moves.size
            if (started || used + left * perMove <= seconds) { out += st.number; used += left * perMove } else break
        }
        return out
    }

    /** 단계를 순서대로 해서 이동 예산 안에 끝나는 손패 장수 */
    fun tilesWithin(a: Advice, budget: Int): Int {
        var moves = 0; var tiles = 0
        for (st in a.steps) {
            if (st.blocked) break
            moves += st.moves.size
            if (moves > budget) break
            tiles += st.handTiles
        }
        return tiles
    }

    /**
     * 제한 시간 고려: 최대 장수 계획이 시간 안에 다 안 되면, 손패를 한 장씩 빼 본 계획들 중
     * '시간 안에 내는 장수'가 가장 많은 것(같으면 이동이 적은 것)을 고른다. 몇 번 반복해 더 줄여 본다.
     */
    private fun withinTime(
        best: Advice, sol: Solution, bc: Array<IntArray>, hc: Array<IntArray>, bj: Int, hj: Int,
        validOrig: List<List<DetectedTile>>, hint: String?, budget: Int,
        result: (Solution?, String, String?) -> Advice,
    ): Advice {
        if (budget == Int.MAX_VALUE || best.moves.size <= budget) return best
        fun score(a: Advice) = tilesWithin(a, budget)
        var pick = best; var pickH = hc; var pickJ = hj
        repeat(3) {
            var improved = false
            val tries = ArrayList<Pair<Array<IntArray>, Int>>()
            if (pickJ > 0) tries += pickH to pickJ - 1
            for (c in 0..3) for (v in 1..13) if (pickH[c][v] > 0) tries += Array(4) { pickH[it].clone() }.also { it[c][v]-- } to pickJ
            for ((h2, j2) in tries) {
                val alt = Solver.solve(bc, h2, bj, j2) ?: continue
                if (alt.handTiles == 0 || alt.handTiles < score(pick)) continue
                val a2 = result(preserve(alt, validOrig), "${alt.handTiles}장 내기 (합 ${alt.handValue}) — 제한 시간 안에 끝나는 배치", hint)
                if (a2.steps.any { it.blocked }) continue
                if (compareValuesBy(a2, pick, { score(it) }, { -it.moves.size }) > 0) { pick = a2; pickH = h2; pickJ = j2; improved = true }
            }
            if (!improved || pick.moves.size <= budget) return pick
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
            PlanSet(0, status, plan, noRoom = !placed.placed[i], relaxed = placed.relaxed[i])
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
        return sets.mapIndexed { i, p -> PlanSet(p.number, p.status, tiles[i], p.noRoom, p.step, p.relaxed) }
    }

    /** 계획의 판 쪽 위치를 f 로 옮긴 같은 계획(게임이 판을 줄였을 때). 손패 타일 위치는 그대로. */
    fun remap(a: Advice, rec: Recognizer.Result, f: (Rect) -> Rect): Advice {
        val map = java.util.IdentityHashMap<PlanTile, PlanTile>()
        fun scr(d: DetectedTile?) = d?.let { if (it.inHand) it else it.copy(rect = f(it.rect), glyph = f(it.glyph)) }
        for (st in a.steps) for (s in st.sets) for (pt in s.tiles)
            map[pt] = PlanTile(pt.tile, scr(pt.screen), pt.target?.let(f)).also { it.via = pt.via?.let(f) }
        for (st in a.steps) for (m in st.moves) if (m.isPark)
            map[m] = PlanTile(m.tile, scr(m.screen), m.target?.let(f), parkFor = map[m.parkFor])
        val steps = a.steps.map { st ->
            Step(st.number, st.sets.map { s -> PlanSet(s.number, s.status, s.tiles.map { map[it]!! }, s.noRoom, s.step, s.relaxed) },
                st.valid, st.moves.map { map[it]!! })
        }
        return Advice(rec, steps.flatMap { it.sets }, a.keptSets, a.handPlayed, a.handValue, a.message, a.warnings, a.jokerHint, steps, a.overTime)
    }

    /** 세트별로 목적지·남는 타일 위치를 (dx, dy) 만큼 옮긴 같은 계획(게임이 일부 세트를 밀었을 때) */
    fun shiftSets(a: Advice, shifts: Map<PlanSet, Pair<Int, Int>>): Advice {
        val map = java.util.IdentityHashMap<PlanTile, PlanTile>()
        fun mv(r: Rect, d: Pair<Int, Int>) = Rect(r.left + d.first, r.top + d.second, r.right + d.first, r.bottom + d.second)
        for (st in a.steps) for (s in st.sets) {
            val d = shifts[s]
            for (pt in s.tiles) {
                val scr = if (d != null && pt.target == null && !pt.tile.fromHand) pt.screen?.let { it.copy(rect = mv(it.rect, d), glyph = mv(it.glyph, d)) } else pt.screen
                map[pt] = PlanTile(pt.tile, scr, pt.target?.let { t -> if (d != null) mv(t, d) else t }).also { it.via = pt.via }
            }
        }
        for (st in a.steps) for (m in st.moves) if (m.isPark) map[m] = PlanTile(m.tile, m.screen, m.target, parkFor = map[m.parkFor])
        val steps = a.steps.map { st ->
            Step(st.number, st.sets.map { s -> PlanSet(s.number, s.status, s.tiles.map { map[it]!! }, s.noRoom, s.step, s.relaxed) },
                st.valid, st.moves.map { map[it]!! })
        }
        return Advice(a.recognized, steps.flatMap { it.sets }, a.keptSets, a.handPlayed, a.handValue, a.message, a.warnings, a.jokerHint, steps, a.overTime)
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
                if (st.relaxed) continue // 게임이 다시 배치하는 세트(원래 세트째 묶여 있으므로 빼도 나머지 판엔 영향 없음)
                val r = if (pt in applied) pt.target else if (pt in parked) pt.via else if (pt.tile.fromHand) null else pt.screen?.rect
                r?.let { ps += BoardCheck.P(pt.tile.kind, it.cx, it.cy) }
            }
            return ps
        }
        fun movesOf(c: Collection<Int>) = c.flatMap { i -> sets[i].tiles.filter { it.target != null } }
        /**
         * 판의 '무효 타일 수'를 화면 좌표가 아니라 논리 위치로 센다(게임이 판을 줄여 다시 배치해도 그대로).
         * 원래 판 세트마다 한 줄(그 세트 안 순번), 판 타일이 남는 계획 세트는 그 원래 세트 줄에 이어 붙고,
         * 남는 타일이 없는 계획 세트는 자기 줄. 잠시 내려놓은 타일은 혼자. 각 줄에서 이어진 묶음이 무효면 그 장수만큼.
         */
        val origIndex = HashMap<DetectedTile, Pair<Int, Int>>()
        orig.forEachIndexed { o, set -> set.sortedBy { it.rect.cx }.forEachIndexed { i, t -> origIndex[t] = o to i } }
        val axisOf = sets.map { st ->
            val stay = st.tiles.withIndex().firstOrNull { (_, pt) -> pt.target == null && !pt.tile.fromHand && pt.screen in origIndex }
            if (stay == null) null else origIndex[stay.value.screen]!!.let { (o, i) -> o to (i - stay.index) }
        }
        val movingBoardTiles = sets.flatMap { st -> st.tiles.filter { !it.tile.fromHand && it.target != null } }
        fun logicalBadness(applied: Set<PlanTile>, parkedNow: Set<PlanTile>): Int {
            val axes = HashMap<String, MutableList<Pair<Int, TileKind>>>()
            val gone = movingBoardTiles.filter { it in applied || it in parkedNow }.mapNotNull { it.screen }.toHashSet()
            orig.forEachIndexed { o, set ->
                set.sortedBy { it.rect.cx }.forEachIndexed { i, t -> if (t !in gone) axes.getOrPut("O$o") { ArrayList() } += i to t.kind }
            }
            sets.forEachIndexed { si, st ->
                val ax = axisOf[si]
                st.tiles.forEachIndexed { k, pt ->
                    if (pt.target == null || pt !in applied) return@forEachIndexed
                    if (ax != null) axes.getOrPut("O${ax.first}") { ArrayList() } += (ax.second + k) to pt.tile.kind
                    else axes.getOrPut("S$si") { ArrayList() } += k to pt.tile.kind
                }
            }
            var bad = parkedNow.size
            for (list in axes.values) {
                val sorted = list.sortedBy { it.first }
                var run = ArrayList<TileKind>(); var prev = Int.MIN_VALUE
                fun flush() { if (run.isNotEmpty() && !Solver.isValidSet(run)) bad += run.size; run = ArrayList() }
                for ((pos, k) in sorted) { if (pos != prev + 1) flush(); run += k; prev = pos }
                flush()
            }
            return bad
        }
        // 논리 위치(자동 축소 세트 포함) + 화면 위치(겹침, 이웃 세트와 붙음 — 자동 축소 세트 제외) 둘 다 0 이어야 유효
        fun badness(applied: Set<PlanTile>) = logicalBadness(applied, parked) + BoardCheck.badness(positions(applied), tw, th)
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
        // 단계 안의 이동 순서: '세트를 하나씩 끝내기'.
        // 1) 놓을 칸이 모두 비어 있는(또는 자동 축소) 세트 중, 그 세트를 다 만든 뒤 판의 무효 타일이 가장 적은 것부터.
        // 2) 세트 안에서는 이미 있는 타일(남는 판 타일) 옆부터 차례로 이어 붙인다 → 만드는 중에도 빈틈이 없다.
        //    같은 조건이면 손패를 먼저(판의 다른 세트를 늦게 깨도록).
        // 3) 모든 세트의 자리가 서로 막혀 있으면(순환) 막고 있는 타일 하나를 빈 칸에 잠시 내려놓는다.
        val applied = HashSet<PlanTile>()
        fun near(a: Rect, x: Int, y: Int) = kotlin.math.abs(a.cx - x) < tw * 0.8 && kotlin.math.abs(a.cy - y) < th * 0.8
        val stepMoves = order.map { (idx, ok) ->
            if (!ok && idx.any { sets[it].noRoom }) return@map emptyList<PlanTile>()
            val seq = ArrayList<PlanTile>()
            val pending = idx.filter { i -> sets[i].tiles.any { it.target != null } }.toMutableList()
            var guard = 0
            while (pending.isNotEmpty() && guard++ < 200) {
                val cur = positions(applied)
                // 세트 si 의 아직 안 놓은 목적지를 막고 있는 다른 세트의 움직일 타일들
                fun blockers(si: Int): List<PlanTile> {
                    if (sets[si].relaxed) return emptyList()
                    val mine = sets[si].tiles.mapNotNull { it.screen }.toSet()
                    val out = ArrayList<PlanTile>()
                    for (m in sets[si].tiles) {
                        val t = m.target ?: continue
                        if (m in applied) continue
                        if (cur.none { near(t, it.cx, it.cy) }) continue
                        // 그 칸에 있는 타일이 같은 세트 것(곧 옮길)이면 괜찮다. 아니면 그 타일의 이동을 찾는다.
                        val occ = sets.flatMap { it.tiles }.firstOrNull { o ->
                            o !in applied && o.target != null && !o.tile.fromHand && o.screen?.rect?.let { r -> near(t, r.cx, r.cy) } == true ||
                                o in parked && o.via?.let { v -> near(t, v.cx, v.cy) } == true
                        }
                        if (occ == null || occ.screen in mine) continue
                        out += occ
                    }
                    return out
                }
                var candidates = pending.filter { blockers(it).isEmpty() }
                if (candidates.isEmpty()) {
                    // 순환: 막는 타일이 가장 적은 세트를 고르고, 막는 타일을 빈 칸에 잠시 내려놓는다
                    val si = pending.minBy { blockers(it).size }
                    var ok2 = true
                    for (b in blockers(si)) {
                        if (b in parked) { ok2 = false; break }
                        val now = positions(applied)
                        val pTarget = parking.firstOrNull { c ->
                            now.none { near(c, it.cx, it.cy) } && sets.none { st -> st.tiles.any { o -> o.target?.let { near(it, c.cx, c.cy) } == true } }
                        }
                        if (pTarget == null) { ok2 = false; break }
                        seq += PlanTile(b.tile, b.screen, pTarget, parkFor = b)
                        b.via = pTarget; parked += b
                    }
                    candidates = listOf(si)
                    if (!ok2) candidates = listOf(si) // 내려놓을 칸이 없으면 그냥 진행(드묾)
                }
                val si = candidates.minWith(
                    compareBy<Int> { i -> badness(applied + sets[i].tiles.filter { it.target != null }) }
                        .thenByDescending { i -> sets[i].tiles.count { it.tile.fromHand } }
                        .thenBy { i -> sets[i].tiles.count { it.target != null } },
                )
                // 세트 안: 이미 있는 칸(남는 타일·놓은 타일) 옆부터, 없으면 왼쪽 끝부터
                val tiles = sets[si].tiles
                val present = BooleanArray(tiles.size) { k -> tiles[k].target == null || tiles[k] in applied }
                val left = tiles.indices.filter { !present[it] }.toMutableList()
                while (left.isNotEmpty()) {
                    val adj = left.filter { k -> (k > 0 && present[k - 1]) || (k < tiles.size - 1 && present[k + 1]) }
                    // 새 세트(이미 있는 타일 없음)는 손패 타일로 시작 → 판의 멀쩡한 세트를 깨는 이동은 최대한 뒤로
                    val start = left.filter { tiles[it].tile.fromHand }.ifEmpty { left }
                    val k = (adj.ifEmpty { start }).minWith(compareBy<Int> { if (tiles[it].tile.fromHand) 0 else 1 }.thenBy { it })
                    left.remove(k); present[k] = true
                    seq += tiles[k]; applied += tiles[k]
                }
                pending.remove(si)
            }
            seq
        }
        var n = 0
        return order.mapIndexed { k, (idx, ok) ->
            val ss = idx.sortedBy { sets[it].status.ordinal.let { o -> if (o == 1) -1 else o } }
                .map { i -> val p = sets[i]; PlanSet(++n, p.status, p.tiles, p.noRoom, k + 1, p.relaxed) }
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
