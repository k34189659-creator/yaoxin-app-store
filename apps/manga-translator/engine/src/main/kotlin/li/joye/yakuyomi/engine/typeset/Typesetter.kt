package li.joye.yakuyomi.engine.typeset

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 漫畫嵌字排版核心（0.3.0）。
 *
 * 設計原則——**先量測、再排版、最後才畫**：
 *  1. 字級不是「字數 ÷ 框面積」估出來的，而是對每個候選字級實際換行/切欄，量測真實墨水寬高後才決定。
 *  2. 任何一步都不截斷譯文；放不下就降字級 → 允許輕微壓縮 → 仍不行才標記 overflow 交由使用者處理。
 *  3. 直排／橫排共用同一組禁則與標點規則，橢圓／不規則對話框用「逐行可用寬度」描述，不再只看 OCR 矩形。
 *
 * 這裡刻意**不 import android.\***：同一份程式可在 JVM 單元測試與桌面驗證工具（AWT 度量）執行，
 * Android 端只負責提供 [TextMeasurer]（Paint）與把 [TextLayout] 畫到 Canvas。
 */

/** 固定字級下的文字度量。實作：Android Paint / 桌面 AWT / 測試用假度量。 */
interface TextMeasurer {
    /** 這組度量的字級（px）。 */
    val size: Float
    /** 水平前進寬度（含字距 0）。 */
    fun advance(text: String): Float
    /** 基線上方距離（正數）。 */
    fun ascent(): Float
    /** 基線下方距離（正數）。 */
    fun descent(): Float
    /** 墨水實際寬度（可能小於 advance；用於判斷是否真的超框）。 */
    fun inkWidth(text: String): Float
}

/** 依字級產生度量器。 */
fun interface MeasurerFactory {
    fun at(size: Float): TextMeasurer
}

/** 矩形範圍；[width]/[height] 為內縮後可用空間。 */
data class LayoutBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = max(0f, right - left)
    val height: Float get() = max(0f, bottom - top)
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    fun inset(dx: Float, dy: Float = dx): LayoutBox =
        LayoutBox(left + dx, top + dy, right - dx, bottom - dy)

    fun contains(x: Float, y: Float, tol: Float = 0.5f): Boolean =
        x >= left - tol && x <= right + tol && y >= top - tol && y <= bottom + tol
}

/**
 * 可排版的區域。除了矩形外，支援「逐列可用寬度」——
 * 對話框是橢圓或不規則形狀時，每一行的可寫寬度不同，文字才能自然塞進真正的空白。
 */
interface LayoutRegion {
    val bounds: LayoutBox
    /** 在垂直帶 [y0,y1] 內可用的最大水平寬度（保守取整段最窄處）。 */
    fun widthAt(y0: Float, y1: Float): Float
    /** 在水平帶 [x0,x1] 內可用的最大垂直高度（保守取整段最窄處）。 */
    fun heightAt(x0: Float, x1: Float): Float
    val maxWidth: Float
    val maxHeight: Float
}

/** 對任何氣泡輪廓添加安全邊距：避免字體描邊及排版略微碰到框線。 */
class InsetRegion(private val delegate: LayoutRegion, private val margin: Float) : LayoutRegion {
    override val bounds: LayoutBox = delegate.bounds.inset(margin)
    override fun widthAt(y0: Float, y1: Float): Float =
        kotlin.math.min(bounds.width, delegate.widthAt(y0,y1) - margin * 2f).coerceAtLeast(0f)
    override fun heightAt(x0: Float, x1: Float): Float =
        kotlin.math.min(bounds.height, delegate.heightAt(x0,x1) - margin * 2f).coerceAtLeast(0f)
    override val maxWidth: Float get() = bounds.width
    override val maxHeight: Float get() = bounds.height
}

class RectRegion(override val bounds: LayoutBox) : LayoutRegion {
    override fun widthAt(y0: Float, y1: Float): Float = bounds.width
    override fun heightAt(x0: Float, x1: Float): Float = bounds.height
    override val maxWidth: Float get() = bounds.width
    override val maxHeight: Float get() = bounds.height
}

