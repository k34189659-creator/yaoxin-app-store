package tw.dafeiyu.mangatranslator.core

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import li.joye.yakuyomi.engine.Detector
import li.joye.yakuyomi.engine.EngineConfig
import li.joye.yakuyomi.engine.Grouping
import li.joye.yakuyomi.engine.Inpainter
import li.joye.yakuyomi.engine.LlmTranslator
import li.joye.yakuyomi.engine.Ocr
import li.joye.yakuyomi.engine.OcrRemedyMode
import li.joye.yakuyomi.engine.PpOcrV6
import li.joye.yakuyomi.engine.Pt
import li.joye.yakuyomi.engine.RegionEraseInfo
import li.joye.yakuyomi.engine.RegionInspection
import li.joye.yakuyomi.engine.RegionLayout
import li.joye.yakuyomi.engine.RegionOverride
import li.joye.yakuyomi.engine.SmartInpaintResult
import li.joye.yakuyomi.engine.SmartRenderer
import li.joye.yakuyomi.engine.TextKind
import li.joye.yakuyomi.engine.TextLine
import li.joye.yakuyomi.engine.TextRegion
import li.joye.yakuyomi.engine.Translator
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** 一頁的階段結果；任何階段失敗都保留已完成的中間結果給診斷。 */
enum class SmartStatus { DONE, PARTIAL, SKIPPED, FAILED }

data class AnalysisStage(
    val lines: List<TextLine>,
    val regions: List<TextRegion>,
    val textMask: Bitmap,
    val detectMs: Long,
    val ocrMs: Long,
    val detectionPasses: Int,
    val addedLines: Int,
    val ocrRemedyCount: Int,
    val errors: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    /** 每個「有原文」區的檢查結果（索引與 translate/erase/render 的順序一致）。 */
    val inspections: Map<Int, RegionInspection> = emptyMap(),
    val ocrEngine: String = "",
    val ppOnly: Int = 0,
    val ncnnOnly: Int = 0,
    val disagreements: Int = 0,
)

data class RegionTranslationInfo(
    val index: Int,
    val source: String,
    val translated: String,
    val status: String, // ok / cached / failed / preserved
    val error: String? = null,
)

data class TranslationStage(
    val translations: List<RegionTranslationInfo>,
    val keptIndices: List<Int>,
    val ms: Long,
    val promptTokens: Int,
    val completionTokens: Int,
    val costUsd: Double,
    val error: String? = null,
    val raw: String? = null,
    /** 因為是藝術字／不確定區而「刻意不擦、不覆蓋」的區。 */
    val preservedIndices: List<Int> = emptyList(),
)

data class EraseStage(
    val cleaned: Bitmap,
    val strategies: List<RegionEraseInfo>,
    val ms: Long,
    val method: String,
    val error: String? = null,
)

data class RenderStage(
    val final: Bitmap,
    val layouts: List<RegionLayout>,
    val ms: Long,
    val error: String? = null,
)

data class SmartPageOutcome(
    val status: SmartStatus,
    val final: Bitmap?,
    val cleaned: Bitmap?,
    val mask: Bitmap?,
    val analysis: AnalysisStage?,
    val translation: TranslationStage?,
    val erase: EraseStage?,
    val render: RenderStage?,
    val error: String?,
    val stats: PageStatsInfo,
    val cache: Map<String, String> = emptyMap(),
    val warnings: List<String> = emptyList(),
    val regionDiag: List<RegionDiag> = emptyList(),
)

/** 診斷每區的資料（可存 meta，不含 API Key）。 */
data class RegionDiag(
    val index: Int,
    val x0: Float,
    val y0: Float,
    val x1: Float,
    val y1: Float,
    val direction: String,
    val source: String,
    val translated: String,
    val ocrProb: Float,
    val ocrStrategy: String,
    val translationStatus: String,
    val translationError: String?,
    val eraseStrategy: String?,
    val layoutFontSize: Int,
    val layoutOverflow: Boolean,
    val layoutNote: String?,
    val kind: String = "",
    val eraseSafe: Boolean = true,
)

