package li.joye.yakuyomi.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import li.joye.yakuyomi.engine.typeset.Cjk
import li.joye.yakuyomi.engine.typeset.HorizontalLayout
import li.joye.yakuyomi.engine.typeset.LayoutBox
import li.joye.yakuyomi.engine.typeset.InsetRegion
import li.joye.yakuyomi.engine.typeset.LayoutRegion
import li.joye.yakuyomi.engine.typeset.PaintMeasurerFactory
import li.joye.yakuyomi.engine.typeset.RectRegion
import li.joye.yakuyomi.engine.typeset.TextLayout
import li.joye.yakuyomi.engine.typeset.TypesetOptions
import li.joye.yakuyomi.engine.typeset.Typesetter
import li.joye.yakuyomi.engine.typeset.VerticalLayout
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** 單一文字區的手動排版覆寫（存在 meta，重排版時不呼叫 API）。 */
data class RegionOverride(
    val dx: Float = 0f,
    val dy: Float = 0f,
    val fontScale: Float = 1f,
    val orientation: TextOrientation? = null,
)

/** 排版結果；overflow=true 代表全文在最小字級仍放不進框（已保留全文、標記待處理，不截斷）。 */
data class RegionLayout(
    val index: Int,
    val textLength: Int,
    val vertical: Boolean,
    val fontSize: Int,
    val lines: Int,
    val overflow: Boolean,
    val box: RectF,
    val note: String? = null,
)

data class DetailedRenderResult(
    val bitmap: Bitmap,
    val layouts: List<RegionLayout>,
)

/**
 * 0.3.0 排版器（取代 0.2.0 的「估算字級」做法）：
 *
 *  - **真實字度量**：[Typesetter] 對每個候選字級實際換行／切欄，用 [PaintMeasurerFactory] 量到的
 *    advance / ascent / descent 判斷放不放得下；不再用「字數 × 係數」猜。
 *  - **對話框輪廓**：[RegionAnalyzer] 找到的氣泡內緣（含逐列寬度）直接當排版區域，
 *    橢圓或不規則氣泡的每一行只用到該高度真正可寫的寬度。
 *  - **字級不再二次縮小**：0.2.0 先算 fit 再乘 fontScale，會造成「判定放得下、實際溢出」；
 *    現在 scale 直接進候選字級，最後一定重新驗證。
 *  - **原字樣式**：文字色／描邊色取自原圖（[TextStyleInfo]），在原背景上仍可讀時優先保留。
 *  - **絕不截斷**：任何情況都完整畫出；真的放不下就標 overflow 給使用者手動調整。
 */
object SmartRenderer {

    fun renderDetailed(
        cleaned: Bitmap,
        regions: List<TextRegion>,
        cfg: RenderConfig = RenderConfig(),
        typeface: Typeface? = null,
        overrides: Map<Int, RegionOverride> = emptyMap(),
        inspections: Map<Int, RegionInspection> = emptyMap(),
        styleSource: Bitmap? = null,
    ): DetailedRenderResult {
        val out = cleaned.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val face = typeface ?: Typeface.DEFAULT
        val factory = PaintMeasurerFactory(face)
        val fill = Paint().apply { color = Color.BLACK; isAntiAlias = true; this.typeface = face }
        val stroke = Paint().apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            isAntiAlias = true
            this.typeface = face
            strokeJoin = Paint.Join.ROUND
        }
        val layouts = ArrayList<RegionLayout>(regions.size)

