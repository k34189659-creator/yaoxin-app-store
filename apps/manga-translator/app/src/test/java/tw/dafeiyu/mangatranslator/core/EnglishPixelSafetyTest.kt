package tw.dafeiyu.mangatranslator.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Neutral pixel fixtures: the border, background and accepted edit area have known ground truth. */
class EnglishPixelSafetyTest {
    private val width = 128
    private val height = 100
    private val black = rgb(15, 15, 15)
    private val white = rgb(247, 247, 247)

    private fun rgb(r: Int, g: Int, b: Int) = (0xff shl 24) or (r shl 16) or (g shl 8) or b
    private fun rect(l: Int, t: Int, r: Int, b: Int) = listOf(
        EnglishPixelSafety.Point(l.toFloat(), t.toFloat()), EnglishPixelSafety.Point(r.toFloat(), t.toFloat()),
        EnglishPixelSafety.Point(r.toFloat(), b.toFloat()), EnglishPixelSafety.Point(l.toFloat(), b.toFloat()))
    private fun fill(pixels: IntArray, l: Int, t: Int, r: Int, b: Int, color: Int) {
        for (y in t until b) for (x in l until r) pixels[y * width + x] = color
    }

    private data class Scene(val original: IntArray, val clean: IntArray, val detector: IntArray,
        val polygons: List<List<EnglishPixelSafety.Point>>)

    private fun scene(background: Int = white, coloredGlyph: Boolean = false): Scene {
        val clean = IntArray(width * height) { rgb(102, 118, 148) }
        fill(clean, 9, 9, 119, 91, black)
        fill(clean, 12, 12, 116, 88, background)
        val original = clean.copyOf()
        val detector = IntArray(original.size)
        val color = if (coloredGlyph) rgb(220, 30, 55) else black
        for (x in listOf(36, 48, 60, 72)) {
            fill(original, x, 32, x + 5, 48, color)
            fill(detector, x, 32, x + 5, 48, -1)
        }
        return Scene(original, clean, detector, listOf(rect(32, 28, 83, 52)))
    }

    private fun prepare(scene: Scene) = EnglishPixelSafety.prepare(scene.original, scene.detector,
        width, height, scene.polygons)

    private fun assertOutsideMaskUnchanged(original: IntArray, output: IntArray, mask: BooleanArray) {
        for (i in original.indices) if (!mask[i]) assertEquals("Pixel $i outside the accepted edit mask changed", original[i], output[i])
    }

    @Test fun whiteBubbleRemovesGlyphsWithoutTouchingFrameOrOtherPixels() {
        val scene = scene()
        val plan = prepare(scene)
        assertNull(plan.failure)
        assertTrue(plan.framed)
        val output = EnglishPixelSafety.smoothFill(scene.original, plan)
        assertNotNull(output)
        output!!
        assertOutsideMaskUnchanged(scene.original, output, plan.edit)
        for (y in 32 until 48) for (x in listOf(37, 49, 61, 73)) {
            assertEquals("Original letter should be removed", white, output[y * width + x])
        }
        for (x in 9 until 119) {
            assertEquals(black, output[9 * width + x])
            assertFalse(plan.edit[9 * width + x])
        }
        val bounds = plan.layout!!
        for (y in bounds.top until bounds.bottom) for (x in bounds.left until bounds.right) {
            assertTrue("Layout must be wholly inside the verified interior", plan.interior!![y * width + x])
        }
    }

    @Test fun greenBubbleKeepsItsExactColorAndBorder() {
        val green = rgb(113, 222, 132)
        val scene = scene(green)
        val plan = prepare(scene)
        assertNull(plan.failure)
        assertTrue(plan.framed)
        assertFalse(plan.coloredInk)
        val output = EnglishPixelSafety.smoothFill(scene.original, plan)!!
        assertOutsideMaskUnchanged(scene.original, output, plan.edit)
        for (i in output.indices) if (plan.edit[i]) assertEquals(green, output[i])
    }

    @Test fun coloredLettersAreDetectedIndependentlyOfColoredBubble() {
        val scene = scene(rgb(113, 222, 132), coloredGlyph = true)
        val plan = prepare(scene)
        assertNull(plan.failure)
        assertTrue(plan.framed)
        assertTrue(plan.coloredInk)
    }

    @Test fun ordinaryOpenWhitePageIsNotMisclassifiedAsEnclosedBubble() {
        val p = IntArray(width * height) { white }
        val detector = IntArray(p.size)
        for (x in listOf(35, 49, 63)) {
            fill(p, x, 35, x + 4, 47, black)
            fill(detector, x, 35, x + 4, 47, -1)
        }
        val plan = EnglishPixelSafety.prepare(p, detector, width, height, listOf(rect(31, 31, 73, 51)))
        assertNull(plan.failure)
        assertFalse(plan.framed)
        assertNull(EnglishPixelSafety.smoothFill(p, plan))
    }

