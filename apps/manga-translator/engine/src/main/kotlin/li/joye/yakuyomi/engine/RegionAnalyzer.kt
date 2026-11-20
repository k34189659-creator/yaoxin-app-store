package li.joye.yakuyomi.engine

import android.graphics.Bitmap
import android.graphics.Color
import li.joye.yakuyomi.engine.typeset.EllipseRegion
import li.joye.yakuyomi.engine.typeset.LayoutBox
import li.joye.yakuyomi.engine.typeset.LayoutRegion
import li.joye.yakuyomi.engine.typeset.ProfileRegion
import li.joye.yakuyomi.engine.typeset.RectRegion
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** 漫畫文字類型：不同類型走不同的擦字／嵌字策略。 */
enum class TextKind {
    /** 一般對話（有氣泡、白底）。 */
    DIALOGUE,

    /** 旁白／說明（無氣泡、常為方框或直接壓在白底）。 */
    NARRATION,

    /** 音效字（短、粗、常在畫面上）。 */
    SFX,

    /** 彩色藝術字（有顏色、描邊、旋轉）。 */
    COLORED_ART,

    /** 手寫體／裝飾體。 */
    HANDWRITING,

    /** 畫面介面文字（看板、螢幕 UI）。 */
    UI,

    /** 裝飾性文字。 */
    DECORATIVE,

    /** 不確定：**不擦、不覆蓋**，保留原圖並標記待人工處理。 */
    UNKNOWN,
}

/** 從原圖量到的文字樣式。 */
data class TextStyleInfo(
    val textColor: Int,
    val outlineColor: Int,
    val hasOutline: Boolean,
    val saturated: Boolean,
    val bold: Boolean,
    val textLuma: Float,
    val bgLuma: Float,
    val confidence: Float,
)

/**
 * 單一文字區的完整檢查結果：類型、可用排版區域、樣式、是否可安全擦除。
 * 由 [RegionAnalyzer] 產生，擦字（Inpainter）與嵌字（SmartRenderer）共用同一份判斷。
 */
data class RegionInspection(
    val kind: TextKind,
    val shape: LayoutRegion,
    val interiorFound: Boolean,
    val style: TextStyleInfo,
    val eraseSafe: Boolean,
    val note: String? = null,
)

/**
 * 漫畫文字區分析（0.3.0）：
 *  1. **對話框內部空間**：從文字框外圍取樣背景色，往內 flood fill（文字本身視為可通行），
 *     得到真正的氣泡內緣；碰到視窗邊界＝漏出氣泡 → 放棄，退回矩形。
 *  2. **文字樣式**：用偵測器的文字遮罩取文字像素的中位色，再用遮罩外環判斷描邊。
 *  3. **類型判斷**：依「是否找到氣泡內緣／背景是否均勻／文字是否高飽和／字數」分類。
 *
 * 全部是像素統計，不需要額外模型；失敗時一律退回安全值（矩形 + 不確定 → 不擦）。
 */
object RegionAnalyzer {

    fun inspect(page: Bitmap, r: TextRegion): RegionInspection {
        val w = page.width
        val h = page.height
        val bx0 = r.x0.toInt().coerceIn(0, w - 1)
        val by0 = r.y0.toInt().coerceIn(0, h - 1)
        val bx1 = r.x1.toInt().coerceIn(bx0 + 1, w)
        val by1 = r.y1.toInt().coerceIn(by0 + 1, h)
        val bw = bx1 - bx0
        val bh = by1 - by0
        val textLen = r.sourceText.trim().length

        // 傾斜文字不做氣泡內緣估計（旋轉座標系成本高且容易誤判），直接用矩形。
        val tilted = abs(r.angle) >= 2f
        val interior = if (tilted) null else estimateInterior(page, bx0, by0, bx1, by1)
        val bg = backgroundStats(page, bx0, by0, bx1, by1)
        val style = analyzeStyle(page, bx0, by0, bx1, by1, bg)

        val uniformBg = bg.std < 22f
        val vertical = r.direction == "v"
        val kind = when {
            interior != null && textLen >= 1 -> TextKind.DIALOGUE
            uniformBg && !style.saturated && textLen >= 2 -> TextKind.NARRATION
            style.saturated && style.confidence > 0.3f -> TextKind.COLORED_ART
            !uniformBg && textLen <= 8 -> TextKind.SFX
            textLen in 1..6 -> TextKind.SFX
            else -> TextKind.UNKNOWN
        }

        val shape: LayoutRegion = when {
            interior != null -> interior
            else -> {
                // 沒有氣泡時不要放大框：以原文字框為主，只留極小呼吸空間。
                val pad = if (kind == TextKind.SFX) 2f else min(6f, max(bw, bh) * 0.04f)
                RectRegion(LayoutBox(r.x0 - pad, r.y0 - pad, r.x1 + pad, r.y1 + pad))
            }
        }

        val eraseSafe = when (kind) {
            TextKind.DIALOGUE, TextKind.NARRATION -> true
            TextKind.SFX -> false
            TextKind.COLORED_ART -> false
            TextKind.HANDWRITING, TextKind.UI, TextKind.DECORATIVE, TextKind.UNKNOWN -> false
        }
        return RegionInspection(
            kind = kind,
            shape = shape,
            interiorFound = interior != null,
            style = style,
            eraseSafe = eraseSafe,
            note = if (eraseSafe) null else "類型 ${kind.name}：保留原圖，待人工處理",
        )
    }

