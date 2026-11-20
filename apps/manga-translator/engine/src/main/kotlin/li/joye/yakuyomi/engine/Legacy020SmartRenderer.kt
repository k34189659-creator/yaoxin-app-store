package li.joye.yakuyomi.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** 瑤芯漫畫翻譯 0.2.0 原始直排/橫排嵌字；保留 0.4.0 編輯資料類型。 */
object Legacy020SmartRenderer {

    fun renderDetailed(
        page: Bitmap,
        regions: List<TextRegion>,
        cfg: RenderConfig = RenderConfig(),
        typeface: Typeface? = null,
        overrides: Map<Int, RegionOverride> = emptyMap(),
    ): DetailedRenderResult {
        val out = page.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val face = typeface ?: Typeface.DEFAULT
        val fill = Paint().apply { color = Color.BLACK; isAntiAlias = true; this.typeface = face }
        val stroke = Paint().apply {
            color = Color.WHITE; style = Paint.Style.STROKE; isAntiAlias = true; this.typeface = face
        }
        val layouts = ArrayList<RegionLayout>(regions.size)
        regions.forEachIndexed { idx, region ->
            val text = region.translatedText.ifBlank { region.sourceText }
            if (text.isBlank()) {
                layouts.add(RegionLayout(idx, 0, region.direction == "v", 0, 0, false, RectF(), "空白"))
                return@forEachIndexed
            }
            val ov = overrides[idx] ?: RegionOverride()
            val vertical = when (ov.orientation ?: cfg.orientation) {
                TextOrientation.AUTO -> region.direction == "v"
                TextOrientation.VERTICAL -> true
                TextOrientation.HORIZONTAL -> false
            }
            val (fillColor, outlineColor) = textColors(page, region, cfg)
            fill.color = fillColor
            stroke.color = outlineColor
            val rotate = abs(region.angle) >= 1f
            val x0: Float; val y0: Float; val x1: Float; val y1: Float
            if (rotate) {
                x0 = region.cx - region.boxW / 2f + ov.dx
                y0 = region.cy - region.boxH / 2f + ov.dy
                x1 = region.cx + region.boxW / 2f + ov.dx
                y1 = region.cy + region.boxH / 2f + ov.dy
                canvas.save(); canvas.rotate(region.angle, region.cx + ov.dx, region.cy + ov.dy)
            } else {
                x0 = region.x0 + ov.dx
                y0 = region.y0 + ov.dy
                x1 = region.x1 + ov.dx
                y1 = region.y1 + ov.dy
            }
            val box = RectF(x0, y0, x1, y1)
            if (x1 - x0 < 8f || y1 - y0 < 8f) {
                layouts.add(RegionLayout(idx, text.length, vertical, 0, 0, true, box, "文字框過小"))
                if (rotate) canvas.restore()
                return@forEachIndexed
            }
            val layout = if (vertical) {
                drawVertical(canvas, box, text, fill, stroke, cfg, region.onArt, ov)
            } else {
                drawHorizontal(canvas, box, text, fill, stroke, cfg, region.onArt, ov)
            }
            layouts.add(RegionLayout(idx, text.length, vertical, layout.fontSize, layout.lines, layout.overflow, box, layout.note))
            if (rotate) canvas.restore()
        }
        return DetailedRenderResult(out, layouts)
    }

    private data class Fit(val fontSize: Int, val lines: Int, val overflow: Boolean, val note: String?)

    private fun textColors(page: Bitmap, r: TextRegion, cfg: RenderConfig): Pair<Int, Int> {
        if (cfg.colorMode == "mono") return Color.BLACK to Color.WHITE
        val lum = bgLuminance(page, r.x0, r.y0, r.x1, r.y1)
        return when {
            lum < 80f -> Color.WHITE to Color.BLACK
            lum > 185f -> Color.BLACK to Color.WHITE
            // 中間亮度（網點/灰底）：黑字粗白邊最穩
            else -> Color.BLACK to Color.WHITE
        }
    }

    private fun bgLuminance(page: Bitmap, rx0: Float, ry0: Float, rx1: Float, ry1: Float): Float {
        val w = page.width; val h = page.height
        val x0 = rx0.toInt().coerceIn(0, w - 1)
        val y0 = ry0.toInt().coerceIn(0, h - 1)
        val x1 = rx1.toInt().coerceIn(x0 + 1, w)
        val y1 = ry1.toInt().coerceIn(y0 + 1, h)
        val bw = x1 - x0; val bh = y1 - y0
        val px = IntArray(bw * bh)
        page.getPixels(px, 0, bw, x0, y0, bw, bh)
        var sum = 0.0
        for (p in px) sum += 0.299 * ((p shr 16) and 0xFF) + 0.587 * ((p shr 8) and 0xFF) + 0.114 * (p and 0xFF)
        return (sum / px.size).toFloat()
    }

