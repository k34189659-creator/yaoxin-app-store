package tw.dafeiyu.mangatranslator.core

import li.joye.yakuyomi.engine.RegionOverride
import li.joye.yakuyomi.engine.TextOrientation
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 一頁的診斷快照（存 meta/<page>.diag.json；不含 API Key、預設不含圖片）。 */
data class PageDiag(
    val pageIndex: Int,
    val createdAt: Long,
    val status: String,
    val qualityMode: String,
    val detectionPasses: Int,
    val addedLines: Int,
    val ocrRemedyCount: Int,
    val detectMs: Long,
    val ocrMs: Long,
    val translateMs: Long,
    val inpaintMs: Long,
    val renderMs: Long,
    val wallMs: Long,
    val lines: Int,
    val regions: Int,
    val kept: Int,
    val promptTokens: Int,
    val completionTokens: Int,
    val costUsd: Double,
    val errors: List<String>,
    val warnings: List<String>,
    val regionDiag: List<RegionDiag>,
    val cache: Map<String, String>,
    val overrides: Map<Int, RegionOverride>,
    val inpaintMethod: String,
    val apiError: String? = null,
    /** detect/ocr/translate/erase/render/layout → ok/partial/failed/skipped */
    val stageStatus: Map<String, String> = emptyMap(),
)

object DiagCodec {
    fun file(projectDir: File, page: PageEntry): File = File(projectDir, "meta/%06d.diag.json".format(page.index))

    fun save(projectDir: File, page: PageEntry, diag: PageDiag) {
        val f = file(projectDir, page)
        f.parentFile?.mkdirs()
        val root = JSONObject().apply {
            put("version", 1)
            put("page", diag.pageIndex)
            put("createdAt", diag.createdAt)
            put("status", diag.status)
            put("qualityMode", diag.qualityMode)
            put("stages", JSONObject().apply {
                put("detectionPasses", diag.detectionPasses)
                put("addedLines", diag.addedLines)
                put("ocrRemedyCount", diag.ocrRemedyCount)
                put("detectMs", diag.detectMs)
                put("ocrMs", diag.ocrMs)
                put("translateMs", diag.translateMs)
                put("inpaintMs", diag.inpaintMs)
                put("renderMs", diag.renderMs)
                put("wallMs", diag.wallMs)
                put("lines", diag.lines)
                put("regions", diag.regions)
                put("kept", diag.kept)
                put("promptTokens", diag.promptTokens)
                put("completionTokens", diag.completionTokens)
                put("costUsd", diag.costUsd)
                put("inpaintMethod", diag.inpaintMethod)
            })
            put("errors", JSONArray().apply { diag.errors.forEach { put(it) } })
            put("warnings", JSONArray().apply { diag.warnings.forEach { put(it) } })
            put("apiError", diag.apiError ?: JSONObject.NULL)
            put("stageStatus", JSONObject().apply { diag.stageStatus.forEach { (k, v) -> put(k, v) } })
            put("regions", JSONArray().apply {
                diag.regionDiag.forEach { r ->
                    put(JSONObject().apply {
                        put("index", r.index)
                        put("x0", r.x0.toDouble()); put("y0", r.y0.toDouble())
                        put("x1", r.x1.toDouble()); put("y1", r.y1.toDouble())
                        put("direction", r.direction)
                        put("source", r.source)
                        put("translated", r.translated)
                        put("ocrProb", r.ocrProb.toDouble())
                        put("ocrStrategy", r.ocrStrategy)
                        put("translationStatus", r.translationStatus)
                        put("translationError", r.translationError ?: JSONObject.NULL)
                        put("eraseStrategy", r.eraseStrategy ?: JSONObject.NULL)
                        put("layoutFontSize", r.layoutFontSize)
                        put("layoutOverflow", r.layoutOverflow)
                        put("layoutNote", r.layoutNote ?: JSONObject.NULL)
                        put("kind", r.kind)
                        put("eraseSafe", r.eraseSafe)
                    })
                }
            })
            put("cache", JSONObject().apply { diag.cache.forEach { (k, v) -> put(k, v) } })
            put("overrides", JSONObject().apply {
                diag.overrides.forEach { (idx, o) ->
                    put(idx.toString(), JSONObject().apply {
                        put("dx", o.dx.toDouble()); put("dy", o.dy.toDouble())
                        put("fontScale", o.fontScale.toDouble())
                        put("orientation", o.orientation?.name ?: JSONObject.NULL)
                    })
                }
            })
        }
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(root.toString(), Charsets.UTF_8)
        if (!tmp.renameTo(f)) {
            tmp.copyTo(f, overwrite = true)
            tmp.delete()
        }
    }

