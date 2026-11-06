package com.johun.rummiassist

import com.johun.rummiassist.core.Recognizer
import org.junit.Test

class SynDebugTest {
    @Test fun dbg() {
        val p = System.getenv("SYN_DBG") ?: return
        val img = javax.imageio.ImageIO.read(java.io.File(p))
        val px = img.getRGB(0, 0, img.width, img.height, null, 0, img.width)
        val rec = Recognizer()
        println("rackTop=${rec.findRackTop(px, img.width, img.height)}")
        val r = rec.recognize(px, img.width, img.height)
        for (t in r.tiles.sortedBy { it.glyph.cy }) println("  ${t.kind} hand=${t.inHand} glyph=${t.glyph} d=${"%.3f".format(t.score)}")
        for (g in rec.findGlyphs(px, img.width, img.height)) println("  G ${g.rect} ${g.color} ${rec.classify(g)}")
    }
}
