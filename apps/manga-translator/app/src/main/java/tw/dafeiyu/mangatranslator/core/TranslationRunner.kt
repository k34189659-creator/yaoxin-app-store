package tw.dafeiyu.mangatranslator.core

import android.graphics.Bitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import li.joye.yakuyomi.engine.Detector
import li.joye.yakuyomi.engine.EngineConfig
import li.joye.yakuyomi.engine.Inpainter
import li.joye.yakuyomi.engine.Legacy020Inpainter
import li.joye.yakuyomi.engine.LlmTranslator
import li.joye.yakuyomi.engine.ModelSet
import li.joye.yakuyomi.engine.Ocr
import li.joye.yakuyomi.engine.OcrRemedyMode
import li.joye.yakuyomi.engine.PageAnalysis
import li.joye.yakuyomi.engine.PpOcrV6
import li.joye.yakuyomi.engine.Translator
import java.io.File
import kotlin.coroutines.coroutineContext

data class RunnerEvent(
    val kind: String,
    val pageIndex: Int,
    val pageName: String,
    val message: String,
)

data class RunnerResult(
    val done: Int,
    val partial: Int,
    val failed: Int,
    val skipped: Int,
    val cancelled: Boolean,
    val fatal: String? = null,
)

/**
 * 0.2.0 runner：使用 [SmartTranslator] 的分階段管線，每頁完成立刻落地。
 *
 * 狀態語義（相對 0.1.0 修正）：
 *  - DONE：所有文字區都有有效譯文、排版無 overflow。
 *  - PARTIAL：部分文字區失敗或排版 overflow；已輸出完成的區域，失敗區保留原始日文、標記待檢查。
 *  - FAILED：全部失敗/API 錯誤/擦字或排版例外；保留原圖，可重試。
 *  - SKIPPED：沒有可翻文字（不重試）。
 *
 * 翻譯快取存在 meta/<page>.diag.json：重試、重做擦字/排版不會重複扣 API。
 */