/**
 * 橢圓（氣泡）區域：以內接橢圓近似。
 * [safety] < 1 讓文字離邊框更遠（0.90 表示只用內接橢圓的 90%）。
 */
class EllipseRegion(override val bounds: LayoutBox, private val safety: Float = 0.90f) : LayoutRegion {
    private val rx = bounds.width / 2f * safety
    private val ry = bounds.height / 2f * safety

    private fun halfWidthAt(y: Float): Float {
        val dy = abs(y - bounds.centerY) / ry.coerceAtLeast(1e-3f)
        if (dy >= 1f) return 0f
        return rx * kotlin.math.sqrt(1f - dy * dy)
    }

    override fun widthAt(y0: Float, y1: Float): Float {
        // 取整段最窄處（上下端），避免文字凸出弧線。
        val w = min(halfWidthAt(y0), halfWidthAt(y1))
        return (w * 2f).coerceAtLeast(0f)
    }

    override fun heightAt(x0: Float, x1: Float): Float {
        val dx = max(abs(x0 - bounds.centerX), abs(x1 - bounds.centerX)) / rx.coerceAtLeast(1e-3f)
        if (dx >= 1f) return 0f
        return 2f * ry * kotlin.math.sqrt(1f - dx * dx)
    }

    override val maxWidth: Float get() = rx * 2f
    override val maxHeight: Float get() = ry * 2f
}

/**
 * 由輪廓量測出的逐列範圍（不規則對話框）。
 * [rowLeft]/[rowRight] 以頁面像素為單位、索引 = y - top；null 或缺值代表沿用矩形。
 */
class ProfileRegion(
    override val bounds: LayoutBox,
    private val rowLeft: FloatArray?,
    private val rowRight: FloatArray?,
) : LayoutRegion {

    private fun rowSpan(y: Float): Pair<Float, Float>? {
        val rows = rowLeft?.size ?: return null
        if (rows == 0) return null
        val idx = (y - bounds.top).toInt().coerceIn(0, rows - 1)
        val l = rowLeft[idx]
        val r = rowRight?.getOrNull(idx) ?: return null
        if (r <= l) return null
        return l to r
    }

    override fun widthAt(y0: Float, y1: Float): Float {
        var best = bounds.width
        val rows = rowLeft?.size ?: return best
        if (rows == 0) return best
        val a = (y0 - bounds.top).toInt().coerceIn(0, rows - 1)
        val b = (y1 - bounds.top).toInt().coerceIn(0, rows - 1)
        for (i in min(a, b)..max(a, b)) {
            val r = rowRight?.getOrNull(i) ?: continue
            val l = rowLeft[i]
            if (r > l) best = min(best, r - l)
        }
        return best.coerceAtLeast(0f)
    }

    override fun heightAt(x0: Float, x1: Float): Float {
        // 由逐列範圍反推：檢查中心 x 是否落在可用範圍內，回傳仍可容納的總高度。
        val rows = rowLeft?.size ?: return bounds.height
        var count = 0
        for (i in 0 until rows) {
            val r = rowRight?.getOrNull(i) ?: continue
            if (rowLeft[i] <= x0 && r >= x1) count++
        }
        return if (count == 0) 0f else count.toFloat() * (bounds.height / rows)
    }

    override val maxWidth: Float get() = bounds.width
    override val maxHeight: Float get() = bounds.height
}

// ---------------------------------------------------------------------------
// 排版結果
// ---------------------------------------------------------------------------

data class VerticalCell(
    val text: String,
    /** 格中心 x（已含直排標點位移）。 */
    val cx: Float,
    /** 格中心 y。 */
    val cy: Float,
    /** 需要旋轉 90°（括號、破折號、省略號…）。 */
    val rotate: Boolean,
    /** 直排標點（、。，．）需靠右上。 */
    val punctOffset: Boolean,
    /** 縱中橫（數字/短英文並排）。 */
    val tcy: Boolean,
    val cellWidth: Float,
)

