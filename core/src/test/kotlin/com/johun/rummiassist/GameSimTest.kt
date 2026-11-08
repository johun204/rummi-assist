package com.johun.rummiassist

import com.johun.rummiassist.core.Advisor
import com.johun.rummiassist.core.Rect
import com.johun.rummiassist.core.Recognizer
import com.johun.rummiassist.core.Solver
import com.johun.rummiassist.core.TileColor
import com.johun.rummiassist.core.TileKind
import com.johun.rummiassist.core.Tracker
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * 가상 루미큐브 1대1. 우리 쪽 플레이어는 실제 앱과 똑같이 '화면 이미지 → 인식 → 계획(턴 동안 고정, 축소 따라가기,
 * 진행 추적)' 만 보고 ① 화살표를 그대로 수행한다. 한 턴 15초, 이동 1번에 2.3초(끌기 1.5초 + 앱이 화면 확인 0.8초).
 * 시간이 끝났을 때 판이 무효면 그 턴은 되돌리고 벌칙으로 1장 뽑는다. 상대는 솔버로 최대한 내는 AI.
 * 발견한 문제는 종류별로 세어 보고한다.  실행: SIM_GAMES=8 ./gradlew :core:test --tests '*GameSimTest*'
 */
class GameSimTest {
    private val R = SyntheticTest()
    private val recognizer = Recognizer()

    class VTile(val id: Int, val kind: TileKind, val red: Boolean)

    // ------------------------------------------------------------ 판(칸 격자) — 자리가 모자라면 격자를 늘리고 타일을 줄인다(자동 축소)
    class Board(var cols: Int = 12, var rows: Int = 5) {
        val cells = HashMap<Pair<Int, Int>, VTile>()
        fun copy() = Board(cols, rows).also { it.cells.putAll(cells) }
        /** 범위를 벗어난 칸이면 격자를 늘려(필요하면 기존 타일을 밀어) 그 칸이 들어오게 한다. 바뀐 칸 좌표를 돌려준다. */
        fun ensure(r0: Int, c0: Int): Pair<Int, Int> {
            var r = r0; var c = c0
            if (c < 0) { shift(0, -c); cols += -c; c = 0 }
            if (r < 0) { shift(-r, 0); rows += -r; r = 0 }
            if (c >= cols) cols = c + 1
            if (r >= rows) rows = r + 1
            return r to c
        }
        fun shift(dr: Int, dc: Int) {
            val old = HashMap(cells); cells.clear()
            for ((k, v) in old) cells[(k.first + dr) to (k.second + dc)] = v
        }
        /** 줄마다 이어진 타일 묶음(세트) */
        fun groups(): List<List<Pair<Pair<Int, Int>, VTile>>> {
            val out = ArrayList<List<Pair<Pair<Int, Int>, VTile>>>()
            for (r in cells.keys.map { it.first }.distinct().sorted()) {
                val row = cells.entries.filter { it.key.first == r }.sortedBy { it.key.second }
                var cur = ArrayList<Pair<Pair<Int, Int>, VTile>>(); var prev = Int.MIN_VALUE
                for (e in row) { if (e.key.second != prev + 1 && cur.isNotEmpty()) { out += cur; cur = ArrayList() }; cur += e.key to e.value; prev = e.key.second }
                if (cur.isNotEmpty()) out += cur
            }
            return out
        }
        fun valid() = groups().all { g -> Solver.isValidSet(g.map { it.second.kind }) }
        /** 빈 곳(양옆 한 칸 띄움)에 세트를 놓는다. 없으면 맨 아래에 줄을 하나 늘린다. */
        fun placeNew(tiles: List<VTile>) {
            val n = tiles.size
            for (r in 0 until rows) for (c in 0..cols - n) {
                if ((c - 1..c + n).all { (r to it) !in cells }) { tiles.forEachIndexed { k, t -> cells[r to c + k] = t }; return }
            }
            rows += 1
            if (cols < n) cols = n
            tiles.forEachIndexed { k, t -> cells[(rows - 1) to k] = t }
        }
    }

    // ------------------------------------------------------------ 화면 그리기
    private val left = 330; private val top = 100; private val right = 1680
    private class Shot(val img: Img, val boardRects: Map<VTile, Rect>, val handRects: Map<VTile, Rect>, val boardW: Int)

