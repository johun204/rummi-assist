package com.johun.rummiassist.core

/** 인식 결과 → 추천 수. 판에 이미 있는 세트는 가능한 한 그대로 두고, 바뀌는 세트만 알려 준다. */
object Advisor {

    enum class Status(val label: String) { NEW("새 세트"), EXTENDED("붙이기"), REARRANGED("재배치"), MOVED("자리 이동") }

    /**
     * screen: 화면의 실제 타일(손패/판), target: 옮겨 놓을 판 위 자리(null = 제자리, 또는 놓을 빈 곳이 없음)
     * moved: 판 타일인데 다른 자리로 옮겨야 함
     */
    class PlanTile(val tile: SetTile, val screen: DetectedTile?, val target: Rect? = null) {
        val moved get() = !tile.fromHand && target != null
    }
    /** noRoom: 판에 이 세트를 놓을 빈 자리를 못 찾음(화살표 없음) */
    class PlanSet(val number: Int, val status: Status, val tiles: List<PlanTile>, val noRoom: Boolean = false)

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
    )

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
        return result(preserve(sol, validOrig), "최대 ${sol.handTiles}장 내기 (합 ${sol.handValue})", hint)
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
        var all: List<Placement.Pending> = pending
        var placed = Placement.place(rec, all)
        if (placed.placed.any { !it } && canMoveBoard) {
            // 자리가 모자라면 그대로 둘 세트까지 포함해 판 전체를 다시 채워 본다(첫 등록 전엔 판을 못 건드리니 안 함).
            val used = pending.flatMap { p -> p.tiles.map { it.second } }.toHashSet()
            val untouched = orig.indices.filter { a -> orig[a].none { it in used } }
            val withKept = pending + untouched.map { a -> Placement.Pending(keptTiles(orig[a]), a) }
            val re = Placement.place(rec, withKept, repack = true)
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
        // 화면 표시 순서: 붙이기 → 새 세트 → 재배치 → 자리 이동
        val ordered = out.sortedBy { it.status.ordinal.let { o -> if (o == 1) -1 else o } }
            .mapIndexed { i, p -> PlanSet(i + 1, p.status, p.tiles, p.noRoom) }
        return Advice(rec, ordered, keptAnchors.size, sol.handTiles, sol.handValue, msg, warnings, hint)
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
