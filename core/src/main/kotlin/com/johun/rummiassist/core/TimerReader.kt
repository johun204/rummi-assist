package com.johun.rummiassist.core

import kotlin.math.max
import kotlin.math.min

/**
 * 내 차례일 때 왼쪽 아래 내 아바타 위에 뜨는 남은 시간(흰 숫자, 예: 16 → 0)을 읽는다.
 * 흰 픽셀(밝고 무채색)의 연결 요소 중 숫자 크기(화면 높이의 3~6%)인 것을 왼→오로 묶어 숫자 템플릿과 비교.
 * 숫자가 없으면(상대 차례) null.
 */
object TimerReader {
    const val GW = 6
    const val GH = 9

    class Template(val digit: Int, val aspect: Float, val grid: FloatArray)

    val templates: List<Template> by lazy {
        TimerTemplates.DATA.trim().lines().map { l ->
            val (d, a, g) = l.trim().split(' ')
            Template(d.toInt(), a.toFloat(), FloatArray(g.length) { (g[it] - 'a') / 15f })
        }
    }

    /** 전체 화면에서 남은 초 */
    fun read(px: IntArray, w: Int, h: Int): Int? {
        val x0 = 0; val x1 = (w * 0.16).toInt(); val y0 = (h * 0.80).toInt(); val y1 = (h * 0.925).toInt()
        return readDigits(px, w, h, x0, y0, x1, y1, (h * 0.03).toInt(), (h * 0.06).toInt())
    }

    /** 영역 안에서 높이 minH..maxH 인 흰 숫자들을 읽어 정수로. 못 읽으면 null */
    fun readDigits(px: IntArray, w: Int, h: Int, x0: Int, y0: Int, x1: Int, y1: Int, minH: Int, maxH: Int): Int? {
        val ds = digits(px, w, h, x0, y0, x1, y1, minH, maxH)
        if (ds.isEmpty() || ds.size > 2) return null
        var v = 0
        for (g in ds) v = v * 10 + (classify(g) ?: return null)
        return v
    }

    class Glyph(val x0: Int, val y0: Int, val x1: Int, val y1: Int, val grid: FloatArray) {
        val aspect get() = (x1 - x0 + 1).toFloat() / (y1 - y0 + 1)
    }

    fun isWhite(c: Int): Boolean {
        val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
        return min(r, min(g, b)) > 215 && max(r, max(g, b)) - min(r, min(g, b)) < 30
    }

    /** 숫자 후보(흰 연결 요소)를 왼→오 순서로. 같은 줄(세로 겹침)에 있는 것만 */
    fun digits(px: IntArray, w: Int, h: Int, x0: Int, y0: Int, x1: Int, y1: Int, minH: Int, maxH: Int): List<Glyph> {
        val bw = x1 - x0; val bh = y1 - y0
        if (bw <= 0 || bh <= 0) return emptyList()
        val seen = BooleanArray(bw * bh)
        val out = ArrayList<Glyph>()
        val stack = IntArray(bw * bh)
        for (sy in 0 until bh) for (sx in 0 until bw) {
            val si = sy * bw + sx
            if (seen[si] || !isWhite(px[(y0 + sy) * w + x0 + sx])) continue
            var sp = 0; stack[sp++] = si; seen[si] = true
            val pts = ArrayList<Int>()
            var ax = Int.MAX_VALUE; var ay = Int.MAX_VALUE; var bx = -1; var by = -1
            while (sp > 0) {
                val p = stack[--sp]; pts += p
                val x = p % bw; val y = p / bw
                ax = min(ax, x); bx = max(bx, x); ay = min(ay, y); by = max(by, y)
                for ((dx, dy) in arrayOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                    val nx = x + dx; val ny = y + dy
                    if (nx !in 0 until bw || ny !in 0 until bh) continue
                    val q = ny * bw + nx
                    if (!seen[q] && isWhite(px[(y0 + ny) * w + x0 + nx])) { seen[q] = true; stack[sp++] = q }
                }
            }
            val gh = by - ay + 1; val gw = bx - ax + 1
            if (gh !in minH..maxH || gw > gh || pts.size < gh * 2) continue
            val grid = FloatArray(GW * GH); val cnt = IntArray(GW * GH)
            for (y in ay..by) for (x in ax..bx) {
                val k = ((y - ay) * GH / gh) * GW + (x - ax) * GW / gw
                cnt[k]++
            }
            for (p in pts) {
                val x = p % bw; val y = p / bw
                grid[((y - ay) * GH / gh) * GW + (x - ax) * GW / gw] += 1f
            }
            for (k in grid.indices) if (cnt[k] > 0) grid[k] /= cnt[k]
            out += Glyph(x0 + ax, y0 + ay, x0 + bx, y0 + by, grid)
        }
        if (out.isEmpty()) return out
        // 가장 큰 글자와 세로로 겹치는 것만(같은 숫자 줄)
        val ref = out.maxBy { it.y1 - it.y0 }
        return out.filter { min(it.y1, ref.y1) - max(it.y0, ref.y0) > (ref.y1 - ref.y0) * 0.6 }.sortedBy { it.x0 }
    }

    fun classify(g: Glyph): Int? {
        var best: Template? = null; var bd = Double.MAX_VALUE
        for (t in templates) {
            val ar = g.aspect / t.aspect
            if (ar < 0.6 || ar > 1.6) continue
            var d = 0.0
            for (i in t.grid.indices) { val e = g.grid[i] - t.grid[i]; d += e * e }
            d = d / t.grid.size + 0.2 * (ar - 1) * (ar - 1)
            if (d < bd) { bd = d; best = t }
        }
        return best?.takeIf { bd < 0.08 }?.digit
    }
}
