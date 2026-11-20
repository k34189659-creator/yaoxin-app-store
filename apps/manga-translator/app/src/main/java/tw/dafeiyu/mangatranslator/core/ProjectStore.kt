package tw.dafeiyu.mangatranslator.core

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * 專案落地：每個專案一個資料夾
 * ```
 * project.json        專案與逐頁狀態（原子替換）
 * source.zip          匯入時的原始 ZIP（匯出、續傳用，不改動）
 * original/000001.jpg 解壓後的原圖（僅供預覽/重排版）
 * translated/…        翻譯後的頁面
 * meta/000001.json    文字區、原文/譯文、遮罩檔名、尺寸
 * meta/000001.mask.png 文字筆畫遮罩（重新排版時重建背景用）
 * ```
 * `project.json` 每處理完一頁就原子寫入（tmp → fsync → rename），中途斷電/被系統殺掉也能續傳。
 */
class ProjectStore(val root: File) {
    init { root.mkdirs() }

    fun projectDir(id: String): File = File(root, id)

    fun save(project: Project) {
        // 多個協程（runner / UI / 續傳）可能同時存檔；同一個 tmp 檔必須序列化，否則會互相截斷。
        synchronized(ProjectStore::class.java) {
            saveLocked(project)
        }
    }

    private fun saveLocked(project: Project) {
        project.updatedAt = System.currentTimeMillis()
        val dir = projectDir(project.id)
        dir.mkdirs()
        val tmp = File(dir, "project.json.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(project.toJson().toString().toByteArray(Charsets.UTF_8))
            out.flush()
            out.fd.sync()
        }
        val target = File(dir, "project.json")
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }

    fun load(dir: File): Project? {
        val f = File(dir, "project.json")
        if (!f.isFile) return null
        return runCatching { Project.fromJson(JSONObject(f.readText(Charsets.UTF_8))) }.getOrNull()
    }

    fun load(id: String): Project? = load(projectDir(id))

    /** 首頁清單：只讀 project.json（不碰圖片），修復殘留的 RUNNING → PAUSED。 */
    fun list(): List<ProjectSummary> {
        val dirs = root.listFiles { f -> f.isDirectory } ?: return emptyList()
        val out = ArrayList<ProjectSummary>()
        for (dir in dirs) {
            val p = load(dir) ?: continue
            if (p.status == ProjectStatus.RUNNING || p.pages.any { it.status == PageStatus.RUNNING }) {
                p.status = ProjectStatus.PAUSED
                p.pages.filter { it.status == PageStatus.RUNNING }.forEach { it.status = PageStatus.PENDING }
                runCatching { save(p) }
            }
            out.add(
                ProjectSummary(
                    id = p.id, name = p.name, pageCount = p.pages.size,
                    done = p.doneCount, partial = p.partialCount, failed = p.failedCount, skipped = p.skippedCount,
                    updatedAt = p.updatedAt, status = p.status,
                )
            )
        }
        return out.sortedByDescending { it.updatedAt }
    }

    fun delete(id: String): Boolean {
        val dir = projectDir(id)
        if (!dir.canonicalPath.startsWith(root.canonicalPath + File.separator)) return false
        return dir.deleteRecursively()
    }
}
