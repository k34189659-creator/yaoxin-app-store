package tw.dafeiyu.mangatranslator.core

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import li.joye.yakuyomi.engine.Detector
import li.joye.yakuyomi.engine.DetectorConfig
import li.joye.yakuyomi.engine.EngineConfig
import li.joye.yakuyomi.engine.Grouping
import li.joye.yakuyomi.engine.Inpainter
import li.joye.yakuyomi.engine.Legacy020Inpainter
import li.joye.yakuyomi.engine.LlmTranslator
import li.joye.yakuyomi.engine.Ocr
import li.joye.yakuyomi.engine.OcrRemedyMode
import li.joye.yakuyomi.engine.Pt
import li.joye.yakuyomi.engine.RegionEraseInfo
import li.joye.yakuyomi.engine.RegionLayout
import li.joye.yakuyomi.engine.RegionOverride
import li.joye.yakuyomi.engine.SmartInpaintResult
import li.joye.yakuyomi.engine.SmartRenderer
import li.joye.yakuyomi.engine.Legacy020SmartRenderer
import li.joye.yakuyomi.engine.TextLine
import li.joye.yakuyomi.engine.TextRegion
import li.joye.yakuyomi.engine.Translator
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 直接保留瑤芯漫畫翻譯 0.2.0 的處理核心。
 * 輸出型別與 0.4.0 相容，避免把舊 UI、舊專案格式一起倒退。
 * 只在日文模式運作；英文保留 0.4.0 的獨立辨識模式。
 */
