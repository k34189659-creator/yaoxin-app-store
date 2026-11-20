package tw.dafeiyu.mangatranslator.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PatchCodecTest {
    @Test
    fun `manual patch metadata roundtrips`() {
        val root = Files.createTempDirectory("yaoxin-patch").toFile()
        val p = Project("p1", "test", "source.zip", 1)
        val dir = File(root, p.id).apply { mkdirs() }
        val page = PageEntry(2, "1.jpg", "jpg", 100, 200)
        val patches = listOf(
            ManualPatch("a", 2, 10f, 20f, 30f, 40f, "原文", "譯文", "meta/000002.patch.a.png", "applied", 123, renderRevision = 1),
            ManualPatch("b", 2, 50f, 60f, 70f, 80f, "原文2", "譯文2", "meta/000002.patch.b.png", "ocr_ok", 456),
        )
        PatchCodec.save(dir, page, patches)
        val loaded = PatchCodec.load(dir, page)
        assertEquals(2, loaded.size)
        assertEquals("原文", loaded[0].source)
        assertEquals("譯文2", loaded[1].translated)
        assertEquals(30f, loaded[0].w, 0.001f)
        assertEquals("ocr_ok", loaded[1].status)
        assertEquals(1, loaded[0].renderRevision)
        assertEquals(0, loaded[1].renderRevision)
    }

    @Test
    fun `empty patches load as empty list`() {
        val root = Files.createTempDirectory("yaoxin-patch2").toFile()
        val page = PageEntry(0, "1.jpg", "jpg", 1, 1)
        val p = Project("p2", "test", "source.zip", 1)
        val dir = File(root, p.id).apply { mkdirs() }
        assertTrue(PatchCodec.load(dir, page).isEmpty())
    }

    @Test
    fun `legacy patch json remains loadable and is marked as unverified`() {
        val directory = Files.createTempDirectory("yaoxin-old-patch").toFile()
        val page = PageEntry(0, "page.jpg", "jpg", 100, 200)
        val file = PatchCodec.file(directory, page)
        file.parentFile!!.mkdirs()
        file.writeText("""[{"id":"legacy","source":"Hello.","translated":"你好。","status":"applied","patchFile":"old.png"}]""")
        val loaded = PatchCodec.load(directory, page).single()
        assertEquals("legacy", loaded.id)
        assertEquals("你好。", loaded.translated)
        assertEquals(0, loaded.renderRevision)
    }
}
