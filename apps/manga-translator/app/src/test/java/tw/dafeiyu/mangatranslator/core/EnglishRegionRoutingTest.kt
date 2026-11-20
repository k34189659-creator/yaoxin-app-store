package tw.dafeiyu.mangatranslator.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EnglishRegionRoutingTest {
    private fun feature(spread: Float, sat: Float, ink: Float, border: Float = 0f) =
        EnglishRegionFeatures(spread, sat, ink, border, 65, 21)

    @Test fun normalBubble() {
        assertEquals(EnglishRegionType.NORMAL_BUBBLE,
            EnglishRegionRouting.classify(feature(8f, 2f, .1f)).type)
    }
    @Test fun plainArt() {
        assertEquals(EnglishRegionType.PLAIN_ART,
            EnglishRegionRouting.classify(feature(92f, 10f, .05f)).type)
    }
    @Test fun coloredBubble() {
        assertEquals(EnglishRegionType.COLORED_BUBBLE,
            EnglishRegionRouting.classify(feature(14f, 82f, .1f)).type)
    }
    @Test fun coloredArt() {
        assertEquals(EnglishRegionType.COLORED_ART,
            EnglishRegionRouting.classify(feature(96f, 20f, .7f)).type)
    }
    @Test fun uncertainRegionHasLowConfidence() {
        val r = EnglishRegionRouting.classify(EnglishRegionFeatures(10f, 20f, 0f, 0f, 4, 2))
        assertTrue(r.confidence < .5f)
    }
}
