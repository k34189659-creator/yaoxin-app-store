package tw.dafeiyu.mangatranslator.core

import org.junit.Assert.assertEquals
import org.junit.Test

class EnglishDialogueGroupingTest {
    private fun b(x0: Float, y0: Float, x1: Float, y1: Float) =
        EnglishDialogueGrouping.Box(x0, y0, x1, y1)

    @Test fun linesWithinSameBubbleAreGroupedDespiteInterleavedOcrOrder() {
        val lines = listOf(
            b(20f, 10f, 90f, 26f),
            b(220f, 12f, 295f, 28f),
            b(18f, 32f, 95f, 48f),
            b(218f, 34f, 299f, 50f),
            b(25f, 54f, 87f, 70f),
        )
        assertEquals(listOf(listOf(0, 2, 4), listOf(1, 3)), EnglishDialogueGrouping.group(lines))
    }

    @Test fun distantBubblesAreNotMerged() {
        val lines = listOf(b(50f, 10f, 120f, 26f), b(52f, 100f, 118f, 116f))
        assertEquals(2, EnglishDialogueGrouping.group(lines).size)
    }

    @Test fun blankBoxesAreIgnored() {
        val lines = listOf(b(2f, 2f, 2f, 2f), b(10f, 10f, 60f, 25f))
        assertEquals(listOf(listOf(1)), EnglishDialogueGrouping.group(lines))
    }
    @Test fun separatorPreventsAdjacentBubblesFromMerging() {
        val lines = listOf(b(20f, 10f, 100f, 27f), b(20f, 33f, 100f, 50f))
        assertEquals(1, EnglishDialogueGrouping.group(lines).size)
        assertEquals(2, EnglishDialogueGrouping.group(lines) { _, _ -> true }.size)
    }
}