    private fun render(b: Board, hand: List<VTile>): Shot {
        val s1 = R.s1
        val img = Img(s1.px.clone(), s1.w, s1.h)
        val n = hand.size
        val perRow = maxOf(1, (n + 1) / 2)
        val wh = minOf(100, (1295 / (perRow * 1.045)).toInt())
        val hh = (wh * 1.48).roundToInt()
        val row1 = (img.h - hh * 1.85).roundToInt()
        val rackTop = row1 + (hh * 0.15).roundToInt()
        for (y in 90 until img.h) for (x in 300 until 1700) img.px[y * img.w + x] =
            if (y < rackTop || x < 330 || x > 1650) s1.px[minOf(y, 929) * s1.w + 1695]
            else SyntheticTest.mix(0xFFDCAF82.toInt(), 0xFFBE9169.toInt(), (y - rackTop).toFloat() / (img.h - rackTop))
        val areaH = rackTop - 30 - top
        val w = minOf(115.0, (right - left) / (b.cols * 1.045), areaH / (b.rows * 1.48 * 1.03)).toInt()
        val pitch = (w * 1.045).roundToInt(); val rowPitch = (w * 1.48 * 1.03).roundToInt()
        val br = HashMap<VTile, Rect>()
        for ((rc, t) in b.cells) {
            val x0 = left + rc.second * pitch; val y0 = top + rc.first * rowPitch
            R.drawTile(img, x0, y0, w, t.kind, t.red)
            br[t] = Rect(x0, y0, x0 + w, y0 + (w * 1.48).roundToInt())
        }
        val hr = HashMap<VTile, Rect>()
        val hp = (wh * 1.045).roundToInt()
        hand.forEachIndexed { i, t ->
            val r = i / perRow; val c = i % perRow
            val x0 = 345 + c * hp; val y0 = row1 + r * (hh * 0.93).roundToInt()
            R.drawTile(img, x0, y0, wh, t.kind, t.red)
            hr[t] = Rect(x0, y0, x0 + wh, y0 + hh)
        }
        return Shot(img, br, hr, w)
    }

    private fun cellOf(b: Board, shot: Shot, r: Rect): Pair<Int, Int> {
        val w = shot.boardW
        val pitch = w * 1.045; val rowPitch = w * 1.48 * 1.03
        return ((r.cy - (top + w * 0.74)) / rowPitch).roundToInt() to ((r.cx - (left + w / 2.0)) / pitch).roundToInt()
    }

    // ------------------------------------------------------------ 기록
    private val counts = LinkedHashMap<String, Int>()
    private val examples = LinkedHashMap<String, MutableList<String>>()
    private fun bug(kind: String, ex: String) {
        counts[kind] = (counts[kind] ?: 0) + 1
        val l = examples.getOrPut(kind) { ArrayList() }
        if (l.size < 3) l += ex
    }
    private val stat = LinkedHashMap<String, Int>()
    private fun st(k: String, n: Int = 1) { stat[k] = (stat[k] ?: 0) + n }

    // ------------------------------------------------------------ 앱 흉내(AssistService.render 와 같은 규칙)
    private inner class App {
        var plan: Advisor.Advice? = null
        var turnStart: List<com.johun.rummiassist.core.DetectedTile>? = null
        var turnTotals: Map<TileKind, Int>? = null
        fun frame(r: Recognizer.Result, meldDone: Boolean, useJoker: Boolean): Pair<Advisor.Advice, Tracker.Progress> {
            var a = plan
            var prog: Tracker.Progress? = null
            var reason: String? = null
            if (a != null && Tracker.sameTotals(a.recognized, r)) {
                val f = Tracker.follow(a, r)
                if (f == null) { a = null; reason = "축소 따라가기 실패" }
                else {
                    prog = Tracker.track(f, r)
                    if (prog.deviated) { a = null; prog = null; reason = "계획 이탈로 판정" } else a = f
                }
            } else a = null
            if (a == null) {
                if (reason != null) st("턴 도중 재계획($reason)")
                val totals = r.tiles.groupingBy { it.kind }.eachCount()
                if (totals != turnTotals) { turnTotals = totals; turnStart = r.board }
                val placed = turnStart?.let { Advisor.placedSince(it, r.board) } ?: emptyList()
                a = Advisor.advise(r, meldDone, useJoker, moveBudget = (System.getenv("SIM_BUDGET") ?: "6").toInt(), placedThisTurn = placed)
                prog = Tracker.track(a, r)
            }
            plan = a
            return a to prog!!
        }
    }

