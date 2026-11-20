package tw.dafeiyu.mangatranslator.core

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import li.joye.yakuyomi.engine.RegionLayout
import li.joye.yakuyomi.engine.RegionOverride
import li.joye.yakuyomi.engine.TextRegion
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 英文模式專用後處理。日文仍使用 0.2.0 的擦字和 Legacy020SmartRenderer，不進入這裡。
 * 先完成安全範圍內的試排，再讓呼叫端擦字。放不下的區域完全不畫。
 */
internal object EnglishVisualFinish {
    /**
     * 英文局部背景補色：只在背景能以同一平滑色面描述時覆蓋 AOT 結果。
     * 舊版依每個像素的周圍中位數重塗，容易造成破碎的粉／綠／灰色斑。
     * 現在改為區域一致的漸層模型；遇到插畫、網點、多色氣泡則保留原 AOT。
     */
    fun repairFlatBackground(original: Bitmap, cleaned: Bitmap, textMask: Bitmap,
                             regions: List<Pair<Int, TextRegion>>,
                             routes: Map<Int, EnglishRegionRoute>) {
        val width = original.width; val height = original.height
        if (width <= 0 || height <= 0 || cleaned.width != width || cleaned.height != height ||
            textMask.width != width || textMask.height != height) return
        for ((index, r) in regions) {
            val route = routes[index] ?: continue
            // 無框文字背景本來就是插畫，絕不可用平面補色覆蓋；只允許平滑氣泡修補。
            if (route.type !in setOf(EnglishRegionType.NORMAL_BUBBLE, EnglishRegionType.COLORED_BUBBLE) ||
                route.confidence < .72f || route.features.backgroundSpread > 23f) continue
            val x0 = (r.x0.toInt() - 12).coerceIn(0, width - 1)
            val y0 = (r.y0.toInt() - 12).coerceIn(0, height - 1)
            val x1 = (r.x1.toInt() + 12).coerceIn(x0 + 1, width)
            val y1 = (r.y1.toInt() + 12).coerceIn(y0 + 1, height)
            val bw = x1 - x0; val bh = y1 - y0
            if (bw < 5 || bh < 5 || bw.toLong() * bh > 500_000L) continue
            val src = IntArray(bw * bh); val out = IntArray(src.size); val mask = IntArray(src.size)
            original.getPixels(src, 0, bw, x0, y0, bw, bh)
            cleaned.getPixels(out, 0, bw, x0, y0, bw, bh)
            textMask.getPixels(mask, 0, bw, x0, y0, bw, bh)
            if (EnglishBackgroundModel.refine(src, out, mask, bw, bh) > 0) {
                cleaned.setPixels(out, 0, bw, x0, y0, bw, bh)
            }
        }
    }

    /** 手動修復/重新嵌字入口也要使用英文分類；保留原有四參數介面以免舊呼叫編譯失敗。 */
    fun repairFlatBackground(original: Bitmap, cleaned: Bitmap, textMask: Bitmap,
                             regions: List<TextRegion>) {
        val routes = EnglishRegionClassifier.classify(original, textMask, regions)
        repairFlatBackground(original, cleaned, textMask, regions.mapIndexed { i, r -> i to r }, routes)
    }

    data class Rendered(val bitmap: Bitmap, val layouts: List<RegionLayout>)

    data class PlannedRegion(
        val index: Int,
        val text: String,
        val box: RectF,
        val contentBox: RectF,
        val fontSize: Int,
        val rows: List<String>,
        val firstBaseline: Float,
        val lineAdvance: Float,
        val strokeWidth: Float,
        val fitted: Boolean,
        val note: String?,
        val pageWidth: Int,
        val pageHeight: Int,
    ) {
        val layout: RegionLayout get() = RegionLayout(index, text.length, false, fontSize,
            rows.size, !fitted, RectF(box), note)
    }

