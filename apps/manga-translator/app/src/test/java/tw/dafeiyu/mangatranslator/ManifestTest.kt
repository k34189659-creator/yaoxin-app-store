package tw.dafeiyu.mangatranslator

import li.joye.yakuyomi.engine.ModelSet
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ManifestTest {
    @Test
    fun `bundled manifest has exactly the seven translation files`() {
        val text = File("src/main/assets/models.json").readText(Charsets.UTF_8)
        val root = JSONObject(text)
        val arr = root.getJSONArray("models")
        val roles = HashMap<String, Int>()
        var total = 0L
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            roles[o.getString("role")] = (roles[o.getString("role")] ?: 0) + 1
            val sha = o.getString("sha256")
            assertEquals(64, sha.length)
            assertTrue(o.getString("url").startsWith("https://"))
            if (o.getString("role") != "charseg") total += o.getLong("size")
        }
        assertEquals(2, roles["detector"])
        assertEquals(3, roles["ocr"])
        assertEquals(2, roles["inpainter"])
        assertEquals(7, (roles["detector"] ?: 0) + (roles["ocr"] ?: 0) + (roles["inpainter"] ?: 0))
        assertTrue(
            "翻譯模型總大小應約 247MB，實際 $total",
            total in (200L * 1024 * 1024)..(300L * 1024 * 1024),
        )
        assertEquals("manifest 應仍包含夜讀模型（本 App 會過濾，不下載）", 4, roles["charseg"])
    }

    @Test
    fun `model set resolves three required roles`() {
        val files = listOf(
            "dbnet_detect.ncnn.param" to "/m/dbnet_detect.ncnn.param",
            "ocr_48px_ctc.ncnn.param" to "/m/ocr_48px_ctc.ncnn.param",
            "ocr_48px_ctc_mixed.ncnn.param" to "/m/ocr_48px_ctc_mixed.ncnn.param",
            "mit_aot_fixed512.ncnn.param" to "/m/mit_aot_fixed512.ncnn.param",
        )
        val set = ModelSet.resolve(files)
        assertNotNull(set)
        assertEquals("/m/ocr_48px_ctc.ncnn.param", set!!.ocr)
        assertEquals("/m/dbnet_detect.ncnn.param", set.detectorNcnn)
        assertEquals("/m/mit_aot_fixed512.ncnn.param", set.aotInpainterNcnn)
    }
}
