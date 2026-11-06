package com.johun.rummiassist.core

import kotlin.math.abs

/**
 * 화면 위치로 놓인 판이 루미큐브 규칙에 맞는지: 같은 줄에서 붙어 있는(타일 폭 1.5배 이내) 타일 묶음
 * 하나하나가 유효한 세트(런 3장+, 그룹 3~4장)여야 하고, 두 타일이 같은 자리를 차지하면 안 된다.
 */
object BoardCheck {
    class P(val kind: TileKind, val cx: Int, val cy: Int)

    /** 문제가 없으면 null, 있으면 설명 */
    fun problem(tiles: List<P>, tileW: Int, tileH: Int): String? {
        for (i in tiles.indices) for (j in i + 1 until tiles.size)
            if (abs(tiles[i].cx - tiles[j].cx) < tileW * 0.8 && abs(tiles[i].cy - tiles[j].cy) < tileH * 0.8)
                return "자리 겹침 ${tiles[i].kind} ${tiles[j].kind}"
        for (g in groups(tiles, tileW, tileH))
            if (!Solver.isValidSet(g.map { it.kind })) return "무효 세트 ${g.map { it.kind }}"
        return null
    }

    fun groups(tiles: List<P>, tileW: Int, tileH: Int): List<List<P>> {
        val rows = ArrayList<MutableList<P>>()
        for (p in tiles.sortedBy { it.cy }) {
            val row = rows.firstOrNull { abs(it[0].cy - p.cy) < tileH / 2 }
            if (row != null) row += p else rows += mutableListOf(p)
        }
        val out = ArrayList<List<P>>()
        for (r in rows) {
            r.sortBy { it.cx }
            var cur = mutableListOf(r[0])
            for ((x, y) in r.zipWithNext()) { if (y.cx - x.cx > tileW * 1.5) { out += cur; cur = mutableListOf() }; cur += y }
            out += cur
        }
        return out
    }
}
