package li.joye.yakuyomi.engine.typeset

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 排版核心的迴歸測試（純 JVM，不需要 Android）。
 *
 * 用「方塊字度量」模擬 CJK：advance = fontSize、ascent = 0.88×、descent = 0.22×。
 * 這些測試守住 0.3.0 的四個承諾：
 *  1. 不截斷：所有字元都出現在結果裡。
 *  2. 不溢出：在不該 overflow 的情境下，每欄／每行都在可用範圍內。
 *  3. 直排規則：標點位置、旋轉字、縱中橫、禁則。
 *  4. 輪廓感知：橢圓區域的邊緣欄位自動變短。
 */
class TypesetterTest {

    private class FakeMeasurer(override val size: Float) : TextMeasurer {
        override fun advance(text: String): Float {
            var w = 0f
            for (c in text) w += if (c.code < 0x2000) size * 0.55f else size
            return w
        }
        override fun ascent(): Float = size * 0.88f
        override fun descent(): Float = size * 0.22f
        override fun inkWidth(text: String): Float = advance(text)
    }

    private val factory = MeasurerFactory { s -> FakeMeasurer(s) }

    private fun opts(max: Int = 48, min: Int = 8) = TypesetOptions(
        maxFontSize = max, minFontSize = min, measurer = factory,
    )

    private fun rect(w: Float, h: Float) = RectRegion(LayoutBox(0f, 0f, w, h))

    @Test
    fun insetRegionReservesMarginForBubbleBorder() {
        val source = rect(180f, 170f)
        val inset = InsetRegion(source, 8f)
        assertEquals(164f, inset.bounds.width, 0.001f)
        assertEquals(154f, inset.bounds.height, 0.001f)
        val layout = Typesetter.layoutHorizontal("這是一句測試，文字應保持在對話框內。", inset, opts(max = 30))
        assertFalse("應在框內排完", layout.overflow)
        assertTrue("要保留左右安全內距", layout.usedWidth <= inset.bounds.width + .5f)
    }

    @Test
    fun insetTinyBubbleMarksOverflowInsteadOfPaintingOverArt() {
        val inset = InsetRegion(rect(55f, 45f), 5f)
        val layout = Typesetter.layoutHorizontal("文字".repeat(180), inset, opts(max = 30, min = 9))
        assertTrue("不要強塞出框或截斷", layout.overflow)
    }

    // ---------------------------------------------------------------- vertical

    @Test
    fun vertical_keepsEveryCharacter() {
        val text = "這是一段用來測試直排排版的長文字，不能有任何一個字被截斷。"
        val layout = Typesetter.layoutVertical(text, rect(120f, 260f), opts())
        val got = layout.columns.flatMap { c -> c.cells }.joinToString("") { it.text }
        assertEquals(TextNormalizer.normalize(text), got)
    }

    @Test
    fun vertical_fitsInsideBox() {
        val layout = Typesetter.layoutVertical("測試直排文字排版是否正確", rect(120f, 260f), opts())
        assertFalse("不該 overflow", layout.overflow)
        assertTrue(layout.usedWidth <= 120f + 0.5f)
        assertTrue(layout.usedHeight <= 260f + 0.5f)
        for (col in layout.columns) for (cell in col.cells) {
            assertTrue("cell x out of box: ${cell.cx}", cell.cx in -0.5f..120.5f)
            assertTrue("cell y out of box: ${cell.cy}", cell.cy in -0.5f..260.5f)
        }
    }

    @Test
    fun vertical_punctuationAndRotationFlags() {
        val layout = Typesetter.layoutVertical("「あ、い。」", rect(80f, 300f), opts())
        val cells = layout.columns.flatMap { it.cells }
        val punct = cells.first { it.text == "、" }
        assertTrue("、要標記直排標點位移", punct.punctOffset)
        val bracket = cells.first { it.text == "「" }
        assertTrue("「要旋轉", bracket.rotate)
    }

    @Test
    fun vertical_tateChuYokoGroupsDigits() {
        val layout = Typesetter.layoutVertical("2020年の夏", rect(80f, 300f), opts())
        val cells = layout.columns.flatMap { it.cells }
        assertTrue("2020 應併成一格", cells.any { it.text == "2020" && it.tcy })
    }