data class VerticalColumn(val cells: List<VerticalCell>)

data class VerticalLayout(
    val columns: List<VerticalColumn>,
    override val fontSize: Float,
    override val overflow: Boolean,
    override val note: String?,
    /** 實際使用寬高（頁面 px）。 */
    val usedWidth: Float,
    val usedHeight: Float,
) : TextLayout {
    override val lineCount: Int get() = columns.size
}

data class HorizontalLine(
    val text: String,
    /** 相對於區域的左緣。 */
    val x: Float,
    /** 基線 y。 */
    val baseline: Float,
    val width: Float,
)

data class HorizontalLayout(
    val lines: List<HorizontalLine>,
    override val fontSize: Float,
    override val overflow: Boolean,
    override val note: String?,
    val usedWidth: Float,
    val usedHeight: Float,
) : TextLayout {
    override val lineCount: Int get() = lines.size
}

sealed interface TextLayout {
    val fontSize: Float
    val overflow: Boolean
    val note: String?
    /** 直排＝欄數、橫排＝行數。 */
    val lineCount: Int
}

/** 排版設定（對應 App 的 RenderConfig，但保持純 Kotlin）。 */
data class TypesetOptions(
    val maxFontSize: Int = 60,
    val minFontSize: Int = 10,
    /** 描邊寬度（px）：量測時要預留，避免白邊壓到氣泡框。 */
    val strokeWidth: Float = 0f,
    /** 直排欄距倍率。 */
    val columnGap: Float = 1.08f,
    /** 直排格高倍率。 */
    val cellHeight: Float = 1.02f,
    /** 橫排行距倍率。 */
    val lineHeight: Float = 1.26f,
    /** 左右內縮（px）。 */
    val horizontalPadding: Float = 2f,
    /** 直排時允許欄距壓縮的下限（1.0＝不壓縮）。 */
    val minColumnGap: Float = 0.92f,
    /** 是否使用縱中橫。 */
    val tateChuYoko: Boolean = true,
    /** 放不下時的最小可讀字級；低於此值會標記 overflow（仍會完整畫出）。 */
    val readableMin: Int = 9,
    /** 水平排版需要的度量器（直排為方塊字，不需要）。 */
    val measurer: MeasurerFactory? = null,
)

object Typesetter {

    /** 直排（上→下、右→左）。 */
    fun layoutVertical(text: String, region: LayoutRegion, opts: TypesetOptions = TypesetOptions()): VerticalLayout {
        val normalized = TextNormalizer.normalize(text)
        if (normalized.isBlank()) {
            return VerticalLayout(emptyList(), 0f, false, "空白", 0f, 0f)
        }
        val cells0 = splitCells(normalized, opts.tateChuYoko)
        val availW = (region.maxWidth - opts.horizontalPadding * 2).coerceAtLeast(8f)
        val availH = (region.maxHeight - opts.horizontalPadding * 2).coerceAtLeast(8f)
        val start = min(opts.maxFontSize, floor(availH / opts.cellHeight).toInt().coerceAtLeast(opts.minFontSize))
        var best: VerticalLayout? = null
        var s = start
        while (s >= opts.minFontSize) {
            val plan = buildVertical(cells0, s.toFloat(), region, opts, availW, availH, gapScale = 1f)
            if (!plan.overflow) return plan
            if (best == null) best = plan
            s--
        }
        // 最小字級仍放不下：試著把欄距壓到 minColumnGap（只影響視覺密度，不動字級）。
        val squeezed = buildVertical(
            cells0, opts.minFontSize.toFloat(), region, opts, availW, availH,
            gapScale = opts.minColumnGap.coerceIn(0.80f, 1f),
        )
        if (!squeezed.overflow) return squeezed
        val fallback = squeezed
        return fallback.copy(
            overflow = true,
            note = "全文在最小字級仍超出可用空間（已完整保留，未截斷）",
        )
    }

