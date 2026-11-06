package com.johun.rummiassist.core

/** 타일 색. 순서는 솔버 내부 인덱스로도 쓰인다. */
enum class TileColor(val code: Char, val label: String, val argb: Int) {
    BLACK('K', "검정", 0xFF202020.toInt()),
    RED('R', "빨강", 0xFFD0222A.toInt()),
    ORANGE('O', "주황", 0xFFF08020.toInt()),
    BLUE('B', "파랑", 0xFF0039FF.toInt());
}

/** 타일 종류. 조커는 color/value 가 null. */
data class TileKind(val color: TileColor?, val value: Int) {
    val isJoker get() = color == null
    override fun toString() = if (isJoker) "J" else "${color!!.code}$value"

    companion object {
        val JOKER = TileKind(null, 0)
        fun of(c: TileColor, v: Int) = TileKind(c, v)
    }
}

data class Rect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
    val cx get() = (left + right) / 2
    val cy get() = (top + bottom) / 2
}

/** 화면에서 인식한 타일 1개. */
data class DetectedTile(val kind: TileKind, val rect: Rect, val glyph: Rect, val inHand: Boolean, val score: Double)
