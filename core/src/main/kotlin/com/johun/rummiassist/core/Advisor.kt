package com.johun.rummiassist.core

/** 인식 결과 → 추천 수. 판에 이미 있는 세트는 가능한 한 그대로 두고, 바뀌는 세트만 알려 준다. */
object Advisor {

    enum class Status(val label: String) { NEW("새 세트"), EXTENDED("붙이기"), REARRANGED("재배치") }

    /** screen: 화면의 실제 타일(손패/판), moved: 판 타일인데 다른 세트로 옮겨야 함 */
    class PlanTile(val tile: SetTile, val screen: DetectedTile?, val moved: Boolean)
    class PlanSet(val number: Int, val status: Status, val tiles: List<PlanTile>)

    class Advice(
        val recognized: Recognizer.Result,
        val sets: List<PlanSet>,
        val keptSets: Int,
        val handPlayed: Int,
        val handValue: Int,
        val message: String,
        val warnings: List<String>,
    )

    fun advise(rec: Recognizer.Result, initialMeldDone: Boolean): Advice {
        val warnings = ArrayList<String>()
        val boardTiles = rec.board
        val handTiles = rec.hand
        val all = HashMap<TileKind, Int>()
        for (t in rec.tiles) all[t.kind] = (all[t.kind] ?: 0) + 1
        for ((k, n) in all) if (!k.isJoker && n > 2) warnings += "$k 가 ${n}장으로 인식됨(최대 2장)"
        if ((all[TileKind.JOKER] ?: 0) > 2) warnings += "조커가 3장 이상으로 인식됨"

        val origSets = rec.boardSets
        val validOrig = origSets.filter { s -> Solver.isValidSet(s.map { it.kind }) }
        for (s in origSets - validOrig.toSet()) warnings += "판 세트 인식 이상: ${s.joinToString(" ") { it.kind.toString() }}"

        fun result(sol: Solution?, msg: String): Advice {
            if (sol == null || sol.handTiles == 0) return Advice(rec, emptyList(), origSets.size, 0, 0, msg, warnings)
            return present(rec, origSets, sol, msg, warnings)
        }

        val (hc, hj) = counts(handTiles)
        if (!initialMeldDone) {
            // 첫 등록: 손패만으로 30점 이상. 먼저 장수 최대, 부족하면 점수 최대.
            val empty = Solver.emptyCounts()
            var sol = Solver.solve(empty, hc, 0, hj, wCount = 1000, wValue = 1, jokerValue = true)
            if (sol != null && sol.handValue < 30) sol = Solver.solve(empty, hc, 0, hj, wCount = 1, wValue = 1000, jokerValue = true)
            if (sol == null || sol.handValue < 30)
                return result(null, "첫 등록(30점) 불가 — 타일을 뽑으세요" + (sol?.let { " (최대 ${it.handValue}점)" } ?: ""))
            return result(sol, "첫 등록: 손패 ${sol.handTiles}장, ${sol.handValue}점")
        }

        val (bc, bj) = counts(boardTiles)
        val sol = Solver.solve(bc, hc, bj, hj)
            ?: return result(null, "판을 유효한 세트로 해석할 수 없어요(인식 오류 가능)")
        if (sol.handTiles == 0) return result(null, "낼 수 있는 조합이 없어요 — 타일을 뽑으세요")
        return result(preserve(sol, validOrig), "손패 ${sol.handTiles}장 내기 (합 ${sol.handValue})")
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
        rec: Recognizer.Result, orig: List<List<DetectedTile>>, sol: Solution, msg: String, warnings: List<String>,
    ): Advice {
        val origOf = HashMap<DetectedTile, Int>()
        orig.forEachIndexed { i, s -> s.forEach { origOf[it] = i } }
        val usedScreen = HashSet<DetectedTile>()
        val out = ArrayList<PlanSet>()
        var kept = 0
        for (set in sol.sets.sortedBy { s -> s.count { it.fromHand } == s.size }) {
            val boardKinds = set.filter { !it.fromHand }.map { it.kind }
            // 겹치는 판 타일이 가장 많은 원래 세트를 기준으로 삼는다.
            val anchor = orig.indices.maxByOrNull { i -> overlap(orig[i].filter { it !in usedScreen }.map { it.kind }, boardKinds) }
                ?.takeIf { boardKinds.isNotEmpty() && overlap(orig[it].map { t -> t.kind }, boardKinds) > 0 }
            val plan = set.map { st ->
                val cand = rec.tiles.filter { it.kind == st.kind && it.inHand == st.fromHand && it !in usedScreen }
                val pick = cand.firstOrNull { anchor != null && origOf[it] == anchor } ?: cand.firstOrNull()
                pick?.let { usedScreen += it }
                PlanTile(st, pick, !st.fromHand && (pick == null || origOf[pick] != anchor))
            }
            val anchorKinds = anchor?.let { orig[it].map { t -> t.kind } }
            val sameAsAnchor = anchorKinds != null && sameMultiset(anchorKinds, set.map { it.kind })
            if (sameAsAnchor) { kept++; continue }
            val status = when {
                boardKinds.isEmpty() -> Status.NEW
                anchorKinds != null && plan.none { it.moved } && sameMultiset(anchorKinds, boardKinds) -> Status.EXTENDED
                else -> Status.REARRANGED
            }
            out += PlanSet(0, status, plan)
        }
        // 화면 표시 순서: 붙이기 → 새 세트 → 재배치
        val ordered = out.sortedBy { it.status.ordinal.let { o -> if (o == 1) -1 else o } }
            .mapIndexed { i, p -> PlanSet(i + 1, p.status, p.tiles) }
        return Advice(rec, ordered, kept, sol.handTiles, sol.handValue, msg, warnings)
    }

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
