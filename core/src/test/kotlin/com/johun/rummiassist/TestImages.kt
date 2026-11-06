package com.johun.rummiassist

import java.awt.image.BufferedImage
import javax.imageio.ImageIO

class Img(val px: IntArray, val w: Int, val h: Int)

fun loadImg(name: String): Img {
    val bi: BufferedImage = ImageIO.read(Img::class.java.getResourceAsStream("/$name")!!)
    val px = bi.getRGB(0, 0, bi.width, bi.height, null, 0, bi.width)
    return Img(px, bi.width, bi.height)
}

/** 첨부 캡처의 정답. 게임판(행 순서, 행 안에서 왼→오), 그다음 손패(윗줄, 아랫줄). */
val TRUTH = mapOf(
    "s1.png" to ("K7 K8 K9" to
        "R11 O6 B2 B8 K3 O1 K1 K7 R4 K4 R4 O8 K4 K8 O7 O2 J R1 O12"),
    "s2.png" to ("K10 K11 K12 K13 K6 K7 K8 K9 K1 K2 K3 K4 K5 K6 K7 K8 B13 O13 R13 O6 O7 O8 O9 O1 O2 O3 O2 O3 J O5" to
        "R9 R10 R11 K4 K12 B2 B3 B6 B8 B10 B11 O12 R1 R2 R4 R4 R6 R7 R11 J"),
    "s3.png" to ("K10 K11 K12 K13 K6 K7 K8 K9 K1 K2 K3 K4 K5 K6 K7 K8 B13 O13 R13 O6 O7 O8 O9 O2 O3 J O5 O1 O2 O3" to
        "R9 R10 R11 K4 K12 B2 B3 B6 B8 B10 B11 O12 R1 R2 R4 R4 R6 R7 R11 J"),
    "s4.png" to ("K10 K11 K12 K13 K6 K7 K8 K9 O2 O3 J O5" to
        "K1 O1 R1 B11 B2 O2 R2 O3 K6 O6 R6 K12 K8 B8 R7 B10 B6 O8 K2 K3 B3 K4 K4 R4 R4 K7 O7 R10 R11 O12 J K5 R11"),
)

/** 행(세로 중심이 비슷한 것끼리) 단위로 묶고 행 안은 x 순서. */
fun <T> readingOrder(items: List<T>, cy: (T) -> Int, x: (T) -> Int, hgt: (T) -> Int): List<T> {
    val rows = ArrayList<MutableList<T>>()
    for (it in items.sortedBy(cy)) {
        val row = rows.firstOrNull { r -> kotlin.math.abs(cy(it) - cy(r[0])) < hgt(r[0]) / 2 }
        if (row != null) row += it else rows += mutableListOf(it)
    }
    return rows.flatMap { r -> r.sortedBy(x) }
}

/** 면적 평균 축소/확대(게임의 GPU 렌더링처럼 부드럽게). */
fun scaled(img: Img, f: Double): Img {
    val w = (img.w * f).toInt(); val h = (img.h * f).toInt()
    return Img(resampleArgb(img.px, img.w, img.h, w, h), w, h)
}

fun resampleArgb(src: IntArray, sw: Int, sh: Int, dw: Int, dh: Int): IntArray {
    val ch = Array(3) { c -> FloatArray(sw * sh) { (src[it] shr (16 - 8 * c) and 255).toFloat() } }
    val out = ch.map { resample(it, sw, sh, dw, dh) }
    return IntArray(dw * dh) {
        val r = out[0][it].toInt().coerceIn(0, 255); val g = out[1][it].toInt().coerceIn(0, 255); val b = out[2][it].toInt().coerceIn(0, 255)
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
}

/** 확대는 쌍선형, 축소는 상자(면적) 적분. */
fun resample(src: FloatArray, sw: Int, sh: Int, dw: Int, dh: Int): FloatArray {
    val sx = sw.toDouble() / dw; val sy = sh.toDouble() / dh
    val out = FloatArray(dw * dh)
    if (sx <= 1 && sy <= 1) {
        for (y in 0 until dh) for (x in 0 until dw) {
            val fx = ((x + 0.5) * sx - 0.5).coerceIn(0.0, sw - 1.0); val fy = ((y + 0.5) * sy - 0.5).coerceIn(0.0, sh - 1.0)
            val ix = fx.toInt(); val iy = fy.toInt(); val ax = fx - ix; val ay = fy - iy
            val ix1 = minOf(ix + 1, sw - 1); val iy1 = minOf(iy + 1, sh - 1)
            out[y * dw + x] = ((src[iy * sw + ix] * (1 - ax) + src[iy * sw + ix1] * ax) * (1 - ay) +
                (src[iy1 * sw + ix] * (1 - ax) + src[iy1 * sw + ix1] * ax) * ay).toFloat()
        }
        return out
    }
    // 가로 → 세로 분리 적분
    fun axis(n: Int, scale: Double, i: Int, add: (Int, Double) -> Unit) {
        val a = i * scale; val b = (i + 1) * scale
        var k = a.toInt()
        while (k < b && k < n) { val wgt = minOf(b, k + 1.0) - maxOf(a, k.toDouble()); if (wgt > 0) add(k, wgt); k++ }
    }
    val tmp = FloatArray(dw * sh)
    for (y in 0 until sh) for (x in 0 until dw) {
        var s = 0.0; var ws = 0.0
        axis(sw, sx, x) { k, wgt -> s += src[y * sw + k] * wgt; ws += wgt }
        tmp[y * dw + x] = (s / ws).toFloat()
    }
    for (y in 0 until dh) for (x in 0 until dw) {
        var s = 0.0; var ws = 0.0
        axis(sh, sy, y) { k, wgt -> s += tmp[k * dw + x] * wgt; ws += wgt }
        out[y * dw + x] = (s / ws).toFloat()
    }
    return out
}
