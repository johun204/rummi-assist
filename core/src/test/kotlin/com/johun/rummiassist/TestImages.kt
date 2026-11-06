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

fun scaled(img: Img, f: Double): Img {
    val src = java.awt.image.BufferedImage(img.w, img.h, java.awt.image.BufferedImage.TYPE_INT_RGB)
    src.setRGB(0, 0, img.w, img.h, img.px, 0, img.w)
    val w = (img.w * f).toInt(); val h = (img.h * f).toInt()
    val dst = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB)
    val g = dst.createGraphics()
    g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
    g.drawImage(src, 0, 0, w, h, null)
    g.dispose()
    return Img(dst.getRGB(0, 0, w, h, null, 0, w), w, h)
}