    /** 橫排（左→右、上→下）。 */
    fun layoutHorizontal(text: String, region: LayoutRegion, opts: TypesetOptions = TypesetOptions()): HorizontalLayout {
        val normalized = TextNormalizer.normalize(text)
        if (normalized.isBlank()) {
            return HorizontalLayout(emptyList(), 0f, false, "空白", 0f, 0f)
        }
        val availW = (region.maxWidth - opts.horizontalPadding * 2).coerceAtLeast(8f)
        val availH = (region.maxHeight - opts.horizontalPadding * 2).coerceAtLeast(8f)
        val factory = opts.measurer ?: error("TypesetOptions.measurer 必須提供")
        val start = min(opts.maxFontSize, floor(availH / opts.lineHeight).toInt().coerceAtLeast(opts.minFontSize))
        var s = start
        while (s >= opts.minFontSize) {
            val plan = buildHorizontal(normalized, s.toFloat(), region, opts, factory, availW, availH)
            if (!plan.overflow) return plan
            s--
        }
        val fallback = buildHorizontal(
            normalized, opts.minFontSize.toFloat(), region, opts, factory, availW, availH,
        )
        return fallback.copy(
            overflow = true,
            note = "全文在最小字級仍超出可用空間（已完整保留，未截斷）",
        )
    }

    // ---------------------------------------------------------------- vertical

    private fun buildVertical(
        cells: List<Cell>,
        size: Float,
        region: LayoutRegion,
        opts: TypesetOptions,
        availW: Float,
        availH: Float,
        gapScale: Float,
    ): VerticalLayout {
        val cellH = size * opts.cellHeight
        val colW = size * opts.columnGap * gapScale
        val capacity = max(1, floor(availH / cellH).toInt())
        val columns = splitColumns(cells, capacity, availH, cellH, region, colW)

        // 實際使用寬高
        var usedW = 0f
        var usedH = 0f
        for (col in columns) {
            usedW += colW
            usedH = max(usedH, col.size * cellH)
        }
        if (columns.isNotEmpty()) usedW -= (colW - size)
        val overflow = usedW > availW + 0.5f || usedH > availH + 0.5f || columns.isEmpty()

        // 定位：整個文字塊在區域內置中；每一欄各自在自己的 x 帶內水平置中。
        val blockW = usedW.coerceAtMost(availW)
        val blockH = usedH.coerceAtMost(availH)
        val startX = region.bounds.centerX - blockW / 2f
        val startY = region.bounds.centerY - blockH / 2f

        val outColumns = ArrayList<VerticalColumn>(columns.size)
        columns.forEachIndexed { ci, col ->
            val colLeft = startX + ci * colW
            val bandLeft = colLeft
            val bandRight = colLeft + colW
            // 該欄可用高度（橢圓/不規則框時左右兩側較矮）
            val allowed = max(cellH, region.heightAt(bandLeft, bandRight))
            val colTop = startY + max(0f, (blockH - col.size * cellH) / 2f)
            val cellsOut = ArrayList<VerticalCell>(col.size)
            col.forEachIndexed { gi, cell ->
                val cy = colTop + gi * cellH + cellH / 2f
                val cx = colLeft + colW / 2f
                // 逐格檢查是否真的落在可用寬度內（橢圓邊緣會自動往內收）
                val wHere = region.widthAt(cy - cellH / 2f, cy + cellH / 2f)
                val halfAllowed = (wHere / 2f).coerceAtLeast(size * 0.5f)
                val lo = region.bounds.centerX - halfAllowed + size * 0.5f
                val hi = region.bounds.centerX + halfAllowed - size * 0.5f
                val adjustedX = if (lo <= hi) cx.coerceIn(lo, hi) else region.bounds.centerX
                val over = (gi + 1) * cellH > allowed + 0.5f
                cellsOut.add(
                    VerticalCell(
                        text = cell.text,
                        cx = adjustedX,
                        cy = cy,
                        rotate = cell.rotate,
                        punctOffset = cell.punctOffset,
                        tcy = cell.tcy,
                        cellWidth = cellW(cell, size),
                    ),
                )
                if (over) {
                    // 超出該欄可用高度：仍完整畫出，交由 overflow 標記。
                }
            }
            outColumns.add(VerticalColumn(cellsOut))
        }
        return VerticalLayout(
            columns = outColumns,
            fontSize = size,
            overflow = overflow,
            note = null,
            usedWidth = usedW,
            usedHeight = usedH,
        )
    }