    // ------------------------------------------------------------ interior

    /** 氣泡內緣：回傳逐列可用範圍。 */
    private fun estimateInterior(page: Bitmap, bx0: Int, by0: Int, bx1: Int, by1: Int): LayoutRegion? {
        val w = page.width
        val h = page.height
        val bw = bx1 - bx0
        val bh = by1 - by0
        if (bw < 10 || bh < 10) return null

        val pad = (max(bw, bh) * 0.30f).coerceIn(10f, 72f)
        val wx0 = (bx0 - pad).toInt().coerceIn(0, w - 1)
        val wy0 = (by0 - pad).toInt().coerceIn(0, h - 1)
        val wx1 = (bx1 + pad).toInt().coerceIn(wx0 + 8, w)
        val wy1 = (by1 + pad).toInt().coerceIn(wy0 + 8, h)
        val ww = wx1 - wx0
        val wh = wy1 - wy0
        // 大視窗降採樣，BFS 成本可控
        val step = if (ww * wh > 90_000) 2 else 1
        val sw = ww / step
        val sh = wh / step
        if (sw < 6 || sh < 6) return null

        val px = IntArray(ww * wh)
        page.getPixels(px, 0, ww, wx0, wy0, ww, wh)

        val lum = FloatArray(sw * sh)
        for (y in 0 until sh) {
            val row = y * step * ww
            for (x in 0 until sw) {
                val p = px[row + x * step]
                lum[y * sw + x] = 0.299f * ((p shr 16) and 0xFF) + 0.587f * ((p shr 8) and 0xFF) + 0.114f * (p and 0xFF)
            }
        }

        // 文字框外環（2–7px）估計背景色
        val ring = ArrayList<Float>(256)
        val ringDist = 7 * step
        val ix0 = (bx0 - wx0) / step
        val iy0 = (by0 - wy0) / step
        val ix1 = (bx1 - wx0) / step
        val iy1 = (by1 - wy0) / step
        val coreL = (ix0 - ringDist).coerceAtLeast(0)
        val coreT = (iy0 - ringDist).coerceAtLeast(0)
        val coreR = (ix1 + ringDist).coerceAtMost(sw - 1)
        val coreB = (iy1 + ringDist).coerceAtMost(sh - 1)
        for (y in coreT..coreB) for (x in coreL..coreR) {
            val insideCore = x in ix0..ix1 && y in iy0..iy1
            if (insideCore) continue
            ring.add(lum[y * sw + x])
        }
        if (ring.size < 24) return null
        val bgMean = ring.average().toFloat()
        var varSum = 0.0
        for (v in ring) varSum += (v - bgMean) * (v - bgMean)
        val bgStd = kotlin.math.sqrt(varSum / ring.size).toFloat()
        if (bgStd > 24f) return null

        // BFS：可由外環進入、且顏色接近背景；文字框內部一律視為可通行（文字本身會擋路）。
        val visited = BooleanArray(sw * sh)
        val queue = IntArray(sw * sh)
        var qh = 0
        var qt = 0
        val tol = 30f
        fun seed(x: Int, y: Int) {
            if (x < 0 || y < 0 || x >= sw || y >= sh) return
            val i = y * sw + x
            if (visited[i]) return
            if (abs(lum[i] - bgMean) > tol && !(x in ix0..ix1 && y in iy0..iy1)) return
            visited[i] = true
            queue[qt++] = i
        }
        // 從文字框外一圈開始
        for (y in coreT..coreB) {
            seed(coreL, y)
            seed(coreR, y)
        }
        for (x in coreL..coreR) {
            seed(x, coreT)
            seed(x, coreB)
        }
        var leak = false
        while (qh < qt) {
            val i = queue[qh++]
            val x = i % sw
            val y = i / sw
            if (x == 0 || y == 0 || x == sw - 1 || y == sh - 1) leak = true
            if (x > 0) seed(x - 1, y)
            if (x < sw - 1) seed(x + 1, y)
            if (y > 0) seed(x, y - 1)
            if (y < sh - 1) seed(x, y + 1)
            if (qt >= queue.size) break
        }
        if (leak) return null

        var count = 0
        var fx0 = Int.MAX_VALUE
        var fy0 = Int.MAX_VALUE
        var fx1 = -1
        var fy1 = -1
        val left = IntArray(sh) { Int.MAX_VALUE }
        val right = IntArray(sh) { -1 }
        for (y in 0 until sh) for (x in 0 until sw) {
            if (!visited[y * sw + x]) continue
            count++
            if (x < left[y]) left[y] = x
            if (x > right[y]) right[y] = x
            if (x < fx0) fx0 = x
            if (x > fx1) fx1 = x
            if (y < fy0) fy0 = y
            if (y > fy1) fy1 = y
        }
        if (fy1 < fy0) return null
        val area = count.toFloat() * step * step
        val boxArea = bw.toFloat() * bh
        if (area < boxArea * 1.12f) return null
        // 允許的內緣不能超出文字框太多（避免把整個版面當成氣泡）
        val grownW = (fx1 - fx0 + 1) * step
        val grownH = (fy1 - fy0 + 1) * step
        if (grownW > bw * 2.4f || grownH > bh * 2.4f) return null

        // 轉回全解析度並內縮安全距離
        val margin = max(2f, min(bw, bh) * 0.055f)
        val outLeft = FloatArray(sh)
        val outRight = FloatArray(sh)
        var lastL = (fx0 * step + wx0).toFloat()
        var lastR = (fx1 * step + wx0).toFloat()
        for (y in 0 until sh) {
            if (right[y] < 0) {
                outLeft[y] = lastL
                outRight[y] = lastR
            } else {
                val l = (left[y] * step + wx0).toFloat()
                val rr = ((right[y] + 1) * step + wx0).toFloat()
                lastL = l
                lastR = rr
                outLeft[y] = l + margin
                outRight[y] = rr - margin
            }
        }
        // 逐列限制在整體範圍內；過窄的列直接沿用上一列
        for (y in 0 until sh) {
            if (outRight[y] - outLeft[y] < bw * 0.35f) {
                outLeft[y] = lastL + margin
                outRight[y] = lastR - margin
            }
        }
        val top = (fy0 * step + wy0).toFloat()
        val bottom = ((fy1 + 1) * step + wy0).toFloat()
        val bounds = LayoutBox(
            (fx0 * step + wx0).toFloat(), top,
            ((fx1 + 1) * step + wx0).toFloat(), bottom,
        )
        // ProfileRegion 的 row 索引以 bounds.top 起算、且是「全解析度像素列」，
        // 因此把取樣列的結果展開成 step 條。
        val rows = (fy1 - fy0 + 1) * step
        val profileLeft = FloatArray(rows)
        val profileRight = FloatArray(rows)
        for (sy in fy0..fy1) {
            for (k in 0 until step) {
                val idx = (sy - fy0) * step + k
                if (idx in 0 until rows) {
                    profileLeft[idx] = outLeft[sy]
                    profileRight[idx] = outRight[sy]
                }
            }
        }
        return ProfileRegion(bounds, profileLeft, profileRight)
    }

