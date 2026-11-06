package com.johun.rummiassist.core

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 화면 캡처(ARGB 픽셀)에서 타일을 찾는다. 해상도/타일 크기에 의존하지 않도록
 * "밝은 타일 면 안에 갇힌 진한 잉크 덩어리 = 숫자/조커 그림" 이라는 성질만 사용한다.
 *
 * 1. 타일 면(밝고 채도 낮은 픽셀) 마스크를 만든다.
 * 2. 밝지 않은 픽셀의 연결 요소 중 화면 테두리에 닿지 않고 작고 진한 것 = 글자 조각.
 * 3. 다른 조각의 bbox 안에 들어간 조각(조커 눈/입)은 합치고, 같은 색으로 나란한 조각("1"+"3")을 한 글자로 묶는다.
 * 4. 글자 주변이 타일 면으로 둘러싸였는지 확인하고, 정규화한 격자 특징을 템플릿과 비교해 숫자를 정한다.
 * 5. 나무 받침대(랙) 위에 있으면 손패, 아니면 게임판.
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

    class Result(val tiles: List<DetectedTile>, val rackTop: Int, val width: Int, val height: Int) {
        val board get() = tiles.filter { !it.inHand }
        val hand get() = tiles.filter { it.inHand }
    }

    fun recognize(px: IntArray, w: Int, h: Int): Result {
        val glyphs0 = findGlyphs(px, w, h)
        // 이름표 숫자 같은 작은 잡티 제거: 판/손패 글자 크기는 서로 2배 이상 차이 나지 않는다.
        val med = glyphs0.map { it.rect.height }.sorted().let { if (it.isEmpty()) 0 else it[it.size / 2] }
        val glyphs = glyphs0.filter { it.rect.height >= med * 0.45 }
        val rackTop = findRackTop(px, w, h)
        val tiles = ArrayList<DetectedTile>()
        for (g in glyphs) {
            val (label, dist) = classify(g) ?: continue
            // 작은 글자는 격자 오차가 커서 허용치를 넓힌다.
            if (dist > MAX_DIST + max(0, 28 - g.rect.height) * 0.006) continue
            val kind = if (label == "J") TileKind.JOKER else TileKind.of(g.color, label.toInt())
            val rect = tileRect(px, w, h, g.rect)
            tiles += DetectedTile(kind, rect, g.rect, g.rect.bottom > rackTop, dist)
        }
        return Result(clipBelow(tiles), rackTop, w, h)
    }

    /** 랙 두 줄처럼 아래 타일이 위 타일 아랫부분을 가리면, 위 타일 사각형을 아래 타일 윗변에서 자른다. */
    private fun clipBelow(tiles: List<DetectedTile>): List<DetectedTile> = tiles.map { t ->
        val cut = tiles.filter { o ->
            o !== t && o.glyph.top > t.glyph.bottom && o.rect.top < t.rect.bottom &&
                o.rect.left < t.rect.right && o.rect.right > t.rect.left
        }.minOfOrNull { it.rect.top } ?: return@map t
        t.copy(rect = t.rect.copy(bottom = maxOf(cut, t.glyph.bottom)))
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

    // ---------------------------------------------------------------- 글자 찾기

    fun findGlyphs(px: IntArray, w: Int, h: Int): List<Glyph> {
        val light = BooleanArray(w * h) { isLight(px[it]) }
        val label = IntArray(w * h) // 0 = 미방문, -1 = 버림, >0 = 조각 번호
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
            !it.touchesBorder && it.h >= 8 && it.h <= maxH && it.w <= maxH &&
                it.area >= 20 && it.strong >= it.area * 0.35
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
            if (u.h < 14) continue
            if (!surroundedByFace(light, w, h, u)) continue
            val ids = u.comps.map { it.id }.toHashSet()
            if (u.color != TileColor.BLACK && u.vivid < 165) continue
            out += Glyph(
                Rect(u.x0, u.y0, u.x1 + 1, u.y1 + 1),
                u.color,
                u.vivid,
                gridFeature(label, w, ids, u),
                (u.x1 - u.x0 + 1).toFloat() / (u.y1 - u.y0 + 1),
            )
        }
        return out
    }

    private fun surroundedByFace(light: BooleanArray, w: Int, h: Int, u: Blob): Boolean {
        val m = max(2, (u.h * 0.1).roundToInt()) // 조커 그림은 타일을 거의 꽉 채워 여백이 좁다
        val x0 = u.x0 - m; val x1 = u.x1 + m; val y0 = u.y0 - m; val y1 = u.y1 + m
        if (x0 < 0 || y0 < 0 || x1 >= w || y1 >= h) return false
        var tot = 0; var lit = 0
        for (y in y0..y1) for (x in x0..x1) {
            if (x in u.x0..u.x1 && y in u.y0..u.y1) continue
            tot++
            if (light[y * w + x]) lit++
        }
        return lit >= tot * 0.8
    }

    private fun gridFeature(label: IntArray, w: Int, ids: Set<Int>, u: Blob): FloatArray {
        val g = FloatArray(GW * GH)
        val cnt = IntArray(GW * GH)
        val bw = u.x1 - u.x0 + 1
        val bh = u.y1 - u.y0 + 1
        for (y in u.y0..u.y1) for (x in u.x0..u.x1) {
            val gx = (x - u.x0) * GW / bw
            val gy = (y - u.y0) * GH / bh
            val k = gy * GW + gx
            cnt[k]++
            if (label[y * w + x] in ids) g[k] += 1f
        }
        for (k in g.indices) if (cnt[k] > 0) g[k] /= cnt[k]
        // 작은 글자의 안티앨리어싱/격자 정렬 차이를 줄이려고 3x3 평균으로 부드럽게 한다.
        val out = FloatArray(GW * GH)
        for (y in 0 until GH) for (x in 0 until GW) {
            var s = 0f; var n = 0
            for (dy in -1..1) for (dx in -1..1) {
                val xx = x + dx; val yy = y + dy
                if (xx in 0 until GW && yy in 0 until GH) { s += g[yy * GW + xx] * (if (dx == 0 && dy == 0) 4 else 1); n += if (dx == 0 && dy == 0) 4 else 1 }
            }
            out[y * GW + x] = s / n
        }
        return out
    }

    // ---------------------------------------------------------------- 타일 영역, 랙

    /** 글자에서 좌우/위로 타일 면이 끝나는 곳까지 넓혀 타일 사각형을 추정한다. */
    private fun tileRect(px: IntArray, w: Int, h: Int, g: Rect): Rect {
        fun lit(x: Int, y: Int) = x in 0 until w && y in 0 until h && isLight(px[y * w + x])
        val y = g.cy
        var l = g.left - 1
        while (l > 0 && (lit(l, y) || lit(l - 1, y))) l--
        var r = g.right
        while (r < w - 1 && (lit(r, y) || lit(r + 1, y))) r++
        var t = g.top - 1
        val minT = g.top - g.height
        while (t > minT && t > 0 && (lit(g.cx, t) || lit(g.cx, t - 1))) t--
        // 타일 폭이 이웃 타일과 붙어 과하게 넓어졌으면 글자 크기 기준으로 자른다.
        val maxW = (g.height * 2.0).roundToInt()
        if (r - l > maxW) {
            val half = maxW / 2
            l = max(l, g.cx - half); r = min(r, g.cx + half)
        }
        val b = min(h, t + ((r - l) * 1.35).roundToInt())
        return Rect(l, t, r, b)
    }

    /** 나무 받침대 윗변: 화면 아래쪽 절반에서 나무색 픽셀이 화면 높이 1% 이상 연속으로 3% 넘게 보이기 시작하는 줄. 없으면 72% 지점. */
    fun findRackTop(px: IntArray, w: Int, h: Int): Int {
        val need = max(3, h / 100)
        var run = 0
        for (y in h / 2 until h) {
            var n = 0
            var x = 0
            while (x < w) { if (isWood(px[y * w + x])) n++; x += 2 }
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
        val h get() = y1 - y0 + 1
        init { update() }
        fun absorb(o: Blob) { comps += o.comps; update() }
        private fun update() {
            x0 = comps.minOf { it.x0 }; x1 = comps.maxOf { it.x1 }
            y0 = comps.minOf { it.y0 }; y1 = comps.maxOf { it.y1 }
            val n = comps.sumOf { it.strong }.coerceAtLeast(1)
            val r = (comps.sumOf { it.sr } / n).toInt()
            val g = (comps.sumOf { it.sg } / n).toInt()
            val b = (comps.sumOf { it.sb } / n).toInt()
            color = colorOf(r, g, b)
            vivid = max(r, max(g, b))
        }
    }

    companion object {
        const val GW = 12
        const val GH = 16
        const val MAX_DIST = 0.06

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
