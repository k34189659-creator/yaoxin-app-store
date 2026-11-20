package tw.dafeiyu.mangatranslator.core

import android.graphics.Bitmap
import android.graphics.Color
import kotlinx.coroutines.runBlocking
import li.joye.yakuyomi.engine.EnglishTranslationGuard
import li.joye.yakuyomi.engine.Pt
import li.joye.yakuyomi.engine.TextLine
import li.joye.yakuyomi.engine.TextRegion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Full bitmap adapter + mask ownership + erase + native lettering; no cloud API or NCNN needed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EnglishImagePipelineGraphicsTest {
    private val white = Color.rgb(247, 247, 247)
    private val black = Color.rgb(15, 15, 15)
    private val green = Color.rgb(113, 222, 132)

    private data class Scene(val original: Bitmap, val detector: Bitmap, val regions: List<TextRegion>)

    private fun scene(background: Int = white, count: Int = 1, artwork: Boolean = false): Scene {
        val width = 160 * count
        val height = 120
        val pixels = IntArray(width * height) { i ->
            if (!artwork) Color.rgb(102, 118, 148)
            else if (i % width < i / width) Color.rgb(226, 205, 170) else Color.rgb(45, 103, 181)
        }
        val mask = IntArray(pixels.size) { Color.BLACK }
        fun fill(data: IntArray, l: Int, t: Int, r: Int, b: Int, c: Int) {
            for (y in t until b) for (x in l until r) data[y * width + x] = c
        }
        val regions = (0 until count).map { index ->
            val offset = index * 160
            if (!artwork) {
                fill(pixels, offset + 20, 20, offset + 140, 100, black)
                fill(pixels, offset + 23, 23, offset + 137, 97, background)
            }
            for (x in listOf(52, 66, 80, 94)) {
                fill(pixels, offset + x, 50, offset + x + 5, 66, black)
                fill(mask, offset + x, 50, offset + x + 5, 66, Color.WHITE)
            }
            val line = TextLine(listOf(Pt(offset + 45f, 44f), Pt(offset + 115f, 44f),
                Pt(offset + 115f, 72f), Pt(offset + 45f, 72f)), 1f).apply {
                text = if (index == 0) "HELLO FRIEND" else "GOOD EVENING"
            }
            TextRegion(listOf(line), "h").apply { translatedText = if (index == 0) "你好。" else "晚安。" }
        }
        fun bitmap(data: IntArray) = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            setPixels(data, 0, width, 0, 0, width, height)
        }
        return Scene(bitmap(pixels), bitmap(mask), regions)
    }

    @Test
    fun `white and green enclosed bubbles erase and typeset without calling the model`() = runBlocking {
        for (background in listOf(white, green)) {
            val s = scene(background)
            var calls = 0
            val result = EnglishImagePipeline.finishWithReconstruction(s.original, s.detector, s.regions,
                reconstructor = { _, _, _ -> calls++; error("Smooth bubbles must not need a model") },
                residualOcr = { emptyList() })
            assertEquals(result.failures.toString(), listOf(0), result.accepted)
            assertEquals(0, calls)
            assertEquals(if (background == white) EnglishRegionType.NORMAL_BUBBLE else EnglishRegionType.COLORED_BUBBLE,
                result.routes.getValue(0).type)
            for (y in 50 until 66) for (x in listOf(53, 67, 81, 95)) {
                assertEquals("The original glyph must be fully removed", background, result.cleaned.getPixel(x, y))
            }
            for (x in 20 until 140) {
                assertEquals("Bubble top contour must stay unchanged", black, result.final.getPixel(x, 20))
                assertEquals("Bubble bottom contour must stay unchanged", black, result.final.getPixel(x, 99))
            }
            assertFalse("Chinese glyphs must be genuinely painted", result.cleaned.sameAs(result.final))
        }
    }

    @Test
    fun `runaway response is rejected before either erasure or model execution`() = runBlocking {
        val s = scene()
        s.regions.single().translatedText = "文".repeat(4096)
        var calls = 0
        val result = EnglishImagePipeline.finishWithReconstruction(s.original, s.detector, s.regions,
            reconstructor = { _, _, _ -> calls++; error("Rejected response reached model") },
            residualOcr = { error("Nothing should be erased") })
        assertTrue(result.accepted.isEmpty())
        assertTrue(result.failures.containsKey(0))
        assertEquals(0, calls)
        assertTrue(s.original.sameAs(result.cleaned))
        assertTrue(s.original.sameAs(result.final))
    }

    @Test
    fun `valid but unfittable translation keeps original pixels before erasure`() = runBlocking {
        val s = scene()
        val region = s.regions.single()
        region.lines.single().text = "A long conversation needs enough space to remain readable. ".repeat(8)
        region.translatedText = (0 until 150).joinToString("") { (0x4e00 + it).toChar().toString() }
        assertEquals("Fixture must pass response validation so this tests layout rejection", null,
            EnglishTranslationGuard.validate(region.sourceText, region.translatedText))
        var calls = 0
        val result = EnglishImagePipeline.finishWithReconstruction(s.original, s.detector, s.regions,
            reconstructor = { _, _, _ -> calls++; error("Unfittable text reached model") },
            residualOcr = { error("Nothing should have been erased") })
        assertTrue(result.accepted.isEmpty())
        assertTrue(result.layouts.single().overflow)
        assertEquals(0, calls)
        assertTrue(s.original.sameAs(result.cleaned))
        assertTrue(s.original.sameAs(result.final))
    }

    @Test
    fun `model changes outside accepted strokes never reach the cleaned image`() = runBlocking {
        val s = scene(artwork = true)
        var calls = 0
        val result = EnglishImagePipeline.finishWithReconstruction(s.original, s.detector, s.regions,
            reconstructor = { crop, _, _ ->
                calls++
                crop.copy(Bitmap.Config.ARGB_8888, true).apply { eraseColor(Color.MAGENTA) }
            }, residualOcr = { emptyList() })
        assertEquals(result.failures.toString(), listOf(0), result.accepted)
        assertEquals(1, calls)
        assertEquals(EnglishRegionType.PLAIN_ART, result.routes.getValue(0).type)
        var changed = 0
        for (y in 0 until s.original.height) for (x in 0 until s.original.width) {
            if (s.original.getPixel(x, y) == result.cleaned.getPixel(x, y)) continue
            assertTrue("Model changed an unowned pixel ($x,$y)", Color.red(result.mask.getPixel(x, y)) > 0)
            changed++
        }
        assertTrue(changed > 0)
    }

    @Test
    fun `model exception preserves every original pixel and reports failed region`() = runBlocking {
        val s = scene(artwork = true)
        val result = EnglishImagePipeline.finishWithReconstruction(s.original, s.detector, s.regions,
            reconstructor = { _, _, _ -> throw IllegalStateException("Synthetic model failure") },
            residualOcr = { emptyList() })
        assertTrue(result.accepted.isEmpty())
        assertTrue(result.failures.containsKey(0))
        assertTrue(s.original.sameAs(result.cleaned))
        assertTrue(s.original.sameAs(result.final))
    }

    @Test
    fun `residual English restores failed bubble exactly without undoing successful neighbour`() = runBlocking {
        val s = scene(count = 2)
        val result = EnglishImagePipeline.finishWithReconstruction(s.original, s.detector, s.regions,
            reconstructor = { _, _, _ -> error("No model required") },
            residualOcr = { listOf(s.regions[0].lines.single()) })
        assertEquals(result.failures.toString(), listOf(1), result.accepted)
        assertTrue(result.failures.containsKey(0))
        for (y in 0 until s.original.height) for (x in 0 until 160) {
            assertEquals("Failed bubble was not restored in cleaned image", s.original.getPixel(x, y), result.cleaned.getPixel(x, y))
            assertEquals("Failed bubble was not restored in final image", s.original.getPixel(x, y), result.final.getPixel(x, y))
        }
        for (y in 50 until 66) for (x in listOf(213, 227, 241, 255)) {
            assertEquals("Neighbour erasure must remain committed", white, result.cleaned.getPixel(x, y))
        }
        assertFalse(result.cleaned.sameAs(result.final))
    }

    @Test
    fun `stale rendering plan rolls erasure back instead of reporting success`() = runBlocking {
        val s = scene()
        val result = EnglishImagePipeline.finishWithReconstruction(s.original, s.detector, s.regions,
            reconstructor = { _, _, _ -> error("No model required") },
            residualOcr = {
                s.regions.single().translatedText = "這段內容在排版之後變更。"
                emptyList()
            })
        assertTrue(result.accepted.isEmpty())
        assertTrue(result.failures.containsKey(0))
        assertTrue(s.original.sameAs(result.cleaned))
        assertTrue(s.original.sameAs(result.final))
    }
}
