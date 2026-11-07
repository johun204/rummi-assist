package com.johun.rummiassist

import com.johun.rummiassist.core.Advisor
import com.johun.rummiassist.core.BoardCheck

/**
 * 단계를 1단계부터 하나씩 실행해 보며 매 단계 뒤의 판이 유효한지(겹침 없음, 붙은 묶음마다 3장 이상 유효 세트) 본다.
 * 모든 손패 타일엔 목적지가 있어야 한다(자리 부족 세트는 제외). 문제가 있으면 설명 문자열.
 */
fun layoutProblem(a: Advisor.Advice): String? {
    val rec = a.recognized
    if (a.sets.isEmpty()) return null
    val inPlan = a.sets.flatMap { s -> s.tiles.mapNotNull { it.screen } }.toSet()
    val ref = (rec.board.firstOrNull() ?: rec.hand.first()).rect
    val tw = if (rec.board.isNotEmpty()) ref.width else (ref.width * 0.75).toInt()
    val th = if (rec.board.isNotEmpty()) ref.height else (ref.height * 0.75).toInt()
    val blockedSteps = a.steps.filter { it.blocked }.map { it.number }.toSet()
    // 자동 축소 배치 세트는 게임이 다시 놓으므로 위치 검사에서 뺀다(원래 판 세트째 묶여 있음)
    val relaxedTiles = a.sets.filter { it.relaxed }.flatMap { s -> s.tiles }.toSet()
    for (s in a.sets) if (s.step !in blockedSteps && !s.relaxed) for (pt in s.tiles) {
        val r = pt.target ?: if (pt.tile.fromHand) return "손패 ${pt.tile} 목적지 없음" else continue
        if (r.bottom > rec.rackTop || r.left < 0 || r.right > rec.width || r.top < 0) return "${pt.tile} 목적지가 판 밖 $r"
        // 같은 줄에서 한두 칸 옆으로만 밀기(제자리 세트 정리 아닌 단순 이동) 금지
        if (pt.target != null && !pt.tile.fromHand && pt.screen != null &&
            kotlin.math.abs(pt.screen!!.rect.cy - r.cy) < th / 2 && kotlin.math.abs(pt.screen!!.rect.cx - r.cx) < tw * 1.5 &&
            s.tiles.all { it.tile.fromHand || it.target != null }) return "${pt.tile} 세트째 옆으로 밀기"
    }
    // 이동 순서대로 하나씩 옮길 때, 놓는 칸은 그 순간 비어 있어야 한다(타일 위에 덮어 놓기 금지)
    run {
        val pos = HashMap<Any, com.johun.rummiassist.core.Rect>()
        for (t in rec.board) if (relaxedTiles.none { it.screen === t }) pos[t] = t.rect
        for (m in a.moves) {
            if (m in relaxedTiles) continue
            val t = m.target!!
            val src: Any = m.screen ?: m
            pos.entries.firstOrNull { (k, r) -> k !== src && kotlin.math.abs(r.cx - t.cx) < tw * 0.8 && kotlin.math.abs(r.cy - t.cy) < th * 0.8 }
                ?.let { return "${m.tile}${if (m.isPark) "(임시)" else ""} 놓을 때 그 칸에 아직 타일이 있음" }
            pos[src] = t
        }
    }
    // 만드는 순서: 세트는 하나씩 끝내고(동시에 만드는 세트 1개 이하), 만드는 중인 세트엔 빈틈이 없어야 한다
    run {
        val placed = HashSet<Advisor.PlanTile>()
        val setOf = a.sets.flatMap { s -> s.tiles.map { it to s } }.toMap()
        for (m in a.moves) {
            if (m.isPark) continue
            val s = setOf.getValue(m)
            val before = s.tiles.map { it.target == null || it in placed }
            val k = s.tiles.indexOf(m)
            // 새로 놓는 타일은 그 세트의 이미 있는 타일 바로 옆이어야 한다(세트의 첫 타일이면 예외) → 따로 떨어진 타일 없음
            if (before.any { it } && !((k > 0 && before[k - 1]) || (k < before.size - 1 && before[k + 1])))
                return "세트 #${s.number} 에 떨어진 자리로 놓음: ${s.tiles.mapIndexed { i, t -> if (before[i]) t.tile.toString() else if (i == k) "[${t.tile}]" else "_" }}"
            placed += m
            val building = a.sets.count { st -> st.tiles.any { it.target != null && it in placed } && st.tiles.any { it.target != null && it !in placed } }
            if (building > 1) return "동시에 만드는 세트 ${building}개"
        }
    }
    val boardValidAtStart = BoardCheck.problem(rec.board.map { BoardCheck.P(it.kind, it.rect.cx, it.rect.cy) }, tw, th) == null
    for (upto in 1..a.steps.size) {
        val step = a.steps[upto - 1]
        if (step.blocked) continue
        val ps = ArrayList<BoardCheck.P>()
        for (t in rec.board) if (t !in inPlan) ps += BoardCheck.P(t.kind, t.rect.cx, t.rect.cy)
        for (s in a.sets) if (!s.relaxed) for (pt in s.tiles) {
            val r = if (s.step <= upto && s.step !in blockedSteps) pt.target ?: pt.screen?.rect else if (pt.tile.fromHand) null else pt.screen?.rect
            r?.let { ps += BoardCheck.P(pt.tile.kind, it.cx, it.cy) }
        }
        val p = BoardCheck.problem(ps, tw, th)
        // 처음 판이 유효했다면 모든 단계 뒤에도 유효해야 한다. 마지막 단계 뒤는 항상 유효해야 한다.
        if (p != null && boardValidAtStart) return "단계 $upto/${a.steps.size} 뒤 $p"
        if (p == null != step.valid) return "단계 $upto valid 표시 불일치"
    }
    return null
}
