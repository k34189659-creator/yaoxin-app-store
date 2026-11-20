package tw.dafeiyu.mangatranslator.core

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import li.joye.yakuyomi.engine.EnglishTranslationGuard
import li.joye.yakuyomi.engine.Legacy020Inpainter
import li.joye.yakuyomi.engine.Pt
import li.joye.yakuyomi.engine.RegionEraseInfo
import li.joye.yakuyomi.engine.RegionLayout
import li.joye.yakuyomi.engine.RegionOverride
import li.joye.yakuyomi.engine.TextLine
import li.joye.yakuyomi.engine.TextRegion
import kotlin.coroutines.coroutineContext
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

internal typealias EnglishReconstructor = suspend (Bitmap, List<TextRegion>, Bitmap) -> Bitmap

/** The single English image path used by automatic translation, re-lettering and frame rescue. */
internal object EnglishImagePipeline {
    data class Result(
        val cleaned: Bitmap,
        val final: Bitmap,
        val mask: Bitmap,
        val layouts: List<RegionLayout>,
        val strategies: List<RegionEraseInfo>,
        val accepted: List<Int>,
        val failures: Map<Int, String>,
        val routes: Map<Int, EnglishRegionRoute>,
        val warnings: List<String>,
        val inpaintMs: Long,
        val renderMs: Long,
    )
    private data class Work(val index: Int, val region: TextRegion, val x: Int, val y: Int,
                            val original: IntArray, val plan: EnglishPixelSafety.Plan,
                            val route: EnglishRegionRoute) {
        val width get() = plan.width
        val height get() = plan.height
        val box get() = plan.layout?.let { RectF((x + it.left).toFloat(), (y + it.top).toFloat(),
            (x + it.right).toFloat(), (y + it.bottom).toFloat()) }
    }

    suspend fun finish(
        original: Bitmap,
        detectorMask: Bitmap,
        regions: List<TextRegion>,
        inpainter: Legacy020Inpainter,
        overrides: Map<Int, RegionOverride> = emptyMap(),
        maxFontSize: Int = 48,
        residualOcr: (suspend (Bitmap) -> List<TextLine>)? = null,
    ): Result = finishWithReconstruction(original, detectorMask, regions,
        reconstructor = { crop, localRegions, mask -> inpainter.inpaintSmart(crop, localRegions, mask, "aot").bitmap },
        overrides = overrides, maxFontSize = maxFontSize, residualOcr = residualOcr)