class Legacy020SmartTranslator(
    private val detector: Detector,
    private val ocr: Ocr,
    private val inpainter: Legacy020Inpainter,
    private val translator: LlmTranslator?,
    private val mockTranslator: Translator? = null,
    private val config: EngineConfig = EngineConfig(),
    private val remedy: OcrRemedyMode = OcrRemedyMode.BALANCED,
    private val smartInpaint: Boolean = true,
) {
    // ---------------- 1. 偵測 + OCR ----------------

    suspend fun analyze(page: Bitmap): AnalysisStage {
        val errors = ArrayList<String>()
        val warnings = ArrayList<String>()
        var passes = 0
        val tDet0 = System.currentTimeMillis()
        val first = try {
            passes++
            detector.detect(page)
        } catch (t: Throwable) {
            return AnalysisStage(emptyList(), emptyList(), blankMask(page), System.currentTimeMillis() - tDet0, 0, passes, 0, 0,
                errors + "偵測失敗：${t.message ?: t.javaClass.simpleName}")
        }
        val lines = ArrayList(first.lines)
        val mask = first.textMask
        var added = 0
        val detectMsBase = System.currentTimeMillis() - tDet0

        val tOcr0 = System.currentTimeMillis()
        try {
            ocr.recognizeAllBest(page, lines, remedy)
        } catch (t: Throwable) {
            errors.add("OCR 失敗：${t.message ?: t.javaClass.simpleName}")
        }

        if (remedy != OcrRemedyMode.OFF) {
            // 第二輪（BALANCED）：只對空讀/低信心行框附近裁切放大重新偵測。
            val suspects = lines.filter { it.text.isBlank() || it.ocrProb < 0.5f }.take(16)
            val newLines = ArrayList<TextLine>()
            for (line in suspects) {
                val bb = bbox(line, page.width, page.height) ?: continue
                val padX = max(24, ((bb[2] - bb[0]) * 0.8f).toInt())
                val padY = max(24, ((bb[3] - bb[1]) * 0.8f).toInt())
                val x0 = (bb[0].toInt() - padX).coerceIn(0, page.width - 1)
                val y0 = (bb[1].toInt() - padY).coerceIn(0, page.height - 1)
                val x1 = (bb[2].toInt() + padX).coerceIn(x0 + 8, page.width)
                val y1 = (bb[3].toInt() + padY).coerceIn(y0 + 8, page.height)
                val cw = x1 - x0; val ch = y1 - y0
                if (cw < 32 || ch < 32) continue
                val crop = Bitmap.createBitmap(page, x0, y0, cw, ch)
                try {
                    passes++
                    val d = detector.detect(
                        crop,
                        config.detector.copy(
                            dbnetInputSize = 1024,
                            dbBinThreshold = 0.45f,
                            dbBoxThreshold = 0.60f,
                        ),
                    )
                    for (nl in d.lines) {
                        val q = nl.quad.map { Pt(it.x + x0, it.y + y0) }
                        val cand = TextLine(q, nl.score)
                        if (lines.none { overlaps(it, cand) }) newLines.add(cand)
                    }
                    orMask(mask, d.textMask, x0, y0)
                    d.textMask.recycle()
                } catch (t: Throwable) {
                    warnings.add("局部重新偵測失敗：${t.message}")
                } finally {
                    crop.recycle()
                }
            }
            if (newLines.isNotEmpty()) {
                try {
                    ocr.recognizeAllBest(page, newLines, remedy)
                } catch (t: Throwable) {
                    warnings.add("補抓行 OCR 失敗：${t.message}")
                }
                lines.addAll(newLines)
                added += newLines.size
            }
        }

        if (remedy == OcrRemedyMode.HIGH || (remedy != OcrRemedyMode.OFF && lines.isEmpty())) {
            // 第三輪（HIGH，或第一輪完全沒偵測到）：整頁 2×2 重疊切片、局部放大，
            // 補抓完全沒被第一輪看到的文字（簡單白底頁若 DBNet 第一輪漏光，靠這輪救回）。
            val tileW = (page.width * 0.56f).toInt().coerceAtLeast(64)
            val tileH = (page.height * 0.56f).toInt().coerceAtLeast(64)
            val stepX = max(1, page.width - tileW)
            val stepY = max(1, page.height - tileH)
            val xs = listOf(0, stepX).distinct()
            val ys = listOf(0, stepY).distinct()
            val highLines = ArrayList<TextLine>()
            for (ty in ys) for (tx in xs) {
                val cw = min(tileW, page.width - tx); val ch = min(tileH, page.height - ty)
                if (cw < 64 || ch < 64) continue
                val crop = Bitmap.createBitmap(page, tx, ty, cw, ch)
                try {
                    passes++
                    val d = detector.detect(crop, config.detector.copy(dbnetInputSize = 1024))
                    for (nl in d.lines) {
                        val q = nl.quad.map { Pt(it.x + tx, it.y + ty) }
                        val cand = TextLine(q, nl.score)
                        if (lines.none { overlaps(it, cand) } && highLines.none { overlaps(it, cand) }) highLines.add(cand)
                    }
                    orMask(mask, d.textMask, tx, ty)
                    d.textMask.recycle()
                } catch (t: Throwable) {
                    warnings.add("高品質切片偵測失敗：${t.message}")
                } finally {
                    crop.recycle()
                }
            }
            if (highLines.isNotEmpty()) {
                try {
                    ocr.recognizeAllBest(page, highLines, remedy)
                } catch (t: Throwable) {
                    warnings.add("高品質補抓 OCR 失敗：${t.message}")
                }
                lines.addAll(highLines)
                added += highLines.size
            }
        }

        val ocrMs = System.currentTimeMillis() - tOcr0
        val regions = try { Grouping.group(lines) } catch (t: Throwable) {
            errors.add("分群失敗：${t.message}")
            emptyList()
        }
        val remedyCount = lines.count { it.ocrStrategy.isNotBlank() && it.ocrStrategy != "normal" }
        return AnalysisStage(
            lines = lines,
            regions = regions,
            textMask = mask,
            detectMs = detectMsBase,
            ocrMs = ocrMs,
            detectionPasses = passes,
            addedLines = added,
            ocrRemedyCount = remedyCount,
            errors = errors,
            warnings = warnings,
        )
    }

    // ---------------- 2. 翻譯 ----------------

    suspend fun translate(analysis: AnalysisStage, cache: Map<String, String>): TranslationStage {
        val t0 = System.currentTimeMillis()
        val regions = analysis.regions.filter { it.sourceText.isNotBlank() }
        val result = ArrayList<RegionTranslationInfo>(regions.size)
        val kept = ArrayList<Int>()
        val pending = ArrayList<Int>()
        val pendingSources = ArrayList<String>()
        regions.forEachIndexed { i, r ->
            val hit = cache[r.sourceText.trim()]
            if (!hit.isNullOrBlank() && validTranslation(r.sourceText, hit)) {
                r.translatedText = hit
                result.add(RegionTranslationInfo(i, r.sourceText, hit, "cached"))
                kept.add(i)
            } else {
                pending.add(i); pendingSources.add(r.sourceText)
            }
        }

        var error: String? = null
        var raw: String? = null
        var prompt = 0; var completion = 0; var cost = 0.0
        if (pendingSources.isNotEmpty()) {
            var translated = List(pendingSources.size) { "" }
            val mock = mockTranslator
            if (mock != null) {
                translated = runCatching { mock.translate(pendingSources) }
                    .getOrElse { error = "模擬翻譯失敗：${it.message}"; pendingSources.map { q -> q } }
            } else if (translator != null) {
                val r = translator.translateDetailed(pendingSources)
                translated = r.translations
                error = r.error
                raw = r.raw
                r.usage?.let { prompt = it.promptTokens; completion = it.completionTokens; cost = it.costUsd ?: 0.0 }
            } else {
                error = "沒有可用的翻譯器（未設定 API Key）"
                translated = pendingSources.map { it }
            }
            pending.forEachIndexed { k, regionIndex ->
                val src = pendingSources[k]
                val tr = translated.getOrElse(k) { "" }
                val region = regions.getOrNull(regionIndex)
                if (region != null && validTranslation(src, tr)) {
                    region.translatedText = tr.trim()
                    result.add(RegionTranslationInfo(regionIndex, src, tr.trim(), "ok"))
                    kept.add(regionIndex)
                } else {
                    val why = when {
                        tr.isBlank() -> "空譯文"
                        src.trim().equals(tr.trim(), ignoreCase = true) -> "譯文與原文相同（模型漏譯/拒絕）"
                        tr.all { it.isDigit() } -> "譯文只有數字"
                        else -> "無效譯文"
                    }
                    result.add(RegionTranslationInfo(regionIndex, src, tr, "failed", why))
                }
            }
        }
        result.sortBy { it.index }
        kept.sort()
        return TranslationStage(
            translations = result, keptIndices = kept, ms = System.currentTimeMillis() - t0,
            promptTokens = prompt, completionTokens = completion, costUsd = cost,
            error = error, raw = raw,
        )
    }

    // ---------------- 3. 擦字 ----------------

    suspend fun erase(page: Bitmap, analysis: AnalysisStage, translation: TranslationStage): EraseStage {
        val t0 = System.currentTimeMillis()
        val regions = analysis.regions.filter { it.sourceText.isNotBlank() }
        val kept = translation.keptIndices.mapNotNull { regions.getOrNull(it) }
        if (kept.isEmpty()) {
            return EraseStage(page.copy(Bitmap.Config.ARGB_8888, true), emptyList(), 0, "none")
        }
        return try {
            val mode = if (smartInpaint) "smart" else config.inpainter.method
            val r: SmartInpaintResult = inpainter.inpaintSmart(page, kept, analysis.textMask, mode)
            EraseStage(r.bitmap, r.strategies, System.currentTimeMillis() - t0, mode)
        } catch (t: Throwable) {
            // 擦字失敗：保留原圖，不要產生半成品。
            EraseStage(page.copy(Bitmap.Config.ARGB_8888, true), emptyList(), System.currentTimeMillis() - t0, "failed",
                "擦字失敗：${t.message ?: t.javaClass.simpleName}")
        }
    }

    // ---------------- 4. 排版 ----------------

    fun render(cleaned: Bitmap, analysis: AnalysisStage, translation: TranslationStage,
               overrides: Map<Int, RegionOverride> = emptyMap()): RenderStage {
        val t0 = System.currentTimeMillis()
        val regions = analysis.regions.filter { it.sourceText.isNotBlank() }
        // kept 是「原始 region index」的清單；排版器收到的是壓縮後清單，所以 layout/override 的 index 要映射回原始 index。
        val pairs = translation.keptIndices.mapNotNull { idx -> regions.getOrNull(idx)?.let { idx to it } }
        val kept = pairs.map { it.second }
        if (kept.isEmpty()) return RenderStage(cleaned.copy(Bitmap.Config.ARGB_8888, true), emptyList(), 0)
        val posOverrides = pairs.mapIndexedNotNull { pos, (orig, _) ->
            overrides[orig]?.let { pos to it }
        }.toMap()
        return try {
            val r = Legacy020SmartRenderer.renderDetailed(cleaned, kept, config.render, null, posOverrides)
            val mapped = r.layouts.map { l -> l.copy(index = pairs.getOrNull(l.index)?.first ?: l.index) }
            RenderStage(r.bitmap, mapped, System.currentTimeMillis() - t0)
        } catch (t: Throwable) {
            RenderStage(cleaned.copy(Bitmap.Config.ARGB_8888, true), emptyList(), System.currentTimeMillis() - t0,
                "排版失敗：${t.message ?: t.javaClass.simpleName}")
        }
    }

    // ---------------- 一次跑完 ----------------

    suspend fun run(
        page: Bitmap,
        cache: Map<String, String> = emptyMap(),
        overrides: Map<Int, RegionOverride> = emptyMap(),
    ): SmartPageOutcome {
        val t0 = System.currentTimeMillis()
        return runWithAnalysis(page, analyze(page), cache, overrides, startedAt = t0)
    }

    /** 英文專用 OCR 分組後仍走 0.2.0 的翻譯、擦字、嵌字；不更動日文 analyze/run 的行為。 */
    suspend fun runWithAnalysis(
        page: Bitmap,
        analysis: AnalysisStage,
        cache: Map<String, String> = emptyMap(),
        overrides: Map<Int, RegionOverride> = emptyMap(),
        startedAt: Long = System.currentTimeMillis(),
    ): SmartPageOutcome {
        val t0 = startedAt
        val warnings = ArrayList<String>(analysis.warnings)
        val statsBase = PageStatsInfo(
            lines = analysis.lines.size, regions = analysis.regions.size,
            detectMs = analysis.detectMs, ocrMs = analysis.ocrMs,
        )
        if (analysis.regions.isEmpty()) {
            val failed = analysis.errors.isNotEmpty()
            return SmartPageOutcome(
                if (failed) SmartStatus.FAILED else SmartStatus.SKIPPED,
                null, null, analysis.textMask, analysis, null, null, null,
                analysis.errors.joinToString("；").ifBlank { null },
                statsBase.copy(wallMs = System.currentTimeMillis() - t0), warnings = warnings,
                regionDiag = emptyList(),
            )
        }
        val translation = translate(analysis, cache)
        val regionsWithText = analysis.regions.count { it.sourceText.isNotBlank() }
        if (translation.keptIndices.isEmpty()) {
            val status = if (translation.error != null) SmartStatus.FAILED else SmartStatus.SKIPPED
            val msg = translation.error ?: "全部文字區都沒有有效譯文（可能被模型拒絕或譯文=原文）"
            return SmartPageOutcome(status, null, null, analysis.textMask, analysis, translation, null, null,
                msg, statsBase.copy(translateMs = translation.ms, promptTokens = translation.promptTokens,
                    completionTokens = translation.completionTokens, costUsd = translation.costUsd,
                    wallMs = System.currentTimeMillis() - t0),
                cache = collectCache(analysis, translation), warnings = warnings,
                regionDiag = regionDiag(analysis, translation))
        }
        val erase = erase(page, analysis, translation)
        if (erase.error != null) {
            warnings.add(erase.error)
            return SmartPageOutcome(SmartStatus.FAILED, null, erase.cleaned, analysis.textMask, analysis, translation,
                erase, null, erase.error, statsBase.copy(translateMs = translation.ms, inpaintMs = erase.ms,
                    promptTokens = translation.promptTokens, completionTokens = translation.completionTokens,
                    costUsd = translation.costUsd, wallMs = System.currentTimeMillis() - t0),
                cache = collectCache(analysis, translation), warnings = warnings,
                regionDiag = regionDiag(analysis, translation))
        }
        val render = render(erase.cleaned, analysis, translation, overrides)
        if (render.error != null) {
            warnings.add(render.error)
            return SmartPageOutcome(SmartStatus.FAILED, null, erase.cleaned, analysis.textMask, analysis, translation,
                erase, render, render.error, statsBase.copy(translateMs = translation.ms, inpaintMs = erase.ms,
                    renderMs = render.ms, promptTokens = translation.promptTokens,
                    completionTokens = translation.completionTokens, costUsd = translation.costUsd,
                    wallMs = System.currentTimeMillis() - t0),
                cache = collectCache(analysis, translation), warnings = warnings,
                regionDiag = regionDiag(analysis, translation))
        }
        val overflow = render.layouts.count { it.overflow }
        val partial = translation.keptIndices.size < regionsWithText || overflow > 0
        if (overflow > 0) warnings.add("有 $overflow 個文字區在最小字級仍放不進框（已保留全文，待手動排版）")
        if (translation.keptIndices.size < regionsWithText) {
            warnings.add("有 ${regionsWithText - translation.keptIndices.size} 個文字區沒有有效譯文，保留原始日文")
        }
        val status = if (partial) SmartStatus.PARTIAL else SmartStatus.DONE
        val stats = statsBase.copy(
            kept = translation.keptIndices.size,
            translateMs = translation.ms, inpaintMs = erase.ms, renderMs = render.ms,
            promptTokens = translation.promptTokens, completionTokens = translation.completionTokens,
            costUsd = translation.costUsd, wallMs = System.currentTimeMillis() - t0,
        )
        return SmartPageOutcome(
            status = status, final = render.final, cleaned = erase.cleaned, mask = analysis.textMask,
            analysis = analysis, translation = translation, erase = erase, render = render,
            error = if (partial) warnings.joinToString("；").take(500) else null,
            stats = stats, cache = collectCache(analysis, translation), warnings = warnings,
            regionDiag = regionDiag(analysis, translation, render)
        )
    }

    private fun collectCache(analysis: AnalysisStage, translation: TranslationStage): Map<String, String> {
        val regions = analysis.regions.filter { it.sourceText.isNotBlank() }
        val out = LinkedHashMap<String, String>()
        translation.translations.filter { it.status == "ok" || it.status == "cached" }.forEach { t ->
            out[t.source.trim()] = t.translated.trim()
        }
        return out
    }

    private fun regionDiag(
        analysis: AnalysisStage,
        translation: TranslationStage,
        render: RenderStage? = null,
        erase: EraseStage? = null,
    ): List<RegionDiag> {
        val regions = analysis.regions.filter { it.sourceText.isNotBlank() }
        val tByIndex = translation.translations.associateBy { it.index }
        val lByIndex = render?.layouts?.associateBy { it.index } ?: emptyMap()
        val keptPairs = translation.keptIndices.mapNotNull { idx -> regions.getOrNull(idx)?.let { idx to it } }
        val eraseByOrig = erase?.strategies?.associate { s ->
            (keptPairs.getOrNull(s.index)?.first ?: s.index) to s.strategy
        } ?: emptyMap()
        return regions.mapIndexed { i, r ->
            val t = tByIndex[i]
            val l = lByIndex[i]
            RegionDiag(
                index = i, x0 = r.x0, y0 = r.y0, x1 = r.x1, y1 = r.y1, direction = r.direction,
                source = r.sourceText, translated = r.translatedText,
                ocrProb = r.lines.maxOfOrNull { it.ocrProb } ?: 0f,
                ocrStrategy = r.lines.map { it.ocrStrategy }.filter { it.isNotBlank() }.joinToString("+"),
                translationStatus = t?.status ?: "failed", translationError = t?.error,
                eraseStrategy = eraseByOrig[i],
                layoutFontSize = l?.fontSize ?: 0, layoutOverflow = l?.overflow ?: false, layoutNote = l?.note,
            )
        }
    }

    private fun validTranslation(src: String, tr: String): Boolean {
        val t = tr.trim()
        if (t.isEmpty()) return false
        if (t.all { it.isDigit() }) return false
        if (src.trim().equals(t, ignoreCase = true)) return false
        return true
    }

    private fun blankMask(page: Bitmap): Bitmap {
        val b = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
        Canvas(b).drawColor(android.graphics.Color.BLACK)
        return b
    }

    private fun bbox(line: TextLine, w: Int, h: Int): FloatArray? {
        if (line.quad.isEmpty()) return null
        var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE
        var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
        for (p in line.quad) {
            x0 = min(x0, p.x); y0 = min(y0, p.y)
            x1 = max(x1, p.x); y1 = max(y1, p.y)
        }
        if (x1 <= x0 || y1 <= y0) return null
        return floatArrayOf(x0.coerceIn(0f, w.toFloat()), y0.coerceIn(0f, h.toFloat()),
            x1.coerceIn(0f, w.toFloat()), y1.coerceIn(0f, h.toFloat()))
    }

    private fun overlaps(a: TextLine, b: TextLine): Boolean {
        val b1 = bbox(a, Int.MAX_VALUE, Int.MAX_VALUE) ?: return false
        val b2 = bbox(b, Int.MAX_VALUE, Int.MAX_VALUE) ?: return false
        val ix = max(0f, min(b1[2], b2[2]) - max(b1[0], b2[0]))
        val iy = max(0f, min(b1[3], b2[3]) - max(b1[1], b2[1]))
        val ia = ix * iy
        val a1 = (b1[2] - b1[0]) * (b1[3] - b1[1])
        val a2 = (b2[2] - b2[0]) * (b2[3] - b2[1])
        val union = a1 + a2 - ia
        if (union <= 0f) return false
        if (ia / union > 0.25f) return true
        val c1x = (b1[0] + b1[2]) / 2; val c1y = (b1[1] + b1[3]) / 2
        val c2x = (b2[0] + b2[2]) / 2; val c2y = (b2[1] + b2[3]) / 2
        val minSide = min(min(b1[2] - b1[0], b1[3] - b1[1]), min(b2[2] - b2[0], b2[3] - b2[1]))
        return abs(c1x - c2x) < minSide * 0.5f && abs(c1y - c2y) < minSide * 0.5f
    }

    private fun orMask(dst: Bitmap, src: Bitmap, ox: Int, oy: Int) {
        val sw = min(src.width, dst.width - ox)
        val sh = min(src.height, dst.height - oy)
        if (sw <= 0 || sh <= 0) return
        val s = IntArray(sw * sh); src.getPixels(s, 0, sw, 0, 0, sw, sh)
        val d = IntArray(sw * sh); dst.getPixels(d, 0, sw, ox, oy, sw, sh)
        var changed = false
        for (i in s.indices) {
            if ((s[i] and 0xFF) > 127 && (d[i] and 0xFF) <= 127) { d[i] = 0xFFFFFFFF.toInt(); changed = true }
        }
        if (changed) dst.setPixels(d, 0, sw, ox, oy, sw, sh)
    }
}
