package tw.dafeiyu.mangatranslator.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnglishTextLayoutTest {
    private fun measure(text: String, size: Int) = text.codePointCount(0, text.length) * size.toFloat()
    private fun metrics(size: Int) = EnglishTextLayout.Metrics(-size.toFloat(), size * .25f)
    private fun fit(text: String, width: Float, height: Float, cap: Int = 8, scale: Float = 1f) =
        EnglishTextLayout.fit(text, width, height, cap, scale, ::measure, ::metrics)

    @Test fun runawayOutputIsRejectedBeforeAnyMeasurement() {
        val result = EnglishTextLayout.fit("好".repeat(2049), 500f, 2000f, 40,
            measure = { _, _ -> error("Runaway text must not be measured") },
            metrics = { error("Runaway text must not reach font layout") })
        assertFalse(result.fitted)
        assertTrue(result.rows.isEmpty())
    }

    @Test fun cannotFitReturnsNoDrawableRowsInsteadOfClippedText() {
        val result = fit("早安", 80f, 7f, 24)
        assertFalse(result.fitted)
        assertTrue(result.rows.isEmpty())
        assertTrue(result.reason!!.contains("未嵌字"))
    }

    @Test fun fontScaleIsAppliedBeforeFitAndCannotIntroduceOverflow() {
        for (scale in listOf(.55f, 1f, 1.4f)) {
            val result = fit("今天下午一起散步。", 52f, 37f, 24, scale)
            assertTrue(result.fitted)
            assertTrue(result.height <= 37.01f)
            assertTrue(result.rows.all { measure(it, result.fontSize) <= 52.01f })
            assertEquals("今天下午一起散步。", result.rows.joinToString(""))
        }
    }

    @Test fun punctuationMovesWithItsNeighborInsteadOfStartingALine() {
        val result = fit("天氣很好，今天出門。", 32f, 150f)
        assertTrue(result.fitted)
        assertEquals("天氣很好，今天出門。", result.rows.joinToString(""))
        assertTrue(result.rows.none { it.first() in "，。！？）」』" })
    }

    @Test fun openingQuoteDoesNotEndARow() {
        val result = fit("明天「再見」！", 24f, 150f)
        assertTrue(result.fitted)
        assertEquals("明天「再見」！", result.rows.joinToString(""))
        assertTrue(result.rows.none { it.last() in "（「『" || it.first() in "，。！？）」』" })
    }

    @Test fun latinWordsStayTogetherWhenTheyFit() {
        val result = fit("HELLO WORLD", 48f, 100f)
        assertTrue(result.fitted)
        assertEquals(listOf("HELLO", "WORLD"), result.rows)
    }

    @Test fun veryLongLatinWordMayBreakWithoutDroppingLetters() {
        val result = fit("ABCDEFG", 24f, 100f)
        assertTrue(result.fitted)
        assertEquals(listOf("ABC", "DEF", "G"), result.rows)
    }

    @Test fun supplementaryUnicodeCharacterIsNeverSplitBetweenRows() {
        val text = "甲😀乙😀丙"
        val result = fit(text, 16f, 100f)
        assertTrue(result.fitted)
        assertEquals(text, result.rows.joinToString(""))
        assertTrue(result.rows.none { Character.isLowSurrogate(it.first()) || Character.isHighSurrogate(it.last()) })
    }

    @Test fun actualGlyphExtentsControlVerticalFit() {
        val result = EnglishTextLayout.fit("你好", 80f, 12f, 8,
            measure = ::measure, metrics = { EnglishTextLayout.Metrics(-12f, 4f) })
        assertFalse(result.fitted)
    }

    @Test fun explicitNewlineIsPreserved() {
        val result = fit("你好\n世界", 100f, 100f)
        assertTrue(result.fitted)
        assertEquals(listOf("你好", "世界"), result.rows)
    }

    @Test fun invalidGeometryCannotBecomeADrawablePlan() {
        for (width in listOf(Float.NaN, Float.POSITIVE_INFINITY, -1f, 0f)) {
            assertFalse(fit("你好", width, 100f).fitted)
        }
    }

    @Test fun extremelyTallNarrowColumnHasABoundedLineCount() {
        var measurements = 0
        val result = EnglishTextLayout.fit("好".repeat(2048), 8f, 5000f, 8,
            measure = { text, size -> measurements++; measure(text, size) }, metrics = ::metrics)
        assertFalse(result.fitted)
        assertTrue(result.rows.isEmpty())
        assertTrue(measurements <= 512)
    }
}
