package tw.dafeiyu.mangatranslator.core

import android.graphics.Bitmap
import li.joye.yakuyomi.engine.PageAnalysis
import li.joye.yakuyomi.engine.Pt
import li.joye.yakuyomi.engine.TextLine
import li.joye.yakuyomi.engine.TextRegion
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 把引擎的 [PageAnalysis] 落地成可重新排版的資料：
 *  - meta/xxxxxx.json：每個文字區的方向、角度、框、onArt，以及每一行的四邊形與原文
 *  - meta/xxxxxx.mask.png：DBNet 的筆畫遮罩（重新擦字時不用重跑 OCR/翻譯）
 *
 * 有了這兩份 + original 原圖，就能只跑「擦字 + 排版」重建中文頁，完全不呼叫付費 API。
 */
data class LoadedAnalysis(
    val regions: List<TextRegion>,
    val maskFile: File,
    val inpaint: String,
)

object AnalysisCodec {
    fun save(
        projectDir: File,
        page: PageEntry,
        analysis: PageAnalysis,
        inpaint: String,
    ): Pair<String, String> {
        val metaRel = "meta/%06d.json".format(page.index)
        val maskRel = "meta/%06d.mask.png".format(page.index)
        val metaFile = File(projectDir, metaRel)
        val maskFile = File(projectDir, maskRel)
        metaFile.parentFile?.mkdirs()
        maskFile.parentFile?.mkdirs()

        ImageIo.saveMask(analysis.mask, maskFile)

        val root = JSONObject().apply {
            put("version", 1)
            put("page", page.index)
            put("width", page.width)
            put("height", page.height)
            put("inpaint", inpaint)
            put("regions", JSONArray().apply {
                analysis.regions.forEach { r ->
                    put(JSONObject().apply {
                        put("direction", r.direction)
                        put("angle", r.angle.toDouble())
                        put("cx", r.cx.toDouble()); put("cy", r.cy.toDouble())
                        put("boxW", r.boxW.toDouble()); put("boxH", r.boxH.toDouble())
                        put("onArt", r.onArt)
                        put("translatedText", r.translatedText)
                        put("lines", JSONArray().apply {
                            r.lines.forEach { ln ->
                                put(JSONObject().apply {
                                    put("text", ln.text)
                                    put("direction", ln.direction)
                                    put("quad", JSONArray().apply {
                                        ln.quad.forEach { p ->
                                            put(JSONArray().put(p.x.toDouble()).put(p.y.toDouble()))
                                        }
                                    })
                                })
                            }
                        })
                    })
                }
            })
        }
        val tmp = File(metaFile.parentFile, metaFile.name + ".tmp")
        tmp.writeText(root.toString(), Charsets.UTF_8)
        if (!tmp.renameTo(metaFile)) {
            tmp.copyTo(metaFile, overwrite = true)
            tmp.delete()
        }
        return metaRel to maskRel
    }

    fun load(projectDir: File, page: PageEntry): LoadedAnalysis? {
        val metaRel = page.metaFile ?: return null
        val maskRel = page.maskFile ?: return null
        val metaFile = File(projectDir, metaRel)
        val maskFile = File(projectDir, maskRel)
        if (!metaFile.isFile || !maskFile.isFile) return null
        return runCatching {
            val root = JSONObject(metaFile.readText(Charsets.UTF_8))
            val arr = root.getJSONArray("regions")
            val regions = ArrayList<TextRegion>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val lines = ArrayList<TextLine>()
                val la = o.getJSONArray("lines")
                for (j in 0 until la.length()) {
                    val lo = la.getJSONObject(j)
                    val qa = lo.getJSONArray("quad")
                    val quad = ArrayList<Pt>(qa.length())
                    for (k in 0 until qa.length()) {
                        val xy = qa.getJSONArray(k)
                        quad.add(Pt(xy.getDouble(0).toFloat(), xy.getDouble(1).toFloat()))
                    }
                    val line = TextLine(quad, 1f)
                    line.text = lo.optString("text", "")
                    line.direction = lo.optString("direction", "v")
                    lines.add(line)
                }
                val region = TextRegion(
                    lines = lines,
                    direction = o.optString("direction", "v"),
                    angle = o.optDouble("angle", 0.0).toFloat(),
                    cx = o.optDouble("cx", 0.0).toFloat(),
                    cy = o.optDouble("cy", 0.0).toFloat(),
                    boxW = o.optDouble("boxW", 0.0).toFloat(),
                    boxH = o.optDouble("boxH", 0.0).toFloat(),
                )
                region.translatedText = o.optString("translatedText", "")
                region.onArt = o.optBoolean("onArt", false)
                regions.add(region)
            }
            LoadedAnalysis(regions, maskFile, root.optString("inpaint", "aot"))
        }.getOrNull()
    }

    /** 把使用者在 UI 修改的譯文寫回 meta（不重跑辨識/翻譯）。 */
    fun updateTranslations(projectDir: File, page: PageEntry, translated: List<String>): Boolean {
        val metaRel = page.metaFile ?: return false
        val metaFile = File(projectDir, metaRel)
        if (!metaFile.isFile) return false
        return runCatching {
            val root = JSONObject(metaFile.readText(Charsets.UTF_8))
            val arr = root.getJSONArray("regions")
            for (i in 0 until minOf(arr.length(), translated.size)) {
                arr.getJSONObject(i).put("translatedText", translated[i])
            }
            val tmp = File(metaFile.parentFile, metaFile.name + ".tmp")
            tmp.writeText(root.toString(), Charsets.UTF_8)
            if (!tmp.renameTo(metaFile)) {
                tmp.copyTo(metaFile, overwrite = true)
                tmp.delete()
            }
            true
        }.getOrDefault(false)
    }

    /** 重新辨識後更新原文（lines[].text）；同時把該區 translatedText 清空，讓 UI 顯示需重新翻譯。 */
    fun updateSourceTexts(projectDir: File, page: PageEntry, regionIndex: Int, lineTexts: List<String>): Boolean {
        val metaRel = page.metaFile ?: return false
        val metaFile = File(projectDir, metaRel)
        if (!metaFile.isFile) return false
        return runCatching {
            val root = JSONObject(metaFile.readText(Charsets.UTF_8))
            val r = root.getJSONArray("regions").getJSONObject(regionIndex)
            val lines = r.getJSONArray("lines")
            // 行數可能因重新辨識而不同：保留原本的四邊形，只更新文字；多出來的行補最後一個 quad。
            for (i in 0 until minOf(lines.length(), lineTexts.size)) {
                lines.getJSONObject(i).put("text", lineTexts[i])
            }
            r.put("translatedText", "")
            val tmp = File(metaFile.parentFile, metaFile.name + ".tmp")
            tmp.writeText(root.toString(), Charsets.UTF_8)
            if (!tmp.renameTo(metaFile)) {
                tmp.copyTo(metaFile, overwrite = true)
                tmp.delete()
            }
            true
        }.getOrDefault(false)
    }

    fun recycle(analysis: PageAnalysis?) {
        runCatching { analysis?.mask?.recycle() }
    }
}
