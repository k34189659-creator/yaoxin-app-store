package tw.dafeiyu.mangatranslator.core

import org.json.JSONArray
import org.json.JSONObject

enum class ProjectStatus { IMPORTING, READY, RUNNING, PAUSED, DONE, DONE_WITH_ERRORS, ERROR }
enum class PageStatus { PENDING, RUNNING, DONE, PARTIAL, FAILED, SKIPPED }

data class PageStatsInfo(
    val lines: Int = 0,
    val regions: Int = 0,
    val kept: Int = 0,
    val detectMs: Long = 0,
    val ocrMs: Long = 0,
    val translateMs: Long = 0,
    val inpaintMs: Long = 0,
    val renderMs: Long = 0,
    val wallMs: Long = 0,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val costUsd: Double = 0.0,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("lines", lines); put("regions", regions); put("kept", kept)
        put("detectMs", detectMs); put("ocrMs", ocrMs); put("translateMs", translateMs)
        put("inpaintMs", inpaintMs); put("renderMs", renderMs); put("wallMs", wallMs)
        put("promptTokens", promptTokens); put("completionTokens", completionTokens)
        put("costUsd", costUsd)
    }

    companion object {
        fun fromJson(o: JSONObject?) = if (o == null) null else PageStatsInfo(
            o.optInt("lines"), o.optInt("regions"), o.optInt("kept"),
            o.optLong("detectMs"), o.optLong("ocrMs"), o.optLong("translateMs"),
            o.optLong("inpaintMs"), o.optLong("renderMs"), o.optLong("wallMs"),
            o.optInt("promptTokens"), o.optInt("completionTokens"), o.optDouble("costUsd", 0.0),
        )
    }
}

data class PageEntry(
    val index: Int,
    val zipPath: String,
    val ext: String,
    val width: Int,
    val height: Int,
    var status: PageStatus = PageStatus.PENDING,
    var error: String? = null,
    var translatedFile: String? = null,
    var metaFile: String? = null,
    var maskFile: String? = null,
    var regionCount: Int = 0,
    var translatedRegions: Int = 0,
    var attempts: Int = 0,
    var stats: PageStatsInfo? = null,
    var updatedAt: Long = 0L,
) {
    val originalFile: String get() = "original/%06d.%s".format(index, ext)
    val displayName: String get() = zipPath.substringAfterLast('/')

    fun toJson(): JSONObject = JSONObject().apply {
        put("index", index); put("zipPath", zipPath); put("ext", ext)
        put("width", width); put("height", height); put("status", status.name)
        put("error", error ?: JSONObject.NULL)
        put("translatedFile", translatedFile ?: JSONObject.NULL)
        put("metaFile", metaFile ?: JSONObject.NULL)
        put("maskFile", maskFile ?: JSONObject.NULL)
        put("regionCount", regionCount); put("translatedRegions", translatedRegions)
        put("attempts", attempts); put("stats", stats?.toJson()); put("updatedAt", updatedAt)
    }

    companion object {
        fun fromJson(o: JSONObject) = PageEntry(
            index = o.getInt("index"),
            zipPath = o.getString("zipPath"),
            ext = o.getString("ext"),
            width = o.optInt("width"),
            height = o.optInt("height"),
            status = runCatching { PageStatus.valueOf(o.optString("status", "PENDING")) }
                .getOrDefault(PageStatus.PENDING),
            error = o.optString("error").takeIf { it.isNotBlank() && it != "null" },
            translatedFile = o.optString("translatedFile").takeIf { it.isNotBlank() && it != "null" },
            metaFile = o.optString("metaFile").takeIf { it.isNotBlank() && it != "null" },
            maskFile = o.optString("maskFile").takeIf { it.isNotBlank() && it != "null" },
            regionCount = o.optInt("regionCount"),
            translatedRegions = o.optInt("translatedRegions"),
            attempts = o.optInt("attempts"),
            stats = PageStatsInfo.fromJson(o.optJSONObject("stats")),
            updatedAt = o.optLong("updatedAt"),
        )
    }
}