    // --------------------------------------------------------------- style

    private data class BgStats(val meanLum: Float, val std: Float, val color: Int)

    private fun backgroundStats(page: Bitmap, bx0: Int, by0: Int, bx1: Int, by1: Int): BgStats {
        val w = bx1 - bx0
        val h = by1 - by0
        if (w <= 0 || h <= 0) return BgStats(255f, 0f, Color.WHITE)
        val pad = 3
        val ex0 = (bx0 - pad).coerceIn(0, page.width - 1)
        val ey0 = (by0 - pad).coerceIn(0, page.height - 1)
        val ex1 = (bx1 + pad).coerceIn(ex0 + 1, page.width)
        val ey1 = (by1 + pad).coerceIn(ey0 + 1, page.height)
        val ew = ex1 - ex0
        val eh = ey1 - ey0
        val px = IntArray(ew * eh)
        page.getPixels(px, 0, ew, ex0, ey0, ew, eh)
        var sum = 0.0
        var sum2 = 0.0
        var n = 0
        val colors = IntArray(ew * eh)
        for (y in 0 until eh) for (x in 0 until ew) {
            val gx = ex0 + x
            val gy = ey0 + y
            if (gx in bx0 until bx1 && gy in by0 until by1) continue
            val p = px[y * ew + x]
            val l = 0.299 * ((p shr 16) and 0xFF) + 0.587 * ((p shr 8) and 0xFF) + 0.114 * (p and 0xFF)
            sum += l
            sum2 += l * l
            colors[n++] = p
        }
        if (n == 0) return BgStats(255f, 0f, Color.WHITE)
        val mean = (sum / n).toFloat()
        val varr = (sum2 / n - (sum / n) * (sum / n)).coerceAtLeast(0.0)
        return BgStats(mean, kotlin.math.sqrt(varr).toFloat(), medianColor(colors, n))
    }

