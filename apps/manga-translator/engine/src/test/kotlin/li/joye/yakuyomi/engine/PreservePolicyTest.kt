package li.joye.yakuyomi.engine

import li.joye.yakuyomi.engine.typeset.LayoutBox
import li.joye.yakuyomi.engine.typeset.RectRegion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「全部區域都不可擦 → 整頁沒變更」的保底規則。
 *
 * 這是使用者回報「第 47/57 頁整頁保留原圖」在 0.3.0 可能重新出現的邊界：
 * 分類保守地保留藝術字時，若剛好整頁都被保留，頁面就不會有任何修改。
 */
class PreservePolicyTest {

    private fun region(text: String): TextRegion {
        val line = TextLine(
            listOf(Pt(0f, 0f), Pt(20f, 0f), Pt(20f, 20f), Pt(0f, 20f)),
            1f,
        )
        line.text = text
        return TextRegion(listOf(line), "v")
    }

    private fun unsafe(note: String? = null) = RegionInspection(
        kind = TextKind.UNKNOWN,
        shape = RectRegion(LayoutBox(0f, 0f, 100f, 100f)),
        interiorFound = false,
        style = TextStyleInfo(0, 0xFFFFFF, false, false, false, 0f, 255f, 0f),
        eraseSafe = false,
        note = note,
    )

    private fun safe() = unsafe().copy(kind = TextKind.DIALOGUE, eraseSafe = true)

    @Test
    fun allUnsafeWithLongTextForcesProgress() {
        val regions = listOf(region("這是一段足夠長的旁白文字"), region("短"), region("另一段也夠長的文字"))
        val inspections = mapOf(0 to unsafe(), 1 to unsafe(), 2 to unsafe())

        val r = PreservePolicy.ensureProgress(regions, inspections)

        assertTrue(r.forced)
        assertEquals(listOf(0, 2), r.forcedIndices.sorted())
        assertTrue(r.inspections[0]!!.eraseSafe)
        assertTrue(r.inspections[2]!!.eraseSafe)
        assertFalse("短區域仍保留", r.inspections[1]!!.eraseSafe)
        assertTrue(r.inspections[0]!!.note!!.contains("保守擦字"))
    }

    @Test
    fun mixedPageIsUntouched() {
        val regions = listOf(region("這是一段足夠長的旁白文字"), region("另一段文字"))
        val inspections = mapOf(0 to unsafe(), 1 to safe())

        val r = PreservePolicy.ensureProgress(regions, inspections)

        assertFalse(r.forced)
        assertTrue(r.forcedIndices.isEmpty())
        assertFalse(r.inspections[0]!!.eraseSafe)
    }

    @Test
    fun allShortRegionsStayPreserved() {
        val regions = listOf(region("咚"), region("啊"), region("!"))
        val inspections = mapOf(0 to unsafe(), 1 to unsafe(), 2 to unsafe())

        val r = PreservePolicy.ensureProgress(regions, inspections)

        assertFalse("整頁只有短裝飾字時不強制擦除", r.forced)
        assertTrue(r.inspections.values.all { !it.eraseSafe })
    }

    @Test
    fun graphicSoundEffectsRemainUntouchedEvenWhenEntirePageWouldBePreserved() {
        val graphic = unsafe().copy(kind = TextKind.SFX)
        val art = unsafe().copy(kind = TextKind.COLORED_ART)
        val regions = listOf(region("ドドドドドドド"), region("ゴゴゴゴゴゴゴ"))
        val result = PreservePolicy.ensureProgress(regions, mapOf(0 to graphic, 1 to art))
        assertFalse(result.forced)
        assertTrue(result.inspections.values.all { !it.eraseSafe })
    }

    @Test
    fun emptyInputIsSafe() {
        val r = PreservePolicy.ensureProgress(emptyList(), emptyMap())
        assertFalse(r.forced)
        assertTrue(r.inspections.isEmpty())
    }
}
