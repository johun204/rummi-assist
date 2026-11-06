package com.johun.rummiassist.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 화면 캡처(ARGB 픽셀)에서 타일을 찾는다. 타일 수에 따라 크기가 바뀌므로 절대 크기를 쓰지 않는다.
 *
 * 1. 타일 면(밝고 채도 낮은 픽셀) 마스크를 만든다.
 * 2. 밝지 않은 픽셀의 연결 요소 중 화면 테두리에 닿지 않고 작고 진한 것 = 글자 조각.
 * 3. 다른 조각의 bbox 안에 들어간 조각(조커 눈/입)은 합치고, 같은 색으로 나란한 조각("1"+"3")을 한 글자로 묶는다.
 * 4. 글자를 "타일 면색 ↔ 잉크색" 사이 비율(안티앨리어싱까지 반영한 잉크 농도)로 바꿔
 *    크기와 무관한 격자 특징을 만들고 템플릿과 비교해 숫자를 정한다.
 * 5. 나무 받침대(랙) 위면 손패, 아니면 게임판. 판끼리, 손패끼리는 타일 크기가 모두 같다는 점을 이용해
 *    크기가 튀는 잡티를 버리고 타일 사각형 크기를 통일한다.
 * 6. 판에서 같은 줄 타일 사이에 타일 면이 끊긴 구간(배경)이 타일 폭의 35% 넘게 있으면 다른 세트.
 */
class Recognizer(private val templates: List<Template> = Templates.all) {

    class Glyph(
        val rect: Rect,
        val color: TileColor,
        /** 잉크 평균색의 가장 큰 채널. 유색 글자는 선명(>=165)해야 한다. */
        val vivid: Int,
        val grid: FloatArray,
        val aspect: Float,
    )

    data class Template(val label: String, val aspect: Float, val grid: FloatArray)

    class Result(
        val tiles: List<DetectedTile>,
        /** 판 위 세트(붙어 있는 타일끼리), 위→아래, 왼→오 순서 */
        val boardSets: List<List<DetectedTile>>,
        val rackTop: Int, val width: Int, val height: Int,
    ) {
        val board get() = tiles.filter { !it.inHand }
        val hand get() = tiles.filter { it.inHand }
    }

    private class Frame(val px: IntArray, val w: Int, val h: Int) {
        val light = BooleanArray(w * h) { isLight(px[it]) }
        fun lit(x: Int, y: Int) = x in 0 until w && y in 0 until h && light[y * w + x]
    }

    fun recognize(px: IntArray, w: Int, h: Int): Result {
        val f = Frame(px, w, h)
        val rackTop = findRackTop(px, w, h)
        data class Cand(val g: Glyph, val kind: TileKind, val dist: Double)
        val cands = ArrayList<Cand>()
        for (g in findGlyphs(f)) {
            val (label, dist) = classify(g) ?: continue
            // 작은 글자는 격자 오차가 커서 허용치를 조금 넓힌다.
            if (dist > MAX_DIST + max(0, 28 - g.rect.height) * 0.004) continue
            cands += Cand(g, if (label == "J") TileKind.JOKER else TileKind.of(g.color, label.toInt()), dist)
        }
        val tiles = ArrayList<DetectedTile>()
        for (inHand in listOf(false, true)) {
            var region = cands.filter { (it.g.rect.bottom > rackTop) == inHand }
            // 같은 구역 타일은 크기가 같다: 숫자 글자 높이 중앙값에서 벗어난 것은 잡티.
            val digitH = median(region.filter { !it.kind.isJoker }.map { it.g.rect.height })
            if (digitH > 0) region = region.filter {
                val r = it.g.rect.height.toDouble() / digitH
                if (it.kind.isJoker) r in 1.25..1.85 else r in 0.8..1.25
            }
            if (region.isEmpty()) continue
            val glyphH = if (digitH > 0) digitH.toDouble() else region[0].g.rect.height / JOKER_H
            // 타일 폭: 글자 양옆 타일 면(밝은 부분) 끝까지 잰 값의 중앙값. 실제 타일은 테두리 음영을 더해
            // 면 폭의 1.08배, 높이는 면 폭의 1.59배(첨부 캡처 실측).
            val faceW = median(region.map { faceWidth(f, it.g.rect, glyphH) }).coerceAtLeast(1)
            val tileW = (faceW * 1.08).roundToInt()
            val tileH = (faceW * TILE_RATIO).roundToInt()
            // 글자 윗변→타일 윗변 거리도 구역 안에서 같다. 랙 아랫줄은 윗줄 타일에 가려 길게 재지므로 최솟값.
            val topGap = region.filter { !it.kind.isJoker }.ifEmpty { region }
                .minOf { it.g.rect.top - faceTop(f, it.g.rect, glyphH) }
            for (c in region) {
                val digitTop = if (c.kind.isJoker) c.g.rect.cy - (glyphH / 2).roundToInt() else c.g.rect.top
                val top = digitTop - topGap
                val rect = Rect(c.g.rect.cx - tileW / 2, top, c.g.rect.cx - tileW / 2 + tileW, min(h, top + tileH))
                tiles += DetectedTile(c.kind, rect, c.g.rect, inHand, c.dist)
            }
        }
        val clipped = clipBelow(tiles)
        return Result(clipped, groupBoard(f, clipped.filter { !it.inHand }), rackTop, w, h)
    }