    // ------------------------------------------------------------ 게임 진행
    @Test fun play() {
        val games = (System.getenv("SIM_GAMES") ?: return).toInt()
        val outDir = System.getenv("SIM_SAVE")
        for (g in 1..games) playGame(g, Random(1000 + g), outDir)
        println("\n===== 가상 대전 결과 ($games 판, 턴 제한 15초) =====")
        for ((k, v) in stat) println("  $k: $v")
        println("----- 발견한 문제 -----")
        if (counts.isEmpty()) println("  없음")
        for ((k, v) in counts) {
            println("  [$v 회] $k")
            for (e in examples[k]!!) println("       예) $e")
        }
    }

    private fun playGame(g: Int, rnd: Random, outDir: String?) {
        val deck = ArrayList<VTile>()
        var id = 0
        for (c in TileColor.entries) for (v in 1..13) repeat(2) { deck += VTile(id++, TileKind.of(c, v), false) }
        deck += VTile(id++, TileKind.JOKER, true); deck += VTile(id++, TileKind.JOKER, false)
        deck.shuffle(rnd)
        val me = ArrayList(deck.take(14)); val opp = ArrayList(deck.drop(14).take(14))
        val pool = ArrayList(deck.drop(28))
        var board = Board()
        var meMelded = false; var oppMelded = false
        val app = App()
        var passes = 0
        for (turn in 1..80) {
            // ---- 내 차례
            run {
                val all = board.cells.values.toList() + me + opp + pool
                if (all.size != all.toSet().size || all.size != 106) println("SIMBUG g$g t$turn 타일 수 ${all.size}, 중복 ${all.size - all.toSet().size}")
            }
            val before = board.copy(); val handBefore = ArrayList(me)
            var time = 0.0
            var playedFromHand = 0
            var outcome = "진행"
            var frames = 0
            while (true) {
                if (frames++ > 60) { bug("한 턴에 화면 확인 60번 초과(멈춤)", "game $g turn $turn"); outcome = "중단"; break }
                val shot = render(board, me)
                val r = recognizer.recognize(shot.img.px, shot.img.w, shot.img.h)
                // 인식 확인
                val want = (board.cells.values.map { it.kind.toString() } + me.map { it.kind.toString() }).sorted()
                val got = r.tiles.map { it.kind.toString() }.sorted()
                if (want != got) {
                    bug("인식 오류", "game $g turn $turn: 빠짐 ${want - got.toSet()} 더 읽음 ${got - want.toSet()} (판 타일폭 ${shot.boardW}px, 손패 ${me.size}장)")
                    outDir?.let { SyntheticTest.saveImg(shot.img, "$it/recog_g${g}_t$turn.png") }
                }
                val (a, prog) = try { app.frame(r, meMelded, false) } catch (e: Throwable) {
                    bug("예외: ${e.javaClass.simpleName}", "game $g turn $turn: ${e.message}"); outcome = "중단"; break
                }
                if (frames == 1) {
                    st("내 턴")
                    if (a.moves.isEmpty()) { st("낼 게 없어 뽑음"); outcome = "뽑기"; break }
                    if (a.overTime) { st("시간 안에 못 끝내 뽑기 권고"); outcome = "뽑기"; break }
                    st("계획한 이동 수 합", a.moves.size); st("계획한 손패 수 합", a.handPlayed)
                    if (a.moves.size * 2.3 > 15) st("15초 안에 다 못 하는 계획")
                    if (a.steps.firstOrNull()?.let { it.moves.size * 2.3 > 15 } == true) st("1단계부터 15초 초과")
                }
                val dbg = System.getenv("SIM_DEBUG")?.split(':')?.let { it[0].toInt() == g && it[1].toInt() == turn } == true
                if (dbg) {
                    println("--- g$g t$turn frame$frames meld=$meMelded time=$time board ${board.cols}x${board.rows} w=${shot.boardW}")
                    for (rr in 0 until board.rows) println("    " + (0 until board.cols).joinToString(" ") { c -> board.cells[rr to c]?.kind?.toString()?.padEnd(3) ?: " . " })
                    println("    hand: " + me.joinToString(" ") { it.kind.toString() })
                    println("    plan: ${a.message} steps=${a.steps.map { "${it.number}:${it.moves.size}" }}")
                    for (st in a.sets) println("      set#${st.number}${if (st.relaxed) "(축소)" else ""}: " + st.tiles.joinToString(" ") { t -> "${t.tile}@" + ((t.target ?: t.screen?.rect)?.let { cellOf(board, shot, it) }) })
                    for (m in a.moves) println("      ${if (m in prog.done) "✓" else " "} ${m.tile}${if (m.isPark) "(임시)" else ""} from ${(prog.source[m] ?: m.screen)?.rect?.let { cellOf(board, shot, it) }} to ${cellOf(board, shot, m.target!!)} set#${a.sets.firstOrNull { s -> m in s.tiles || m.parkFor in s.tiles }?.number}")
                }
                val left = a.moves.filter { it !in prog.done }
                if (left.isEmpty()) { outcome = "완료"; break }
                val next = left.first()
                // 단계 시작인데 남은 시간에 그 단계를 못 끝내면, 이미 낸 게 있으면 여기서 턴을 끝낸다
                val step = a.steps.first { next in it.moves }
                val stepLeft = step.moves.count { it !in prog.done }
                if (stepLeft == step.moves.size && playedFromHand > 0 && time + stepLeft * 2.3 > 15) { outcome = "시간 맞춰 멈춤"; break }
                // 출발 타일 찾기
                val src = (prog.source[next] ?: next.screen)?.rect
                if (src == null) { bug("출발점 없음", "game $g turn $turn ${next.tile}"); outcome = "중단"; break }
                val pool2 = if (next.tile.fromHand && !next.isPark) shot.handRects else shot.boardRects + shot.handRects
                val vt = pool2.entries.filter { it.key.kind == next.tile.kind }.minByOrNull { abs(it.value.cx - src.cx) + abs(it.value.cy - src.cy) }?.key
                if (vt == null || pool2[vt]!!.let { abs(it.cx - src.cx) > shot.boardW || abs(it.cy - src.cy) > shot.boardW * 1.5 }) {
                    bug("안내한 출발 타일이 그 자리에 없음", "game $g turn $turn: ${next.tile} @${src.cx},${src.cy}")
                    outcome = "중단"; break
                }
                if (dbg) println("    >> move ${next.tile} src=${src.cx},${src.cy} vt@${board.cells.entries.firstOrNull { it.value === vt }?.key ?: "hand"} rect=${pool2[vt]?.let { "${it.cx},${it.cy}" }}")
                var (tr, tc) = cellOf(board, shot, next.target!!)
                val inside = tr in 0 until board.rows && tc in 0 until board.cols
                if (!inside) st("판 밖 목적지(자동 축소)")
                val occ = board.cells[tr to tc]
                if (occ != null && occ !== vt) {
                    val relaxed = a.sets.firstOrNull { next in it.tiles || next.parkFor in it.tiles }?.relaxed == true
                    bug(if (relaxed) "자동 축소 배치가 다른 타일 칸을 가리킴" else "이미 타일이 있는 칸으로 안내(덮어쓰기)",
                        "game $g turn $turn: ${next.tile} → (${tr},${tc}) 에 ${occ.kind}")
                    // 게임 흉내: 그 줄의 그 칸부터 오른쪽으로 한 칸씩 밀어 자리를 만든다
                    val rowTiles = board.cells.entries.filter { it.key.first == tr && it.key.second >= tc }.sortedByDescending { it.key.second }
                    for (e in rowTiles) { board.cells.remove(e.key); board.cells[tr to e.key.second + 1] = e.value }
                    board.ensure(tr, rowTiles.maxOf { it.key.second } + 1)
                }
                // 수행
                if (next.tile.fromHand && vt in me) { me.remove(vt); if (!next.isPark) playedFromHand++ }
                else board.cells.entries.firstOrNull { it.value === vt }?.let { board.cells.remove(it.key) }
                    ?: run { me.remove(vt) }
                var cell = board.ensure(tr, tc)
                val nextSet = a.sets.firstOrNull { next in it.tiles }
                if (nextSet?.relaxed == true) {
                    // 게임의 자동 정리 흉내(사용자 확인): 세트 끝에 붙였을 때 옆의 다른 세트와 닿으면 그 세트를 한 칸 밀어 띄운다
                    val k = nextSet.tiles.indexOf(next)
                    // 격자가 왼쪽/위로 늘어나 좌표가 밀렸으면 같은 만큼 보정
                    val dr = cell.first - tr; val dc = cell.second - tc
                    fun slotCell(i: Int) = nextSet.tiles.getOrNull(i)?.let { t -> (t.target ?: t.screen?.rect)?.let { cellOf(board, shot, it) } }?.let { (it.first + dr) to (it.second + dc) }
                    for (dir in intArrayOf(-1, 1)) {
                        val nb = cell.first to cell.second + dir
                        if (board.cells[nb] == null || board.cells[nb] === vt) continue
                        if (slotCell(k + dir) == nb) continue // 같은 세트
                        val block = board.cells.entries.filter { it.key.first == cell.first && (if (dir < 0) it.key.second <= nb.second else it.key.second >= nb.second) }
                        val moved = block.map { it.key to it.value }
                        moved.forEach { board.cells.remove(it.first) }
                        moved.forEach { (kk, v) -> board.cells[kk.first to kk.second + dir] = v }
                        st("자동 정리로 옆 세트 밀어냄")
                        val (r2, c2) = board.ensure(cell.first, (moved.minOfOrNull { it.first.second } ?: 0) + dir)
                        if (c2 != (moved.minOfOrNull { it.first.second } ?: 0) + dir) cell = cell.first to cell.second + (c2 - ((moved.minOfOrNull { it.first.second } ?: 0) + dir))
                        board.ensure(r2, (moved.maxOfOrNull { it.first.second } ?: 0) + dir)
                    }
                }
                if (board.cells[cell] != null && board.cells[cell] !== vt) println("SIMBUG2 g$g t$turn 덮어씀 ${board.cells[cell]!!.kind} by ${vt.kind} at $cell")
                board.cells[cell] = vt
                run { val all = board.cells.values.toList() + me; if (all.size != all.toSet().size) println("SIMBUG3 g$g t$turn after move ${next.tile} dup") }
                time += 2.3
                st("수행한 이동")
                if (time > 15.0) { outcome = "시간 초과"; break }
            }
            // 턴 판정
            val gotTiles = handBefore.size - me.size
            val ok = gotTiles > 0 && board.valid() && (meMelded || meldOk(before, board))
            when {
                outcome == "뽑기" -> { pool.removeLastOrNull()?.let { me += it }; passes++ }
                ok -> {
                    st("성공한 턴"); st("낸 손패 수 합", gotTiles); meMelded = true; passes = 0
                    if (outcome == "시간 초과") st("시간 끝났지만 판은 유효")
                }
                else -> {
                    val why = when {
                        gotTiles == 0 -> "손패를 하나도 못 냄"
                        !board.valid() -> "판 무효(${board.groups().filter { !Solver.isValidSet(it.map { p -> p.second.kind }) }.take(3).map { gr -> gr.map { it.second.kind } }})"
                        else -> "첫 등록 규칙 위반(30점 미만이거나 판 타일 사용)"
                    }
                    bug("턴 실패 → 되돌리고 벌칙: $outcome / $why", "game $g turn $turn")
                    if (outDir != null) SyntheticTest.saveImg(render(board, me).img, "$outDir/fail_g${g}_t$turn.png")
                    board = before; me.clear(); me += handBefore
                    pool.removeLastOrNull()?.let { me += it }
                    st("실패한 턴")
                }
            }
            app.plan = null
            if (me.isEmpty()) { st("내가 이긴 판"); return }
            // ---- 상대 차례
            run { val all = board.cells.values.toList() + me + opp + pool; if (all.size != all.toSet().size) println("SIMBUG4 g$g t$turn before opp dup") }
            if (!opponentTurn(board, opp, oppMelded).also { if (it) oppMelded = true }) { pool.removeLastOrNull()?.let { opp += it }; passes++ } else passes = 0
            run { val all = board.cells.values.toList() + me + opp + pool; if (all.size != all.toSet().size) println("SIMBUG5 g$g t$turn after opp dup: " + all.groupBy { it.id }.filter { it.value.size > 1 }.map { it.value[0].kind }) }
            if (opp.isEmpty()) { st("상대가 이긴 판"); return }
            if (pool.isEmpty() && passes >= 4) { st("무승부(더미 소진)"); return }
        }
        st("80턴 제한")
    }