    @Test
    fun vertical_kinsoku_noColumnStartsWithProhibited() {
        // 每欄 1–2 字，逼出欄首禁則
        val layout = Typesetter.layoutVertical("あ、い。う、え。お", rect(48f, 80f), opts(max = 24, min = 8))
        for (col in layout.columns) {
            val first = col.cells.firstOrNull()?.text ?: continue
            val c = first.first()
            assertFalse("欄首不可為 $c", Cjk.isNoStart(c) && c !in "」』）")
        }
    }

    @Test
    fun vertical_ellipseShortensSideColumns() {
        val ellipse = EllipseRegion(LayoutBox(0f, 0f, 240f, 240f))
        val layout = Typesetter.layoutVertical(
            "這是一段很長的台詞用來測試橢圓形對話框的邊緣欄位是否會自動變短避免壓到框線",
            ellipse, opts(max = 40),
        )
        assertFalse(layout.overflow)
        // 最左／最右欄的格數應少於中間欄
        val counts = layout.columns.map { it.cells.size }
        val middle = counts.maxOrNull() ?: 0
        assertTrue("邊緣欄位應較短：$counts", counts.first() <= middle)
    }

    @Test
    fun vertical_overflowKeepsAllText() {
        val text = "字".repeat(200)
        val layout = Typesetter.layoutVertical(text, rect(30f, 30f), opts(max = 20, min = 6))
        assertTrue("小框＋長文要標 overflow", layout.overflow)
        val got = layout.columns.flatMap { it.cells }.joinToString("") { it.text }
        assertEquals(200, got.length)
    }

    @Test
    fun fontSizeShrinksWithLongerText() {
        val short = Typesetter.layoutVertical("短", rect(200f, 200f), opts())
        val long = Typesetter.layoutVertical("長".repeat(80), rect(200f, 200f), opts())
        assertTrue("長文必須用更小字級", long.fontSize < short.fontSize)
    }

    // -------------------------------------------------------------- horizontal

    @Test
    fun horizontal_wrapsWithinWidth() {
        val text = "這是一段用來測試橫排換行的中文文字，應該要在適當的位置斷行而不是超出邊界。"
        val region = rect(140f, 240f)
        val layout = Typesetter.layoutHorizontal(text, region, opts())
        assertFalse(layout.overflow)
        val m = FakeMeasurer(layout.fontSize)
        for (line in layout.lines) {
            assertTrue("行寬 ${line.width} > 140", line.width <= 140f + 0.5f)
            assertTrue(line.baseline in -1f..241f)
        }
        assertEquals(TextNormalizer.normalize(text), layout.lines.joinToString("") { it.text }.replace(" ", ""))
    }

    @Test
    fun horizontal_doesNotBreakLatinWord() {
        val layout = Typesetter.layoutHorizontal(
            "This is a manga translation test sentence",
            rect(120f, 200f), opts(max = 30),
        )
        for (line in layout.lines) {
            assertFalse("英文單字不可被切開：$line", line.text.endsWith("-"))
        }
        assertEquals(
            "Thisisamangatranslationtestsentence",
            layout.lines.joinToString("") { it.text }.replace(" ", ""),
        )
    }

    @Test
    fun horizontal_ellipseLinesGetShorterNearEdges() {
        val ellipse = EllipseRegion(LayoutBox(0f, 0f, 240f, 240f))
        val layout = Typesetter.layoutHorizontal("這是一段用來測試橢圓對話框橫排的文字內容", ellipse, opts(max = 34))
        assertFalse(layout.overflow)
        val first = layout.lines.first().width
        val mid = layout.lines[layout.lines.size / 2].width
        assertTrue("橢圓上下緣的行應較短", first <= mid + 0.5f)
    }

    // -------------------------------------------------------------- normalizer

    @Test
    fun normalizer_convertsHalfwidthPunctuation() {
        assertEquals("你好，世界！", TextNormalizer.normalize("你好, 世界!"))
        assertEquals("等等……", TextNormalizer.normalize("等等..."))
        assertEquals("（測試）", TextNormalizer.normalize("(測試)"))
    }

    @Test
    fun normalizer_removesSpacesBetweenCjkButKeepsLatin() {
        assertEquals("中文之間沒有空格", TextNormalizer.normalize("中文 之間 沒有 空格"))
        assertEquals("OK 那就走吧", TextNormalizer.normalize("OK 那就 走吧"))
    }

    @Test
    fun mostlyLatinDetection() {
        assertTrue(Cjk.isMostlyLatin("HAPPY BIRTHDAY!"))
        assertFalse(Cjk.isMostlyLatin("生日快樂"))
        assertFalse(Cjk.isMostlyLatin("生日 HAPPY"))
    }
}