    fun classify(g: Glyph): Pair<String, Double>? {
        var best: String? = null
        var bestD = Double.MAX_VALUE
        for (t in templates) {
            val ar = g.aspect / t.aspect
            if (ar < 0.6f || ar > 1.6f) continue
            var d = 0.0
            for (i in t.grid.indices) {
                val e = g.grid[i] - t.grid[i]
                d += e * e
            }
            d = d / t.grid.size + 0.15 * (ar - 1) * (ar - 1)
            if (d < bestD) { bestD = d; best = t.label }
        }
        return best?.let { it to bestD }
    }

    // ---------------------------------------------------------------- 판 세트 묶기

    /**
     * 같은 줄(글자 세로 중심이 비슷)에서 x 순으로 보며, 이웃 타일 글자 사이를 지나는 가로줄에서
     * 타일 면이 아닌 픽셀이 연속으로 타일 폭의 35% 넘게 이어지면 떨어진 것(다른 세트)으로 본다.
     * 붙어 있는 타일 사이 이음매는 타일 폭의 약 10%.
     */
    private fun groupBoard(f: Frame, board: List<DetectedTile>): List<List<DetectedTile>> {
        if (board.isEmpty()) return emptyList()
        val tileW = (board[0].rect.width / 1.08).roundToInt() // 면 폭 기준
        val rows = ArrayList<MutableList<DetectedTile>>()
        for (t in board.sortedBy { it.rect.cy }) {
            val row = rows.firstOrNull { r -> abs(r[0].rect.cy - t.rect.cy) < r[0].rect.height / 3 }
            if (row != null) row += t else rows += mutableListOf(t)
        }
        val sets = ArrayList<List<DetectedTile>>()
        for (r in rows) {
            r.sortBy { it.rect.cx }
            var cur = mutableListOf(r[0])
            for ((a, b) in r.zipWithNext()) {
                if (!touching(f, a, b, tileW)) { sets += cur; cur = mutableListOf() }
                cur += b
            }
            sets += cur
        }
        return sets
    }

    private fun touching(f: Frame, a: DetectedTile, b: DetectedTile, tileW: Int): Boolean {
        if (b.rect.cx - a.rect.cx > tileW * 1.6) return false
        // 글자 아래(타일 면, 워터마크 위) 두 줄을 본다. 둘 중 하나라도 끊김이 짧으면 붙어 있음.
        var best = Int.MAX_VALUE
        for (fy in doubleArrayOf(0.5, 0.62)) {
            val y = (a.rect.top + a.rect.height * fy).roundToInt()
            var run = 0; var longest = 0
            for (x in a.glyph.right until b.glyph.left) {
                if (f.lit(x, y)) run = 0 else { run++; longest = max(longest, run) }
            }
            best = min(best, longest)
        }
        return best <= tileW * 0.35
    }

    // ---------------------------------------------------------------- 글자 찾기

    fun findGlyphs(px: IntArray, w: Int, h: Int): List<Glyph> = findGlyphs(Frame(px, w, h))

