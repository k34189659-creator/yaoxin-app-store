package tw.dafeiyu.mangatranslator.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class ZipPipelineTest {
    private fun tempDir(): File = Files.createTempDirectory("yaoxin-test").toFile()

    private fun buildZip(file: File, entries: List<Pair<String, ByteArray>>) {
        ZipOutputStream(file.outputStream()).use { out ->
            entries.forEach { (name, bytes) ->
                out.putNextEntry(ZipEntry(name))
                out.write(bytes)
                out.closeEntry()
            }
        }
    }

    @Test
    fun `import preserves natural page order and folder structure`() {
        val root = tempDir()
        val zip = File(root, "manga.zip")
        buildZip(
            zip,
            listOf(
                "ch1/10.jpg" to byteArrayOf(1),
                "ch1/2.jpg" to byteArrayOf(2),
                "ch1/1.jpg" to byteArrayOf(3),
                "ch2/1.jpg" to byteArrayOf(4),
                "ch1/3.PNG" to byteArrayOf(5),
                "ch1/4.webp" to byteArrayOf(6),
                "ch1/readme.txt" to "hello".toByteArray(),
                "__MACOSX/._ch1.jpg" to byteArrayOf(7),
                ".DS_Store" to byteArrayOf(8),
            ),
        )
        val projects = File(root, "projects").apply { mkdirs() }
        val dir = File(projects, "p_test").apply { mkdirs() }
        zip.copyTo(File(dir, "source.zip"))
        val p = ZipImporter.import(File(dir, "source.zip"), dir, "測試漫畫", sizeOf = { 800 to 1200 })

        assertEquals(
            listOf("ch1/1.jpg", "ch1/2.jpg", "ch1/3.PNG", "ch1/4.webp", "ch1/10.jpg", "ch2/1.jpg"),
            p.pages.map { it.zipPath },
        )
        assertEquals(listOf("jpg", "jpg", "png", "webp", "jpg", "jpg"), p.pages.map { it.ext })
        assertEquals(PageStatus.PENDING, p.pages[0].status)
        assertTrue(File(dir, "original/000000.jpg").isFile)
        assertTrue(File(dir, "project.json").isFile)
        assertNotNull(ProjectStore(projects).load(p.id))
        assertFalse(p.pages.any { it.zipPath.endsWith(".txt") || it.zipPath.contains("MACOSX") })
    }

    @Test
    fun `rejects path traversal`() {
        val root = tempDir()
        val zip = File(root, "bad.zip")
        buildZip(zip, listOf("../evil.jpg" to byteArrayOf(1)))
        val dir = File(root, "projects/p_bad").apply { mkdirs() }
        val copy = File(dir, "source.zip")
        zip.copyTo(copy)
        try {
            ZipImporter.import(copy, dir, "bad", sizeOf = { 10 to 10 })
            fail("應該拒絕路徑穿越")
        } catch (e: ImportException) {
            assertTrue(e.message!!.contains("穿越"))
        }
        assertFalse(File(root, "evil.jpg").exists())
    }

    @Test
    fun `rejects absolute paths`() {
        val root = tempDir()
        val zip = File(root, "abs.zip")
        buildZip(zip, listOf("/abs.jpg" to byteArrayOf(1)))
        val dir = File(root, "projects/p_abs").apply { mkdirs() }
        val copy = File(dir, "source.zip")
        zip.copyTo(copy)
        try {
            ZipImporter.import(copy, dir, "abs", sizeOf = { 10 to 10 })
            fail("應該拒絕絕對路徑")
        } catch (e: ImportException) {
            assertTrue(e.message!!.contains("絕對路徑"))
        }
    }

    @Test
    fun `rejects zip without supported images`() {
        val root = tempDir()
        val zip = File(root, "text.zip")
        buildZip(zip, listOf("readme.txt" to "no images".toByteArray()))
        val dir = File(root, "projects/p_text").apply { mkdirs() }
        val copy = File(dir, "source.zip")
        zip.copyTo(copy)
        try {
            ZipImporter.import(copy, dir, "text", sizeOf = { 10 to 10 })
            fail("應該拒絕沒有圖片的 ZIP")
        } catch (e: ImportException) {
            assertTrue(e.message!!.contains("沒有任何"))
        }
    }

    @Test
    fun `project json round trips statuses stats and unicode`() {
        val root = tempDir()
        val store = ProjectStore(root)
        val p = Project(
            id = "p_round", name = "中文/日文 專案", sourceZipName = "source.zip",
            createdAt = 123, updatedAt = 456, status = ProjectStatus.PAUSED,
            providerId = "venice", modelId = "venice-uncensored-1-2",
            inpaint = "aot", orientation = "AUTO",
            totalPromptTokens = 10, totalCompletionTokens = 20, totalCostUsd = 0.001,
            pages = mutableListOf(
                PageEntry(
                    0, "第1話/1.jpg", "jpg", 800, 1200, status = PageStatus.DONE,
                    translatedFile = "translated/000000.jpg", metaFile = "meta/000000.json",
                    maskFile = "meta/000000.mask.png", regionCount = 3, translatedRegions = 3,
                    stats = PageStatsInfo(lines = 3, regions = 2, kept = 2, wallMs = 1234),
                ),
                PageEntry(1, "第1話/2.jpg", "jpg", 800, 1200, status = PageStatus.FAILED, error = "HTTP 500"),
            ),
        )
        store.save(p)
        val loaded = store.load("p_round")!!
        assertEquals(p.name, loaded.name)
        assertEquals(ProjectStatus.PAUSED, loaded.status)
        assertEquals(2, loaded.pages.size)
        assertEquals(PageStatus.DONE, loaded.pages[0].status)
        assertEquals("meta/000000.mask.png", loaded.pages[0].maskFile)
        assertEquals(1234, loaded.pages[0].stats!!.wallMs)
        assertEquals("HTTP 500", loaded.pages[1].error)
        assertEquals(0.001, loaded.totalCostUsd, 0.0000001)
    }

    @Test
    fun `partial pages export their translated image`() {
        val root = tempDir()
        val dir = File(root, "p_partial").apply { mkdirs() }
        val source = File(dir, "source.zip")
        buildZip(source, listOf("1.jpg" to byteArrayOf(1, 2, 3)))
        File(dir, "translated").mkdirs()
        File(dir, "translated/000000.jpg").writeBytes(byteArrayOf(9, 9))
        val p = Project(
            id = "p_partial", name = "x", sourceZipName = "source.zip", createdAt = 1,
            pages = mutableListOf(
                PageEntry(0, "1.jpg", "jpg", 1, 1, status = PageStatus.PARTIAL, translatedFile = "translated/000000.jpg"),
            ),
        )
        assertEquals(1, p.partialCount)
        assertEquals(1, p.processedCount)
        val out = ByteArrayOutputStream()
        val result = MangaExporter.export(dir, p, out)
        assertEquals(1, result.writtenPages)
        assertEquals(0, result.originalPages)
        val zip = File(root, "partial-out.zip")
        zip.writeBytes(out.toByteArray())
        ZipFile(zip).use { zf ->
            assertTrue(zf.getInputStream(zf.getEntry("1.jpg")).readBytes().contentEquals(byteArrayOf(9, 9)))
        }
    }

    fun `export replaces only done pages and keeps failed skipped original bytes`() {
        val root = tempDir()
        val dir = File(root, "p_export").apply { mkdirs() }
        val source = File(dir, "source.zip")
        buildZip(
            source,
            listOf(
                "ch1/1.jpg" to byteArrayOf(11, 12, 13),
                "ch1/2.jpg" to byteArrayOf(21, 22, 23),
                "ch1/3.jpg" to byteArrayOf(31, 32, 33),
                "readme.txt" to "hello".toByteArray(),
            ),
        )
        File(dir, "translated").mkdirs()
        File(dir, "translated/000000.jpg").writeBytes(byteArrayOf(91, 92, 93, 94))
        val project = Project(
            id = "p_export", name = "匯出測試", sourceZipName = "source.zip", createdAt = 1,
            pages = mutableListOf(
                PageEntry(0, "ch1/1.jpg", "jpg", 1, 1, status = PageStatus.DONE, translatedFile = "translated/000000.jpg"),
                PageEntry(1, "ch1/2.jpg", "jpg", 1, 1, status = PageStatus.FAILED, error = "x"),
                PageEntry(2, "ch1/3.jpg", "jpg", 1, 1, status = PageStatus.SKIPPED),
            ),
        )
        val before = source.readBytes()
        val out = ByteArrayOutputStream()
        val result = MangaExporter.export(dir, project, out)
        assertEquals(1, result.writtenPages)
        assertEquals(2, result.originalPages)
        assertEquals(1, result.copiedOtherFiles)
        assertTrue(result.warnings.isEmpty())
        assertTrue(before.contentEquals(source.readBytes()))

        val zip = File(root, "out.zip")
        zip.writeBytes(out.toByteArray())
        ZipFile(zip).use { zf ->
            val names = zf.entries().toList().map { it.name }
            assertEquals(listOf("ch1/1.jpg", "ch1/2.jpg", "ch1/3.jpg", "readme.txt"), names)
            assertTrue(zf.getInputStream(zf.getEntry("ch1/1.jpg")).readBytes().contentEquals(byteArrayOf(91, 92, 93, 94)))
            assertTrue(zf.getInputStream(zf.getEntry("ch1/2.jpg")).readBytes().contentEquals(byteArrayOf(21, 22, 23)))
            assertTrue(zf.getInputStream(zf.getEntry("ch1/3.jpg")).readBytes().contentEquals(byteArrayOf(31, 32, 33)))
            assertEquals("hello", zf.getInputStream(zf.getEntry("readme.txt")).bufferedReader().readText())
        }
    }


    @Test
    fun `chinese and japanese file names survive import and export`() {
        val root = tempDir()
        val dir = File(root, "p_unicode").apply { mkdirs() }
        val source = File(dir, "source.zip")
        buildZip(
            source,
            listOf(
                "第一話/01.jpg" to byteArrayOf(1),
                "第一話/02.jpg" to byteArrayOf(2),
                "第二話/01.jpg" to byteArrayOf(3),
                "説明.txt" to "ok".toByteArray(),
            ),
        )
        val p = ZipImporter.import(source, dir, "中文專案", sizeOf = { 10 to 10 })
        assertEquals(listOf("第一話/01.jpg", "第一話/02.jpg", "第二話/01.jpg"), p.pages.map { it.zipPath })
        File(dir, "translated").mkdirs()
        File(dir, "translated/000000.jpg").writeBytes(byteArrayOf(9))
        p.pages[0].status = PageStatus.DONE
        p.pages[0].translatedFile = "translated/000000.jpg"
        val out = ByteArrayOutputStream()
        MangaExporter.export(dir, p, out)
        val zip = File(root, "unicode-out.zip")
        zip.writeBytes(out.toByteArray())
        ZipFile(zip).use { zf ->
            val names = zf.entries().toList().map { it.name }
            assertEquals(listOf("第一話/01.jpg", "第一話/02.jpg", "第二話/01.jpg", "説明.txt"), names)
            assertTrue(zf.getInputStream(zf.getEntry("第一話/01.jpg")).readBytes().contentEquals(byteArrayOf(9)))
            assertEquals("ok", zf.getInputStream(zf.getEntry("説明.txt")).bufferedReader().readText())
        }
    }

    @Test
    fun `list resets stale running pages to pending`() {
        val root = tempDir()
        val store = ProjectStore(root)
        val p = Project(
            id = "p_resume", name = "resume", sourceZipName = "source.zip", createdAt = 1,
            status = ProjectStatus.RUNNING,
            pages = mutableListOf(
                PageEntry(0, "1.jpg", "jpg", 1, 1, status = PageStatus.DONE),
                PageEntry(1, "2.jpg", "jpg", 1, 1, status = PageStatus.RUNNING),
            ),
        )
        store.save(p)
        val summaries = store.list()
        assertEquals(1, summaries.size)
        val loaded = store.load("p_resume")!!
        assertEquals(ProjectStatus.PAUSED, loaded.status)
        assertEquals(PageStatus.PENDING, loaded.pages[1].status)
        assertTrue(true)
    }


    @Test
    fun `export follows natural page order even when source zip entries are scrambled`() {
        val root = tempDir()
        val dir = File(root, "p_scramble").apply { mkdirs() }
        val source = File(dir, "source.zip")
        // 故意亂序：10, 2, 1
        buildZip(
            source,
            listOf(
                "ch1/10.jpg" to byteArrayOf(10),
                "ch1/2.jpg" to byteArrayOf(2),
                "ch1/1.jpg" to byteArrayOf(1),
                "readme.txt" to "r".toByteArray(),
            ),
        )
        val project = Project(
            id = "p_scramble", name = "x", sourceZipName = "source.zip", createdAt = 1,
            pages = mutableListOf(
                PageEntry(0, "ch1/1.jpg", "jpg", 1, 1),
                PageEntry(1, "ch1/2.jpg", "jpg", 1, 1),
                PageEntry(2, "ch1/10.jpg", "jpg", 1, 1),
            ),
        )
        val out = ByteArrayOutputStream()
        MangaExporter.export(dir, project, out)
        val zip = File(root, "scramble-out.zip")
        zip.writeBytes(out.toByteArray())
        ZipFile(zip).use { zf ->
            assertEquals(
                listOf("ch1/1.jpg", "ch1/2.jpg", "ch1/10.jpg", "readme.txt"),
                zf.entries().toList().map { it.name },
            )
        }
    }

    @Test
    fun `export warns and keeps original when translated file is missing`() {
        val root = tempDir()
        val dir = File(root, "p_missing").apply { mkdirs() }
        val source = File(dir, "source.zip")
        buildZip(source, listOf("1.jpg" to byteArrayOf(1, 2)))
        val project = Project(
            id = "p_missing", name = "x", sourceZipName = "source.zip", createdAt = 1,
            pages = mutableListOf(
                PageEntry(0, "1.jpg", "jpg", 1, 1, status = PageStatus.DONE, translatedFile = "translated/000000.jpg"),
            ),
        )
        val out = ByteArrayOutputStream()
        val result = MangaExporter.export(dir, project, out)
        assertEquals(0, result.writtenPages)
        assertEquals(1, result.originalPages)
        assertTrue(result.warnings.any { it.contains("找不到譯圖") })
    }
}