    fun load(projectDir: File, page: PageEntry): PageDiag? {
        val f = file(projectDir, page)
        if (!f.isFile) return null
        return runCatching {
            val root = JSONObject(f.readText(Charsets.UTF_8))
            val st = root.optJSONObject("stages") ?: JSONObject()
            val errors = strings(root.optJSONArray("errors"))
            val warnings = strings(root.optJSONArray("warnings"))
            val regions = ArrayList<RegionDiag>()
            root.optJSONArray("regions")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    regions.add(
                        RegionDiag(
                            index = o.optInt("index"),
                            x0 = o.optDouble("x0", 0.0).toFloat(),
                            y0 = o.optDouble("y0", 0.0).toFloat(),
                            x1 = o.optDouble("x1", 0.0).toFloat(),
                            y1 = o.optDouble("y1", 0.0).toFloat(),
                            direction = o.optString("direction", "v"),
                            source = o.optString("source", ""),
                            translated = o.optString("translated", ""),
                            ocrProb = o.optDouble("ocrProb", 0.0).toFloat(),
                            ocrStrategy = o.optString("ocrStrategy", ""),
                            translationStatus = o.optString("translationStatus", ""),
                            translationError = o.optString("translationError").takeIf { it.isNotBlank() && it != "null" },
                            eraseStrategy = o.optString("eraseStrategy").takeIf { it.isNotBlank() && it != "null" },
                            layoutFontSize = o.optInt("layoutFontSize"),
                            layoutOverflow = o.optBoolean("layoutOverflow"),
                            layoutNote = o.optString("layoutNote").takeIf { it.isNotBlank() && it != "null" },
                            kind = o.optString("kind", ""),
                            eraseSafe = o.optBoolean("eraseSafe", true),
                        )
                    )
                }
            }
            val cache = LinkedHashMap<String, String>()
            root.optJSONObject("cache")?.let { o ->
                val keys = o.keys()
                while (keys.hasNext()) { val k = keys.next(); cache[k] = o.optString(k) }
            }
            val stageStatus = LinkedHashMap<String, String>()
            root.optJSONObject("stageStatus")?.let { o ->
                val keys = o.keys()
                while (keys.hasNext()) { val k = keys.next(); stageStatus[k] = o.optString(k) }
            }
            val overrides = LinkedHashMap<Int, RegionOverride>()
            root.optJSONObject("overrides")?.let { o ->
                val keys = o.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val idx = k.toIntOrNull() ?: continue
                    val v = o.getJSONObject(k)
                    overrides[idx] = RegionOverride(
                        dx = v.optDouble("dx", 0.0).toFloat(),
                        dy = v.optDouble("dy", 0.0).toFloat(),
                        fontScale = v.optDouble("fontScale", 1.0).toFloat(),
                        orientation = v.optString("orientation").takeIf { it.isNotBlank() && it != "null" }
                            ?.let { runCatching { TextOrientation.valueOf(it) }.getOrNull() },
                    )
                }
            }
            PageDiag(
                pageIndex = root.optInt("page", page.index),
                createdAt = root.optLong("createdAt"),
                status = root.optString("status", "UNKNOWN"),
                qualityMode = root.optString("qualityMode", ""),
                detectionPasses = st.optInt("detectionPasses"),
                addedLines = st.optInt("addedLines"),
                ocrRemedyCount = st.optInt("ocrRemedyCount"),
                detectMs = st.optLong("detectMs"),
                ocrMs = st.optLong("ocrMs"),
                translateMs = st.optLong("translateMs"),
                inpaintMs = st.optLong("inpaintMs"),
                renderMs = st.optLong("renderMs"),
                wallMs = st.optLong("wallMs"),
                lines = st.optInt("lines"),
                regions = st.optInt("regions"),
                kept = st.optInt("kept"),
                promptTokens = st.optInt("promptTokens"),
                completionTokens = st.optInt("completionTokens"),
                costUsd = st.optDouble("costUsd", 0.0),
                errors = errors,
                warnings = warnings,
                regionDiag = regions,
                cache = cache,
                overrides = overrides,
                inpaintMethod = st.optString("inpaintMethod", ""),
                apiError = root.optString("apiError").takeIf { it.isNotBlank() && it != "null" },
                stageStatus = stageStatus,
            )
        }.getOrNull()
    }

    /** 從既有 meta 的原文/譯文建立快取（手動改過的譯文也算）。 */
    fun cacheFromMeta(projectDir: File, page: PageEntry): Map<String, String> {
        val meta = page.metaFile ?: return emptyMap()
        val f = File(projectDir, meta)
        if (!f.isFile) return emptyMap()
        return runCatching {
            val root = JSONObject(f.readText(Charsets.UTF_8))
            val arr = root.optJSONArray("regions") ?: return emptyMap()
            val out = LinkedHashMap<String, String>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val src = o.optString("sourceText", "")
                // meta 的 lines 才是原文；sourceText 不一定有寫
                val lines = o.optJSONArray("lines")
                val sb = StringBuilder()
                if (lines != null) for (j in 0 until lines.length()) sb.append(lines.getJSONObject(j).optString("text", ""))
                val source = if (sb.isNotBlank()) sb.toString() else src
                val tr = o.optString("translatedText", "")
                if (source.isNotBlank() && tr.isNotBlank()) out[source.trim()] = tr.trim()
            }
            out
        }.getOrDefault(emptyMap())
    }

    fun overridesFromMeta(projectDir: File, page: PageEntry): Map<Int, RegionOverride> =
        load(projectDir, page)?.overrides ?: emptyMap()

    private fun strings(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) out.add(arr.getString(i))
        return out
    }
}