    /** 첫 등록: 판의 원래 타일은 그대로, 새로 낸 타일만으로 이룬 세트 합이 30 이상 */
    private fun meldOk(before: Board, after: Board): Boolean {
        for ((k, v) in before.cells) if (after.cells[k] !== v) return false
        val old = before.cells.values.toSet()
        var sum = 0
        for (g in after.groups()) {
            val tiles = g.map { it.second }
            if (tiles.none { it !in old }) continue
            if (tiles.any { it in old }) return false
            sum += setValue(tiles.map { it.kind })
        }
        return sum >= 30
    }

    private fun setValue(k: List<TileKind>): Int {
        val real = k.filter { !it.isJoker }
        if (real.isEmpty()) return 0
        if (real.all { it.value == real[0].value } && k.size <= 4 && real.map { it.color }.toSet().size == real.size) return real[0].value * k.size
        val i0 = k.indexOfFirst { !it.isJoker }
        val start = k[i0].value - i0
        return k.indices.sumOf { start + it }
    }

    /** 상대: 솔버로 최대한 낸다. 바뀌지 않은 세트는 제자리, 바뀐 세트·새 세트는 빈 곳에. */
    private fun opponentTurn(board: Board, hand: MutableList<VTile>, melded: Boolean): Boolean {
        fun counts(ts: Collection<VTile>): Pair<Array<IntArray>, Int> {
            val a = Solver.emptyCounts(); var j = 0
            for (t in ts) if (t.kind.isJoker) j++ else a[t.kind.color!!.ordinal][t.kind.value]++
            return a to j
        }
        val (hc, hj) = counts(hand)
        val sol = if (!melded) Solver.solve(Solver.emptyCounts(), hc, 0, hj, wCount = 1, wValue = 1000, jokerValue = true)?.takeIf { it.handValue >= 30 }
        else { val (bc, bj) = counts(board.cells.values); Solver.solve(bc, hc, bj, hj) }
        if (sol == null || sol.handTiles == 0) return false
        // 손패/판 타일 실물 배정
        val handLeft = ArrayList(hand)
        val groups = board.groups()
        val kept = HashSet<List<Pair<Pair<Int, Int>, VTile>>>()
        val newSets = ArrayList<List<VTile>>()
        val boardLeft = ArrayList(board.cells.values)
        // 먼저 그대로 둘 세트를 모두 정하고(그 타일은 다른 세트에 쓰지 않게), 그다음 새 세트에 실물 배정
        val rest = ArrayList<List<com.johun.rummiassist.core.SetTile>>()
        for (set in sol.sets) {
            val kinds = set.map { it.kind }
            val same = groups.firstOrNull { gr -> gr !in kept && gr.map { it.second.kind }.sortedBy { it.toString() } == kinds.sortedBy { it.toString() } && set.none { it.fromHand } }
            if (same != null) { kept += same; same.forEach { boardLeft.remove(it.second) } } else rest += set
        }
        for (set in rest) {
            newSets += set.map { st ->
                val pool = if (st.fromHand) handLeft else boardLeft
                val t = pool.first { it.kind == st.kind }
                pool.remove(t); t
            }
        }
        // 바뀐 세트는 판에서 빼고 새로 놓는다(첫 등록이면 판은 건드리지 않음)
        val keepTiles = kept.flatMap { g -> g.map { it.second } }.toSet()
        if (melded) board.cells.entries.removeIf { it.value !in keepTiles }
        for (s in newSets) board.placeNew(s)
        hand.clear(); hand += handLeft
        return true
    }
}