class TranslationRunner(
    private val projectDir: File,
    private val project: Project,
    private val models: ModelSet,
    private val alphabet: List<String>,
    private val apiKey: String?,
    private val config: EngineConfig,
    private val retries: Int = 2,
    private val mock: Boolean = false,
    private val remedy: OcrRemedyMode = OcrRemedyMode.BALANCED,
    private val smartInpaint: Boolean = true,
    private val includeFailed: Boolean = false,
    private val onlyIndex: Int? = null,
    /** PP-OCRv6 模型（det, rec）；null＝未下載，標準/高品質模式會自動回退 NCNN。 */
    private val ppPaths: Pair<String, String>? = null,
    private val ppDict: List<String> = emptyList(),
    private val ocrMode: OcrMode = OcrMode.STANDARD,
    private val ocrLang: SourceLanguage = SourceLanguage.AUTO,
    private val styleMode: String = "original",
    private val exportFormat: String = "original",
    private val onEvent: (RunnerEvent) -> Unit = {},
) {
    private class Session(
        val detector: Detector,
        val ocr: Ocr,
        val inpainter: Inpainter?,
        val legacyInpainter: Legacy020Inpainter?,
        val llm: LlmTranslator?,
        val process: suspend (Bitmap, Map<String, String>, Map<Int, li.joye.yakuyomi.engine.RegionOverride>) -> SmartPageOutcome,
        val pp: PpOcrV6? = null,
        val mlkit: MlKitLatinOcr? = null,
    ) : AutoCloseable {
        fun warmUp() {
            detector.warmUp()
            ocr.warmUp()
            inpainter?.warmUp()
            legacyInpainter?.warmUp()
        }

        override fun close() {
            runCatching { detector.close() }
            runCatching { ocr.close() }
            runCatching { inpainter?.close() }
            runCatching { legacyInpainter?.close() }
            runCatching { pp?.close() }
            runCatching { mlkit?.close() }
        }
    }

    suspend fun run(): RunnerResult = withContext(kotlinx.coroutines.Dispatchers.Default) {
        project.status = ProjectStatus.RUNNING
        project.lastError = null
        persist()
        var cancelled = false
        var fatal: String? = null
        var session: Session? = null
        try {
            session = createSession()
            val coreName = if (ocrLang == SourceLanguage.ENGLISH) "英文對話框分組＋0.2.0 擦字嵌字" else "日文 0.2.0 原始核心"
            onEvent(RunnerEvent("engine_ready", -1, "", if (mock) "$coreName 已就緒（模擬翻譯）" else "$coreName 已就緒"))
            val targets = project.pages.filter {
                (onlyIndex == null || it.index == onlyIndex) &&
                    (it.status == PageStatus.PENDING || (includeFailed && it.status == PageStatus.FAILED))
            }
            if (targets.isEmpty()) {
                finish()
                return@withContext RunnerResult(project.doneCount, project.partialCount, project.failedCount, project.skippedCount, false)
            }
            for (page in targets) {
                coroutineContext.ensureActive()
                processPage(session, page)
            }
            finish()
        } catch (c: CancellationException) {
            cancelled = true
            project.pages.filter { it.status == PageStatus.RUNNING }.forEach {
                it.status = PageStatus.PENDING
                it.updatedAt = System.currentTimeMillis()
            }
            project.status = ProjectStatus.PAUSED
            persist()
            onEvent(RunnerEvent("paused", -1, "", "已暫停，進度已儲存"))
            throw c
        } catch (t: Throwable) {
            fatal = t.message ?: t.javaClass.simpleName
            project.status = ProjectStatus.ERROR
            project.lastError = fatal
            project.pages.filter { it.status == PageStatus.RUNNING }.forEach { it.status = PageStatus.PENDING }
            persist()
            onEvent(RunnerEvent("fatal", -1, "", "處理中止：$fatal"))
        } finally {
            runCatching { session?.close() }
        }
        RunnerResult(project.doneCount, project.partialCount, project.failedCount, project.skippedCount, cancelled, fatal)
    }

    private suspend fun createSession(): Session {
        onEvent(RunnerEvent("loading", -1, "", "載入 OCR / 偵測 / 擦字模型…"))
        val detector = Detector(models.detectorNcnn!!, config.detector)
        val ocr = Ocr(models.ocr, alphabet, config.ocr)
        // 共用 0.2.0 擦字模型，但日文分析與英文分析兩條獨立前處理路徑。
        val inpainter: Inpainter? = null
        val legacyInpainter = Legacy020Inpainter(models.aotInpainterNcnn!!,
            if (ocrLang == SourceLanguage.ENGLISH) config.inpainter.copy(maskDilate = 3f, bboxPad = 3) else config.inpainter)
        val llm = if (!mock && !apiKey.isNullOrBlank()) LlmTranslator(apiKey, config.translator) else null
        val mockTranslator = if (mock) MockTranslator() else null
        val pp: PpOcrV6? = null
        val mlkit = if (ocrLang == SourceLanguage.ENGLISH) MlKitLatinOcr() else null
        val process: suspend (Bitmap, Map<String, String>, Map<Int, li.joye.yakuyomi.engine.RegionOverride>) -> SmartPageOutcome =
            if (ocrLang == SourceLanguage.ENGLISH) {
                val english = English020SmartTranslator(
                    detector, ocr, requireNotNull(mlkit), legacyInpainter, llm, mockTranslator,
                    config, remedy, smartInpaint,
                )
                val executor: suspend (Bitmap, Map<String, String>, Map<Int, li.joye.yakuyomi.engine.RegionOverride>) -> SmartPageOutcome =
                    { page, cache, overrides -> english.run(page, cache, overrides) }
                executor
            } else {
                val legacy = Legacy020SmartTranslator(
                    detector = detector, ocr = ocr, inpainter = legacyInpainter,
                    translator = llm, mockTranslator = mockTranslator, config = config,
                    remedy = remedy, smartInpaint = smartInpaint,
                )
                val executor: suspend (Bitmap, Map<String, String>, Map<Int, li.joye.yakuyomi.engine.RegionOverride>) -> SmartPageOutcome =
                    { page, cache, overrides -> legacy.run(page, cache, overrides) }
                executor
            }
        val s = Session(detector, ocr, inpainter, legacyInpainter, llm, process, pp, mlkit)
        s.warmUp()
        return s
    }

    private suspend fun processPage(session: Session, page: PageEntry) {
        page.status = PageStatus.RUNNING
        page.attempts += 1
        page.updatedAt = System.currentTimeMillis()
        persist()
        onEvent(RunnerEvent("page_start", page.index, page.displayName, "正在處理第 ${page.index + 1} 頁"))

        val originalFile = File(projectDir, page.originalFile)
        var bitmap: Bitmap? = null
        try {
            bitmap = ImageIo.decode(originalFile) ?: throw RuntimeException("原圖讀取失敗")
        } catch (e: ImageIo.ImageTooLargeException) {
            markFailed(page, e.message ?: "圖片過大")
            return
        } catch (oom: OutOfMemoryError) {
            markFailed(page, "記憶體不足，無法載入此頁")
            return
        } catch (t: Throwable) {
            markFailed(page, "原圖解碼失敗：${t.message}")
            return
        }

        val cache = LinkedHashMap<String, String>()
        cache.putAll(DiagCodec.cacheFromMeta(projectDir, page))
        val prevDiag = DiagCodec.load(projectDir, page)
        prevDiag?.cache?.let { cache.putAll(it) }
        val overrides = prevDiag?.overrides ?: emptyMap()

        var outcome: SmartPageOutcome? = null
        var lastError: String? = null
        val attempts = retries + 1
        try {
            for (attempt in 1..attempts) {
                coroutineContext.ensureActive()
                val started = System.currentTimeMillis()
                outcome = try {
                    session.process(bitmap, cache, overrides)
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    SmartPageOutcome(
                        status = SmartStatus.FAILED, final = null, cleaned = null, mask = null,
                        analysis = null, translation = null, erase = null, render = null,
                        error = "${t.javaClass.simpleName}: ${t.message}",
                        stats = PageStatsInfo(wallMs = System.currentTimeMillis() - started),
                    )
                }
                val o = outcome!!
                accumulate(o.stats)
                lastError = o.error
                if (o.status != SmartStatus.FAILED) break
                if (attempt >= attempts || !shouldRetry(o)) break
                // A retry must reuse already validated translations and release its discarded image.
                cache.putAll(o.cache)
                listOfNotNull(o.final, o.cleaned, o.mask, o.analysis?.textMask).distinct().forEach {
                    if (!it.isRecycled) it.recycle()
                }
                outcome = null
                onEvent(RunnerEvent("retry", page.index, page.displayName,
                    "失敗：${o.error?.take(80)}；重試 $attempt/$retries"))
                delay(backoff(attempt))
            }
            var o = outcome ?: throw RuntimeException("沒有結果")
            if (ocrLang == SourceLanguage.ENGLISH && o.final != null) {
                val outdated = PatchCodec.load(projectDir, page).count { !eligibleEnglishPatch(it) }
                if (outdated > 0) {
                    val warning = "有 $outdated 個舊版或無效的框選補救尚未重建，本次未套用；可在補救清單重新嵌字"
                    o = o.copy(status = if (o.status == SmartStatus.DONE) SmartStatus.PARTIAL else o.status,
                        warnings = o.warnings + warning, error = listOfNotNull(o.error, warning).joinToString("；"))
                }
            }
            val persisted = runCatching { persistOutcome(page, o, overrides) }
            if (persisted.isFailure) {
                markFailed(page, "結果儲存失敗（磁碟空間？）：${persisted.exceptionOrNull()?.message}")
                return
            }
            when (o.status) {
                SmartStatus.DONE -> {
                    page.status = PageStatus.DONE
                    page.error = null
                    onEvent(RunnerEvent("page_done", page.index, page.displayName,
                        "完成：${o.stats.lines} 行、${o.stats.kept} 區譯文"))
                }
                SmartStatus.PARTIAL -> {
                    page.status = PageStatus.PARTIAL
                    page.error = o.error ?: o.warnings.joinToString("；").take(500)
                    onEvent(RunnerEvent("page_partial", page.index, page.displayName,
                        "部分完成：${o.stats.kept}/${o.stats.regions} 區；失敗區保留原文"))
                }
                SmartStatus.SKIPPED -> {
                    page.status = PageStatus.SKIPPED
                    page.error = o.error ?: "沒有可翻譯文字"
                    onEvent(RunnerEvent("page_skip", page.index, page.displayName, "略過：${page.error}"))
                }
                SmartStatus.FAILED -> {
                    page.status = PageStatus.FAILED
                    page.error = o.error ?: lastError ?: "未知錯誤"
                    onEvent(RunnerEvent("page_failed", page.index, page.displayName,
                        "失敗：${page.error?.take(160)}"))
                }
            }
            persist()
        } finally {
            runCatching { bitmap?.recycle() }
        }
    }

    private fun persistOutcome(page: PageEntry, o: SmartPageOutcome, overrides: Map<Int, li.joye.yakuyomi.engine.RegionOverride>) {
        try {
        // 先存譯圖（只有 DONE/PARTIAL 才有 final）；再存 meta/mask；最後 diag。
        val analysis = o.analysis
        var translatedRel: String? = null
        if (o.final != null) {
            val outExt = ImageIo.extensionFor(page.ext, exportFormat)
            translatedRel = "translated/%06d.%s".format(page.index, outExt)
            val patches = PatchCodec.load(projectDir, page).let { saved ->
                if (ocrLang == SourceLanguage.ENGLISH) saved.filter(::eligibleEnglishPatch) else saved
            }
            val final = if (patches.isEmpty()) o.final else PatchCodec.apply(projectDir, o.final, patches)
            ImageIo.saveAs(final, File(projectDir, translatedRel), outExt, exportFormat)
            if (final !== o.final) final.recycle()
        }
        var metaRel: String? = null
        var maskRel: String? = null
        if (analysis != null && analysis.regions.isNotEmpty()) {
            runCatching {
                // meta 只存「有原文」的區，讓 editor/diag 的 region index 一致（群組可能含空白區）。
                val textRegions = analysis.regions.filter { it.sourceText.isNotBlank() }
                val (m, k) = AnalysisCodec.save(projectDir, page, PageAnalysis(analysis.textMask, textRegions), project.inpaint)
                metaRel = m; maskRel = k
            }
        }
        page.translatedFile = translatedRel
        page.metaFile = metaRel
        page.maskFile = maskRel
        page.regionCount = analysis?.regions?.size ?: 0
        page.translatedRegions = if (ocrLang == SourceLanguage.ENGLISH) o.stats.kept
            else o.translation?.keptIndices?.size ?: 0
        page.stats = o.stats
        page.updatedAt = System.currentTimeMillis()

        val diag = PageDiag(
            pageIndex = page.index,
            createdAt = System.currentTimeMillis(),
            status = o.status.name,
            qualityMode = remedy.name,
            detectionPasses = analysis?.detectionPasses ?: 0,
            addedLines = analysis?.addedLines ?: 0,
            ocrRemedyCount = analysis?.ocrRemedyCount ?: 0,
            detectMs = o.stats.detectMs,
            ocrMs = o.stats.ocrMs,
            translateMs = o.stats.translateMs,
            inpaintMs = o.stats.inpaintMs,
            renderMs = o.stats.renderMs,
            wallMs = o.stats.wallMs,
            lines = o.stats.lines,
            regions = o.stats.regions,
            kept = o.stats.kept,
            promptTokens = o.stats.promptTokens,
            completionTokens = o.stats.completionTokens,
            costUsd = o.stats.costUsd,
            errors = analysis?.errors ?: emptyList(),
            warnings = o.warnings + (o.error?.let { listOf(it) } ?: emptyList()),
            regionDiag = o.regionDiag,
            cache = o.cache,
            overrides = overrides,
            inpaintMethod = o.erase?.method ?: project.inpaint,
            apiError = o.translation?.error,
            stageStatus = mapOf(
                "detect" to if (analysis == null) "failed" else if (analysis.regions.isNotEmpty()) "ok" else "failed",
                "ocr" to if (analysis?.lines.orEmpty().any { it.text.isNotBlank() }) "ok" else "failed",
                "translate" to when {
                    o.translation == null -> "skipped"
                    o.translation.keptIndices.isEmpty() -> "failed"
                    o.translation.error != null -> "partial"
                    else -> "ok"
                },
                "erase" to when {
                    o.erase == null -> "skipped"
                    o.erase.error != null && o.stats.kept > 0 && ocrLang == SourceLanguage.ENGLISH -> "partial"
                    o.erase.error != null -> "failed"
                    else -> "ok"
                },
                "render" to when {
                    o.render == null -> "skipped"
                    o.render.error != null -> "failed"
                    else -> "ok"
                },
                "layout" to if (o.render?.layouts?.any { it.overflow } == true) "partial" else "ok",
            ),
        )
        runCatching { DiagCodec.save(projectDir, page, diag) }

        persist()
        } finally {
            // 不論成功或儲存例外，本頁產生的 bitmap 都要釋放。
            runCatching { o.final?.recycle() }
            runCatching { o.cleaned?.recycle() }
            runCatching { o.mask?.recycle() }
        }
    }

    private fun markFailed(page: PageEntry, reason: String) {
        page.status = PageStatus.FAILED
        page.error = reason
        page.updatedAt = System.currentTimeMillis()
        persist()
        onEvent(RunnerEvent("page_failed", page.index, page.displayName, "失敗：$reason"))
    }

    private fun finish() {
        project.status = when {
            project.pages.any { it.status == PageStatus.PENDING || it.status == PageStatus.RUNNING } -> ProjectStatus.READY
            project.failedCount > 0 || project.partialCount > 0 -> ProjectStatus.DONE_WITH_ERRORS
            else -> ProjectStatus.DONE
        }
        project.updatedAt = System.currentTimeMillis()
        persist()
        onEvent(RunnerEvent("finished", -1, "",
            "結束：完成 ${project.doneCount}、部分 ${project.partialCount}、失敗 ${project.failedCount}、略過 ${project.skippedCount}"))
    }

    private fun accumulate(stats: PageStatsInfo) {
        project.totalPromptTokens += stats.promptTokens
        project.totalCompletionTokens += stats.completionTokens
        project.totalCostUsd += stats.costUsd
    }

    private fun persist() {
        runCatching { ProjectStore(projectDir.parentFile!!).save(project) }
    }

    private fun backoff(attempt: Int): Long = when (attempt) {
        1 -> 2_000L
        2 -> 6_000L
        else -> 12_000L
    }

    private fun shouldRetry(o: SmartPageOutcome): Boolean {
        if (o.analysis?.errors?.isNotEmpty() == true) return false
        val api = o.translation?.error
        if (api != null) {
            if (ocrLang == SourceLanguage.ENGLISH) {
                // Content/ID/length rejection is deterministic, not a reason to buy another reply.
                return Regex("(?i)HTTP (408|429|5[0-9]{2})|SocketTimeoutException|ConnectException|UnknownHostException|InterruptedIOException")
                    .containsMatchIn(api)
            }
            if (Regex("http (400|401|403|404|405|422)").containsMatchIn(api.lowercase())) return false
            return true
        }
        // 擦字/排版是可恢復的 CPU 失敗；翻譯已快取時重跑不會再扣 API。
        return o.erase?.error != null || o.render?.error != null
    }

    private fun eligibleEnglishPatch(patch: ManualPatch): Boolean = patch.renderRevision >= 1 &&
        li.joye.yakuyomi.engine.EnglishTranslationGuard.validate(patch.source, patch.translated) == null &&
        File(projectDir, patch.patchFile).isFile
}

/** 模擬翻譯（僅測試/自測用）：不呼叫任何 API，回傳固定繁中句子以驗證去字＋嵌字＋ZIP 流程。 */
class MockTranslator : Translator {
    private val canned = listOf(
        "這是模擬翻譯，用來測試排版與流程。",
        "第二個對話框的模擬譯文。",
        "不要把手機當成翻譯伺服器。",
        "模擬翻譯不會花費任何 API 費用。",
        "如果看到這段文字，代表嵌字成功。",
    )
    override suspend fun translate(queries: List<String>): List<String> =
        queries.mapIndexed { i, _ -> canned[i % canned.size] }
}
