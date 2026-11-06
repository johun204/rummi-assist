package com.johun.rummiassist

import com.johun.rummiassist.core.DetectedTile
import com.johun.rummiassist.core.Recognizer
import com.johun.rummiassist.core.TileColor
import com.johun.rummiassist.core.TileKind
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * 첨부 캡처에서 뽑은 빈 타일 + 글자 모양으로 판/손패 타일 크기, 장수, 세트 간격(붙음/떨어짐),
 * 기기 해상도를 무작위로 바꾼 게임 화면을 만들어 인식과 세트 구분을 검증한다.
 */
class SyntheticTest {
    private class GlyphShape(val t: FloatArray, val w: Int, val h: Int)

    private val s1 = loadImg("s1.png")
    private val s4 = loadImg("s4.png")

    // s4 판의 검정 6 타일(실측 x 886..997, y 141..305)에서 글자를 지운 빈 타일
    private val blank: Img = run {
        val x0 = 886; val y0 = 141; val w = 111; val h = 164
        val px = IntArray(w * h) { s4.px[(y0 + it / w) * s4.w + x0 + it % w] }
        // 글자(923..958, 169..220)를 좌우 면색 사이 보간으로 지운다.
        val gl = 918 - x0; val gr = 963 - x0
        for (y in 165 - y0..224 - y0) {
            val a = px[y * w + gl]; val b = px[y * w + gr]
            for (x in gl + 1 until gr) {
                val k = (x - gl).toFloat() / (gr - gl)
                px[y * w + x] = mix(a, b, k)
            }
        }
        Img(px, w, h)
    }

    /** 숫자/조커별 글자 농도 지도: s4 판, s1 손패, s4 손패 순으로 처음 나온 것. */
    private val shapes: Map<String, GlyphShape> = run {
        val rec = Recognizer()
        val m = LinkedHashMap<String, GlyphShape>()
        for (img in listOf(s4, s1)) {
            val r = rec.recognize(img.px, img.w, img.h)
            for (t in r.tiles.sortedBy { if (it.inHand) 1 else 0 }) {
                val key = if (t.kind.isJoker) "J" + (if (t.kind == TileKind.JOKER && isRed(img, t)) "R" else "K") else t.kind.value.toString()
                if (key !in m) m[key] = extract(img, t)
            }
        }
        m
    }

    private fun isRed(img: Img, t: DetectedTile): Boolean {
        var red = 0
        for (y in t.glyph.top until t.glyph.bottom) for (x in t.glyph.left until t.glyph.right) {
            val c = img.px[y * img.w + x]
            if ((c shr 16 and 255) > 150 && (c shr 8 and 255) < 80) red++
        }
        return red > 20
    }