    private fun textScale(textLength: Int, cfg: RenderConfig, ov: RegionOverride): Float {
        // 短句不套用全域 0.85 縮小，避免「一字台詞」被縮得很小；長句才整體收一點。
        val base = if (textLength <= 6) 1f else cfg.fontScale
        return (base * ov.fontScale.coerceIn(0.4f, 2.5f)).coerceIn(0.35f, 2.5f)
    }

    private fun drawVertical(
        canvas: Canvas,
        box: RectF,
        text: String,
        fill: Paint,
        stroke: Paint,
        cfg: RenderConfig,
        onArt: Boolean,
        ov: RegionOverride,
    ): Fit {
        val chars = text.filter { it != '\n' }
        val cells = toVerticalCells(chars, cfg.tateChuYoko)
        val bw = box.width() * cfg.expandW
        val colRoom = box.height() * cfg.expandH
        val minSize = cfg.fontSizeMin.coerceAtLeast(7)
        val scale = textScale(cells.size, cfg, ov)

        fun columnsFor(s: Int): Pair<List<List<String>>, Int> {
            val lh = s * 1.06f
            val cpc = max(1, (colRoom / lh).toInt())
            return splitColumnsV(cells, cpc) to cpc
        }

        var chosen = -1
        var columns: List<List<String>> = emptyList()
        var s = min(cfg.fontSizeMax, colRoom.toInt().coerceAtLeast(minSize))
        while (s >= minSize) {
            val (cols, _) = columnsFor(s)
            val cw = s * 1.08f
            val fitsWidth = cols.size * cw <= bw + 0.5f
            val fitsHeight = cols.all { it.size * s * 1.06f <= colRoom + 0.5f }
            if (fitsWidth && fitsHeight) { chosen = s; columns = cols; break }
            s--
        }
        val overflow = chosen < 0
        if (overflow) {
            chosen = minSize
            columns = columnsFor(chosen).first
        }
        var size = max(minSize, (chosen * scale).roundToInt())
        if (size > cfg.fontSizeMax) size = cfg.fontSizeMax
        fill.textSize = size.toFloat(); stroke.textSize = size.toFloat()
        stroke.strokeWidth = max(2f, size * (if (onArt) cfg.artStrokeRatio else STROKE_RATIO))
        val lh = size * 1.06f
        val cw = size * 1.08f
        // 縮小後重新切欄，避免欄數與字級不一致
        val finalCpc = max(1, (colRoom / lh).toInt())
        val finalCols = splitColumnsV(cells, finalCpc)
        val tcx = box.centerX()
        val rightCx = tcx + finalCols.size * cw / 2f - cw / 2f
        val blockH = finalCols.maxOfOrNull { it.size } ?.times(lh) ?: 0f
        val startCy = box.centerY() - blockH / 2f
        finalCols.forEachIndexed { col, cellsInCol ->
            val cx = rightCx - col * cw
            var cy = startCy
            cellsInCol.forEach { cell ->
                if (cell.length == 1) drawCharVertical(canvas, cell[0], cx, cy + lh / 2f, cw, lh, fill, stroke, cfg.fontBorder)
                else drawTateChuYoko(canvas, cell, cx, cy + lh / 2f, cw, fill, stroke, cfg.fontBorder)
                cy += lh
            }
        }
        return Fit(size, finalCols.size, overflow, if (overflow) "全文在最小字級仍超出文字框，已保留全文並標記待處理" else null)
    }

    private fun drawHorizontal(
        canvas: Canvas,
        box: RectF,
        text: String,
        fill: Paint,
        stroke: Paint,
        cfg: RenderConfig,
        onArt: Boolean,
        ov: RegionOverride,
    ): Fit {
        val bw = box.width() * cfg.expandW
        val rowRoom = box.height() * cfg.expandH
        val minSize = cfg.fontSizeMin.coerceAtLeast(7)
        val scale = textScale(text.length, cfg, ov)
        var chosen = -1
        var lines: List<String> = emptyList()
        var s = min(cfg.fontSizeMax, rowRoom.toInt().coerceAtLeast(minSize))
        while (s >= minSize) {
            fill.textSize = s.toFloat()
            val ls = wrapCjk(text, fill, bw)
            val maxW = ls.maxOfOrNull { fill.measureText(it) } ?: 0f
            if (ls.size * s * 1.20f <= rowRoom + 0.5f && maxW <= bw + 0.5f) {
                chosen = s; lines = ls; break
            }
            s--
        }
        val overflow = chosen < 0
        if (overflow) {
            chosen = minSize
            fill.textSize = chosen.toFloat()
            lines = wrapCjk(text, fill, bw)
        }
        var size = max(minSize, (chosen * scale).roundToInt())
        if (size > cfg.fontSizeMax) size = cfg.fontSizeMax
        fill.textSize = size.toFloat(); stroke.textSize = size.toFloat()
        stroke.strokeWidth = max(2f, size * (if (onArt) cfg.artStrokeRatio else STROKE_RATIO))
        val finalLines = wrapCjk(text, fill, bw)
        val lh = size * 1.20f
        var baseline = box.centerY() - finalLines.size * lh / 2f + size * ASCENT
        for (ln in finalLines) {
            val tx = box.centerX() - fill.measureText(ln) / 2f
            if (cfg.fontBorder) canvas.drawText(ln, tx, baseline, stroke)
            canvas.drawText(ln, tx, baseline, fill)
            baseline += lh
        }
        return Fit(size, finalLines.size, overflow, if (overflow) "全文在最小字級仍超出文字框，已保留全文並標記待處理" else null)
    }