    /** A model boundary, also used by real Android-graphics regression tests with deterministic repair. */
    suspend fun finishWithReconstruction(
        original: Bitmap,
        detectorMask: Bitmap,
        regions: List<TextRegion>,
        reconstructor: EnglishReconstructor,
        overrides: Map<Int, RegionOverride> = emptyMap(),
        maxFontSize: Int = 48,
        residualOcr: (suspend (Bitmap) -> List<TextLine>)? = null,
    ): Result {
        require(original.width == detectorMask.width && original.height == detectorMask.height)
        val failures = linkedMapOf<Int, String>()
        val warnings = arrayListOf<String>()
        val works = arrayListOf<Work>()
        for ((index, r) in regions.withIndex()) {
            coroutineContext.ensureActive()
            val invalid = EnglishTranslationGuard.validate(r.sourceText, r.translatedText)
            if (invalid != null) failures[index] = invalid
            val work = prepare(original, detectorMask, index, r)
            if (work == null) { failures[index] = "文字區範圍無效或過大，已保留原圖"; continue }
            works.add(work)
            work.plan.failure?.let { failures.putIfAbsent(index, it) }
        }
        val routes = works.associate { it.index to it.route }
        val bounds = works.mapNotNull { w -> w.box?.let { w.index to it } }.toMap().toMutableMap()
        // A larger verified bubble can still contain another OCR group. Never take its space.
        for (work in works) {
            val box = bounds[work.index] ?: continue
            if (regions.indices.any { it != work.index && intersects(box, regions[it]) }) {
                val own = RectF(work.region.x0, work.region.y0, work.region.x1, work.region.y1)
                if (!own.intersect(box) || regions.indices.any { it != work.index && intersects(own, regions[it]) })
                    failures[work.index] = "排版範圍與另一段原文重疊，已保留原圖"
                else bounds[work.index] = own
            }
        }
        var plans = EnglishVisualFinish.plan(original.width, original.height,
            works.map { it.index to it.region }, overrides, maxFontSize, routes, bounds)
        for (p in plans) if (!p.fitted) failures.putIfAbsent(p.index, p.note ?: "譯文放不進安全範圍，已保留原圖")
        // Masks also have ownership. Restoration of a failed region must never undo its neighbour.
        val owners = IntArray(original.width * original.height) { -1 }
        for (work in works) {
            for (i in work.plan.edit.indices) if (work.plan.edit[i]) {
                val pos = (work.y + i / work.width) * original.width + work.x + i % work.width
                val previous = owners[pos]
                if (previous >= 0 && previous != work.index) {
                    failures[previous] = "兩段文字的擦字範圍重疊，已保留原圖"
                    failures[work.index] = "兩段文字的擦字範圍重疊，已保留原圖"
                } else owners[pos] = work.index
            }
        }
        // Distinct source groups may have expanded into the same free area even without touching text.
        val fitted = plans.filter { it.fitted && it.index !in failures }
        for (a in fitted.indices) for (b in a + 1 until fitted.size) {
            if (RectF.intersects(fitted[a].box, fitted[b].box)) {
                failures[fitted[a].index] = "相鄰譯文的排版範圍重疊，已保留原圖"
                failures[fitted[b].index] = "相鄰譯文的排版範圍重疊，已保留原圖"
            }
        }
        val cleaned = original.copy(Bitmap.Config.ARGB_8888, true)
        val mask = Bitmap.createBitmap(original.width, original.height, Bitmap.Config.ARGB_8888)
        mask.eraseColor(Color.BLACK)
        val strategies = arrayListOf<RegionEraseInfo>()
        val eraseStart = System.currentTimeMillis()
        try {
            // Four routes execute sequentially against the same immutable original image.
            for (type in EnglishRegionType.values()) for (work in works.filter { it.route.type == type }) {
                coroutineContext.ensureActive()
                unionMask(mask, work)
                if (work.index in failures) continue
                try {
                    // Only verified, smooth enclosed backgrounds can use a fitted colour plane.
                    // Artwork is always model reconstruction; no diffuse/flat-colour fallback.
                    val flat = when (type) {
                        EnglishRegionType.NORMAL_BUBBLE, EnglishRegionType.COLORED_BUBBLE ->
                            EnglishPixelSafety.smoothFill(work.original, work.plan)
                        EnglishRegionType.PLAIN_ART, EnglishRegionType.COLORED_ART -> null
                    }
                    val candidate = flat ?: reconstruct(work, reconstructor)
                    val limited = EnglishPixelSafety.composite(work.original, candidate, work.plan.edit)
                    if (!EnglishPixelSafety.visiblyErased(work.original, limited, work.plan.ink)) {
                        failures[work.index] = "擦字後仍有大量原筆畫，已保留原圖"
                        continue
                    }
                    pasteMasked(cleaned, work, limited)
                    val strategy = when {
                        flat != null && type == EnglishRegionType.NORMAL_BUBBLE -> "bubble-strokes"
                        flat != null -> "colour-bubble-strokes"
                        type == EnglishRegionType.PLAIN_ART -> "art-aot-strokes"
                        type == EnglishRegionType.COLORED_ART -> "colour-art-aot-strokes"
                        else -> "textured-bubble-aot-strokes"
                    }
                    strategies.add(RegionEraseInfo(work.index, strategy, 0f, 0f))
                } catch (c: CancellationException) { throw c
                } catch (e: Exception) {
                    failures[work.index] = "局部擦字失敗，已保留原圖：${e.message.orEmpty().take(100)}"
                    pasteMasked(cleaned, work, work.original)
                }
            }
            // OCR is run on the cleaned English page before any Chinese is drawn.
            if (works.any { it.index !in failures }) {
                if (residualOcr == null) warnings.add("本次未執行擦字後 OCR，仍需檢查原文殘留")
                else try {
                    val residual = residualOcr(cleaned)
                    for (work in works) {
                        if (work.index in failures) continue
                        if (residual.any { isResidual(it, work.region) }) {
                            failures[work.index] = "擦字後仍辨識到原英文，已保留原圖"
                            pasteMasked(cleaned, work, work.original)
                        }
                    }
                } catch (c: CancellationException) { throw c
                } catch (e: Exception) { warnings.add("擦字後 OCR 未完成：${e.message.orEmpty().take(100)}") }
            }
            val inpaintMs = System.currentTimeMillis() - eraseStart
            plans = plans.map { p -> failures[p.index]?.let { p.copy(fitted = false, rows = emptyList(), note = it) } ?: p }
            val renderStart = System.currentTimeMillis()
            val rendered = EnglishVisualFinish.render(cleaned, works.map { it.index to it.region },
                overrides, maxFontSize, routes, bounds, plans)
            val finalLayouts = rendered.layouts.associateBy { it.index }
            val accepted = plans.filter { it.fitted && finalLayouts[it.index]?.overflow == false }.map { it.index }.sorted()
            for (p in plans) if (p.fitted && p.index !in accepted) {
                failures[p.index] = finalLayouts[p.index]?.note ?: "最終排版未完成，已保留原圖"
                works.firstOrNull { it.index == p.index }?.let { work ->
                    pasteMasked(cleaned, work, work.original)
                    pasteMasked(rendered.bitmap, work, work.original)
                }
            }
            return Result(cleaned, rendered.bitmap, mask, rendered.layouts,
                strategies.filter { it.index in accepted }, accepted, failures, routes, warnings,
                inpaintMs, System.currentTimeMillis() - renderStart)
        } catch (e: Throwable) {
            cleaned.recycle(); mask.recycle(); throw e
        }
    }