    private fun extract(img: Img, t: DetectedTile): GlyphShape {
        val g = t.glyph
        val pad = 2
        val w = g.width + 2 * pad; val h = g.height + 2 * pad
        val face = intArrayOf(205, 201, 192)
        // 잉크색: 글자 안에서 면색과 가장 먼 픽셀
        var ink = face; var far = 0
        for (y in g.top until g.bottom) for (x in g.left until g.right) {
            val c = rgb(img.px[y * img.w + x]); val d = dist2(c, face)
            if (d > far) { far = d; ink = c }
        }
        val t2 = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val c = rgb(img.px[(g.top - pad + y) * img.w + g.left - pad + x])
            var v = 0f; var n = 0f
            for (k in 0..2) { v += (c[k] - face[k]) * (ink[k] - face[k]).toFloat(); n += ((ink[k] - face[k]) * (ink[k] - face[k])).toFloat() }
            t2[y * w + x] = (v / n).coerceIn(0f, 1f)
        }
        return GlyphShape(t2, w, h)
    }

    private val INK = mapOf(
        TileColor.BLACK to intArrayOf(0, 0, 0), TileColor.RED to intArrayOf(190, 4, 7),
        TileColor.ORANGE to intArrayOf(243, 131, 49), TileColor.BLUE to intArrayOf(0, 57, 255),
    )

    /** 타일 1장을 폭 W 로 그려 장면에 붙인다. 판 타일 실측 비율: 높이 1.48W, 글자 높이 0.465W(조커 0.70W), 글자 중심 0.323H. */
    private fun drawTile(scene: Img, x0: Int, y0: Int, w: Int, kind: TileKind, redJoker: Boolean) {
        val h = (w * 1.48).roundToInt()
        val tile = resampleArgb(blank.px, blank.w, blank.h, w, h)
        val shape = shapes.getValue(if (kind.isJoker) "J" + (if (redJoker) "R" else "K") else kind.value.toString())
        val gh = ((if (kind.isJoker) 0.70 else 0.465) * w * shape.h / (shape.h - 4.0)).roundToInt() // pad 제외 높이 맞춤
        val gw = max(1, (gh * shape.w.toDouble() / shape.h).roundToInt())
        val t = resample(shape.t, shape.w, shape.h, gw, gh)
        val ink = if (kind.isJoker) INK.getValue(if (redJoker) TileColor.RED else TileColor.BLACK) else INK.getValue(kind.color!!)
        val gx = (w - gw) / 2; val gy = (0.323 * h - gh / 2.0).roundToInt()
        for (y in 0 until gh) for (x in 0 until gw) {
            val tx = gx + x; val ty = gy + y
            if (tx !in 0 until w || ty !in 0 until h) continue
            val c = tile[ty * w + tx]; val a = t[y * gw + x]
            tile[ty * w + tx] = mix(c, (0xFF shl 24) or (ink[0] shl 16) or (ink[1] shl 8) or ink[2], a)
        }
        for (y in 0 until h) for (x in 0 until w) {
            val sx = x0 + x; val sy = y0 + y
            if (sx in 0 until scene.w && sy in 0 until scene.h) scene.px[sy * scene.w + sx] = tile[y * w + x]
        }
    }

    private class Scene(
        val img: Img, val boardSets: List<List<TileKind>>, val hand: List<TileKind>, val desc: String,
        val wb: Int = 0, val f: Double = 1.0,
    )

    /** 실제 덱(색 4 x 숫자 13 x 2장 + 조커 2)에서 뽑는다. */
    private class Deck(val rnd: Random) {
        val left = HashMap<TileKind, Int>().apply {
            for (c in TileColor.entries) for (v in 1..13) this[TileKind.of(c, v)] = 2
            this[TileKind.JOKER] = 2
        }
        fun take(k: List<TileKind>): Boolean {
            val need = k.groupingBy { it }.eachCount()
            if (need.any { (t, n) -> (left[t] ?: 0) < n }) return false
            for ((t, n) in need) left[t] = left.getValue(t) - n
            return true
        }
        fun draw(): TileKind? {
            val all = left.flatMap { (t, n) -> List(n) { t } }
            if (all.isEmpty()) return null
            return all.random(rnd).also { take(listOf(it)) }
        }
    }

    private fun randomSet(rnd: Random, deck: Deck): List<TileKind>? {
        repeat(30) {
            val tiles: MutableList<TileKind> = if (rnd.nextInt(3) == 0) {
                val v = rnd.nextInt(1, 14)
                TileColor.entries.shuffled(rnd).take(rnd.nextInt(3, 5)).map { TileKind.of(it, v) }.toMutableList()
            } else {
                val len = rnd.nextInt(3, 7); val start = rnd.nextInt(1, 15 - len); val c = TileColor.entries.random(rnd)
                (start until start + len).map { TileKind.of(c, it) }.toMutableList()
            }
            if (rnd.nextInt(6) == 0) tiles[rnd.nextInt(tiles.size)] = TileKind.JOKER
            if (deck.take(tiles)) return tiles
        }
        return null
    }

    private fun makeScene(seed: Int): Scene {
        val rnd = Random(seed)
        val scene = Img(s1.px.clone(), s1.w, s1.h)
        // 판 영역을 배경색(오른쪽 버튼 앞 열의 색)으로 지우고, 랙은 나무색으로 새로 칠한다.
        for (y in 90 until 930) for (x in 300 until 1700) scene.px[y * scene.w + x] = s1.px[y * s1.w + 1695]
        val wh = rnd.nextInt(48, 121)
        val hh = (wh * 1.48).roundToInt()
        val row1 = (scene.h - hh * 1.85).roundToInt()
        val rackTop = row1 + (hh * 0.15).roundToInt()
        for (y in min(930, rackTop) until scene.h) for (x in 300 until 1700) {
            scene.px[y * scene.w + x] = if (y < rackTop || x < 330 || x > 1650) s1.px[y * s1.w + 1695] else {
                val k = (y - rackTop).toFloat() / (scene.h - rackTop)
                mix(0xFFDCAF82.toInt(), 0xFFBE9169.toInt(), k)
            }
        }
        // 판
        val wb = rnd.nextInt(30, 116)
        val hb = (wb * 1.48).roundToInt()
        val pitch = (wb * 1.045).roundToInt()
        val sets = ArrayList<List<TileKind>>()
        val deck = Deck(rnd)
        val maxBoard = rnd.nextInt(3, 90) // 판 장수(게임 진행 정도)
        var y = 100
        var redJ = rnd.nextBoolean()
        while (y + hb < rackTop - 20 && sets.size < 30) {
            var x = 320
            var placed = false
            while (sets.sumOf { it.size } < maxBoard) {
                val s = randomSet(rnd, deck) ?: break
                if (x + s.size * pitch > 1690) { for (k in s) deck.left[k] = deck.left.getValue(k) + 1; break }
                for ((i, k) in s.withIndex()) { drawTile(scene, x + i * pitch, y, wb, k, redJ); if (k.isJoker) redJ = !redJ }
                sets += s
                placed = true
                x += s.size * pitch + pitch * rnd.nextInt(1, 4) // 세트 사이 1~3칸
            }
            if (!placed) break
            y += (hb * 1.03).roundToInt() + if (rnd.nextBoolean()) 0 else pitch // 줄 사이 붙거나 한 칸
        }
        // 손패 두 줄 (아랫줄이 윗줄 아랫부분을 가림)
        val hp = (wh * 1.045).roundToInt()
        val cap = (1640 - 345) / hp
        val n = rnd.nextInt(1, 2 * cap + 1)
        val hand = List(n) { deck.draw() }.filterNotNull()
        for ((i, k) in hand.withIndex()) {
            val r = i / cap; val c = i % cap
            drawTile(scene, 345 + c * hp, row1 + r * (hh * 0.93).roundToInt(), wh, k, rnd.nextBoolean())
        }
        val f = listOf(1.0, 1.0, 0.8, 0.65).random(rnd).let { if (wb * it < 30) 1.0 else it }
        val img = if (f == 1.0) scene else scaled(scene, f)
        return Scene(img, sets, hand, "seed=$seed board W=$wb x${sets.sumOf { it.size }} hand W=$wh x$n scale=$f", wb, f)
    }

    @Test fun manySizesAndLayouts() {
        val rec = Recognizer()
        var fails = 0
        val n = (System.getenv("SYN_N") ?: "120").toInt()
        for (seed in 1..n) {
            val sc = makeScene(seed)
            val r = rec.recognize(sc.img.px, sc.img.w, sc.img.h)
            val gotSets = r.boardSets.map { s -> s.map { it.kind } }
            val gotHand = r.hand.map { it.kind.toString() }.sorted()
            val wantHand = sc.hand.map { it.toString() }.sorted()
            val ok = gotSets == sc.boardSets && gotHand == wantHand
            if (!ok) {
                fails++
                println("FAIL ${sc.desc}")
                if (gotSets != sc.boardSets) println("  board want ${sc.boardSets}\n        got  $gotSets")
                if (gotHand != wantHand) println("  hand  want-got ${wantHand - gotHand.toSet()} got-want ${gotHand - wantHand.toSet()} (${gotHand.size}/${wantHand.size})")
                System.getenv("SYN_SAVE")?.let { saveImg(sc.img, "$it/syn$seed.png") }
            }
        }
        println("synthetic: ${n - fails}/$n ok")
        assertTrue("$fails/$n synthetic scenes failed", fails == 0)
    }

    /** 다양한 크기/배치의 화면에서 제안대로 옮긴 결과가 항상 유효한 판인지. */
    @Test fun movesProduceValidBoard() {
        val rec = Recognizer()
        var bad = 0; var checked = 0; var noRoom = 0; var total = 0; var stepsN = 0; var plans = 0
        val roomLog = ArrayList<Int>()
        val n = (System.getenv("SYN_N") ?: "120").toInt()
        for (seed in 1..n) {
            val sc = makeScene(seed)
            val r = rec.recognize(sc.img.px, sc.img.w, sc.img.h)
            for (meld in listOf(true, false)) for (joker in listOf(true, false)) {
                val a = com.johun.rummiassist.core.Advisor.advise(r, meld, joker)
                if (a.sets.isEmpty()) continue
                checked++
                noRoom += a.sets.count { it.noRoom }; total += a.sets.size
                stepsN += a.steps.count { !it.sets.any { s -> s.noRoom } }; plans++
                if (a.sets.any { it.noRoom }) {
                    // 판 칸 수 대비 완성 판 타일 수(세트 사이 한 칸 포함)
                    val bw = (r.board.firstOrNull() ?: r.hand.first()).rect
                    val cols = (r.width * 0.71 / (bw.width * 1.04)).toInt()
                    val rowsN = ((r.rackTop - r.height * 0.1) / (bw.height * 1.04)).toInt()
                    val need = a.sets.sumOf { it.tiles.size + 1 } + r.board.size + r.boardSets.size
                    roomLog += (100 * need / maxOf(1, cols * rowsN))
                    if (need < cols * rowsN * 0.6) println("ROOM? seed=$seed meld=$meld need=$need cells=${cols * rowsN} sets=${a.sets.size}")
                }
                layoutProblem(a)?.let { bad++; println("LAYOUT seed=$seed meld=$meld joker=$joker: $it") }
            }
        }
        println("steps: 계획당 평균 ${"%.2f".format(stepsN.toDouble() / maxOf(1, plans))} 단계")
        println("layout: ${checked - bad}/$checked ok, 자리 없음 세트 $noRoom/$total, 그때 필요칸/전체칸(%) ${roomLog.sorted()}")
        assertTrue("$bad/$checked layouts invalid", bad == 0)
    }

    /**
     * 끝에서 끝까지: 1단계대로 실제로 타일을 옮긴 화면을 만들어 다시 인식하면
     * (1) 화면에서 읽은 판 세트가 모두 유효하고 (2) 진행 추적이 1단계를 완료로 보고 (3) 계획 이탈로 보지 않아야 한다.
     */
    @Test fun executingStepKeepsBoardValid() {
        val rec = Recognizer()
        var tried = 0; var bad = 0
        val n = (System.getenv("SYN_N") ?: "120").toInt()
        for (seed in 1..n) {
            val sc = makeScene(seed)
            if (sc.f != 1.0) continue
            val r = rec.recognize(sc.img.px, sc.img.w, sc.img.h)
            val a = com.johun.rummiassist.core.Advisor.advise(r, true, true)
            val step = a.steps.firstOrNull() ?: continue
            if (r.board.isEmpty()) continue
            if (step.blocked) continue
            if (step.sets.any { it.relaxed }) continue // 게임의 자동 축소 재배치는 그림으로 흉내 낼 수 없음
            if (com.johun.rummiassist.core.BoardCheck.problem(r.board.map { com.johun.rummiassist.core.BoardCheck.P(it.kind, it.rect.cx, it.rect.cy) },
                    r.board.first().rect.width, r.board.first().rect.height) != null) continue
            tried++
            val img = Img(sc.img.px.clone(), sc.img.w, sc.img.h)
            val moving = step.sets.flatMap { st -> st.tiles.filter { it.target != null } }
            for (pt in moving) { // 원래 자리 지우기
                val sr = pt.screen!!.rect
                val fillC = if (pt.tile.fromHand) 0xFFDCAF82.toInt() else img.px[(sr.top - 4).coerceAtLeast(0) * img.w + 1695]
                for (y in maxOf(0, sr.top - 2) until minOf(img.h, sr.bottom + 3)) for (x in maxOf(0, sr.left - 2) until minOf(img.w, sr.right + 3))
                    img.px[y * img.w + x] = if (pt.tile.fromHand) fillC else img.px[y * img.w + 1695]
            }
            for (pt in moving) {
                val t = pt.target!!
                val h = (sc.wb * 1.48).roundToInt()
                drawTile(img, t.cx - sc.wb / 2, t.cy - h / 2, sc.wb, pt.tile.kind, true)
            }
            val r2 = rec.recognize(img.px, img.w, img.h)
            val invalid = r2.boardSets.filter { !com.johun.rummiassist.core.Solver.isValidSet(it.map { t -> t.kind }) }
            val prog = com.johun.rummiassist.core.Tracker.track(a, r2)
            val same = com.johun.rummiassist.core.Tracker.sameTotals(r, r2)
            val ok = invalid.isEmpty() && moving.all { it in prog.done } && !prog.deviated && same
            if (!ok) {
                bad++
                if (!same) {
                    val c1 = r.tiles.groupingBy { it.kind }.eachCount(); val c2 = r2.tiles.groupingBy { it.kind }.eachCount()
                    println("  diff: " + (c1.keys + c2.keys).filter { c1[it] != c2[it] }.joinToString { "$it ${c1[it]}→${c2[it]}" } + " hand ${r.hand.size}→${r2.hand.size} board ${r.board.size}→${r2.board.size}")
                }
                for (m in a.moves) if (m !in prog.done && m !in prog.source) println("   lost ${m.tile} park=${m.isPark} step=${a.steps.first { m in it.moves }.number} from=${m.screen?.rect} to=${m.target} via=${m.via}")
                println("   step1 moves: " + moving.joinToString { "${it.tile}${if (it.isPark) "(임시)" else ""}" } + " | step1.moves: " + step.moves.joinToString { "${it.tile}${if (it.isPark) "(임시)" else ""}" })
                println("EXEC seed=$seed invalid=${invalid.map { s -> s.map { it.kind } }} done=${prog.done.size}/${moving.size} deviated=${prog.deviated} same=$same")
                System.getenv("SYN_SAVE")?.let { saveImg(img, "$it/exec$seed.png") }
            }
        }
        println("execute step1: ${tried - bad}/$tried ok")
        assertTrue("$bad/$tried executions failed", bad == 0)
    }

    companion object {
        fun rgb(c: Int) = intArrayOf(c shr 16 and 255, c shr 8 and 255, c and 255)
        fun dist2(a: IntArray, b: IntArray) = (0..2).sumOf { (a[it] - b[it]) * (a[it] - b[it]) }
        fun mix(a: Int, b: Int, k: Float): Int {
            val ca = rgb(a); val cb = rgb(b)
            val r = (ca[0] + (cb[0] - ca[0]) * k).roundToInt(); val g = (ca[1] + (cb[1] - ca[1]) * k).roundToInt(); val bl = (ca[2] + (cb[2] - ca[2]) * k).roundToInt()
            return (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
        }
        fun saveImg(img: Img, path: String) {
            val bi = java.awt.image.BufferedImage(img.w, img.h, java.awt.image.BufferedImage.TYPE_INT_RGB)
            bi.setRGB(0, 0, img.w, img.h, img.px, 0, img.w)
            javax.imageio.ImageIO.write(bi, "png", java.io.File(path))
        }
    }
}
