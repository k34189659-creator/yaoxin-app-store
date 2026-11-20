package tw.dafeiyu.mangatranslator.core

import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

data class ExportProgress(
    val doneEntries: Int,
    val totalEntries: Int,
    val doneBytes: Long,
    val totalBytes: Long,
    val currentName: String,
)

data class ExportResult(
    val writtenPages: Int,
    val originalPages: Int,
    val copiedOtherFiles: Int,
    val warnings: List<String>,
)

/**
 * 匯出繁中版 ZIP：
 *  - 頁面順序、資料夾結構、檔名完全沿用原始 ZIP（只替換已成功的圖片條目）。
 *  - 失敗/略過/未處理的頁面直接複製原始壓縮條目（不重新編碼，畫質零損失）。
 *  - 非圖片檔（txt、json、封面說明…）原樣保留。
 *  - 原 ZIP 只讀不寫；輸出由呼叫端提供（SAF 新檔或本機檔）。
 */
object MangaExporter {
    fun export(
        projectDir: File,
        project: Project,
        output: OutputStream,
        onProgress: (ExportProgress) -> Unit = {},
    ): ExportResult {
        val source = File(projectDir, project.sourceZipName)
        if (!source.isFile) throw IllegalStateException("找不到原始 ZIP：${source.name}")
        val pageByPath = project.pages.associateBy { it.zipPath }
        val warnings = ArrayList<String>()
        var writtenPages = 0
        var originalPages = 0
        var copiedOthers = 0

        ZipFile(source).use { zf ->
            val entries = zf.entries().toList().filter { !it.isDirectory }
            // 同名條目只取第一個（ZIP 可以有重複名稱；輸出必須唯一）
            val byName = LinkedHashMap<String, java.util.zip.ZipEntry>()
            for (e in entries) {
                val n = normalizeName(e.name)
                if (!byName.containsKey(n)) byName[n] = e
            }
            // 輸出順序＝App 的自然頁序（不是原始中央目錄順序）；非圖片檔接在後面。
            // 這樣即使來源 ZIP 的條目順序是亂的，輸出仍然是 1,2,3,10 的閱讀順序。
            val pagePaths = project.pages.mapTo(HashSet()) { it.zipPath }
            val ordered = ArrayList<String>(entries.size)
            for (p in project.pages) if (byName.containsKey(p.zipPath)) ordered.add(p.zipPath)
            for (e in entries) {
                val n = normalizeName(e.name)
                if (n !in pagePaths) ordered.add(n)
            }
            val totalBytes = entries.sumOf { it.size.coerceAtLeast(0) }
            var doneBytes = 0L
            var doneEntries = 0
            ZipOutputStream(output.buffered(1 shl 16)).use { out ->
                out.setLevel(6)
                val written = HashSet<String>()
                for (name in ordered) {
                    val e = byName[name] ?: continue
                    if (name in written) {
                        warnings.add("重複條目已略過：$name")
                        continue
                    }
                    written.add(name)
                    val page = pageByPath[name]
                    val useTranslated = page != null &&
                        (page.status == PageStatus.DONE || page.status == PageStatus.PARTIAL) &&
                        page.translatedFile?.let { File(projectDir, it).isFile } == true
                    // 轉檔（PNG/無損 WebP/JPEG97）時副檔名要跟內容一致，
                    // 否則讀者可能用錯解碼器；其他情況完全沿用原條目名。
                    val outputName = if (useTranslated) {
                        val actualExt = File(projectDir, page!!.translatedFile!!).extension.lowercase()
                        val origExt = name.substringAfterLast('.', "").lowercase()
                        if (actualExt.isNotEmpty() && actualExt != origExt) {
                            if (name.contains('.')) name.substringBeforeLast('.') + "." + actualExt
                            else "$name.$actualExt"
                        } else name
                    } else name
                    if (outputName != name) warnings.add("格式轉換：$name → $outputName")
                    val outEntry = ZipEntry(outputName)
                    outEntry.time = e.time.takeIf { it > 0 } ?: System.currentTimeMillis()
                    out.putNextEntry(outEntry)
                    if (useTranslated) {
                        val f = File(projectDir, page!!.translatedFile!!)
                        f.inputStream().use { it.copyTo(out, 1 shl 16) }
                        writtenPages++
                    } else {
                        if (page != null) {
                            originalPages++
                            if (page.status == PageStatus.DONE || page.status == PageStatus.PARTIAL) {
                                warnings.add("已標記完成但找不到譯圖，保留原圖：$name")
                            }
                        } else {
                            copiedOthers++
                        }
                        zf.getInputStream(e).use { it.copyTo(out, 1 shl 16) }
                    }
                    out.closeEntry()
                    doneEntries++
                    doneBytes += e.size.coerceAtLeast(0)
                    onProgress(ExportProgress(doneEntries, entries.size, doneBytes, totalBytes, name))
                }
            }
        }
        return ExportResult(writtenPages, originalPages, copiedOthers, warnings)
    }

    /** 與 ZipImporter 相同的名稱正規化：解壓時用 `\`/`./`/BOM 正規化過的相對路徑，匯出時要能對上。 */
    private fun normalizeName(raw: String): String {
        var name = raw.removePrefix("\uFEFF").replace('\\', '/')
        while (name.startsWith("./")) name = name.substring(2)
        return name
    }
}
