package tw.dafeiyu.mangatranslator.core

import li.joye.yakuyomi.engine.RegionOverride
import li.joye.yakuyomi.engine.TextOrientation
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DiagCodecTest {
    private fun tempDir(): File = Files.createTempDirectory("yaoxin-diag").toFile()

    private fun page() = PageEntry(3, "ch1/4.jpg", "jpg", 800, 1200, metaFile = "meta/000003.json")

    @Test
    fun `diag roundtrip keeps stages regions cache and overrides`() {
        val root = tempDir()
        val p = Project("p1", "test", "source.zip", 1)
        val dir = File(root, p.id).apply { mkdirs() }
        val diag = PageDiag(
            pageIndex = 3, createdAt = 123, status = "PARTIAL", qualityMode = "BALANCED",
            detectionPasses = 3, addedLines = 2, ocrRemedyCount = 1,
            detectMs = 100, ocrMs = 200, translateMs = 300, inpaintMs = 400, renderMs = 500, wallMs = 900,
            lines = 10, regions = 4, kept = 2, promptTokens = 50, completionTokens = 20, costUsd = 0.001,
            errors = listOf("x"), warnings = listOf("w"), apiError = "HTTP 500",
            regionDiag = listOf(
                RegionDiag(0, 1f, 2f, 10f, 20f, "v", "原文", "譯文", 0.91f, "pad+8",
                    "ok", null, "fill", 24, false, null),
                RegionDiag(1, 3f, 4f, 11f, 21f, "h", "原文2", "", 0.2f, "exact",
                    "failed", "空譯文", "aot", 0, true, "超出", kind = "彩色框內", eraseSafe = false),
            ),
            cache = mapOf("原文" to "譯文"), overrides = mapOf(1 to RegionOverride(2f, 3f, 1.2f, TextOrientation.HORIZONTAL)),
            inpaintMethod = "smart",
            stageStatus = mapOf("detect" to "ok", "translate" to "partial"),
        )
        DiagCodec.save(dir, page(), diag)
        val loaded = DiagCodec.load(dir, page())
        assertNotNull(loaded)
        assertEquals("PARTIAL", loaded!!.status)
        assertEquals(3, loaded.detectionPasses)
        assertEquals(4, loaded.regions)
        assertEquals("smart", loaded.inpaintMethod)
        assertEquals("partial", loaded.stageStatus["translate"])
        assertEquals("HTTP 500", loaded.apiError)
        assertEquals(2, loaded.regionDiag.size)
        assertEquals("pad+8", loaded.regionDiag[0].ocrStrategy)
        assertEquals("failed", loaded.regionDiag[1].translationStatus)
        assertTrue(loaded.regionDiag[1].layoutOverflow)
        assertEquals("彩色框內", loaded.regionDiag[1].kind)
        assertEquals(false, loaded.regionDiag[1].eraseSafe)
        assertEquals("譯文", loaded.cache["原文"])
        assertEquals(2f, loaded.overrides[1]!!.dx, 0.001f)
        assertEquals(TextOrientation.HORIZONTAL, loaded.overrides[1]!!.orientation)
    }

    @Test
    fun `cacheFromMeta reads source lines and translated text`() {
        val root = tempDir()
        val p = Project("p1", "test", "source.zip", 1)
        val dir = File(root, p.id).apply { mkdirs(); File(this, "meta").mkdirs() }
        val meta = JSONObject().put("regions", JSONArray().put(
            JSONObject()
                .put("lines", JSONArray().put(JSONObject().put("text", "あ")).put(JSONObject().put("text", "い")))
                .put("translatedText", "甲乙")
        ))
        File(dir, "meta/000003.json").writeText(meta.toString(), Charsets.UTF_8)
        val cache = DiagCodec.cacheFromMeta(dir, page())
        assertEquals("甲乙", cache["あい"])
    }
}
