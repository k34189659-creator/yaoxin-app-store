package tw.dafeiyu.mangatranslator.core

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import li.joye.yakuyomi.engine.Pt
import li.joye.yakuyomi.engine.RegionOverride
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
import java.io.File

/** Real Android pixel/font operations via Robolectric's native graphics runtime. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EnglishVisualFinishGraphicsTest {
    private fun image(): Bitmap = Bitmap.createBitmap(240, 180, Bitmap.Config.ARGB_8888).apply {
        eraseColor(Color.rgb(240, 236, 245))
    }

    private fun region(text: String): TextRegion {
        val line = TextLine(listOf(Pt(60f, 60f), Pt(150f, 60f), Pt(150f, 80f), Pt(60f, 80f)), 1f)
        line.text = "Hello, friend!"
        return TextRegion(listOf(line), "h").apply { translatedText = text }
    }

    @Test
    fun `abnormally long translation never paints a tiny text column`() {
        val source = image()
        val regions = listOf(0 to region("文".repeat(4096)))
        val plans = EnglishVisualFinish.plan(240, 180, regions, emptyMap(), 40)
        assertFalse(plans.single().fitted)
        val result = EnglishVisualFinish.render(source, regions, emptyMap(), 40, plans = plans)
        assertTrue("Rejected layout must leave every background pixel unchanged", source.sameAs(result.bitmap))
        assertTrue(result.layouts.single().overflow)
    }

    @Test
    fun `translation with no vertical room is not drawn or clipped`() {
        val source = image()
        val regions = listOf(0 to region("你好，世界。"))
        val bounds = mapOf(0 to RectF(40f, 40f, 170f, 45f))
        val plans = EnglishVisualFinish.plan(240, 180, regions, emptyMap(), 40, bounds = bounds)
        assertFalse(plans.single().fitted)
        val result = EnglishVisualFinish.render(source, regions, emptyMap(), 40, bounds = bounds, plans = plans)
        assertTrue(source.sameAs(result.bitmap))
    }

    @Test
    fun `fitted Chinese changes pixels only inside verified interior`() {
        val source = image()
        val regions = listOf(0 to region("你好，世界。"))
        val safe = RectF(40f, 40f, 170f, 120f)
        val bounds = mapOf(0 to safe)
        val plans = EnglishVisualFinish.plan(240, 180, regions, emptyMap(), 40, bounds = bounds)
        assertTrue(plans.single().fitted)
        val result = EnglishVisualFinish.render(source, regions, emptyMap(), 40, bounds = bounds, plans = plans)
        var changedInside = 0
        for (y in 0 until source.height) for (x in 0 until source.width) {
            if (source.getPixel(x, y) == result.bitmap.getPixel(x, y)) continue
            assertTrue("Pixel ($x,$y) escaped the verified bubble interior", safe.contains(x.toFloat(), y.toFloat()))
            changedInside++
        }
        assertTrue("Native Canvas must draw actual glyph pixels", changedInside > 20)
        assertFalse(result.layouts.single().overflow)
        System.getProperty("yaoxin.testImages")?.let { path ->
            val destination = File(path, "fitted-chinese.png")
            destination.parentFile?.mkdirs()
            destination.outputStream().use { result.bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test
    fun `render consumes the approved plan without growing the font after fitting`() {
        val source = image()
        val regions = listOf(0 to region("你好，世界。"))
        val bounds = mapOf(0 to RectF(40f, 40f, 170f, 120f))
        val plans = EnglishVisualFinish.plan(240, 180, regions, emptyMap(), 12, bounds = bounds)
        assertTrue(plans.single().fitted)
        val result = EnglishVisualFinish.render(
            source, regions, mapOf(0 to RegionOverride(fontScale = 1.4f)), 60,
            bounds = bounds, plans = plans,
        )
        assertEquals(plans.single().layout.fontSize, result.layouts.single().fontSize)
        assertEquals(plans.single().layout.box, result.layouts.single().box)
        assertFalse(result.layouts.single().overflow)
    }
}
