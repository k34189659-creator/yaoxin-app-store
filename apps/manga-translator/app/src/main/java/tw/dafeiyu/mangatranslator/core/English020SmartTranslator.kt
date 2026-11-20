package tw.dafeiyu.mangatranslator.core

import android.graphics.Bitmap
import kotlinx.coroutines.CancellationException
import li.joye.yakuyomi.engine.Detector
import li.joye.yakuyomi.engine.EngineConfig
import li.joye.yakuyomi.engine.EnglishTranslationGuard
import li.joye.yakuyomi.engine.Legacy020Inpainter
import li.joye.yakuyomi.engine.LlmTranslator
import li.joye.yakuyomi.engine.Ocr
import li.joye.yakuyomi.engine.OcrRemedyMode
import li.joye.yakuyomi.engine.RegionOverride
import li.joye.yakuyomi.engine.TextLine
import li.joye.yakuyomi.engine.TextRegion
import li.joye.yakuyomi.engine.Translator

/**
 * English is an independent pipeline. It reuses the installed AOT model, but never executes
 * Legacy020SmartTranslator.runWithAnalysis or its temporary renderer. Japanese remains separate.
 */
class English020SmartTranslator(
    private val detector: Detector,
    private val ocr: Ocr,
    private val mlkit: MlKitLatinOcr,
    private val inpainter: Legacy020Inpainter,
    private val translator: LlmTranslator?,
    private val mockTranslator: Translator? = null,
    private val config: EngineConfig = EngineConfig(),
    @Suppress("UNUSED_PARAMETER") remedy: OcrRemedyMode = OcrRemedyMode.BALANCED,
    @Suppress("UNUSED_PARAMETER") smartInpaint: Boolean = true,
) {
    suspend fun analyze(page: Bitmap): AnalysisStage {
        val started = System.currentTimeMillis()
        val found = HybridOcr(detector, ocr, null, mlkit, OcrMode.FAST, SourceLanguage.ENGLISH,
            config, OcrRemedyMode.OFF).detectAndRecognize(page)
        try {
            val groups = EnglishDialogueGrouping.group(found.lines.map(::bounds)) { a, b ->
                EnglishBubbleBoundary.hasDivider(page, a, b)
            }
            val regions = groups.map { group ->
                val lines = group.map(found.lines::get)
                val left = lines.minOf { it.quad.minOf { p -> p.x } }
                val top = lines.minOf { it.quad.minOf { p -> p.y } }
                val right = lines.maxOf { it.quad.maxOf { p -> p.x } }
                val bottom = lines.maxOf { it.quad.maxOf { p -> p.y } }
                TextRegion(lines, "h", 0f, (left + right) / 2f, (top + bottom) / 2f, right - left, bottom - top)
            }
            return AnalysisStage(found.lines, regions, found.mask, System.currentTimeMillis() - started,
                0L, found.passes, 0, 0,
                errors = if (found.lines.isEmpty() && found.warnings.any { it.contains("英文 OCR") })
                    listOf(found.warnings.joinToString("；")) else emptyList(),
                warnings = found.warnings, ocrEngine = "ML Kit Latin / bounded English pipeline v2")
        } catch (e: Throwable) { found.mask.recycle(); throw e }
    }

    suspend fun run(page: Bitmap, cache: Map<String, String> = emptyMap(),
                    overrides: Map<Int, RegionOverride> = emptyMap()): SmartPageOutcome {
        val started = System.currentTimeMillis()
        val found = analyze(page)
        val regions = found.regions.filter { it.sourceText.isNotBlank() }
        val analysis = found.copy(regions = regions)
        val stats = PageStatsInfo(lines = analysis.lines.size, regions = regions.size,
            detectMs = analysis.detectMs, ocrMs = analysis.ocrMs)
        if (regions.isEmpty()) return SmartPageOutcome(
            if (analysis.errors.isNotEmpty()) SmartStatus.FAILED else SmartStatus.SKIPPED,
            null, null, analysis.textMask, analysis, null, null, null,
            analysis.errors.joinToString("；").ifBlank { null },
            stats.copy(wallMs = System.currentTimeMillis() - started), warnings = analysis.warnings)

        val translation = try { translate(regions, cache) }
            catch (c: CancellationException) { analysis.textMask.recycle(); throw c }
        val validCache = translation.translations.filter { it.status == "ok" || it.status == "cached" }
            .associate { it.source.trim() to it.translated.trim() }
        val translatedStats = stats.copy(translateMs = translation.ms, promptTokens = translation.promptTokens,
            completionTokens = translation.completionTokens, costUsd = translation.costUsd)
        if (translation.keptIndices.isEmpty()) {
            val message = translation.error ?: translation.translations.mapNotNull { it.error }.distinct().joinToString("；")
            return SmartPageOutcome(SmartStatus.FAILED, null, null, analysis.textMask, analysis, translation,
                null, null, message.ifBlank { "沒有有效英文譯文，已保留原圖" },
                translatedStats.copy(wallMs = System.currentTimeMillis() - started), validCache,
                analysis.warnings, diagnostics(regions, translation, null))
        }
        val image = try {
            EnglishImagePipeline.finish(page, analysis.textMask, regions, inpainter, overrides,
                config.render.fontSizeMax, residualOcr = { mlkit.recognize(it) })
        } catch (c: CancellationException) { analysis.textMask.recycle(); throw c
        } catch (e: Exception) {
            return SmartPageOutcome(SmartStatus.FAILED, null, null, analysis.textMask, analysis, translation,
                null, null, "英文圖片處理失敗，保留原圖：${e.message}",
                translatedStats.copy(wallMs = System.currentTimeMillis() - started), validCache,
                analysis.warnings, diagnostics(regions, translation, null))
        }
        // Persist the exact mask used by this pipeline, not a differently dilated legacy mask.
        analysis.textMask.recycle()
        val finalAnalysis = analysis.copy(textMask = image.mask)
        val trErrors = translation.translations.filter { it.status == "failed" }.associate { it.index to it.error.orEmpty() }
        val failures = image.failures + trErrors
        val warnings = (analysis.warnings + image.warnings + failures.entries.sortedBy { it.key }
            .map { "區域 ${it.key + 1}：${it.value}" }).distinct()
        val status = when {
            image.accepted.isEmpty() -> SmartStatus.FAILED
            image.accepted.size < regions.size || image.warnings.isNotEmpty() -> SmartStatus.PARTIAL
            else -> SmartStatus.DONE
        }
        val message = if (status == SmartStatus.DONE) null else warnings.joinToString("；").take(800)
            .ifBlank { "部分區域未完成，已保留原圖" }
        return SmartPageOutcome(status, image.final, image.cleaned, image.mask, finalAnalysis, translation,
            EraseStage(image.cleaned, image.strategies, image.inpaintMs, "english-regional-v2",
                image.failures.filterKeys { it in translation.keptIndices }.values.distinct().joinToString("；").ifBlank { null }),
            RenderStage(image.final, image.layouts, image.renderMs), message,
            translatedStats.copy(kept = image.accepted.size, inpaintMs = image.inpaintMs,
                renderMs = image.renderMs, wallMs = System.currentTimeMillis() - started),
            validCache, warnings, diagnostics(regions, translation, image))
    }

    private suspend fun translate(regions: List<TextRegion>, cache: Map<String, String>): TranslationStage {
        val started = System.currentTimeMillis()
        val infos = arrayListOf<RegionTranslationInfo>()
        val pending = arrayListOf<Int>()
        for ((index, r) in regions.withIndex()) {
            r.translatedText = ""
            val hit = cache[r.sourceText.trim()]
            if (hit != null && EnglishTranslationGuard.validate(r.sourceText, hit) == null) {
                r.translatedText = hit.trim()
                infos.add(RegionTranslationInfo(index, r.sourceText, hit.trim(), "cached"))
            } else pending.add(index)
        }
        var error: String? = null; var raw: String? = null
        var prompt = 0; var completion = 0; var cost = 0.0
        if (pending.isNotEmpty()) {
            val sources = pending.map { regions[it].sourceText }
            var errors: Map<Int, String> = emptyMap()
            val translated = if (mockTranslator != null) {
                try { mockTranslator.translate(sources) }
                catch (c: CancellationException) { throw c }
                catch (e: Exception) { error = "模擬翻譯失敗：${e.message}"; emptyList() }
            } else if (translator != null) {
                val result = translator.translateDetailed(sources, englishGuard = true)
                error = result.error; raw = result.raw; errors = result.regionErrors
                result.usage?.let { prompt = it.promptTokens; completion = it.completionTokens; cost = it.costUsd ?: 0.0 }
                result.translations
            } else { error = "尚未設定 API Key"; emptyList() }
            for ((pos, index) in pending.withIndex()) {
                val source = sources[pos]; val value = translated.getOrNull(pos).orEmpty().trim()
                val reason = errors[pos] ?: EnglishTranslationGuard.validate(source, value)
                if (reason == null) {
                    regions[index].translatedText = value
                    infos.add(RegionTranslationInfo(index, source, value, "ok"))
                } else {
                    // No invalid response enters persisted region text or a successful cache entry.
                    infos.add(RegionTranslationInfo(index, source, "", "failed", reason))
                }
            }
        }
        infos.sortBy { it.index }
        return TranslationStage(infos, infos.filter { it.status != "failed" }.map { it.index },
            System.currentTimeMillis() - started, prompt, completion, cost, error, raw)
    }

    private fun diagnostics(regions: List<TextRegion>, tr: TranslationStage,
                            image: EnglishImagePipeline.Result?): List<RegionDiag> {
        val translations = tr.translations.associateBy { it.index }
        val layouts = image?.layouts?.associateBy { it.index }.orEmpty()
        val strategies = image?.strategies?.associateBy { it.index }.orEmpty()
        return regions.mapIndexed { index, r ->
            val t = translations[index]; val layout = layouts[index]
            val failure = t?.error ?: image?.failures?.get(index)
            RegionDiag(index, r.x0, r.y0, r.x1, r.y1, "h", r.sourceText, r.translatedText,
                r.lines.maxOfOrNull { it.ocrProb } ?: 0f, "mlkit-latin",
                if (t?.status == "failed") "failed" else if (failure != null) "preserved" else t?.status ?: "failed",
                failure, strategies[index]?.strategy, layout?.fontSize ?: 0, layout?.overflow ?: false,
                failure ?: layout?.note, image?.routes?.get(index)?.type?.label.orEmpty(),
                image != null && index in image.accepted)
        }
    }

    private fun bounds(line: TextLine) = EnglishDialogueGrouping.Box(line.quad.minOf { it.x },
        line.quad.minOf { it.y }, line.quad.maxOf { it.x }, line.quad.maxOf { it.y })
}