    private fun analyzeStyle(page: Bitmap, bx0: Int, by0: Int, bx1: Int, by1: Int, bg: BgStats): TextStyleInfo {
        val w = bx1 - bx0
        val h = by1 - by0
        if (w <= 0 || h <= 0) {
            return TextStyleInfo(Color.BLACK, Color.WHITE, false, false, false, 0f, 255f, 0f)
        }
        val px = IntArray(w * h)
        page.getPixels(px, 0, w, bx0, by0, w, h)
        // 文字像素 = 與背景亮度差 > 45 的像素；取中位色避免抗鋸齒污染。
        val ink = IntArray(w * h)
        var inkCount = 0
        var minL = 255f
        var maxL = 0f
        for (i in px.indices) {
            val p = px[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val l = 0.299f * r + 0.587f * g + 0.114f * b
            if (abs(l - bg.meanLum) > 55f) {
                ink[inkCount++] = p
                if (l < minL) minL = l
                if (l > maxL) maxL = l
            }
        }
        if (inkCount < 12) {
            val fill = if (bg.meanLum > 140f) Color.BLACK else Color.WHITE
            return TextStyleInfo(fill, Color.WHITE, false, false, false, 0f, bg.meanLum, 0.1f)
        }
        val textColor = medianColor(ink, inkCount)
        val tr = (textColor shr 16) and 0xFF
        val tg = (textColor shr 8) and 0xFF
        val tb = textColor and 0xFF
        val textLum = 0.299f * tr + 0.587f * tg + 0.114f * tb
        val sat = max(max(tr, tg), tb) - min(min(tr, tg), tb)
        // 描邊：文字像素外圍一圈（亮度介於文字與背景之間、且不是背景色）
        val outlineVotes = IntArray(9)
        var outlineN = 0
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            val p = px[i]
            val l = 0.299f * ((p shr 16) and 0xFF) + 0.587f * ((p shr 8) and 0xFF) + 0.114f * (p and 0xFF)
            val isInk = abs(l - bg.meanLum) > 55f
            if (isInk) continue
            // 4 鄰居有墨 → 這是文字邊緣
            var near = false
            for ((dx, dy) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                val q = px[(y + dy) * w + (x + dx)]
                val ql = 0.299f * ((q shr 16) and 0xFF) + 0.587f * ((q shr 8) and 0xFF) + 0.114f * (q and 0xFF)
                if (abs(ql - bg.meanLum) > 55f) { near = true; break }
            }
            if (!near) continue
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val bin = ((r / 64) * 3 + (g / 64) + (b / 128)) % 9
            outlineVotes[bin]++
            outlineN++
        }
        var hasOutline = false
        var outlineColor = Color.WHITE
        if (outlineN > 8) {
            var best = 0
            var bestN = 0
            for (i in outlineVotes.indices) if (outlineVotes[i] > bestN) { bestN = outlineVotes[i]; best = i }
            // 邊緣像素顏色若和背景差很多 → 有描邊
            val frac = bestN.toFloat() / outlineN
            if (frac > 0.28f) {
                hasOutline = true
                // 描邊顏色用「背景」的相反亮度近似（漫畫多為白邊黑字或黑邊白字）
                outlineColor = if (bg.meanLum > 140f) Color.WHITE else Color.BLACK
            }
        }
        // 粗體：墨水覆蓋率
        val inkRatio = inkCount.toFloat() / (w * h)
        val bold = inkRatio > 0.32f
        val confidence = min(1f, 0.25f + inkCount / (w * h * 0.4f))
        return TextStyleInfo(
            textColor = textColor,
            outlineColor = outlineColor,
            hasOutline = hasOutline,
            saturated = sat > 48,
            bold = bold,
            textLuma = textLum,
            bgLuma = bg.meanLum,
            confidence = confidence,
        )
    }

    private fun medianColor(px: IntArray, n: Int): Int {
        if (n <= 0) return Color.BLACK
        val r = IntArray(256)
        val g = IntArray(256)
        val b = IntArray(256)
        for (i in 0 until n) {
            val p = px[i]
            r[(p shr 16) and 0xFF]++
            g[(p shr 8) and 0xFF]++
            b[p and 0xFF]++
        }
        fun median(arr: IntArray): Int {
            var acc = 0
            val half = n / 2
            for (i in arr.indices) {
                acc += arr[i]
                if (acc > half) return i
            }
            return 0
        }
        return Color.rgb(median(r), median(g), median(b))
    }
}