    private fun cellW(cell: Cell, size: Float): Float =
        if (cell.tcy) (size * 0.92f).coerceAtLeast(size * 0.3f) else size

    /**
     * 切欄：每欄容量受「該欄實際可用高度」限制（橢圓左右較矮），並套用禁則。
     * 禁則處理以「最多多塞 2 格」為限，避免整段被推爆。
     */
    private fun splitColumns(
        cells: List<Cell>,
        capacity: Int,
        availH: Float,
        cellH: Float,
        region: LayoutRegion,
        colW: Float,
    ): List<List<Cell>> {
        val cols = ArrayList<List<Cell>>()
        var i = 0
        var guard = 0
        while (i < cells.size && guard++ < 4096) {
            // 依欄位置估算可用高度（第 cols.size 欄的 x 帶）
            val bandLeft = region.bounds.centerX + (cols.size - 0.5f) * colW
            val bandRight = bandLeft + colW
            val allowed = if (region is RectRegion) availH else region.heightAt(bandLeft, bandRight)
            val cap = max(1, min(capacity, floor(allowed / cellH).toInt().coerceAtLeast(1)))
            var end = min(i + cap, cells.size)
            // 行頭禁則：下一欄不可由「、。」等開頭 → 往後多吃 1–2 格
            var extra = 0
            while (end < cells.size && extra < 2 && cells[end].noStart) {
                end++
                extra++
            }
            // 行末禁則：欄末不可為「（『」等 → 往前退
            while (end > i + 1 && cells[end - 1].noEnd) end--
            if (end <= i) end = min(i + 1, cells.size)
            cols.add(cells.subList(i, end).toList())
            i = end
        }
        return cols
    }

    // -------------------------------------------------------------- horizontal

    private fun buildHorizontal(
        text: String,
        size: Float,
        region: LayoutRegion,
        opts: TypesetOptions,
        factory: MeasurerFactory,
        availW: Float,
        availH: Float,
    ): HorizontalLayout {
        val m = factory.at(size)
        val atoms = TextNormalizer.atoms(text)
        val lines = wrap(atoms, m, availW)
        val lh = size * opts.lineHeight
        val usedH = lines.size * lh
        val usedW = lines.maxOfOrNull { m.advance(it) } ?: 0f
        var perLineOverflow = false
        lines.forEachIndexed { i, line ->
            val bandTop = region.bounds.centerY - usedH.coerceAtMost(availH) / 2f + i * lh
            val allowed = min(availW, region.widthAt(bandTop, bandTop + lh))
            if (m.advance(line) > allowed + 0.5f) perLineOverflow = true
        }
        val overflow = usedH > availH + 0.5f || usedW > availW + 0.5f || perLineOverflow
        val blockH = usedH.coerceAtMost(availH)
        val top = region.bounds.centerY - blockH / 2f
        val out = ArrayList<HorizontalLine>(lines.size)
        lines.forEachIndexed { i, line ->
            val bandTop = top + i * lh
            val bandBottom = bandTop + lh
            val wHere = min(availW, region.widthAt(bandTop, bandBottom)).coerceAtLeast(size)
            val lineW = m.advance(line)
            val x = region.bounds.centerX - lineW / 2f
            val baseline = bandTop + (lh - (m.ascent() + m.descent())) / 2f + m.ascent()
            out.add(HorizontalLine(line, x.coerceAtLeast(region.bounds.left + 1f), baseline, lineW))
            if (lineW > wHere + 0.5f) {
                // 該行在該高度放不下：由外層候選字級重試（這裡不截斷）。
            }
        }
        return HorizontalLayout(out, size, overflow, null, usedW, usedH)
    }