    private fun prepare(page: Bitmap, detector: Bitmap, index: Int, r: TextRegion): Work? {
        if (r.lines.isEmpty() || r.lines.any { it.quad.size < 3 || it.quad.any { p -> !p.x.isFinite() || !p.y.isFinite() } }) return null
        val rw = r.x1 - r.x0; val rh = r.y1 - r.y0
        if (rw <= 1 || rh <= 1) return null
        val pad = max(24, (max(rw, rh) * .65f).toInt()).coerceAtMost(200)
        val l = (floor(r.x0).toInt() - pad).coerceIn(0, page.width - 1)
        val t = (floor(r.y0).toInt() - pad).coerceIn(0, page.height - 1)
        val right = (ceil(r.x1).toInt() + pad).coerceIn(l + 1, page.width)
        val bottom = (ceil(r.y1).toInt() + pad).coerceIn(t + 1, page.height)
        val w = right - l; val h = bottom - t
        if (w.toLong() * h > 900_000L) return null
        val pixels = IntArray(w * h); val det = IntArray(w * h)
        page.getPixels(pixels, 0, w, l, t, w, h); detector.getPixels(det, 0, w, l, t, w, h)
        val plan = EnglishPixelSafety.prepare(pixels, det, w, h,
            r.lines.map { line -> line.quad.map { EnglishPixelSafety.Point(it.x - l, it.y - t) } })
        val colouredBg = EnglishPixelSafety.saturation(plan.background) >= 33
        val type = when {
            plan.framed && (colouredBg || plan.coloredInk) -> EnglishRegionType.COLORED_BUBBLE
            plan.framed -> EnglishRegionType.NORMAL_BUBBLE
            plan.coloredInk -> EnglishRegionType.COLORED_ART
            else -> EnglishRegionType.PLAIN_ART
        }
        val route = EnglishRegionRoute(type, if (plan.framed) .9f else .65f,
            EnglishRegionFeatures(0f, EnglishPixelSafety.saturation(plan.background).toFloat(),
                if (plan.coloredInk) 1f else 0f, if (plan.framed) 1f else 0f,
                plan.interior?.count { it } ?: 0, plan.ink.count { it }))
        return Work(index, r, l, t, pixels, plan, route)
    }

    private suspend fun reconstruct(w: Work, reconstructor: EnglishReconstructor): IntArray {
        val crop = Bitmap.createBitmap(w.width, w.height, Bitmap.Config.ARGB_8888)
        val mask = Bitmap.createBitmap(w.width, w.height, Bitmap.Config.ARGB_8888)
        var candidate: Bitmap? = null
        try {
            crop.setPixels(w.original, 0, w.width, 0, 0, w.width, w.height)
            val mp = IntArray(w.original.size) { if (w.plan.edit[it]) Color.WHITE else Color.BLACK }
            mask.setPixels(mp, 0, w.width, 0, 0, w.width, w.height)
            val localLines = w.region.lines.map { line -> TextLine(line.quad.map { Pt(it.x - w.x, it.y - w.y) }, line.score).apply {
                text = line.text; direction = "h"; ocrProb = line.ocrProb
            } }
            val local = TextRegion(localLines, "h")
            val reconstructed = reconstructor(crop, listOf(local), mask)
            candidate = reconstructed
            return IntArray(w.original.size).also { reconstructed.getPixels(it, 0, w.width, 0, 0, w.width, w.height) }
        } finally { candidate?.recycle(); mask.recycle(); crop.recycle() }
    }

    private fun pasteMasked(target: Bitmap, w: Work, replacement: IntArray) {
        val current = IntArray(w.original.size)
        target.getPixels(current, 0, w.width, w.x, w.y, w.width, w.height)
        for (i in current.indices) if (w.plan.edit[i]) current[i] = replacement[i]
        target.setPixels(current, 0, w.width, w.x, w.y, w.width, w.height)
    }
    private fun unionMask(mask: Bitmap, w: Work) {
        val current = IntArray(w.original.size)
        mask.getPixels(current, 0, w.width, w.x, w.y, w.width, w.height)
        for (i in current.indices) if (w.plan.edit[i]) current[i] = Color.WHITE
        mask.setPixels(current, 0, w.width, w.x, w.y, w.width, w.height)
    }
    private fun intersects(b: RectF, r: TextRegion) = b.left < r.x1 && b.right > r.x0 && b.top < r.y1 && b.bottom > r.y0
    private fun isResidual(line: TextLine, region: TextRegion): Boolean {
        if (line.quad.isEmpty()) return false
        val needle = line.text.filter { it.isLetterOrDigit() }.uppercase()
        val source = region.sourceText.filter { it.isLetterOrDigit() }.uppercase()
        if (needle.length < 2 || !source.contains(needle)) return false
        val l = line.quad.minOf { it.x }; val t = line.quad.minOf { it.y }
        val r = line.quad.maxOf { it.x }; val b = line.quad.maxOf { it.y }
        val intersection = max(0f, min(r, region.x1) - max(l, region.x0)) * max(0f, min(b, region.y1) - max(t, region.y0))
        return intersection / max(1f, (r - l) * (b - t)) >= .45f
    }
}