    private fun findGlyphs(f: Frame): List<Glyph> {
        val w = f.w; val h = f.h; val px = f.px; val light = f.light
        val label = IntArray(w * h) // 0 = 미방문, >0 = 조각 번호
        val stack = IntArray(w * h)
        val comps = ArrayList<Comp>()
        val maxH = h / 5
        for (start in 0 until w * h) {
            if (light[start] || label[start] != 0) continue
            val id = comps.size + 1
            var sp = 0
            stack[sp++] = start
            label[start] = id
            val c = Comp(id)
            while (sp > 0) {
                val p = stack[--sp]
                val x = p % w
                val y = p / w
                c.add(x, y, px[p])
                if (x == 0 || y == 0 || x == w - 1 || y == h - 1) c.touchesBorder = true
                if (x > 0) { val q = p - 1; if (!light[q] && label[q] == 0) { label[q] = id; stack[sp++] = q } }
                if (x < w - 1) { val q = p + 1; if (!light[q] && label[q] == 0) { label[q] = id; stack[sp++] = q } }
                if (y > 0) { val q = p - w; if (!light[q] && label[q] == 0) { label[q] = id; stack[sp++] = q } }
                if (y < h - 1) { val q = p + w; if (!light[q] && label[q] == 0) { label[q] = id; stack[sp++] = q } }
            }
            comps += c
        }
        val parts = comps.filter {
            !it.touchesBorder && it.h >= 4 && it.h <= maxH && it.w <= maxH &&
                it.area >= 6 && it.strong >= it.area * 0.2
        }

        // 다른 조각 bbox 안에 완전히 들어간 조각은 그 조각에 합친다(조커 얼굴의 눈·코·입).
        val sorted = parts.sortedByDescending { it.w * it.h }
        val groups = ArrayList<MutableList<Comp>>()
        for (c in sorted) {
            val host = groups.firstOrNull { g ->
                val o = g[0]
                c.x0 >= o.x0 && c.x1 <= o.x1 && c.y0 >= o.y0 && c.y1 <= o.y1
            }
            if (host != null) host += c else groups += mutableListOf(c)
        }

        // 같은 색, 같은 높이로 나란히 붙은 조각("1","3")은 한 글자.
        val units = groups.map { Blob(it) }.sortedBy { it.x0 }.toMutableList()
        var merged = true
        while (merged) {
            merged = false
            loop@ for (i in units.indices) for (j in units.indices) {
                if (i == j) continue
                val a = units[i]; val b = units[j]
                if (a.x0 > b.x0) continue
                if (a.color != b.color) continue
                val hh = max(a.h, b.h)
                val ov = min(a.y1, b.y1) - max(a.y0, b.y0) + 1
                if (ov < 0.7 * min(a.h, b.h)) continue
                if (min(a.h, b.h) < 0.75 * hh) continue
                val gap = b.x0 - a.x1 - 1
                if (gap > 0.3 * hh || gap < -0.2 * hh) continue
                if (max(a.x1, b.x1) - a.x0 + 1 > 1.8 * hh) continue
                a.absorb(b)
                units.removeAt(j)
                merged = true
                break@loop
            }
        }

        val out = ArrayList<Glyph>()
        for (u in units) {
            if (u.h < MIN_GLYPH_H) continue
            if (u.color != TileColor.BLACK && u.vivid < 165) continue
            val face = faceColor(f, u) ?: continue
            out += feature(f, u, face) ?: continue
        }
        return out
    }

    /** 글자 둘레가 타일 면이면 그 평균색, 아니면 null(타일 위 글자가 아님). */
    private fun faceColor(f: Frame, u: Blob): IntArray? {
        val m = max(2, (u.h * 0.1).roundToInt()) // 조커 그림은 타일을 거의 꽉 채워 여백이 좁다
        val x0 = u.x0 - m; val x1 = u.x1 + m; val y0 = u.y0 - m; val y1 = u.y1 + m
        if (x0 < 0 || y0 < 0 || x1 >= f.w || y1 >= f.h) return null
        var tot = 0; var lit = 0
        var r = 0L; var g = 0L; var b = 0L
        for (y in y0..y1) for (x in x0..x1) {
            if (x in u.x0..u.x1 && y in u.y0..u.y1) continue
            tot++
            val p = y * f.w + x
            if (f.light[p]) {
                lit++
                val c = f.px[p]
                r += (c shr 16) and 255; g += (c shr 8) and 255; b += c and 255
            }
        }
        if (lit < tot * 0.8) return null
        return intArrayOf((r / lit).toInt(), (g / lit).toInt(), (b / lit).toInt())
    }