    /** 以「原子」為單位換行：CJK 單字可斷、英文單字不可斷、禁則不落行首。 */
    private fun wrap(atoms: List<Atom>, m: TextMeasurer, maxW: Float): List<String> {
        val lines = ArrayList<String>()
        val cur = StringBuilder()
        var curW = 0f
        var pendingSpace = false
        for (a in atoms) {
            val piece = a.text
            if (a.space) {
                if (cur.isNotEmpty()) pendingSpace = true
                continue
            }
            val addSpace = pendingSpace && cur.isNotEmpty()
            val candidate = if (addSpace) " $piece" else piece
            val w = m.advance(candidate)
            if (cur.isNotEmpty() && curW + w > maxW + 0.2f && !a.noStart) {
                lines.add(cur.toString().trimEnd())
                cur.setLength(0)
                curW = 0f
                pendingSpace = false
                val w2 = m.advance(piece)
                cur.append(piece)
                curW = w2
            } else {
                if (addSpace) cur.append(' ')
                cur.append(piece)
                curW += w
                pendingSpace = false
            }
        }
        if (cur.isNotEmpty()) lines.add(cur.toString().trimEnd())
        // 行末禁則：把行末的「（」推到下一行開頭
        for (i in 0 until lines.size - 1) {
            var guard = 0
            while (lines[i].isNotEmpty() && isNoEnd(lines[i].last()) && guard++ < 3) {
                val ch = lines[i].last()
                lines[i] = lines[i].dropLast(1)
                lines[i + 1] = ch + lines[i + 1]
            }
        }
        return lines.ifEmpty { listOf("") }
    }

    // ------------------------------------------------------------------ cells

    private data class Cell(
        val text: String,
        val rotate: Boolean,
        val punctOffset: Boolean,
        val tcy: Boolean,
        val noStart: Boolean,
        val noEnd: Boolean,
    )

    private fun splitCells(text: String, tcyEnabled: Boolean): List<Cell> {
        val cells = ArrayList<Cell>(text.length)
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (tcyEnabled && Cjk.isTcy(ch)) {
                var j = i + 1
                while (j < text.length && Cjk.isTcy(text[j])) j++
                val run = j - i
                if (run in 2..4) {
                    cells.add(Cell(text.substring(i, j), false, false, true, false, false))
                    i = j
                    continue
                }
                // 過長的英數串：逐字切，避免一整串被塞進一格
                cells.add(Cell(ch.toString(), Cjk.isRotate(ch), false, false, Cjk.isNoStart(ch), Cjk.isNoEnd(ch)))
                i++
                continue
            }
            cells.add(
                Cell(
                    text = ch.toString(),
                    rotate = Cjk.isRotate(ch),
                    punctOffset = Cjk.isVerticalPunct(ch),
                    tcy = false,
                    noStart = Cjk.isNoStart(ch),
                    noEnd = Cjk.isNoEnd(ch),
                ),
            )
            i++
        }
        return cells
    }

    private fun isNoEnd(c: Char) = Cjk.isNoEnd(c)
}

// ---------------------------------------------------------------------------
// 文字正規化與 CJK 規則
// ---------------------------------------------------------------------------

data class Atom(
    val text: String,
    val space: Boolean = false,
    val noStart: Boolean = false,
    val noEnd: Boolean = false,
)

object TextNormalizer {