/**
 * 0.3.0 分階段管線：
 *   多引擎偵測/OCR（PP-OCRv6 + NCNN 融合）→ 分群 → **區域檢查（氣泡內緣/類型/樣式）**
 *   → 翻譯（快取／保留區跳過）→ 智慧擦字（只擦安全區）→ 排版（真實字度量＋輪廓）→ **變更稽核**。
 *
 * 與 0.2.0 的差異：
 *  - OCR 不再只有一套；低信心行由第二套補救，偵測漏抓由第二套偵測補回。
 *  - 翻譯前先做區域檢查：藝術字/不確定區**不擦不蓋**，避免把畫面擦壞（也不會被算成「失敗」）。
 *  - 排版用真實字度量與氣泡內緣，不再「估算字級後二次縮小」。
 *  - 每頁輸出前做變更稽核：若最終圖與原圖幾乎相同，明確標失敗並記錄原因，不再默默當成完成。
 */
class SmartTranslator(
    private val detector: Detector,
    private val ocr: Ocr,
    private val inpainter: Inpainter,
    private val translator: LlmTranslator?,
    private val mockTranslator: Translator? = null,
    private val config: EngineConfig = EngineConfig(),
    private val remedy: OcrRemedyMode = OcrRemedyMode.BALANCED,
    private val smartInpaint: Boolean = true,
    private val pp: PpOcrV6? = null,
    private val mlkit: MlKitLatinOcr? = null,
    private val ocrMode: OcrMode = OcrMode.STANDARD,
    private val sourceLang: SourceLanguage = SourceLanguage.AUTO,
) {

    // ---------------- 1. 偵測 + OCR ----------------

    suspend fun analyze(page: Bitmap): AnalysisStage {
        val errors = ArrayList<String>()
        val warnings = ArrayList<String>()
        var passes = 1
        val tDet0 = System.currentTimeMillis()

        val hybrid = HybridOcr(detector, ocr, pp, mlkit, ocrMode, sourceLang, config, remedy)
        val first = try {
            hybrid.detectAndRecognize(page)
        } catch (t: Throwable) {
            return AnalysisStage(
                emptyList(), emptyList(), blankMask(page),
                System.currentTimeMillis() - tDet0, 0, passes, 0, 0,
                errors + "偵測失敗：${t.message ?: t.javaClass.simpleName}",
            )
        }
        warnings.addAll(first.warnings)
        // 英文 OCR 模型不就緒時回報失敗而非「沒有文字／SKIPPED」，供使用者修復重試。
        if (sourceLang == SourceLanguage.ENGLISH && first.lines.isEmpty() &&
            first.warnings.any { it.contains("英文 OCR") }) {
            errors.add("英文文字辨識未成功：請檢查 ML Kit 或改用清晰原圖重試")
        }
        val lines = ArrayList(first.lines)
        val mask = first.mask
        var added = 0
        val detectMsBase = System.currentTimeMillis() - tDet0

        val tOcr0 = System.currentTimeMillis()
        if (sourceLang == SourceLanguage.ENGLISH) {
            // 英文只有 ML Kit，無日文補抓；偵測器的遮罩仍保留。
        } else if (ocrMode == OcrMode.FAST) {
            // FAST 已在 hybrid 內完成 NCNN 辨識；補救輪仍依 remedy 設定。
            if (remedy != OcrRemedyMode.OFF) {
                added += remedyPasses(page, lines, mask, hybrid, warnings)
            }
        } else {
            added += remedyPasses(page, lines, mask, hybrid, warnings)
        }
        val ocrMs = System.currentTimeMillis() - tOcr0

        val regions = try {
            MangaReadingOrder.sort(Grouping.group(lines), sourceLang)
        } catch (t: Throwable) {
            errors.add("分群失敗：${t.message}")
            emptyList()
        }

        // 區域檢查：氣泡內緣、文字類型、原字樣式（擦字與嵌字共用同一份）
        val textRegions = regions.filter { it.sourceText.isNotBlank() }
        val inspections = LinkedHashMap<Int, RegionInspection>(textRegions.size)
        textRegions.forEachIndexed { i, r ->
            inspections[i] = try {
                li.joye.yakuyomi.engine.RegionAnalyzer.inspect(page, r)
            } catch (t: Throwable) {
                warnings.add("區域檢查失敗（第 ${i + 1} 區）：${t.message}")
                RegionInspection(
                    kind = TextKind.UNKNOWN,
                    shape = li.joye.yakuyomi.engine.typeset.RectRegion(
                        li.joye.yakuyomi.engine.typeset.LayoutBox(r.x0, r.y0, r.x1, r.y1),
                    ),
                    interiorFound = false,
                    style = li.joye.yakuyomi.engine.TextStyleInfo(
                        android.graphics.Color.BLACK, android.graphics.Color.WHITE,
                        false, false, false, 0f, 255f, 0f,
                    ),
                    eraseSafe = false,
                    note = "區域檢查例外，暫時保留原圖以免誤擦",
                )
            }
        }
        // 保底：若整頁每個區域都被判為「不可擦」，頁面會完全沒變更（使用者回報的整頁保留原圖）。
        // 此時讓字數足夠的區域改用保守擦字，短小裝飾/音效仍保留。
        val ensured = li.joye.yakuyomi.engine.PreservePolicy.ensureProgress(textRegions, inspections)
        if (ensured.forced) {
            warnings.add("整頁區域皆為不確定類型，已對 ${ensured.forcedIndices.size} 個較長文字區改用保守擦字（避免整頁未處理）")
        }
        val effectiveInspections = ensured.inspections
        val preserved = effectiveInspections.count { !it.value.eraseSafe }
        if (preserved > 0) warnings.add("$preserved 個區域屬於藝術字/不確定類型，保留原圖")

        val remedyCount = lines.count { it.ocrStrategy.isNotBlank() && it.ocrStrategy != "normal" }
        passes = first.passes
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
            inspections = effectiveInspections,
            ocrEngine = first.engine,
            ppOnly = first.ppOnly,
            ncnnOnly = first.ncnnOnly,
            disagreements = first.disagreements,
        )
    }

    /** 0.2.0 已有的兩段補救（局部放大、整頁切片），改用融合辨識。回傳新增行數。 */
    private suspend fun remedyPasses(
        page: Bitmap,
        lines: ArrayList<TextLine>,
        mask: Bitmap,
        hybrid: HybridOcr,
        warnings: MutableList<String>,
    ): Int {
        if (remedy == OcrRemedyMode.OFF) return 0
        var added = 0
        // 第二輪：空讀/低信心行框附近裁切放大重新偵測
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
            val cw = x1 - x0
            val ch = y1 - y0
            if (cw < 32 || ch < 32) continue
            val crop = Bitmap.createBitmap(page, x0, y0, cw, ch)
            try {
                val d = detector.detect(
                    crop,
                    config.detector.copy(dbnetInputSize = 1024, dbBinThreshold = 0.45f, dbBoxThreshold = 0.60f),
                )
                for (nl in d.lines) {
                    val q = nl.quad.map { Pt(it.x + x0, it.y + y0) }
                    val cand = TextLine(q, nl.score)
                    if (lines.none { overlaps(it, cand) } && newLines.none { overlaps(it, cand) }) newLines.add(cand)
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
                hybrid.recognizeFused(page, newLines)
            } catch (t: Throwable) {
                warnings.add("補抓行 OCR 失敗：${t.message}")
            }
            val kept = newLines.filter { !OcrFusion.isJunk(it.text, it.ocrProb) }
            lines.addAll(kept)
            added += kept.size
        }

        // 第三輪：高品質或整頁完全沒偵測到 → 2×2 重疊切片
        if (remedy == OcrRemedyMode.HIGH || lines.isEmpty()) {
            val tileW = (page.width * 0.56f).toInt().coerceAtLeast(64)
            val tileH = (page.height * 0.56f).toInt().coerceAtLeast(64)
            val stepX = max(1, page.width - tileW)
            val stepY = max(1, page.height - tileH)
            val xs = listOf(0, stepX).distinct()
            val ys = listOf(0, stepY).distinct()
            val highLines = ArrayList<TextLine>()
            for (ty in ys) for (tx in xs) {
                val cw = min(tileW, page.width - tx)
                val ch = min(tileH, page.height - ty)
                if (cw < 64 || ch < 64) continue
                val crop = Bitmap.createBitmap(page, tx, ty, cw, ch)
                try {
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
                    hybrid.recognizeFused(page, highLines)
                } catch (t: Throwable) {
                    warnings.add("高品質補抓 OCR 失敗：${t.message}")
                }
                val kept = highLines.filter { !OcrFusion.isJunk(it.text, it.ocrProb) }
                lines.addAll(kept)
                added += kept.size
            }
        }
        return added
    }

    // ---------------- 2. 翻譯 ----------------

    suspend fun translate(analysis: AnalysisStage, cache: Map<String, String>): TranslationStage {
        val t0 = System.currentTimeMillis()
        val regions = analysis.regions.filter { it.sourceText.isNotBlank() }
        val result = ArrayList<RegionTranslationInfo>(regions.size)
        val kept = ArrayList<Int>()
        val preserved = ArrayList<Int>()
        val pending = ArrayList<Int>()
        val pendingSources = ArrayList<String>()

        regions.forEachIndexed { i, r ->
            val insp = analysis.inspections[i]
            if (insp != null && !insp.eraseSafe) {
                preserved.add(i)
                result.add(RegionTranslationInfo(i, r.sourceText, "", "preserved", insp.note))
                return@forEachIndexed
            }
            val hit = cache[r.sourceText.trim()]
            if (!hit.isNullOrBlank() && validTranslation(r.sourceText, hit)) {
                r.translatedText = hit
                result.add(RegionTranslationInfo(i, r.sourceText, hit, "cached"))
                kept.add(i)
            } else {
                pending.add(i)
                pendingSources.add(r.sourceText)
            }
        }

        var error: String? = null
        var raw: String? = null
        var prompt = 0
        var completion = 0
        var cost = 0.0
        if (pendingSources.isNotEmpty()) {
            val mock = mockTranslator
            var translated: List<String>
            if (mock != null) {
                translated = runCatching { mock.translate(pendingSources) }
                    .getOrElse { error = "模擬翻譯失敗：${it.message}"; pendingSources }
            } else if (translator != null) {
                val llm = translator
                var r = llm.translateDetailed(pendingSources)
                // 整頁 0 有效譯文（模型拒絕/回原文）→ 再試一次；只有這種「整頁會消失」的情況才多花一次費用。
                if (r.error == null && pendingSources.indices.none { k ->
                        validTranslation(pendingSources[k], r.translations.getOrElse(k) { "" })
                    }
                ) {
                    val retry = runCatching { llm.translateDetailed(pendingSources) }.getOrNull()
                    if (retry != null && retry.translations.anyIndexed { k, tr ->
                            validTranslation(pendingSources[k], tr)
                        }
                    ) r = retry
                }
                translated = r.translations
                error = r.error
                raw = r.raw
                r.usage?.let { prompt = it.promptTokens; completion = it.completionTokens; cost = it.costUsd ?: 0.0 }
            } else {
                error = "沒有可用的翻譯器（未設定 API Key）"
                translated = pendingSources
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
        preserved.sort()
        return TranslationStage(
            translations = result, keptIndices = kept, ms = System.currentTimeMillis() - t0,
            promptTokens = prompt, completionTokens = completion, costUsd = cost,
            error = error, raw = raw, preservedIndices = preserved,
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
            // 模型或記憶體嚴重出錯：保持原圖並讓該頁失敗可重試，禁止默默大面積平塗。
            EraseStage(page.copy(Bitmap.Config.ARGB_8888, true), emptyList(),
                System.currentTimeMillis() - t0, "failed", "擦字引擎失敗（保留原圖）：${t.message ?: t.javaClass.simpleName}")
        }
    }

    // ---------------- 4. 排版 ----------------

    fun render(
        cleaned: Bitmap,
        analysis: AnalysisStage,
        translation: TranslationStage,
        overrides: Map<Int, RegionOverride> = emptyMap(),
        original: Bitmap? = null,
    ): RenderStage {
        val t0 = System.currentTimeMillis()
        val regions = analysis.regions.filter { it.sourceText.isNotBlank() }
        // kept 是「原始 region index」；排版器收到的是壓縮後清單，索引要映射。
        val pairs = translation.keptIndices.mapNotNull { idx -> regions.getOrNull(idx)?.let { idx to it } }
        val kept = pairs.map { it.second }
        if (kept.isEmpty()) return RenderStage(cleaned.copy(Bitmap.Config.ARGB_8888, true), emptyList(), 0)
        val posOverrides = pairs.mapIndexedNotNull { pos, (orig, _) -> overrides[orig]?.let { pos to it } }.toMap()
        val posInspections = pairs.mapIndexedNotNull { pos, (orig, _) -> analysis.inspections[orig]?.let { pos to it } }.toMap()
        return try {
            val r = SmartRenderer.renderDetailed(
                cleaned, kept, config.render, null, posOverrides, posInspections, original ?: cleaned,
            )
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
        val analysis = analyze(page)
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
                analysis.errors.joinToString("；").ifBlank {
                    if (analysis.lines.isEmpty()) "偵測不到任何文字（含高品質切片）" else "偵測到文字但 OCR 全空"
                },
                statsBase.copy(wallMs = System.currentTimeMillis() - t0), warnings = warnings,
                regionDiag = emptyList(),
            )
        }
        val translation = translate(analysis, cache)
        val regionsWithText = analysis.regions.count { it.sourceText.isNotBlank() }
        if (translation.keptIndices.isEmpty()) {
            val preservedOnly = translation.preservedIndices.size == regionsWithText &&
                translation.preservedIndices.isNotEmpty()
            val status = if (preservedOnly && translation.error == null) SmartStatus.SKIPPED else SmartStatus.FAILED
            val msg = translation.error ?: if (preservedOnly) {
                "全部文字區屬於藝術字/不確定類型，依規則保留原圖"
            } else {
                "全部文字區都沒有有效譯文（模型拒絕或回傳原文）；可重試"
            }
            return SmartPageOutcome(status, null, null, analysis.textMask, analysis, translation, null, null,
                msg, statsBase.copy(
                    translateMs = translation.ms, promptTokens = translation.promptTokens,
                    completionTokens = translation.completionTokens, costUsd = translation.costUsd,
                    wallMs = System.currentTimeMillis() - t0,
                ),
                cache = collectCache(analysis, translation), warnings = warnings,
                regionDiag = regionDiag(analysis, translation))
        }
        val erase = erase(page, analysis, translation)
        if (erase.error != null) warnings.add(erase.error)
        val failedRepairPositions = erase.strategies.filter { it.strategy == "failed-kept" }.map { it.index }.toSet()
        val goodIndices = translation.keptIndices.filterIndexed { pos, _ -> pos !in failedRepairPositions }
        if (failedRepairPositions.isNotEmpty()) {
            warnings.add("有 ${failedRepairPositions.size} 個區域的背景修補失敗，已保留原文待人工修正")
        }
        if (erase.method == "failed" || goodIndices.isEmpty()) {
            val msg = erase.error ?: "所有待翻區域背景修補失敗，已保留原圖"
            return SmartPageOutcome(SmartStatus.FAILED, null, erase.cleaned, analysis.textMask,
                analysis, translation, erase, null, msg, statsBase.copy(
                    translateMs = translation.ms, inpaintMs = erase.ms,
                    wallMs = System.currentTimeMillis() - t0),
                cache = collectCache(analysis, translation), warnings = warnings,
                regionDiag = regionDiag(analysis, translation, erase = erase))
        }
        if (erase.cleaned.width != page.width || erase.cleaned.height != page.height) {
            return SmartPageOutcome(SmartStatus.FAILED, null, erase.cleaned, analysis.textMask, analysis, translation,
                erase, null, "擦字輸出尺寸與原圖不同，已保留原圖", statsBase.copy(wallMs = System.currentTimeMillis() - t0),
                warnings = warnings, regionDiag = regionDiag(analysis, translation))
        }
        val renderTranslation = translation.copy(keptIndices = goodIndices)
        val render = render(erase.cleaned, analysis, renderTranslation, overrides, page)
        if (render.error != null) {
            warnings.add(render.error)
            return SmartPageOutcome(SmartStatus.FAILED, null, erase.cleaned, analysis.textMask, analysis, translation,
                erase, render, render.error, statsBase.copy(translateMs = translation.ms, inpaintMs = erase.ms,
                    renderMs = render.ms, promptTokens = translation.promptTokens,
                    completionTokens = translation.completionTokens, costUsd = translation.costUsd,
                    wallMs = System.currentTimeMillis() - t0),
                cache = collectCache(analysis, translation), warnings = warnings,
                regionDiag = regionDiag(analysis, translation, render, erase))
        }
        // 任何仍會溢出文字框的區域，還原原始區域、保留原文；其餘成功區不受影響。
        val overflowIndices = render.layouts.filter { it.overflow }.map { it.index }.toSet()
        if (overflowIndices.isNotEmpty()) {
            val sourceRegions = analysis.regions.filter { it.sourceText.isNotBlank() }
            overflowIndices.forEach { idx -> sourceRegions.getOrNull(idx)?.let { r ->
                restoreOriginalRegion(render.final, page, r)
            } }
            warnings.add("有 ${overflowIndices.size} 個文字區無法在可讀字級內排入框，已保留原文待手動編輯")
        }
        // 變更稽核：真的沒有改到任何像素 → 不能宣稱完成
        val changed = changedRatio(page, render.final)
        // 取樣 160×160 = 25,600 點；只有「完全沒有任何一個取樣點改變」才判定沒生效。
        if (changed < 0.00002f) {
            val msg = "輸出與原圖幾乎相同（變更像素 ${"%.4f".format(changed * 100)}%）" +
                "：譯文或排版可能未生效，已標記失敗以便重試"
            return SmartPageOutcome(SmartStatus.FAILED, null, erase.cleaned, analysis.textMask, analysis, translation,
                erase, render, msg, statsBase.copy(translateMs = translation.ms, inpaintMs = erase.ms,
                    renderMs = render.ms, promptTokens = translation.promptTokens,
                    completionTokens = translation.completionTokens, costUsd = translation.costUsd,
                    wallMs = System.currentTimeMillis() - t0),
                cache = collectCache(analysis, translation), warnings = warnings + msg,
                regionDiag = regionDiag(analysis, translation, render, erase))
        }
        val overflow = render.layouts.count { it.overflow }
        val preservedCount = translation.preservedIndices.size
        val partial = goodIndices.size < regionsWithText || overflow > 0
        if (overflow > 0) warnings.add("有 $overflow 個文字區在最小字級仍放不進框（已保留全文，待手動排版）")
        if (translation.keptIndices.size < regionsWithText) {
            warnings.add("有 ${regionsWithText - translation.keptIndices.size} 個文字區沒有有效譯文或刻意保留原文")
        }
        if (preservedCount > 0) warnings.add("$preservedCount 個藝術字/不確定區保留原圖")
        val status = if (partial) SmartStatus.PARTIAL else SmartStatus.DONE
        val stats = statsBase.copy(
            kept = goodIndices.size - overflow,
            translateMs = translation.ms, inpaintMs = erase.ms, renderMs = render.ms,
            promptTokens = translation.promptTokens, completionTokens = translation.completionTokens,
            costUsd = translation.costUsd, wallMs = System.currentTimeMillis() - t0,
        )
        return SmartPageOutcome(
            status = status, final = render.final, cleaned = erase.cleaned, mask = analysis.textMask,
            analysis = analysis, translation = translation, erase = erase, render = render,
            error = if (partial) warnings.joinToString("；").take(500) else null,
            stats = stats, cache = combineCache(analysis, translation), warnings = warnings,
            regionDiag = regionDiag(analysis, translation, render, erase),
        )
    }

    // ---------------------------------------------------------------- helpers

    /** 發生字數溢出時只復原原文框，避免譯文跨出對話框。 */
    private fun restoreOriginalRegion(target: Bitmap, source: Bitmap, region: TextRegion) {
        val x0 = (region.x0.toInt() - 8).coerceIn(0, source.width - 1)
        val y0 = (region.y0.toInt() - 8).coerceIn(0, source.height - 1)
        val x1 = (region.x1.toInt() + 8).coerceIn(x0 + 1, source.width)
        val y1 = (region.y1.toInt() + 8).coerceIn(y0 + 1, source.height)
        val w = x1 - x0
        val h = y1 - y0
        val px = IntArray(w*h)
        source.getPixels(px,0,w,x0,y0,w,h)
        target.setPixels(px,0,w,x0,y0,w,h)
    }

    /**
     * 取樣比較兩張圖；回傳「有差異的取樣點比例」。
     * 只取樣 ~6000 點，成本固定，不會因頁面大小爆增。
     */
    private fun changedRatio(a: Bitmap, b: Bitmap): Float {
        if (a.width != b.width || a.height != b.height) return 1f
        val steps = 160
        var diff = 0
        var total = 0
        for (iy in 0 until steps) {
            val y = (a.height - 1) * iy / (steps - 1)
            for (ix in 0 until steps) {
                val x = (a.width - 1) * ix / (steps - 1)
                val p = a.getPixel(x, y)
                val q = b.getPixel(x, y)
                if (p != q) diff++
                total++
            }
        }
        if (total == 0) return 0f
        return diff.toFloat() / total
    }

    private fun collectCache(analysis: AnalysisStage, translation: TranslationStage): Map<String, String> {
        val regions = analysis.regions.filter { it.sourceText.isNotBlank() }
        val out = LinkedHashMap<String, String>()
        translation.translations.filter { it.status == "ok" || it.status == "cached" }.forEach { t ->
            out[t.source.trim()] = t.translated.trim()
        }
        return out
    }

    /** 新舊快取合併：不能因為本頁部分失敗就丟掉先前已付費的快取。 */
    private fun combineCache(analysis: AnalysisStage, translation: TranslationStage): Map<String, String> =
        collectCache(analysis, translation)

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
            val insp = analysis.inspections[i]
            RegionDiag(
                index = i, x0 = r.x0, y0 = r.y0, x1 = r.x1, y1 = r.y1, direction = r.direction,
                source = r.sourceText, translated = r.translatedText,
                ocrProb = r.lines.maxOfOrNull { it.ocrProb } ?: 0f,
                ocrStrategy = r.lines.map { it.ocrStrategy }.filter { it.isNotBlank() }.joinToString("+"),
                translationStatus = t?.status ?: "failed", translationError = t?.error,
                eraseStrategy = eraseByOrig[i],
                layoutFontSize = l?.fontSize ?: 0, layoutOverflow = l?.overflow ?: false, layoutNote = l?.note,
                kind = insp?.kind?.name ?: "",
                eraseSafe = insp?.eraseSafe ?: true,
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
        var x0 = Float.MAX_VALUE
        var y0 = Float.MAX_VALUE
        var x1 = -Float.MAX_VALUE
        var y1 = -Float.MAX_VALUE
        for (p in line.quad) {
            x0 = min(x0, p.x); y0 = min(y0, p.y)
            x1 = max(x1, p.x); y1 = max(y1, p.y)
        }
        if (x1 <= x0 || y1 <= y0) return null
        return floatArrayOf(
            x0.coerceIn(0f, w.toFloat()), y0.coerceIn(0f, h.toFloat()),
            x1.coerceIn(0f, w.toFloat()), y1.coerceIn(0f, h.toFloat()),
        )
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
        val c1x = (b1[0] + b1[2]) / 2
        val c1y = (b1[1] + b1[3]) / 2
        val c2x = (b2[0] + b2[2]) / 2
        val c2y = (b2[1] + b2[3]) / 2
        val minSide = min(min(b1[2] - b1[0], b1[3] - b1[1]), min(b2[2] - b2[0], b2[3] - b2[1]))
        return abs(c1x - c2x) < minSide * 0.5f && abs(c1y - c2y) < minSide * 0.5f
    }

    private fun orMask(dst: Bitmap, src: Bitmap, ox: Int, oy: Int) {
        val sw = min(src.width, dst.width - ox)
        val sh = min(src.height, dst.height - oy)
        if (sw <= 0 || sh <= 0) return
        val s = IntArray(sw * sh)
        src.getPixels(s, 0, sw, 0, 0, sw, sh)
        val d = IntArray(sw * sh)
        dst.getPixels(d, 0, sw, ox, oy, sw, sh)
        var changed = false
        for (i in s.indices) {
            if ((s[i] and 0xFF) > 127 && (d[i] and 0xFF) <= 127) {
                d[i] = 0xFFFFFFFF.toInt()
                changed = true
            }
        }
        if (changed) dst.setPixels(d, 0, sw, ox, oy, sw, sh)
    }
}

/** List 版的 anyIndexed（避免建立中間 List）。 */
private inline fun <T> List<T>.anyIndexed(predicate: (Int, T) -> Boolean): Boolean {
    for (i in indices) if (predicate(i, this[i])) return true
    return false
}
