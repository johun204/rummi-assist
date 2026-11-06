package com.johun.rummiassist

import com.johun.rummiassist.core.Advisor
import com.johun.rummiassist.core.Solver
import com.johun.rummiassist.core.TileKind
import kotlin.math.abs

/**
 * 제안대로 옮긴 뒤의 판을 만들어 본다: 모든 손패 타일에 목적지가 있고, 자리가 겹치지 않고,
 * 같은 줄에서 붙어 있는 타일 묶음 하나하나가 유효한 세트여야 한다. 문제가 있으면 설명 문자열.
 */
fun layoutProblem(a: Advisor.Advice): String? {
    val rec = a.recognized
    if (a.sets.isEmpty()) return null
    data class P(val kind: TileKind, val cx: Int, val cy: Int)
    // 빈 자리가 없어 못 놓은 세트는 빼고 본다(그 세트의 판 타일도 판에서 빠진 것으로).
    val inPlan = a.sets.flatMap { s -> s.tiles.mapNotNull { it.screen } }.toSet()
    val fin = ArrayList<P>()
    for (t in rec.board) if (t !in inPlan) fin += P(t.kind, t.rect.cx, t.rect.cy)
    for (s in a.sets) for (pt in s.tiles) {
        if (s.noRoom) continue
        val r = pt.target ?: if (pt.tile.fromHand) return "손패 ${pt.tile} 목적지 없음" else pt.screen!!.rect
        if (r.bottom > rec.rackTop || r.left < 0 || r.right > rec.width || r.top < 0) return "${pt.tile} 목적지가 판 밖 $r"
        fin += P(pt.tile.kind, r.cx, r.cy)
    }
    val ref = (rec.board.firstOrNull() ?: rec.hand.first()).rect
    val tw = if (rec.board.isNotEmpty()) ref.width else (ref.width * 0.75).toInt()
    val th = if (rec.board.isNotEmpty()) ref.height else (ref.height * 0.75).toInt()
    for (i in fin.indices) for (j in i + 1 until fin.size)
        if (abs(fin[i].cx - fin[j].cx) < tw * 0.8 && abs(fin[i].cy - fin[j].cy) < th * 0.8) return "자리 겹침 ${fin[i]} ${fin[j]}"
    val rows = ArrayList<MutableList<P>>()
    for (p in fin.sortedBy { it.cy }) {
        val row = rows.firstOrNull { abs(it[0].cy - p.cy) < th / 2 }
        if (row != null) row += p else rows += mutableListOf(p)
    }
    for (r in rows) {
        r.sortBy { it.cx }
        var cur = mutableListOf(r[0])
        val groups = ArrayList<List<P>>()
        for ((x, y) in r.zipWithNext()) { if (y.cx - x.cx > tw * 1.5) { groups += cur; cur = mutableListOf() }; cur += y }
        groups += cur
        for (g in groups) if (!Solver.isValidSet(g.map { it.kind })) return "옮긴 뒤 무효 세트 ${g.map { it.kind }}"
    }
    return null
}
