package tw.dafeiyu.mangatranslator.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EnglishBackgroundModelTest {
    private fun rgb(r: Int, g: Int, b: Int): Int =
        (0xff shl 24) or (r shl 16) or (g shl 8) or b

    @Test fun uniformSpeechBubbleRestoresMaskWithoutChangingSurroundings() {
        val w = 56; val h = 30
        val original = IntArray(w*h) { rgb(172, 230, 183) }
        val cleaned = original.copyOf()
        val mask = IntArray(w*h) { rgb(0, 0, 0) }
        for (y in 12..17) for (x in 20..35) {
            val i = y*w+x; original[i] = rgb(12, 12, 12)
            cleaned[i] = rgb(210, 190, 201)
            mask[i] = rgb(255,255,255)
        }
        val n = EnglishBackgroundModel.refine(original,cleaned,mask,w,h)
        assertTrue(n > 0)
        assertEquals(rgb(172,230,183),cleaned[15*w+27])
        assertEquals(rgb(172,230,183),cleaned[2*w+2])
    }

    @Test fun continuousGradientIsReconstructed() {
        val w = 80; val h = 40
        val baseline = IntArray(w*h) { i -> rgb(180 + i%w/4, 220 - i/w/3, 185 + i/w/5) }
        val original = baseline.copyOf(); val out = baseline.copyOf()
        val mask = IntArray(w*h) { rgb(0,0,0) }
        for (y in 14..24) for (x in 22..44) {
            val i = y*w+x; original[i] = rgb(22,22,22); out[i] = rgb(95,95,95)
            mask[i] = rgb(255,255,255)
        }
        assertTrue(EnglishBackgroundModel.refine(original,out,mask,w,h)>0)
        val p=out[19*w+31]; val expected=baseline[19*w+31]
        assertTrue(kotlin.math.abs((p shr 16 and 255)-(expected shr 16 and 255)) <= 3)
        assertEquals(baseline[2*w+2],out[2*w+2])
    }

    @Test fun mixedArtworkDoesNotGetFlattened() {
        val w=80; val h=40
        val original=IntArray(w*h) { i -> if (i%w < w/2) rgb(30,30,30) else rgb(245,210,150) }
        val out=original.copyOf(); val mask=IntArray(w*h) { rgb(0,0,0) }
        for (y in 14..19) for (x in 30..46) {
            val i=y*w+x; mask[i]=rgb(255,255,255)
        }
        assertEquals(0,EnglishBackgroundModel.refine(original,out,mask,w,h))
        assertTrue(original.contentEquals(out))
    }
}
