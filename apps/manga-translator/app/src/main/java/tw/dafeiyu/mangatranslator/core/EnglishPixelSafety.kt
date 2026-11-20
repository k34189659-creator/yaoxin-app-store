package tw.dafeiyu.mangatranslator.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Pixel-only implementation shared by the Android adapter and regression tests. */
internal object EnglishPixelSafety {
    data class Point(val x: Float, val y: Float)
    data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width get() = right - left
        val height get() = bottom - top
    }
    data class Plan(
        val width: Int,
        val height: Int,
        val ink: BooleanArray,
        val edit: BooleanArray,
        val interior: BooleanArray?,
        val layout: Box?,
        val background: Int,
        val coloredInk: Boolean,
        val failure: String?,
    ) {
        val framed get() = interior != null
    }

    /**
     * A closed background component is evidence of an enclosed bubble; uniform colour alone is not.
     * Components are inspected BEFORE clipping them to OCR boxes, so a frame crossing a line box
     * cannot become a new, apparently small letter. Bold letters are not discarded by area alone.
     */
    fun prepare(pixels: IntArray, detector: IntArray, w: Int, h: Int,
                polygons: List<List<Point>>): Plan {
        require(w > 0 && h > 0 && pixels.size == w * h && detector.size == pixels.size)
        val support = BooleanArray(pixels.size)
        for (poly in polygons) {
            if (poly.size < 3) continue
            val l = poly.minOf { it.x }.toInt().coerceIn(0, w - 1)
            val t = poly.minOf { it.y }.toInt().coerceIn(0, h - 1)
            val r = kotlin.math.ceil(poly.maxOf { it.x }).toInt().coerceIn(l + 1, w)
            val b = kotlin.math.ceil(poly.maxOf { it.y }).toInt().coerceIn(t + 1, h)
            for (y in t until b) for (x in l until r) {
                if (inPolygon(x + .5f, y + .5f, poly)) support[y * w + x] = true
            }
        }
        val textBox = bounds(support, w, h)
            ?: return Plan(w, h, support, support.copyOf(), null, null, 0, false, "沒有有效的文字座標")
        val softSupport = dilate(support, w, h, 1)
        val nSupport = softSupport.count { it }
        val bg = dominantColor(pixels, support)
        val interior = enclosedInterior(pixels, support, bg, w, h)
        val foreground = BooleanArray(pixels.size) { i ->
            if (interior != null) interior[i] && distance(pixels[i], bg) > 27
            else (detector[i] and 255) >= 144
        }
        val ink = BooleanArray(pixels.size)
        val seen = BooleanArray(pixels.size)
        val queue = IntArray(pixels.size)
        var rejectedPixels = 0
        for (start in pixels.indices) {
            if (!foreground[start] || seen[start]) continue
            var head = 0; var tail = 1; queue[0] = start; seen[start] = true
            var supported = 0; var touchesEdge = false
            while (head < tail) {
                val i = queue[head++]; val x = i % w; val y = i / w
                if (softSupport[i]) supported++
                if (x == 0 || y == 0 || x == w - 1 || y == h - 1) touchesEdge = true
                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val xx = x + dx; val yy = y + dy
                    if (xx !in 0 until w || yy !in 0 until h) continue
                    val j = yy * w + xx
                    if (foreground[j] && !seen[j]) { seen[j] = true; queue[tail++] = j }
                }
            }
            if (supported == 0) continue
            // Artwork / frame lines continuing outside the OCR area must stay untouched.
            val contained = supported.toFloat() / tail >= if (interior != null) .84f else .90f
            val oversized = tail > nSupport * .65f
            if (!contained || touchesEdge || oversized) { rejectedPixels += supported; continue }
            if (interior == null) {
                // A detector-filled rectangle is not a stroke mask. Demand local contrast too.
                var contrast = 0; var traceSeed = queue[0]; var bestContrast = -1
                val radius = (textBox.height / max(1, polygons.size) / 2).coerceIn(3, 15)
                for (k in 0 until tail) {
                    val i = queue[k]; val x = i % w; val y = i / w
                    var d = 0; var n = 0
                    for (direction in 0..3) {
                        val dx = when (direction) { 0 -> -radius; 1 -> radius; else -> 0 }
                        val dy = when (direction) { 2 -> -radius; 3 -> radius; else -> 0 }
                        val xx = x + dx; val yy = y + dy
                        if (xx in 0 until w && yy in 0 until h) { d += distance(pixels[i], pixels[yy * w + xx]); n++ }
                    }
                    if (n > 0 && d / n >= 18) contrast++
                    if (n > 0 && d / n > bestContrast) { bestContrast = d / n; traceSeed = i }
                }
                if (contrast < tail * .40f) { rejectedPixels += supported; continue }
                // Segmentation can clip a long balloon contour into a short apparent glyph.
                // Follow the ORIGINAL colour, not just the already-clipped detector component.
                if (originalContourEscapes(pixels, softSupport, w, h, traceSeed, tail)) {
                    rejectedPixels += supported; continue
                }
            }
            for (k in 0 until tail) if (softSupport[queue[k]]) ink[queue[k]] = true
        }
        val nInk = ink.count { it }
        val protectedInterior = interior?.let { erode(it, w, h, 1) }
        val edit = dilate(ink, w, h, 1)
        for (i in edit.indices) edit[i] = edit[i] && softSupport[i] && (protectedInterior?.get(i) != false)
        val lost = ink.indices.count { ink[it] && !edit[it] }
        val failure = when {
            nInk < 2 -> "無法取得可信的英文筆畫遮罩"
            nInk > nSupport * .62f -> "筆畫遮罩包含過多背景，已保留原圖"
            lost > max(2, nInk / 25) -> "文字與氣泡邊線相連，無法安全擦除"
            rejectedPixels > max(8, nInk) -> "文字遮罩與圖案或邊線相連，已保留原圖"
            else -> null
        }
        val layout = if (interior == null) textBox else {
            val margin = (textBox.height / max(1, polygons.size) * .55f).roundToInt().coerceIn(3, 20)
            val limit = Box((textBox.left - margin).coerceAtLeast(0), (textBox.top - margin).coerceAtLeast(0),
                (textBox.right + margin).coerceAtMost(w), (textBox.bottom + margin).coerceAtMost(h))
            largestRectangle(erode(interior, w, h, 2), w, limit,
                (textBox.left + textBox.right) / 2, (textBox.top + textBox.bottom) / 2)
        }
        val colored = nInk > 0 && ink.indices.count {
            ink[it] && saturation(pixels[it]) > 60 && saturation(pixels[it]) > saturation(bg) + 25
        } > nInk * .32f
        return Plan(w, h, ink, edit, interior, layout, bg, colored,
            failure ?: if (layout == null) "沒有能容納譯文的安全排版區域" else null)
    }

    /** Fit a background plane only inside a verified bubble. Modify exactly the edit mask. */
    fun smoothFill(original: IntArray, plan: Plan): IntArray? {
        val inside = plan.interior ?: return null
        val w = plan.width; val h = plan.height
        val samples = ArrayList<Int>()
        var available = 0
        val step = max(1, original.size / 5000)
        for (i in original.indices step step) {
            if (!inside[i] || plan.edit[i]) continue
            available++
            if (distance(original[i], plan.background) <= 42) samples.add(i)
        }
        if (samples.size < 12 || samples.size < available * .85f) return null
        val matrix = Array(3) { DoubleArray(3) }
        val rhs = Array(3) { DoubleArray(3) }
        for (i in samples) {
            val v = doubleArrayOf(1.0, (i % w).toDouble() / w, (i / w).toDouble() / h)
            for (a in 0..2) {
                for (b in 0..2) matrix[a][b] += v[a] * v[b]
                for (c in 0..2) rhs[c][a] += v[a] * ((original[i] ushr ((2 - c) * 8)) and 255)
            }
        }
        val coeff = Array(3) { c -> solve(matrix, rhs[c]) ?: return null }
        fun predict(i: Int): Int {
            val x = (i % w).toDouble() / w; val y = (i / w).toDouble() / h
            val rgb = IntArray(3) { c -> (coeff[c][0] + coeff[c][1] * x + coeff[c][2] * y).roundToInt().coerceIn(0, 255) }
            return (0xff shl 24) or (rgb[0] shl 16) or (rgb[1] shl 8) or rgb[2]
        }
        val residual = samples.map { distance(original[it], predict(it)) }.sorted()
        if (residual.average() > 6.0 || residual[residual.size * 9 / 10] > 13) return null
        val out = original.copyOf()
        for (i in out.indices) if (plan.edit[i]) out[i] = predict(i)
        return out
    }

    fun composite(original: IntArray, candidate: IntArray, edit: BooleanArray): IntArray {
        require(original.size == candidate.size && edit.size == original.size)
        return IntArray(original.size) { i -> if (edit[i]) candidate[i] else original[i] }
    }

    /** Necessary check, not a claim of OCR correctness; the pipeline also performs residual OCR. */
    fun visiblyErased(original: IntArray, cleaned: IntArray, ink: BooleanArray): Boolean {
        require(original.size == cleaned.size && ink.size == original.size)
        val count = ink.count { it }
        if (count < 2) return false
        val changed = ink.indices.count { ink[it] && distance(original[it], cleaned[it]) >= 18 }
        return changed >= max(2, (count * .80f).roundToInt())
    }

    private fun enclosedInterior(p: IntArray, support: BooleanArray, bg: Int, w: Int, h: Int): BooleanArray? {
        val box = bounds(support, w, h) ?: return null
        val cx = (box.left + box.right) / 2; val cy = (box.top + box.bottom) / 2
        val seed = p.indices.asSequence().filter { support[it] && distance(p[it], bg) <= 18 }
            .minByOrNull { abs(it % w - cx) + abs(it / w - cy) } ?: return null
        val visited = BooleanArray(p.size); val queue = IntArray(p.size)
        var head = 0; var tail = 1; queue[0] = seed; visited[seed] = true
        var edge = false
        while (head < tail) {
            val i = queue[head++]; val x = i % w; val y = i / w
            if (x == 0 || y == 0 || x == w - 1 || y == h - 1) edge = true
            neighbours(i, w, h) { j ->
                if (!visited[j] && distance(p[j], bg) <= 29) { visited[j] = true; queue[tail++] = j }
            }
        }
        if (edge || tail < 24) return null
        // Flood the exterior of this background. Letter-shaped holes become interior, border ink does not.
        val outside = BooleanArray(p.size); head = 0; tail = 0
        fun add(i: Int) { if (!visited[i] && !outside[i]) { outside[i] = true; queue[tail++] = i } }
        for (x in 0 until w) { add(x); add((h - 1) * w + x) }
        for (y in 0 until h) { add(y * w); add(y * w + w - 1) }
        while (head < tail) { val i = queue[head++]; neighbours(i, w, h) { add(it) } }
        val inside = BooleanArray(p.size) { !outside[it] }
        val supported = support.count { it }
        if (inside.indices.count { inside[it] && support[it] } < supported * .97f) return null
        return inside
    }

    private fun largestRectangle(mask: BooleanArray, w: Int, limit: Box, cx: Int, cy: Int): Box? {
        val heights = IntArray(limit.width); val stack = IntArray(limit.width + 1)
        var best: Box? = null; var area = 0
        for (y in limit.top until limit.bottom) {
            for (x in heights.indices) heights[x] = if (mask[y * w + limit.left + x]) heights[x] + 1 else 0
            var n = 0
            for (x in 0..heights.size) {
                val current = if (x == heights.size) 0 else heights[x]
                while (n > 0 && heights[stack[n - 1]] > current) {
                    val height = heights[stack[--n]]; val left = if (n == 0) 0 else stack[n - 1] + 1
                    val box = Box(limit.left + left, y - height + 1, limit.left + x, y + 1)
                    if (cx in box.left until box.right && cy in box.top until box.bottom && box.width * box.height > area) {
                        best = box; area = box.width * box.height
                    }
                }
                stack[n++] = x
            }
        }
        return best?.takeIf { it.width >= 8 && it.height >= 8 }
    }

    private fun dominantColor(p: IntArray, support: BooleanArray): Int {
        val histogram = IntArray(512)
        fun bin(c: Int) = (((c ushr 21) and 7) shl 6) or (((c ushr 13) and 7) shl 3) or ((c ushr 5) and 7)
        for (i in p.indices) if (support[i]) histogram[bin(p[i])]++
        val best = histogram.indices.maxByOrNull { histogram[it] } ?: 0
        val channels = Array(3) { IntArray(256) }; var count = 0
        for (i in p.indices) if (support[i] && bin(p[i]) == best) {
            count++
            for (c in 0..2) channels[c][(p[i] ushr ((2 - c) * 8)) and 255]++
        }
        if (count == 0) return 0xffeeeeee.toInt()
        val rgb = IntArray(3) { c ->
            var n = 0; var value = 0
            for (v in 0..255) { n += channels[c][v]; if (n > count / 2) { value = v; break } }
            value
        }
        return (0xff shl 24) or (rgb[0] shl 16) or (rgb[1] shl 8) or rgb[2]
    }
    private fun solve(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val m = Array(3) { i -> DoubleArray(4) { j -> if (j == 3) b[i] else a[i][j] } }
        for (k in 0..2) {
            val pivot = (k..2).maxByOrNull { abs(m[it][k]) } ?: return null
            if (abs(m[pivot][k]) < 1e-8) return null
            val row = m[k]; m[k] = m[pivot]; m[pivot] = row
            val d = m[k][k]; for (j in k..3) m[k][j] /= d
            for (i in 0..2) if (i != k) { val f = m[i][k]; for (j in k..3) m[i][j] -= f * m[k][j] }
        }
        return DoubleArray(3) { m[it][3] }
    }
    private fun bounds(m: BooleanArray, w: Int, h: Int): Box? {
        var l = w; var t = h; var r = -1; var b = -1
        for (i in m.indices) if (m[i]) { val x = i % w; val y = i / w; l = min(l, x); t = min(t, y); r = max(r, x); b = max(b, y) }
        return if (r < l) null else Box(l, t, r + 1, b + 1)
    }
    private fun dilate(m: BooleanArray, w: Int, h: Int, radius: Int): BooleanArray {
        val out = BooleanArray(m.size)
        for (i in m.indices) if (m[i]) {
            val x = i % w; val y = i / w
            for (yy in max(0, y - radius)..min(h - 1, y + radius))
                for (xx in max(0, x - radius)..min(w - 1, x + radius)) out[yy * w + xx] = true
        }
        return out
    }
    private fun erode(m: BooleanArray, w: Int, h: Int, radius: Int): BooleanArray {
        val outside = dilate(BooleanArray(m.size) { !m[it] }, w, h, radius)
        return BooleanArray(m.size) { i -> m[i] && !outside[i] && i % w >= radius && i % w < w - radius && i / w >= radius && i / w < h - radius }
    }
    private inline fun neighbours(i: Int, w: Int, h: Int, visit: (Int) -> Unit) {
        val x = i % w; val y = i / w
        if (x > 0) visit(i - 1)
        if (x + 1 < w) visit(i + 1)
        if (y > 0) visit(i - w)
        if (y + 1 < h) visit(i + w)
    }
    private fun originalContourEscapes(p: IntArray, support: BooleanArray, w: Int, h: Int,
                                       seed: Int, componentSize: Int): Boolean {
        val visited = HashSet<Int>(); val queue = java.util.ArrayDeque<Int>()
        visited.add(seed); queue.add(seed)
        var outside = 0
        val allowance = max(3, componentSize / 10)
        val color = p[seed]
        while (queue.isNotEmpty()) {
            val i = queue.removeFirst(); val x = i % w; val y = i / w
            if (!support[i] && ++outside > allowance) return true
            for (dy in -1..1) for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val xx = x + dx; val yy = y + dy
                if (xx !in 0 until w || yy !in 0 until h) continue
                val j = yy * w + xx
                if (j !in visited && distance(p[j], color) <= 18) { visited.add(j); queue.add(j) }
            }
        }
        return false
    }
    private fun inPolygon(x: Float, y: Float, poly: List<Point>): Boolean {
        var inside = false; var j = poly.lastIndex
        for (i in poly.indices) {
            val a = poly[i]; val b = poly[j]
            if ((a.y > y) != (b.y > y) && x < (b.x - a.x) * (y - a.y) / (b.y - a.y) + a.x) inside = !inside
            j = i
        }
        return inside
    }
    fun distance(a: Int, b: Int): Int = (abs(((a ushr 16) and 255) - ((b ushr 16) and 255)) +
        abs(((a ushr 8) and 255) - ((b ushr 8) and 255)) + abs((a and 255) - (b and 255))) / 3
    fun saturation(c: Int): Int = maxOf((c ushr 16) and 255, (c ushr 8) and 255, c and 255) -
        minOf((c ushr 16) and 255, (c ushr 8) and 255, c and 255)
}