    @Test fun diagonalTexturedArtworkUsesOnlyAcceptedMaskWhenCompositing() {
        val clean = IntArray(width * height) { i ->
            val x = i % width; val y = i / width
            val grain = if ((x + y) % 3 == 0) 5 else -4
            if (x < y) rgb(225 + grain, 204 + grain, 169 + grain) else rgb(45 + grain, 103 + grain, 181 + grain)
        }
        val p = clean.copyOf()
        val detector = IntArray(p.size)
        for (x in listOf(37, 51, 65)) {
            fill(p, x, 38, x + 4, 50, black)
            fill(detector, x, 38, x + 4, 50, -1)
        }
        val plan = EnglishPixelSafety.prepare(p, detector, width, height, listOf(rect(32, 33, 74, 55)))
        assertNull(plan.failure)
        assertFalse(plan.framed)
        assertNull(EnglishPixelSafety.smoothFill(p, plan))
        // Simulate an inpainting model changing its entire crop. Only the accepted glyph mask may commit.
        val candidate = IntArray(p.size) { rgb(251, 20, 190) }
        val output = EnglishPixelSafety.composite(p, candidate, plan.edit)
        assertOutsideMaskUnchanged(p, output, plan.edit)
        assertTrue(plan.edit.any { it })
        assertTrue(plan.edit.indices.filter { plan.edit[it] }.all { output[it] == candidate[it] })
    }

    @Test fun detectorCrossingAFrameIsRejectedAndFrameNeverEntersEditMask() {
        val scene = scene()
        // A detector component follows the real frame; one OCR rectangle accidentally intersects it.
        for (y in 9 until 91) for (x in 9 until 119) {
            if (x < 12 || x >= 116 || y < 12 || y >= 88) scene.detector[y * width + x] = -1
        }
        val plan = EnglishPixelSafety.prepare(scene.original, scene.detector, width, height,
            listOf(rect(6, 30, 30, 55)))
        assertNotNull(plan.failure)
        for (y in 9 until 91) for (x in 9 until 119) {
            if (x < 12 || x >= 116 || y < 12 || y >= 88) assertFalse(plan.edit[y * width + x])
        }
    }

    @Test fun detectorClippingCannotTurnARealBubbleBorderIntoALetter() {
        val scene = scene()
        val detector = IntArray(scene.original.size)
        // The real border continues from y=9 to y=90, but the detector returned only this fragment.
        fill(detector, 9, 33, 12, 50, -1)
        val plan = EnglishPixelSafety.prepare(scene.original, detector, width, height,
            listOf(rect(6, 29, 31, 55)))
        assertNotNull("A truncated detector fragment must not authorize erasing the real contour", plan.failure)
        for (y in 33 until 50) for (x in 9 until 12) {
            assertFalse("Real bubble border must not enter the edit mask", plan.edit[y * width + x])
        }
    }

    @Test fun emptyDetectorOnOpenArtworkCannotClaimSuccessfulErasure() {
        val p = IntArray(width * height) { white }
        val plan = EnglishPixelSafety.prepare(p, IntArray(p.size), width, height, listOf(rect(30, 30, 80, 55)))
        assertNotNull(plan.failure)
        assertFalse(plan.ink.any { it })
        assertFalse(plan.edit.any { it })
        assertFalse(EnglishPixelSafety.visiblyErased(p, p.copyOf(), plan.ink))
    }

    @Test fun boldGlyphIsNotDiscardedJustBecauseItIsLargerThanThinStrokes() {
        val scene = scene()
        val original = scene.clean.copyOf()
        val detector = IntArray(original.size)
        fill(original, 44, 34, 55, 48, black)
        fill(detector, 44, 34, 55, 48, -1)
        val plan = EnglishPixelSafety.prepare(original, detector, width, height, listOf(rect(37, 28, 63, 55)))
        assertNull(plan.failure)
        for (y in 34 until 48) for (x in 44 until 55) assertTrue(plan.ink[y * width + x])
    }

    @Test fun smallPatchChangeCannotPassAsWholeWordErasure() {
        val scene = scene()
        val plan = prepare(scene)
        val unchanged = scene.original.copyOf()
        assertFalse(EnglishPixelSafety.visiblyErased(scene.original, unchanged, plan.ink))
        val fewChanged = scene.original.copyOf()
        val inkPixels = plan.ink.indices.filter { plan.ink[it] }
        inkPixels.take(inkPixels.size / 5).forEach { fewChanged[it] = scene.clean[it] }
        assertFalse(EnglishPixelSafety.visiblyErased(scene.original, fewChanged, plan.ink))
        val fullyChanged = EnglishPixelSafety.smoothFill(scene.original, plan)!!
        assertTrue(EnglishPixelSafety.visiblyErased(scene.original, fullyChanged, plan.ink))
    }

    @Test fun patternedBubbleIsNotFlattenedToASinglePlane() {
        val scene = scene()
        val original = scene.clean.copyOf()
        for (y in 12 until 88) for (x in 12 until 116) {
            val shade = if ((x / 3 + y / 3) % 2 == 0) 220 else 246
            original[y * width + x] = rgb(shade, shade, shade)
        }
        for (x in listOf(36, 48, 60, 72)) fill(original, x, 32, x + 5, 48, black)
        val plan = EnglishPixelSafety.prepare(original, scene.detector, width, height, scene.polygons)
        assertTrue(plan.framed)
        assertNull("Texture should require model repair, not a flat fill", EnglishPixelSafety.smoothFill(original, plan))
    }
}
