package com.johun.rummiassist.core

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

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

    /**
     * 게임이 판을 줄이거나 옮기면(자동 축소) 판 타일 위치가 한꺼번에 바뀐다. 옮기지 않은 판 타일끼리 맞춰
     * 배율 s 와 이동 (tx, ty) 를 찾고(새 위치 = s x 옛 위치 + t), 계획의 판 쪽 위치(목적지, 판 타일 출발점, 임시 칸)를
     * 새 좌표로 옮긴 계획을 돌려준다. 거의 그대로면 원래 계획, 맞는 타일이 너무 적으면 null(다시 계산).
     */
    fun follow(advice: Advisor.Advice, rec: Recognizer.Result): Advisor.Advice? {
        val old = advice.recognized.board
        val now = rec.board
        if (old.isEmpty() || now.isEmpty()) return advice
        fun med(l: List<Int>) = l.sorted()[l.size / 2].toDouble()
        val sc = med(now.map { it.rect.width }) / med(old.map { it.rect.width })
        val w = med(now.map { it.rect.width })
        val bin = w * 0.3
        val votes = HashMap<Pair<Int, Int>, MutableList<Pair<Double, Double>>>()
        for (o in old) for (n in now) if (o.kind == n.kind) {
            val tx = n.rect.cx - sc * o.rect.cx; val ty = n.rect.cy - sc * o.rect.cy
            votes.getOrPut((tx / bin).roundToInt() to (ty / bin).roundToInt()) { ArrayList() } += tx to ty
        }
        val best = votes.maxByOrNull { it.value.size }?.value ?: return null
        // 옮기지 않은 판 타일 대부분이 같은 변환으로 맞아야 믿는다
        if (best.size < maxOf(4, (minOf(old.size, now.size) * 0.4).roundToInt())) return null
        val tx = best.sumOf { it.first } / best.size; val ty = best.sumOf { it.second } / best.size
        if (abs(sc - 1) < 0.02 && abs(tx) < w * 0.15 && abs(ty) < w * 0.15) return advice
        return Advisor.remap(advice, rec) { r ->
            val cx = sc * r.cx + tx; val cy = sc * r.cy + ty
            val hw = r.width * sc / 2; val hh = r.height * sc / 2
            Rect((cx - hw).roundToInt(), (cy - hh).roundToInt(), (cx + hw).roundToInt(), (cy + hh).roundToInt())
        }
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