data class Project(
    val id: String,
    val name: String,
    val sourceZipName: String,
    val createdAt: Long,
    var updatedAt: Long = createdAt,
    var lastOpenedAt: Long = createdAt,
    var status: ProjectStatus = ProjectStatus.READY,
    var providerId: String = "venice",
    var modelId: String = "",
    var inpaint: String = "aot",
    var orientation: String = "AUTO",
    var targetLang: String = "zh-TW",
    /** 0.4.0：每個專案記錄手動來源語言；舊專案預設日文，避免改設定影響既有 OCR。 */
    var sourceLang: String = "JAPANESE",
    /** OFF / BALANCED / HIGH：OCR 漏字補救強度。 */
    var remedy: String = "BALANCED",
    var lastError: String? = null,
    var totalPromptTokens: Int = 0,
    var totalCompletionTokens: Int = 0,
    var totalCostUsd: Double = 0.0,
    val pages: MutableList<PageEntry> = mutableListOf(),
) {
    val doneCount: Int get() = pages.count { it.status == PageStatus.DONE }
    val partialCount: Int get() = pages.count { it.status == PageStatus.PARTIAL }
    val failedCount: Int get() = pages.count { it.status == PageStatus.FAILED }
    val skippedCount: Int get() = pages.count { it.status == PageStatus.SKIPPED }
    val pendingCount: Int get() = pages.count { it.status == PageStatus.PENDING }
    val runningCount: Int get() = pages.count { it.status == PageStatus.RUNNING }
    val processedCount: Int get() =
        pages.count { it.status == PageStatus.DONE || it.status == PageStatus.PARTIAL || it.status == PageStatus.SKIPPED || it.status == PageStatus.FAILED }

    fun toJson(): JSONObject = JSONObject().apply {
        put("schema", 1)
        put("id", id); put("name", name); put("sourceZipName", sourceZipName)
        put("createdAt", createdAt); put("updatedAt", updatedAt); put("lastOpenedAt", lastOpenedAt)
        put("status", status.name); put("providerId", providerId); put("modelId", modelId)
        put("inpaint", inpaint); put("orientation", orientation); put("targetLang", targetLang)
        put("sourceLang", sourceLang)
        put("remedy", remedy)
        put("lastError", lastError ?: JSONObject.NULL)
        put("totalPromptTokens", totalPromptTokens)
        put("totalCompletionTokens", totalCompletionTokens)
        put("totalCostUsd", totalCostUsd)
        put("pages", JSONArray().apply { pages.forEach { put(it.toJson()) } })
    }

    companion object {
        fun fromJson(o: JSONObject): Project {
            val pages = ArrayList<PageEntry>()
            o.optJSONArray("pages")?.let { arr ->
                for (i in 0 until arr.length()) pages.add(PageEntry.fromJson(arr.getJSONObject(i)))
            }
            return Project(
                id = o.getString("id"),
                name = o.getString("name"),
                sourceZipName = o.optString("sourceZipName", "source.zip"),
                createdAt = o.optLong("createdAt"),
                updatedAt = o.optLong("updatedAt"),
                lastOpenedAt = o.optLong("lastOpenedAt"),
                status = runCatching { ProjectStatus.valueOf(o.optString("status", "READY")) }
                    .getOrDefault(ProjectStatus.READY),
                providerId = o.optString("providerId", "venice"),
                modelId = o.optString("modelId", ""),
                inpaint = o.optString("inpaint", "aot"),
                orientation = o.optString("orientation", "AUTO"),
                targetLang = o.optString("targetLang", "zh-TW"),
                sourceLang = o.optString("sourceLang", "JAPANESE").let { if (it == "ENGLISH") it else "JAPANESE" },
                remedy = o.optString("remedy", "BALANCED"),
                lastError = o.optString("lastError").takeIf { it.isNotBlank() && it != "null" },
                totalPromptTokens = o.optInt("totalPromptTokens"),
                totalCompletionTokens = o.optInt("totalCompletionTokens"),
                totalCostUsd = o.optDouble("totalCostUsd", 0.0),
                pages = pages,
            )
        }
    }
}

data class ProjectSummary(
    val id: String,
    val name: String,
    val pageCount: Int,
    val done: Int,
    val partial: Int,
    val failed: Int,
    val skipped: Int,
    val updatedAt: Long,
    val status: ProjectStatus,
)