    /**
     * Safe preflight, before modifying any original pixels. Bounds supplied by the caller must be
     * an already verified interior; absent those, we never expand the OCR rectangle into artwork.
     */
    fun plan(
        pageWidth: Int,
        pageHeight: Int,
        regions: List<Pair<Int, TextRegion>>,
        overrides: Map<Int, RegionOverride>,
        maxSize: Int,
        routes: Map<Int, EnglishRegionRoute> = emptyMap(),
        bounds: Map<Int, RectF> = emptyMap(),
    ): List<PlannedRegion> {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT; style = Paint.Style.FILL }
        val ink = Rect()
        return regions.map { (index, region) ->
            val text = region.translatedText
            val ov = overrides[index] ?: RegionOverride()
            val supplied = bounds[index] ?: RectF(region.x0, region.y0, region.x1, region.y1)
            val box = safeBox(supplied, pageWidth, pageHeight)
            val framed = routes[index]?.type !in setOf(EnglishRegionType.PLAIN_ART, EnglishRegionType.COLORED_ART)
            // Reserve enough room for antialiasing and, on artwork, a thin outline.
            val padding = min(4f, max(1.5f, min(box.width(), box.height()) * .04f))
            val base = RectF(box.left + padding, box.top + padding, box.right - padding, box.bottom - padding)
            val dx = ov.dx.takeIf { it.isFinite() } ?: 0f
            val dy = ov.dy.takeIf { it.isFinite() } ?: 0f
            val cx = base.centerX() + dx
            val cy = base.centerY() + dy
            // Offsets move the anchor within the verified area; they cannot move its safe boundary.
            val halfWidth = min(cx - base.left, base.right - cx)
            val halfHeight = min(cy - base.top, base.bottom - cy)
            val content = RectF(cx - halfWidth, cy - halfHeight, cx + halfWidth, cy + halfHeight)
            val sourceHeights = region.lines.mapNotNull { line ->
                if (line.quad.isEmpty()) null else
                    (line.quad.maxOf { it.y } - line.quad.minOf { it.y }).takeIf { it.isFinite() && it > 0f }
            }.sorted()
            val sourceHeight = sourceHeights.getOrNull(sourceHeights.size / 2)
            val naturalCap = sourceHeight?.let { (it * 1.08f).roundToInt() } ?: maxSize
            val cap = min(maxSize, naturalCap).coerceIn(EnglishTextLayout.MIN_FONT_SIZE, 60)
            val fit = EnglishTextLayout.fit(text, content.width(), content.height(), cap, ov.fontScale,
                measure = { value, size ->
                    paint.textSize = size.toFloat()
                    paint.getTextBounds(value, 0, value.length, ink)
                    max(paint.measureText(value), ink.right.toFloat()) - min(0f, ink.left.toFloat())
                },
                metrics = { size ->
                    paint.textSize = size.toFloat()
                    val font = paint.fontMetrics
                    // The actual translated glyphs may use a fallback font with different extents.
                    paint.getTextBounds(text, 0, text.length, ink)
                    EnglishTextLayout.Metrics(min(font.top, ink.top.toFloat()),
                        max(font.bottom, ink.bottom.toFloat()), font.leading)
                })
            val baseline = if (fit.fitted) content.top + (content.height() - fit.height) / 2f - fit.glyphTop else 0f
            PlannedRegion(index, text, box, content, fit.fontSize, fit.rows, baseline, fit.lineAdvance,
                if (framed || !fit.fitted) 0f else (fit.fontSize * .035f).coerceIn(.35f, 1f),
                fit.fitted, fit.reason, pageWidth, pageHeight)
        }
    }

    fun render(
        cleaned: Bitmap,
        regions: List<Pair<Int,TextRegion>>,
        overrides: Map<Int, RegionOverride>,
        maxSize: Int,
        routes: Map<Int, EnglishRegionRoute> = emptyMap(),
        bounds: Map<Int, RectF> = emptyMap(),
        plans: List<PlannedRegion>? = null,
    ): Rendered {
        val output = cleaned.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT; style = Paint.Style.FILL }
        val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT; style = Paint.Style.STROKE }
        val layout = ArrayList<RegionLayout>()
        val byIndex = regions.toMap()
        val ready = plans ?: plan(cleaned.width, cleaned.height, regions, overrides, maxSize, routes, bounds)
        for (planned in ready) {
            val region = byIndex[planned.index] ?: continue
            if (planned.pageWidth != cleaned.width || planned.pageHeight != cleaned.height ||
                planned.text != region.translatedText) {
                layout += planned.layout.copy(overflow = true, note = "排版資料已變更，未嵌字")
                continue
            }
            layout += planned.layout
            // Never draw a failed fit. Clipping is a final safeguard, not an overflow strategy.
            if (!planned.fitted) continue
            paint.textSize = planned.fontSize.toFloat(); outline.textSize = paint.textSize
            val tone = backgroundTone(cleaned, planned.box)
            val darkBackground = tone.first < 100f
            paint.color = if (darkBackground) Color.WHITE else Color.rgb(30, 27, 32)
            outline.color = if (darkBackground) Color.rgb(25, 23, 27) else Color.WHITE
            outline.strokeWidth = planned.strokeWidth
            var baseline = planned.firstBaseline
            val ink = Rect()
            canvas.save(); canvas.clipRect(planned.box)
            for (line in planned.rows) {
                paint.getTextBounds(line, 0, line.length, ink)
                val left = min(0f, ink.left.toFloat())
                val right = max(paint.measureText(line), ink.right.toFloat())
                val x = planned.contentBox.centerX() - (left + right) / 2f
                if (planned.strokeWidth > 0f) canvas.drawText(line, x, baseline, outline)
                canvas.drawText(line, x, baseline, paint)
                baseline += planned.lineAdvance
            }
            canvas.restore()
        }
        return Rendered(output,layout)
    }

    private fun safeBox(value: RectF, width: Int, height: Int): RectF {
        if (width <= 0 || height <= 0 || !value.left.isFinite() || !value.top.isFinite() ||
            !value.right.isFinite() || !value.bottom.isFinite() || value.right <= value.left || value.bottom <= value.top) {
            return RectF()
        }
        val box = RectF(value.left.coerceIn(0f, width.toFloat()), value.top.coerceIn(0f, height.toFloat()),
            value.right.coerceIn(0f, width.toFloat()), value.bottom.coerceIn(0f, height.toFloat()))
        return if (box.width() > 0f && box.height() > 0f) box else RectF()
    }

    /** 返回區域的亮度均值和標準差，判斷畫面是否真的需要描邊。 */
    private fun backgroundTone(bmp: Bitmap, b: RectF): Pair<Float, Float> {
        var total = 0.0; var square = 0.0; var count = 0
        for (y in 1..6) for (x in 1..6) {
            val px = bmp.getPixel((b.left + x * b.width()/7f).roundToInt().coerceIn(0,bmp.width-1),
                (b.top + y * b.height()/7f).roundToInt().coerceIn(0,bmp.height-1))
            val value = (px shr 16 and 255) * .299 + (px shr 8 and 255) * .587 + (px and 255) * .114
            total += value; square += value * value; count++
        }
        val mean = total / count
        val std = kotlin.math.sqrt((square / count - mean * mean).coerceAtLeast(0.0))
        return mean.toFloat() to std.toFloat()
    }
}
