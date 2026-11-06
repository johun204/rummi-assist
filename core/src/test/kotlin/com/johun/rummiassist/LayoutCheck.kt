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
    for (s in a.sets) if (s.step !in blockedSteps) for (pt in s.tiles) {
        val r = pt.target ?: if (pt.tile.fromHand) return "손패 ${pt.tile} 목적지 없음" else continue
        if (r.bottom > rec.rackTop || r.left < 0 || r.right > rec.width || r.top < 0) return "${pt.tile} 목적지가 판 밖 $r"
    }
    val boardValidAtStart = BoardCheck.problem(rec.board.map { BoardCheck.P(it.kind, it.rect.cx, it.rect.cy) }, tw, th) == null
    for (upto in 1..a.steps.size) {
        val step = a.steps[upto - 1]
        if (step.blocked) continue
        val ps = ArrayList<BoardCheck.P>()
        for (t in rec.board) if (t !in inPlan) ps += BoardCheck.P(t.kind, t.rect.cx, t.rect.cy)
        for (s in a.sets) for (pt in s.tiles) {
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