    /**
     * 픽셀마다 잉크 농도 t = (픽셀-면색)을 (잉크색-면색) 방향으로 투영한 비율(0..1).
     * 농도 0.5 이상 픽셀로 글자 경계를 다시 잡고, 칸마다 4x4 점을 쌍선형 보간해 평균 낸다
     * → 글자 크기(10px~100px+)와 무관한 특징.
     */
    private fun feature(f: Frame, u: Blob, face: IntArray): Glyph? {
        val ink = if (u.color == TileColor.BLACK) intArrayOf(0, 0, 0) else intArrayOf(u.ir, u.ig, u.ib)
        val dr = (ink[0] - face[0]).toFloat(); val dg = (ink[1] - face[1]).toFloat(); val db = (ink[2] - face[2]).toFloat()
        val n2 = dr * dr + dg * dg + db * db
        if (n2 < 1f) return null
        val pad = 1
        val bx0 = u.x0 - pad; val by0 = u.y0 - pad
        val bw = u.x1 - u.x0 + 1 + 2 * pad; val bh = u.y1 - u.y0 + 1 + 2 * pad
        val t = FloatArray(bw * bh)
        var tx0 = Int.MAX_VALUE; var ty0 = Int.MAX_VALUE; var tx1 = -1; var ty1 = -1
        for (y in 0 until bh) for (x in 0 until bw) {
            val xx = bx0 + x; val yy = by0 + y
            if (xx !in 0 until f.w || yy !in 0 until f.h) continue
            val c = f.px[yy * f.w + xx]
            val v = (((c shr 16) and 255) - face[0]) * dr + (((c shr 8) and 255) - face[1]) * dg + ((c and 255) - face[2]) * db
            val tv = (v / n2).coerceIn(0f, 1f)
            t[y * bw + x] = tv
            if (tv >= 0.5f) { tx0 = min(tx0, x); tx1 = max(tx1, x); ty0 = min(ty0, y); ty1 = max(ty1, y) }
        }
        if (tx1 < 0) return null
        // 경계: 농도 0.5 지점은 픽셀 중심±0.5 이므로 [tx0, tx1+1) 로 둔다.
        val gx0 = tx0.toFloat(); val gy0 = ty0.toFloat()
        val gw = (tx1 - tx0 + 1).toFloat(); val gh = (ty1 - ty0 + 1).toFloat()
        fun sample(x: Float, y: Float): Float { // 연속 좌표(픽셀 중심 = i+0.5) 쌍선형 보간
            val fx = x - 0.5f; val fy = y - 0.5f
            val ix = kotlin.math.floor(fx).toInt(); val iy = kotlin.math.floor(fy).toInt()
            val ax = fx - ix; val ay = fy - iy
            fun at(px: Int, py: Int) = if (px in 0 until bw && py in 0 until bh) t[py * bw + px] else 0f
            return (at(ix, iy) * (1 - ax) + at(ix + 1, iy) * ax) * (1 - ay) + (at(ix, iy + 1) * (1 - ax) + at(ix + 1, iy + 1) * ax) * ay
        }
        val g = FloatArray(GW * GH)
        val ss = 4
        for (cy in 0 until GH) for (cx in 0 until GW) {
            var s = 0f
            for (sy in 0 until ss) for (sx in 0 until ss) {
                s += sample(gx0 + (cx + (sx + 0.5f) / ss) * gw / GW, gy0 + (cy + (sy + 0.5f) / ss) * gh / GH)
            }
            g[cy * GW + cx] = s / (ss * ss)
        }
        val rect = Rect(bx0 + tx0, by0 + ty0, bx0 + tx1 + 1, by0 + ty1 + 1)
        return Glyph(rect, u.color, u.vivid, blur(g), gw / gh)
    }

    private fun blur(g: FloatArray): FloatArray {
        val out = FloatArray(GW * GH)
        for (y in 0 until GH) for (x in 0 until GW) {
            var s = 0f; var n = 0
            for (dy in -1..1) for (dx in -1..1) {
                val xx = x + dx; val yy = y + dy
                if (xx in 0 until GW && yy in 0 until GH) {
                    val k = if (dx == 0 && dy == 0) 4 else 1
                    s += g[yy * GW + xx] * k; n += k
                }
            }
            out[y * GW + x] = s / n
        }
        return out
    }

    // ---------------------------------------------------------------- 타일 면 크기, 랙

    /** 글자 세로 중심 줄에서 양옆으로 타일 면이 끝나는 곳까지의 폭. 이웃 타일과의 이음매에서 멈춘다. */
    private fun faceWidth(f: Frame, g: Rect, glyphH: Double): Int {
        val y = g.cy
        val limit = (glyphH * 4).roundToInt()
        var l = g.left - 1
        while (g.left - l < limit && (f.lit(l, y) || f.lit(l - 1, y))) l--
        var r = g.right
        while (r - g.right < limit && (f.lit(r, y) || f.lit(r + 1, y))) r++
        return r - l - 1
    }

    /** 글자 위로 타일 면이 끝나는 y. 글자 왼끝/가운데/오른끝 중 가장 짧게 끝나는 곳. */
    private fun faceTop(f: Frame, g: Rect, glyphH: Double): Int {
        val limit = (glyphH * 1.5).roundToInt()
        var best = Int.MIN_VALUE
        for (x in intArrayOf(g.left, g.cx, g.right - 1)) {
            var t = g.top - 1
            while (g.top - t < limit && (f.lit(x, t) || f.lit(x, t - 1))) t--
            best = max(best, t + 1)
        }
        return best
    }

