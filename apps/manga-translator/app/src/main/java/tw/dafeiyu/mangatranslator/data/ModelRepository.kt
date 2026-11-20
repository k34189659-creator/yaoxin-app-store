package tw.dafeiyu.mangatranslator.data

import android.content.Context
import li.joye.yakuyomi.engine.ModelDownloader
import li.joye.yakuyomi.engine.ModelProgress
import li.joye.yakuyomi.engine.ModelSet
import li.joye.yakuyomi.engine.RemoteModel
import org.json.JSONObject
import java.io.File

data class LocalModelFile(
    val role: String,
    val name: String,
    val size: Long,
    val sha256: String,
    val present: Boolean,
)

data class ModelReadiness(
    val ready: Boolean,
    val files: List<LocalModelFile>,
    val totalBytes: Long,
    val presentBytes: Long,
    val manifestVersion: String,
    val fullyVerified: Boolean,
) {
    val missingCount: Int get() = files.count { !it.present }
    val presentCount: Int get() = files.size - missingCount
}

data class DownloadUi(
    val fileName: String = "",
    val fileDone: Long = 0,
    val fileTotal: Long = 0,
    val overallDone: Long = 0,
    val overallTotal: Long = 0,
    val verifying: Boolean = false,
    val finished: Boolean = false,
    val error: String? = null,
)

/**
 * 翻譯模型：不下載整個 models-v5（含夜讀 147MB），只取 detector / OCR / inpainter 這 7 個檔（約 247MB）。
 * 清單直接讀引擎 repo 的 models.json（打包在 assets），下載後逐檔比對 size + sha256。
 */
class ModelRepository(private val context: Context) {
    val dir: File = File(context.filesDir, "models").apply { mkdirs() }
    private val marker = File(dir, ".verified.json")

    data class Manifest(val version: String, val files: List<RemoteModel>)

    fun manifest(): Manifest {
        val text = context.assets.open("models.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
        val root = JSONObject(text)
        val arr = root.getJSONArray("models")
        val files = ArrayList<RemoteModel>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val role = o.getString("role")
            if (role != "detector" && role != "ocr" && role != "inpainter") continue
            files.add(
                RemoteModel(
                    role = role,
                    name = o.getString("name"),
                    url = o.getString("url"),
                    size = o.getLong("size"),
                    sha256 = o.getString("sha256").lowercase(),
                )
            )
        }
        return Manifest(root.optString("version", "unknown"), files)
    }

    fun readiness(): ModelReadiness {
        val m = manifest()
        val files = m.files.map {
            val f = File(dir, it.name)
            LocalModelFile(it.role, it.name, it.size, it.sha256, f.isFile && f.length() == it.size)
        }
        val total = files.sumOf { it.size }
        val present = files.filter { it.present }.sumOf { it.size }
        val markerOk = marker.isFile && runCatching {
            JSONObject(marker.readText()).optString("version") == m.version
        }.getOrDefault(false)
        return ModelReadiness(files.all { it.present }, files, total, present, m.version, markerOk)
    }

    fun modelSet(): ModelSet? {
        val files = dir.listFiles()?.filter { it.isFile }?.map { it.name to it.absolutePath } ?: return null
        return ModelSet.resolve(files)
    }

    fun freeBytes(): Long = dir.usableSpace

    /** 下載（已存在且大小正確的檔會由 [ModelDownloader.ensure] 驗 sha 後跳過）。 */
    suspend fun download(onProgress: (DownloadUi) -> Unit) {
        val m = manifest()
        val total = m.files.sumOf { it.size }
        val completed = HashMap<String, Long>()
        fun emit(p: ModelProgress) {
            when (p) {
                is ModelProgress.Downloading -> {
                    val done = completed.values.sum() + p.bytes
                    onProgress(DownloadUi(p.name, p.bytes, p.total, done.coerceAtMost(total), total))
                }
                is ModelProgress.Verifying -> {
                    onProgress(DownloadUi(p.name, 0, 0, completed.values.sum(), total, verifying = true))
                }
                is ModelProgress.Done -> {
                    completed[p.name] = m.files.firstOrNull { it.name == p.name }?.size ?: 0L
                    onProgress(DownloadUi(p.name, 0, 0, completed.values.sum(), total))
                }
                is ModelProgress.Failed -> {
                    onProgress(DownloadUi(p.name, 0, 0, completed.values.sum(), total, error = p.error))
                }
            }
        }
        val present = dir.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
        if (total - present > freeBytes() - 100L * 1024 * 1024) {
            throw IllegalStateException("儲存空間不足：模型需要 ${(total - present) / 1024 / 1024} MB")
        }
        ModelDownloader.ensure(m.files, dir) { emit(it) }
        writeMarker(m.version, m.files)
        onProgress(DownloadUi(overallDone = total, overallTotal = total, finished = true))
    }