    private fun isTcyChar(c: Char): Boolean =
        c in '0'..'9' || c in 'A'..'Z' || c in 'a'..'z' || c == '!' || c == '?'

    private fun toVerticalCells(chars: String, enabled: Boolean): List<String> {
        if (!enabled) return chars.map { it.toString() }
        val cells = ArrayList<String>()
        var i = 0
        while (i < chars.length) {
            if (isTcyChar(chars[i])) {
                var j = i + 1
                while (j < chars.length && isTcyChar(chars[j])) j++
                if (j - i in 2..MAX_TCY) cells.add(chars.substring(i, j))
                else for (k in i until j) cells.add(chars[k].toString())
                i = j
            } else {
                cells.add(chars[i].toString()); i++
            }
        }
        return cells
    }

    /** 直排切欄；禁則字（不可行頭）併回前一欄。 */
    private fun splitColumnsV(cells: List<String>, cpc: Int): List<List<String>> {
        val cols = ArrayList<List<String>>()
        var i = 0
        while (i < cells.size) {
            var end = minOf(i + cpc, cells.size)
            var ext = 0
            while (end < cells.size && cells[end].length == 1 && cells[end][0] in NO_START && ext < 2) {
                end++; ext++
            }
            cols.add(cells.subList(i, end).toList())
            i = end
        }
        return cols
    }

    private fun drawCharVertical(
        canvas: Canvas,
        ch: Char,
        cx: Float,
        cyc: Float,
        cellW: Float,
        cellH: Float,
        fill: Paint,
        stroke: Paint,
        border: Boolean,
    ) {
        val s = ch.toString()
        val w = fill.measureText(s)
        val fm = fill.fontMetrics
        var baseline = cyc - (fm.ascent + fm.descent) / 2f
        var x = cx - w / 2f
        // 直排句讀：放在格子右上，不置中；不旋轉字形。
        if (ch in VERTICAL_PUNCT) {
            x += cellW * 0.23f
            baseline -= cellH * 0.18f
        }
        val rotate = ch in ROTATE_CHARS
        if (rotate) { canvas.save(); canvas.rotate(90f, cx, cyc) }
        if (border) canvas.drawText(s, x, baseline, stroke)
        canvas.drawText(s, x, baseline, fill)
        if (rotate) canvas.restore()
    }

    private fun drawTateChuYoko(canvas: Canvas, group: String, cx: Float, cyc: Float, cellW: Float, fill: Paint, stroke: Paint, border: Boolean) {
        val w = fill.measureText(group)
        val fm = fill.fontMetrics
        val baseline = cyc - (fm.ascent + fm.descent) / 2f
        val target = cellW * 0.92f
        val scaleX = if (w > target) target / w else 1f
        canvas.save()
        if (scaleX != 1f) canvas.scale(scaleX, 1f, cx, baseline)
        val tx = cx - w / 2f
        if (border) canvas.drawText(group, tx, baseline, stroke)
        canvas.drawText(group, tx, baseline, fill)
        canvas.restore()
    }

    /** 橫排換行：行首禁則字不另起行；最後仍超寬時由 fit 迴圈降字級，最低字級仍超寬則標 overflow。 */
    private fun wrapCjk(text: String, paint: Paint, maxW: Float): List<String> {
        val lines = ArrayList<String>()
        val cur = StringBuilder()
        for (ch in text) {
            if (ch == '\n') { lines.add(cur.toString()); cur.clear(); continue }
            if (cur.isNotEmpty() && paint.measureText(cur.toString() + ch) > maxW && ch !in NO_START) {
                lines.add(cur.toString()); cur.clear()
            }
            cur.append(ch)
        }
        if (cur.isNotEmpty()) lines.add(cur.toString())
        return if (lines.isEmpty()) listOf("") else lines
    }

    private const val ASCENT = 0.82f
    private const val STROKE_RATIO = 0.10f
    private const val MAX_TCY = 4
    private const val ROTATE_CHARS = "ー－—―‐~〜～…‥（）()「」『』【】〔〕［］｛｝〈〉《》＜＞<>｜|：;"
    private const val VERTICAL_PUNCT = "、。，．"
    private const val NO_START = "、。，．：；！？”’）〕】｝」』》〉…‥ーゝゞヽヾ々ぁぃぅぇぉっゃゅょゎァィゥェォッャュョヮ"
}