    /** 랙 두 줄처럼 아래 타일이 위 타일 아랫부분을 가리면, 위 타일 사각형을 아래 타일 윗변에서 자른다. */
    private fun clipBelow(tiles: List<DetectedTile>): List<DetectedTile> = tiles.map { t ->
        val cut = tiles.filter { o ->
            o !== t && o.glyph.top > t.glyph.bottom && o.rect.top < t.rect.bottom &&
                o.rect.left < t.rect.right && o.rect.right > t.rect.left
        }.minOfOrNull { it.rect.top } ?: return@map t
        t.copy(rect = t.rect.copy(bottom = maxOf(cut, t.glyph.bottom)))
    }

    /**
     * 나무 받침대 윗변: 화면 아래쪽 절반에서 나무색 픽셀이 폭의 3% 넘게 보이는 줄이 화면 높이 1% 이상
     * 연속되기 시작하는 곳. 없으면 72% 지점. 주황 글자 가장자리(안티앨리어싱)도 나무색과 비슷하지만
     * 1~2px 두께라서, 아래로 3px·6px 지점도 나무색인 픽셀만 센다.
     */
    fun findRackTop(px: IntArray, w: Int, h: Int): Int {
        val need = max(3, h / 100)
        var run = 0
        for (y in h / 2 until h - 6) {
            var n = 0
            var x = 0
            while (x < w) {
                val p = y * w + x
                if (isWood(px[p]) && isWood(px[p + 3 * w]) && isWood(px[p + 6 * w])) n++
                x += 2
            }
            if (n * 2 > w * 0.03) { if (++run >= need) return y - run + 1 } else run = 0
        }
        return (h * 0.72).roundToInt()
    }

    private class Comp(val id: Int) {
        var x0 = Int.MAX_VALUE; var y0 = Int.MAX_VALUE; var x1 = -1; var y1 = -1
        var area = 0; var strong = 0; var touchesBorder = false
        var sr = 0L; var sg = 0L; var sb = 0L
        val w get() = x1 - x0 + 1
        val h get() = y1 - y0 + 1
        fun add(x: Int, y: Int, c: Int) {
            if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y
            area++
            if (isStrongInk(c)) { strong++; sr += (c shr 16) and 255; sg += (c shr 8) and 255; sb += c and 255 }
        }
    }

    private class Blob(first: List<Comp>) {
        val comps = ArrayList(first)
        var x0 = 0; var y0 = 0; var x1 = 0; var y1 = 0
        lateinit var color: TileColor
        var vivid = 0
        var ir = 0; var ig = 0; var ib = 0
        val h get() = y1 - y0 + 1
        init { update() }
        fun absorb(o: Blob) { comps += o.comps; update() }
        private fun update() {
            x0 = comps.minOf { it.x0 }; x1 = comps.maxOf { it.x1 }
            y0 = comps.minOf { it.y0 }; y1 = comps.maxOf { it.y1 }
            val n = comps.sumOf { it.strong }.coerceAtLeast(1)
            ir = (comps.sumOf { it.sr } / n).toInt()
            ig = (comps.sumOf { it.sg } / n).toInt()
            ib = (comps.sumOf { it.sb } / n).toInt()
            color = colorOf(ir, ig, ib)
            vivid = max(ir, max(ig, ib))
        }
    }

    companion object {
        const val GW = 12
        const val GH = 16
        const val MAX_DIST = 0.05
        const val MIN_GLYPH_H = 9
        const val TILE_RATIO = 1.59
        /** 조커 그림 높이 / 숫자 글자 높이 */
        const val JOKER_H = 1.5

        private fun median(v: List<Int>): Int = if (v.isEmpty()) 0 else v.sorted()[v.size / 2]

        fun isLight(c: Int): Boolean {
            val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
            val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
            return mn >= 150 && mx >= 175 && mx - mn <= 40
        }

        fun isStrongInk(c: Int): Boolean {
            val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
            val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
            return mx < 90 || (mx > 120 && mx - mn > mx * 0.5)
        }

        fun isWood(c: Int): Boolean {
            val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
            return r in 140..240 && r - g in 20..80 && g - b in 15..80
        }

        fun colorOf(r: Int, g: Int, b: Int): TileColor {
            val mx = max(r, max(g, b))
            if (mx < 100) return TileColor.BLACK
            if (b >= r && b >= g) return TileColor.BLUE
            // 빨강 (208,34,42) / 주황 (243,131,49): 초록 성분 비율로 가른다.
            return if (g > r * 0.35) TileColor.ORANGE else TileColor.RED
        }
    }
}