    /** 完整 sha256 驗證（下載後自動做；也可手動重驗）。回傳壞掉的檔名。 */
    suspend fun verify(onProgress: (String, Int, Int) -> Unit = { _, _, _ -> }): List<String> {
        val m = manifest()
        val bad = ArrayList<String>()
        m.files.forEachIndexed { i, it ->
            onProgress(it.name, i, m.files.size)
            val f = File(dir, it.name)
            if (!f.isFile || f.length() != it.size) {
                bad.add(it.name); return@forEachIndexed
            }
            if (ModelDownloader.sha256(f) != it.sha256) bad.add(it.name)
        }
        if (bad.isEmpty()) writeMarker(m.version, m.files)
        return bad
    }

    private fun writeMarker(version: String, files: List<RemoteModel>) {
        val o = JSONObject().apply {
            put("version", version)
            put("verifiedAt", System.currentTimeMillis())
            put("files", JSONObject().apply { files.forEach { put(it.name, it.sha256) } })
        }
        runCatching { marker.writeText(o.toString()) }
    }

    fun deleteAll() {
        dir.listFiles()?.forEach { if (it.isFile) it.delete() }
    }

    // ---------------------------------------------------------------- PP-OCRv6

    /** PP-OCRv6 是「選配的第二套 OCR」：只有標準/高品質模式才需要，因此獨立下載、獨立驗證。 */
    data class PpReadiness(
        val ready: Boolean,
        val files: List<LocalModelFile>,
        val totalBytes: Long,
        val presentBytes: Long,
        val license: String,
        val source: String,
    ) {
        val missingCount: Int get() = files.count { !it.present }
    }

    fun ppManifest(): List<RemoteModel> {
        val text = context.assets.open("ppocr.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
        val arr = JSONObject(text).getJSONArray("files")
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            RemoteModel(
                role = o.getString("role"),
                name = o.getString("name"),
                url = o.getString("url"),
                size = o.getLong("size"),
                sha256 = o.getString("sha256").lowercase(),
            )
        }
    }

    private fun ppMeta(): Pair<String, String> {
        val text = context.assets.open("ppocr.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
        val root = JSONObject(text)
        return root.optString("license", "Apache-2.0") to root.optString("source", "")
    }

    fun ppReadiness(): PpReadiness {
        val files = ppManifest().map {
            val f = File(dir, it.name)
            LocalModelFile(it.role, it.name, it.size, it.sha256, f.isFile && f.length() == it.size)
        }
        val (license, source) = ppMeta()
        return PpReadiness(
            ready = files.all { it.present },
            files = files,
            totalBytes = files.sumOf { it.size },
            presentBytes = files.filter { it.present }.sumOf { it.size },
            license = license,
            source = source,
        )
    }

    /** PP-OCRv6 模型路徑（det, rec）；未就緒回 null。 */
    fun ppPaths(): Pair<String, String>? {
        val files = ppManifest()
        val det = files.firstOrNull { it.name.contains("det") } ?: return null
        val rec = files.firstOrNull { it.name.contains("rec") } ?: return null
        val d = File(dir, det.name)
        val r = File(dir, rec.name)
        if (!d.isFile || d.length() != det.size || !r.isFile || r.length() != rec.size) return null
        return d.absolutePath to r.absolutePath
    }

    suspend fun verifyPp(): List<String> {
        val bad = ArrayList<String>()
        for (m in ppManifest()) {
            val f = File(dir, m.name)
            if (!f.isFile || f.length() != m.size || ModelDownloader.sha256(f) != m.sha256) bad.add(m.name)
        }
        return bad
    }

    suspend fun downloadPp(onProgress: (DownloadUi) -> Unit) {
        val files = ppManifest()
        val total = files.sumOf { it.size }
        if (total - ppReadiness().presentBytes > freeBytes() - 100L * 1024 * 1024) {
            throw IllegalStateException("儲存空間不足：PP-OCRv6 需要 ${(total - ppReadiness().presentBytes) / 1024 / 1024} MB")
        }
        val completed = HashMap<String, Long>()
        ModelDownloader.ensure(files, dir) { p ->
            when (p) {
                is ModelProgress.Downloading ->
                    onProgress(DownloadUi(p.name, p.bytes, p.total, completed.values.sum() + p.bytes, total))
                is ModelProgress.Verifying ->
                    onProgress(DownloadUi(p.name, 0, 0, completed.values.sum(), total, verifying = true))
                is ModelProgress.Done -> {
                    completed[p.name] = files.firstOrNull { it.name == p.name }?.size ?: 0L
                    onProgress(DownloadUi(p.name, 0, 0, completed.values.sum(), total))
                }
                is ModelProgress.Failed ->
                    onProgress(DownloadUi(p.name, 0, 0, completed.values.sum(), total, error = p.error))
            }
        }
        onProgress(DownloadUi(overallDone = total, overallTotal = total, finished = true))
    }

    /** 讀字典（隨 APK 打包，約 76 KB）。 */
    fun ppDictionary(): List<String> =
        context.assets.open("ppocr_dict.txt").bufferedReader(Charsets.UTF_8).readLines()
}
