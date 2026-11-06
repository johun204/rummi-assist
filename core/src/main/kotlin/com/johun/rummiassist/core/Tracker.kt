package com.johun.rummiassist.core

import kotlin.math.abs
import kotlin.math.hypot

/**
 * 한 턴 동안 계획을 고정해 두고 진행 상황만 따라간다.
 * 판+손패 전체 구성이 같으면(아직 같은 턴, 타일을 옮기는 중) 같은 계획을 유지하고,
 * 목적지에 같은 타일이 놓였으면 완료로, 아니면 그 타일의 지금 위치를 찾아 화살표 출발점으로 쓴다.
 */
object Tracker {
    /** done: 목적지에 놓인 계획 타일, source: 아직 안 옮긴 타일의 지금 화면 위치, deviated: 계획과 다르게 움직임 */
    class Progress(val done: Set<Advisor.PlanTile>, val source: Map<Advisor.PlanTile, DetectedTile>, val deviated: Boolean) {
        fun stepDone(step: Advisor.Step) = step.moves.all { it in done }
    }

    /** 판+손패의 타일 구성이 같은가(타일을 뽑거나 상대가 내면 달라진다) */
    fun sameTotals(a: Recognizer.Result, b: Recognizer.Result): Boolean =
        a.tiles.groupingBy { it.kind }.eachCount() == b.tiles.groupingBy { it.kind }.eachCount()

    fun track(advice: Advisor.Advice, rec: Recognizer.Result): Progress {
        val (tw, th) = Placement.tileSize(advice.recognized)
        val used = HashSet<DetectedTile>()
        val done = HashSet<Advisor.PlanTile>()
        val moves = advice.moves // 순서대로, 잠시 내려놓기(park)도 포함
        fun at(r: Rect, k: TileKind) = rec.board.firstOrNull {
            it !in used && it.kind == k && abs(it.rect.cx - r.cx) < tw * 0.45 && abs(it.rect.cy - r.cy) < th * 0.45
        }
        // 최종 자리에 놓였으면 완료
        for (pt in moves) if (!pt.isPark) at(pt.target!!, pt.tile.kind)?.let { used += it; done += pt }
        // 잠시 내려놓기: 그 타일이 최종 자리까지 갔거나, 지금 내려놓은 칸에 있으면 완료
        val parkedNow = HashSet<Advisor.PlanTile>()
        for (pt in moves) if (pt.isPark) {
            if (pt.parkFor in done) done += pt
            else at(pt.target!!, pt.tile.kind)?.let { used += it; done += pt; parkedNow += pt.parkFor!! }
        }
        val source = HashMap<Advisor.PlanTile, DetectedTile>()
        var deviated = false
        val legOf = moves.filter { it.isPark }.associateBy { it.parkFor!! }
        for (pt in moves) {
            if (pt in done) continue
            // 아직 임시 칸에 내려놓기 전이면, 마지막 이동은 내려놓기 이동과 같은 타일을 출발점으로 쓴다
            if (pt.via != null && pt !in parkedNow && legOf[pt] != null) continue
            val from = (if (pt in parkedNow) pt.via else null) ?: pt.screen?.rect ?: continue
            val cand = rec.tiles.filter { it !in used && it.kind == pt.tile.kind && (it.inHand == pt.tile.fromHand || pt in parkedNow) }
            val pick = cand.minByOrNull { if (pt.tile.fromHand && pt !in parkedNow) 0.0 else hypot((it.rect.cx - from.cx).toDouble(), (it.rect.cy - from.cy).toDouble()) }
            // 판 타일이 원래 자리(또는 내려놓은 칸)에서 멀리 옮겨졌으면 계획을 벗어난 것
            if (pick == null || ((!pt.tile.fromHand || pt in parkedNow) && hypot((pick.rect.cx - from.cx).toDouble(), (pick.rect.cy - from.cy).toDouble()) > tw * 0.6)) {
                deviated = true; continue
            }
            used += pick; source[pt] = pick
        }
        for ((fin, leg) in legOf) if (fin !in done && fin !in parkedNow) source[leg]?.let { source[fin] = it }
        return Progress(done, source, deviated)
    }
}
