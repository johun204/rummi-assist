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
        fun stepDone(step: Advisor.Step) = step.sets.all { s -> s.noRoom || s.tiles.all { it.target == null || it in done } }
    }

    /** 판+손패의 타일 구성이 같은가(타일을 뽑거나 상대가 내면 달라진다) */
    fun sameTotals(a: Recognizer.Result, b: Recognizer.Result): Boolean =
        a.tiles.groupingBy { it.kind }.eachCount() == b.tiles.groupingBy { it.kind }.eachCount()

    fun track(advice: Advisor.Advice, rec: Recognizer.Result): Progress {
        val (tw, th) = Placement.tileSize(advice.recognized)
        val used = HashSet<DetectedTile>()
        val done = HashSet<Advisor.PlanTile>()
        val moving = advice.sets.filter { !it.noRoom }.flatMap { s -> s.tiles.filter { it.target != null } }
        for (pt in moving) {
            val t = pt.target!!
            val at = rec.board.firstOrNull {
                it !in used && it.kind == pt.tile.kind && abs(it.rect.cx - t.cx) < tw * 0.45 && abs(it.rect.cy - t.cy) < th * 0.45
            }
            if (at != null) { used += at; done += pt }
        }
        val source = HashMap<Advisor.PlanTile, DetectedTile>()
        var deviated = false
        for (pt in moving) {
            if (pt in done) continue
            val orig = pt.screen ?: continue
            val cand = rec.tiles.filter { it !in used && it.kind == pt.tile.kind && it.inHand == pt.tile.fromHand }
            val pick = cand.minByOrNull { if (pt.tile.fromHand) 0.0 else hypot((it.rect.cx - orig.rect.cx).toDouble(), (it.rect.cy - orig.rect.cy).toDouble()) }
            // 판 타일이 원래 자리에서 멀리(목적지도 아닌 곳으로) 옮겨졌으면 계획을 벗어난 것
            if (pick == null || (!pt.tile.fromHand && hypot((pick.rect.cx - orig.rect.cx).toDouble(), (pick.rect.cy - orig.rect.cy).toDouble()) > tw * 0.6)) {
                deviated = true; continue
            }
            used += pick; source[pt] = pick
        }
        return Progress(done, source, deviated)
    }
}