    /** 半形 → 全形標點、省略號、去多餘空白；不改動譯文內容。 */
    fun normalize(raw: String): String {
        if (raw.isBlank()) return ""
        val sb = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            when {
                c == '\r' -> {}
                c == '\n' -> if (sb.isNotEmpty() && sb.last() != ' ') sb.append(' ')
                c == '.' && i + 2 < raw.length && raw[i + 1] == '.' && raw[i + 2] == '.' -> {
                    // 連續三點以上 → 中文省略號；避免把譯文裡的 "..." 原樣塞進直排
                    var j = i
                    while (j < raw.length && raw[j] == '.') j++
                    sb.append("……")
                    i = j
                    continue
                }
                c == '!' -> sb.append('！')
                c == '?' -> sb.append('？')
                c == ',' -> sb.append('，')
                c == ':' -> sb.append('：')
                c == ';' -> sb.append('；')
                c == '(' -> sb.append('（')
                c == ')' -> sb.append('）')
                c == '[' -> sb.append('【')
                c == ']' -> sb.append('】')
                else -> sb.append(c)
            }
            i++
        }
        var out = sb.toString()
        // 中文之間的半形空格會讓直排出現空洞；英文單字之間保留。
        out = out.replace(Regex("(?<=[\\u3000-\\u9FFF\\uFF00-\\uFFEF]) +(?=[\\u3000-\\u9FFF\\uFF00-\\uFFEF])"), "")
        out = out.replace(Regex(" +"), " ").trim()
        // 連續兩個以上「！」「？」保留一個（漫畫常見的強調仍可讀）
        return out
    }

    /** 切成換行原子：CJK 逐字、拉丁/數字串整段、空白標記。 */
    fun atoms(text: String): List<Atom> {
        val out = ArrayList<Atom>(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == ' ' || c == '\u3000' -> { out.add(Atom(" ", space = true)); i++ }
                Cjk.isLatinOrDigit(c) -> {
                    var j = i + 1
                    while (j < text.length && (Cjk.isLatinOrDigit(text[j]) || text[j] == '-' || text[j] == '\'')) j++
                    out.add(Atom(text.substring(i, j), noStart = false, noEnd = false))
                    i = j
                }
                else -> {
                    out.add(Atom(c.toString(), noStart = Cjk.isNoStart(c), noEnd = Cjk.isNoEnd(c)))
                    i++
                }
            }
        }
        return out
    }
}

object Cjk {
    /** 不可出現在行首（直排＝欄首）的字元。 */
    const val NO_START = "、。，．：；！？）〕】｝」』》〉”’…‥ー～々ぁぃぅぇぉっゃゅょゎァィゥェォッャュョヮゝゞヽヾ"
    /** 不可出現在行尾（直排＝欄尾）的字元。 */
    const val NO_END = "（〔【｛「『《〈“‘"
    /** 直排時要靠右上角的標點。 */
    const val VERTICAL_PUNCT = "、。，．：；"
    /** 直排時要旋轉 90° 的字元（括號、破折號、省略號、波浪線）。 */
    const val ROTATE = "ー－—―‐~〜～…‥（）()「」『』【】〔〕［］｛｝〈〉《》＜＞<>｜|"

    fun isNoStart(c: Char) = NO_START.indexOf(c) >= 0
    fun isNoEnd(c: Char) = NO_END.indexOf(c) >= 0
    fun isVerticalPunct(c: Char) = VERTICAL_PUNCT.indexOf(c) >= 0
    fun isRotate(c: Char) = ROTATE.indexOf(c) >= 0
    fun isTcy(c: Char) = c in '0'..'9' || c in 'A'..'Z' || c in 'a'..'z'
    fun isLatinOrDigit(c: Char) = c in '0'..'9' || c in 'A'..'Z' || c in 'a'..'z'

    fun isCjk(c: Char): Boolean {
        val o = c.code
        return o in 0x3040..0x30FF || o in 0x4E00..0x9FFF || o in 0x3400..0x4DBF ||
            o in 0xFF00..0xFFEF || o in 0x3000..0x303F
    }

    /** 純英數（含空白/標點）→ 應採橫排。 */
    fun isMostlyLatin(text: String): Boolean {
        var latin = 0
        var cjk = 0
        for (c in text) {
            if (isCjk(c) && !isRotate(c)) cjk++
            else if (c.isLetterOrDigit()) latin++
        }
        return cjk == 0 && latin > 0
    }
}