        regions.forEachIndexed { idx, region ->
            val text = region.translatedText.ifBlank { region.sourceText }
            if (text.isBlank()) {
                layouts.add(RegionLayout(idx, 0, region.direction == "v", 0, 0, false, RectF(), "空白"))
                return@forEachIndexed
            }
            val inspection = inspections[idx]
            val ov = overrides[idx] ?: RegionOverride()
            val vertical = when (ov.orientation ?: cfg.orientation) {
                TextOrientation.AUTO -> region.direction == "v" && !Cjk.isMostlyLatin(text)
                TextOrientation.VERTICAL -> true
                TextOrientation.HORIZONTAL -> false
            }
            val tilted = abs(region.angle) >= 2f
            val box: RectF
            val shape: LayoutRegion
            if (tilted) {
                // 旋轉文字：在「去傾斜座標系」排版，畫布繞中心旋轉。
                box = RectF(
                    region.cx - region.boxW / 2f, region.cy - region.boxH / 2f,
                    region.cx + region.boxW / 2f, region.cy + region.boxH / 2f,
                )
                shape = RectRegion(LayoutBox(box.left, box.top, box.right, box.bottom))
            } else {
                val s = inspection?.shape
                if (s != null) {
                    shape = s
                    box = RectF(s.bounds.left, s.bounds.top, s.bounds.right, s.bounds.bottom)
                } else {
                    box = RectF(region.x0, region.y0, region.x1, region.y1)
                    shape = RectRegion(LayoutBox(box.left, box.top, box.right, box.bottom))
                }
            }
            if (box.width() < 6f || box.height() < 6f) {
                layouts.add(RegionLayout(idx, text.length, vertical, 0, 0, true, box, "文字框過小"))
                return@forEachIndexed
            }

            // 保留內距而不是把字塞滿 OCR bbox：描邊也算進可用空間。
            val margin = (kotlin.math.min(box.width(), box.height()) * 0.045f).coerceIn(2.5f, 10f)
            if (box.width() <= margin * 2f + 4f || box.height() <= margin * 2f + 4f) {
                layouts.add(RegionLayout(idx, text.length, vertical, 0, 0, true, box, "文字區太小無法安全嵌字"))
                return@forEachIndexed
            }
            val safeShape = InsetRegion(shape, margin)
            val safeBounds = safeShape.bounds
            box.set(safeBounds.left, safeBounds.top, safeBounds.right, safeBounds.bottom)

            val style = inspection?.style
            val bg = if (styleSource != null) {
                bgLuminance(styleSource, box.left, box.top, box.right, box.bottom)
            } else {
                bgLuminance(cleaned, box.left, box.top, box.right, box.bottom)
            }
            val onArt = region.onArt || inspection?.kind == TextKind.SFX || inspection?.kind == TextKind.COLORED_ART
            val (fillColor, outlineColor) = chooseColors(style, bg, cfg, onArt)
            val baseStroke = if (cfg.fontBorder) 1f else 0f

            // 目標字級：全域上限 × 使用者縮放（縮放直接進候選，不再畫完才縮）。
            val scaledMax = (cfg.fontSizeMax * ov.fontScale.coerceIn(0.4f, 2.5f)).roundToInt()
                .coerceIn(cfg.fontSizeMin, cfg.fontSizeMax)
            val opts = TypesetOptions(
                maxFontSize = scaledMax,
                minFontSize = cfg.fontSizeMin.coerceAtLeast(8),
                strokeWidth = if (cfg.fontBorder) max(1.6f, scaledMax * STROKE_RATIO) else 0f,
                columnGap = if (vertical) 1.09f else 1.0f,
                cellHeight = 1.03f,
                lineHeight = 1.26f,
                horizontalPadding = 2.0f,
                tateChuYoko = cfg.tateChuYoko,
                measurer = factory,
            )
            val layout: TextLayout = try {
                if (vertical) Typesetter.layoutVertical(text, safeShape, opts)
                else Typesetter.layoutHorizontal(text, safeShape, opts)
            } catch (t: Throwable) {
                layouts.add(RegionLayout(idx, text.length, vertical, 0, 0, true, box, "排版失敗：${t.message}"))
                return@forEachIndexed
            }
            if (layout.fontSize <= 0f) {
                layouts.add(RegionLayout(idx, text.length, vertical, 0, 0, true, box, "空白"))
                return@forEachIndexed
            }

            if (layout.overflow) {
                // 不能把超框文字硬畫在對話框和人物上：由呼叫端保留原文，標記 PARTIAL。
                layouts.add(RegionLayout(idx, text.length, vertical, layout.fontSize.roundToInt(),
                    layout.lineCount, true, box, layout.note ?: "超出安全排版範圍，保留原文"))
                return@forEachIndexed
            }
            val size = layout.fontSize
            fill.color = fillColor
            stroke.color = outlineColor
            fill.textSize = size
            stroke.textSize = size
            fill.isFakeBoldText = style?.bold == true && size >= 14f
            val strokeW = if (cfg.fontBorder) {
                max(1.6f, size * (if (onArt) cfg.artStrokeRatio else STROKE_RATIO))
            } else 0f
            stroke.strokeWidth = strokeW

            var dx = ov.dx
            var dy = ov.dy
            if (tilted) {
                canvas.save()
                canvas.rotate(region.angle, region.cx + dx, region.cy + dy)
            }
            when (layout) {
                is VerticalLayout -> drawVertical(canvas, layout, box, dx, dy, fill, stroke, strokeW > 0f)
                is HorizontalLayout -> drawHorizontal(canvas, layout, dx, dy, fill, stroke, strokeW > 0f)
            }
            if (tilted) canvas.restore()

            val usedLines = layout.lineCount
            val note = layout.note ?: when {
                inspection?.kind == TextKind.COLORED_ART -> "藝術字：保留原色"
                inspection?.kind == TextKind.SFX -> "音效字"
                else -> null
            }
            layouts.add(RegionLayout(idx, text.length, vertical, size.roundToInt(), usedLines, layout.overflow, box, note))
        }
        return DetailedRenderResult(out, layouts)
    }

    /** 相容舊呼叫（不帶樣式/輪廓）。 */
    fun render(page: Bitmap, regions: List<TextRegion>, cfg: RenderConfig = RenderConfig(), typeface: Typeface? = null): Bitmap =
        renderDetailed(page, regions, cfg, typeface).bitmap

    // ------------------------------------------------------------------ draw

    private fun drawVertical(
        canvas: Canvas,
        layout: VerticalLayout,
        box: RectF,
        dx: Float,
        dy: Float,
        fill: Paint,
        stroke: Paint,
        border: Boolean,
    ) {
        val size = layout.fontSize
        val fm = fill.fontMetrics
        val ascent = -fm.ascent
        val descent = fm.descent
        val cellH = size * 1.03f
        for (col in layout.columns) {
            for (cell in col.cells) {
                val cx = cell.cx + dx
                var cy = cell.cy + dy
                if (cell.tcy) {
                    drawTcy(canvas, cell.text, cx, cy, cell.cellWidth, fill, stroke, border)
                    continue
                }
                var px = cx
                if (cell.punctOffset) {
                    px += size * 0.20f
                    cy -= cellH * 0.16f
                }
                if (cell.rotate) {
                    canvas.save()
                    canvas.rotate(90f, px, cy)
                }
                val baseline = cy + (ascent - descent) / 2f
                val tw = fill.measureText(cell.text)
                val tx = px - tw / 2f
                if (border) canvas.drawText(cell.text, tx, baseline, stroke)
                canvas.drawText(cell.text, tx, baseline, fill)
                if (cell.rotate) canvas.restore()
            }
        }
    }

    private fun drawHorizontal(
        canvas: Canvas,
        layout: HorizontalLayout,
        dx: Float,
        dy: Float,
        fill: Paint,
        stroke: Paint,
        border: Boolean,
    ) {
        for (line in layout.lines) {
            val x = line.x + dx
            val y = line.baseline + dy
            if (border) canvas.drawText(line.text, x, y, stroke)
            canvas.drawText(line.text, x, y, fill)
        }
    }

    /** 縱中橫：把 2–4 個英數/符號並排塞進一格，必要時水平壓縮。 */
    private fun drawTcy(canvas: Canvas, text: String, cx: Float, cy: Float, cellW: Float, fill: Paint, stroke: Paint, border: Boolean) {
        val w = fill.measureText(text)
        val fm = fill.fontMetrics
        val baseline = cy + (-fm.ascent - fm.descent) / 2f
        val target = cellW * 0.94f
        val scaleX = if (w > target && w > 0f) target / w else 1f
        canvas.save()
        if (scaleX != 1f) canvas.scale(scaleX, 1f, cx, baseline)
        val tx = cx - w / 2f
        if (border) canvas.drawText(text, tx, baseline, stroke)
        canvas.drawText(text, tx, baseline, fill)
        canvas.restore()
    }

    // --------------------------------------------------------------- colors

    private fun chooseColors(style: TextStyleInfo?, bgLuma: Float, cfg: RenderConfig, onArt: Boolean): Pair<Int, Int> {
        // 易讀優先：純看背景亮度（與 0.2.0 相同，保證任何背景都讀得到）。
        val readableFill = if (bgLuma < 118f) Color.WHITE else Color.BLACK
        val readableOutline = if (bgLuma < 118f) Color.BLACK else Color.WHITE
        if (cfg.colorMode == "mono") return Color.BLACK to Color.WHITE
        if (cfg.styleMode == "readable" || style == null || style.confidence < 0.22f) {
            return readableFill to readableOutline
        }
        // 原版風格優先：文字色在原背景上有足夠對比才採用，否則退回易讀色。
        val contrast = contrastRatio(style.textColor, bgLuma)
        if (contrast < 2.6f) return readableFill to readableOutline
        val outline = if (cfg.fontBorder) {
            if (style.hasOutline) style.outlineColor else readableOutline
        } else Color.TRANSPARENT
        return style.textColor to outline
    }

    private fun contrastRatio(color: Int, bgLuma: Float): Float {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        val l = 0.299f * r + 0.587f * g + 0.114f * b
        val hi = max(l, bgLuma) / 255f
        val lo = min(l, bgLuma) / 255f
        return (hi + 0.05f) / (lo + 0.05f)
    }

    private fun bgLuminance(page: Bitmap, rx0: Float, ry0: Float, rx1: Float, ry1: Float): Float {
        val w = page.width
        val h = page.height
        val x0 = rx0.toInt().coerceIn(0, w - 1)
        val y0 = ry0.toInt().coerceIn(0, h - 1)
        val x1 = rx1.toInt().coerceIn(x0 + 1, w)
        val y1 = ry1.toInt().coerceIn(y0 + 1, h)
        val bw = x1 - x0
        val bh = y1 - y0
        val px = IntArray(bw * bh)
        page.getPixels(px, 0, bw, x0, y0, bw, bh)
        var sum = 0.0
        for (p in px) sum += 0.299 * ((p shr 16) and 0xFF) + 0.587 * ((p shr 8) and 0xFF) + 0.114 * (p and 0xFF)
        return (sum / px.size).toFloat()
    }

    private const val STROKE_RATIO = 0.10f
}
