package tw.dafeiyu.mangatranslator.core

import android.graphics.BitmapFactory
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile
import kotlin.math.max

data class ImportProgress(
    val stage: String,
    val doneBytes: Long = 0,
    val totalBytes: Long = 0,
    val doneEntries: Int = 0,
    val totalEntries: Int = 0,
    val currentName: String = "",
)

class ImportException(message: String) : Exception(message)

/**
 * 安全解壓 + 頁序建立。
 *
 * **安全**：ZIP 路徑穿越（`../`、絕對路徑、磁碟代號）、超量檔案數、單檔/總量上限、異常壓縮比、
 * 目標 canonical path 逃逸都會被拒絕；不跟隨符號連結（ZIP 沒有）。
 *
 * **頁序**：只取 jpg/jpeg/png/webp，依「完整相對路徑」自然排序（1,2,3,10），
 * 因此 `第1話/1.jpg … 第1話/10.jpg` 之後才接 `第2話/1.jpg`，資料夾結構與使用者整理順序都保留。
 * 隱藏檔、`__MACOSX/`、`.DS_Store`、`Thumbs.db` 跳過。
 *
 * 匯入後 project.json 已落地；每頁原圖存在 `original/`，ZIP 本身留在 `source.zip`。
 */
object ZipImporter {
    private val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "webp")
    private const val MAX_ENTRIES = 20_000
    private const val MAX_ENTRY_BYTES = 256L * 1024 * 1024
    private const val MAX_TOTAL_BYTES_HARD = 12L * 1024 * 1024 * 1024
    private const val MAX_PIXELS = 120_000_000L
    private const val SUSPICIOUS_RATIO = 100.0

    fun import(
        zip: File,
        projectDir: File,
        displayName: String,
        onProgress: (ImportProgress) -> Unit = {},
        sizeOf: (File) -> Pair<Int, Int>? = ::probeSize,
    ): Project {
        if (!zip.isFile) throw ImportException("找不到 ZIP 檔案")
        projectDir.mkdirs()
        val id = projectDir.name
        val now = System.currentTimeMillis()
        val originalDir = File(projectDir, "original").apply { mkdirs() }
        File(projectDir, "translated").mkdirs()
        File(projectDir, "meta").mkdirs()

        val file = runCatching { ZipFile(zip) }
            .getOrElse { throw ImportException("無法開啟 ZIP（檔案損毀、加密或格式不支援）：${it.message}") }
        file.use { zf ->
            val entries = zf.entries().toList()
            if (entries.size > MAX_ENTRIES) throw ImportException("ZIP 內含 ${entries.size} 個檔案，超過上限 $MAX_ENTRIES")
            var totalUncompressed = 0L
            for (e in entries) {
                if (e.isDirectory) continue
                if (e.size < 0) throw ImportException("ZIP 條目大小異常：${e.name}")
                if (e.size > MAX_ENTRY_BYTES) throw ImportException("單一檔案過大（>256MB）：${e.name}")
                totalUncompressed += e.size
                val ratio = if (e.compressedSize > 0) e.size.toDouble() / e.compressedSize else 1.0
                if (e.size > 10L * 1024 * 1024 && ratio > SUSPICIOUS_RATIO) {
                    throw ImportException("偵測到異常壓縮比（${ratio.toInt()}:1）：${e.name}")
                }
            }
            if (totalUncompressed > MAX_TOTAL_BYTES_HARD) throw ImportException("解壓後總量超過 12GB")
            val need = zip.length() + (totalUncompressed * 1.05).toLong() + 128L * 1024 * 1024
            val usable = projectDir.usableSpace
            if (usable in 1 until need) {
                throw ImportException("儲存空間不足：需要約 ${fmtBytes(need)}，可用 ${fmtBytes(usable)}")
            }

            data class Cand(val entry: java.util.zip.ZipEntry, val rel: String, val ext: String)
            val candidates = ArrayList<Cand>()
            for (e in entries) {
                if (e.isDirectory) continue
                val rel = safePath(e.name) ?: continue
                if (isJunk(rel)) continue
                val ext = rel.substringAfterLast('.', "").lowercase()
                if (ext !in IMAGE_EXTS) continue
                if (!inside(originalDir, rel)) throw ImportException("ZIP 路徑逃逸：${e.name}")
                candidates.add(Cand(e, rel, if (ext == "jpeg") "jpg" else ext))
            }
            if (candidates.isEmpty()) throw ImportException("ZIP 內沒有任何 JPG / PNG / WEBP 圖片")
            candidates.sortWith { a, b -> NaturalOrder.comparePaths(a.rel, b.rel) }

            val pages = ArrayList<PageEntry>(candidates.size)
            val totalBytes = candidates.sumOf { it.entry.size }
            var doneBytes = 0L
            onProgress(ImportProgress("解壓縮", 0, totalBytes, 0, candidates.size, ""))
            candidates.forEachIndexed { i, cand ->
                val ext = cand.ext
                val out = File(projectDir, "original/%06d.%s".format(i, ext))
                val tmp = File(out.parentFile, out.name + ".part")
                try {
                    zf.getInputStream(cand.entry).use { input ->
                        FileOutputStream(tmp).use { output ->
                            val buf = ByteArray(1 shl 16)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                output.write(buf, 0, n)
                                doneBytes += n
                                if (doneBytes % (2L * 1024 * 1024) < (1L * 1024 * 1024)) {
                                    onProgress(ImportProgress("解壓縮", doneBytes, totalBytes, i, candidates.size, cand.rel))
                                }
                            }
                            output.fd.sync()
                        }
                    }
                    if (!tmp.renameTo(out)) {
                        tmp.copyTo(out, overwrite = true)
                        tmp.delete()
                    }
                } catch (t: Throwable) {
                    tmp.delete()
                    throw ImportException("解壓失敗（${cand.rel}）：${t.message}")
                }
                if (i % 8 == 0) onProgress(ImportProgress("解壓縮", doneBytes, totalBytes, i, candidates.size, cand.rel))

                val dims = sizeOf(out)
                val w = max(0, dims?.first ?: 0)
                val h = max(0, dims?.second ?: 0)
                val tooBig = w.toLong() * h.toLong() > MAX_PIXELS
                val bad = w <= 0 || h <= 0
                pages.add(
                    PageEntry(
                        index = i,
                        zipPath = cand.rel,
                        ext = ext,
                        width = w,
                        height = h,
                        status = if (bad || tooBig) PageStatus.FAILED else PageStatus.PENDING,
                        error = when {
                            bad -> "無法解碼圖片（格式不支援或檔案損毀）"
                            tooBig -> "圖片尺寸過大（${w}x${h}），為避免記憶體耗盡不處理；匯出時保留原圖"
                            else -> null
                        },
                        updatedAt = now,
                    )
                )
            }
            onProgress(ImportProgress("建立專案", totalBytes, totalBytes, candidates.size, candidates.size, ""))
            val project = Project(
                id = id,
                name = displayName,
                sourceZipName = "source.zip",
                createdAt = now,
                updatedAt = now,
                status = ProjectStatus.READY,
                pages = pages,
            )
            ProjectStore(projectDir.parentFile!!).save(project)
            return project
        }
    }

    /** 讀圖片尺寸（只讀 header，不配置整張 bitmap）。測試可注入固定尺寸。 */
    private fun probeSize(file: File): Pair<Int, Int>? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { BitmapFactory.decodeFile(file.absolutePath, opts) }
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
        return opts.outWidth to opts.outHeight
    }

    /** 去掉 BOM/空白與前導 `./`，換成 `/`。回傳相對路徑；不安全名稱直接丟 [ImportException]。 */
    private fun safePath(raw: String): String? {
        var name = raw.removePrefix("\uFEFF").removeSuffix("\u0000").replace('\\', '/').trim()
        while (name.startsWith("./")) name = name.substring(2)
        if (name.isEmpty() || name.endsWith("/")) return null
        if (name.startsWith("/")) throw ImportException("ZIP 含絕對路徑：$raw")
        if (Regex("^[A-Za-z]:").containsMatchIn(name)) throw ImportException("ZIP 含磁碟代號路徑：$raw")
        if (name.contains("\u0000")) throw ImportException("ZIP 路徑含非法字元")
        val segs = name.split('/')
        for (s in segs) {
            if (s.isEmpty() || s == "." || s == "..") throw ImportException("ZIP 含路徑穿越：$raw")
        }
        return segs.joinToString("/")
    }

    private fun isJunk(rel: String): Boolean {
        val segs = rel.split('/')
        if (segs.any { it == "__MACOSX" || it == ".git" }) return true
        val base = segs.last()
        if (base.startsWith(".") || base.equals("Thumbs.db", ignoreCase = true)) return true
        if (segs.any { it.startsWith("._") }) return true
        return false
    }

    private fun inside(root: File, rel: String): Boolean {
        val rootPath = root.canonicalFile.path + File.separator
        return File(root, rel).canonicalPath.startsWith(rootPath)
    }

    private fun fmtBytes(n: Long): String = when {
        n >= 1L shl 30 -> "%.1f GB".format(n / (1L shl 30).toDouble())
        n >= 1L shl 20 -> "%.1f MB".format(n / (1L shl 20).toDouble())
        else -> "${n / 1024} KB"
    }

    /** 新專案資料夾（名稱不含使用者檔名，避免奇怪字元；顯示名稱另存）。 */
    fun newProjectDir(root: File): File {
        root.mkdirs()
        val id = "p_${System.currentTimeMillis()}_${(1000..9999).random()}"
        return File(root, id).apply { mkdirs() }
    }

    fun projectNameFromZip(fileName: String): String {
        var n = fileName.substringBeforeLast('.', fileName).trim()
        n = n.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_")
        if (n.isBlank()) n = "漫畫專案"
        return n.take(80)
    }
}
