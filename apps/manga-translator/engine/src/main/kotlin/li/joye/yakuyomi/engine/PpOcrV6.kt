package li.joye.yakuyomi.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * PP-OCRv6 Manga（ONNX Runtime，Android CPU）——0.3.0 新增的第二套 OCR。
 *
 * 模型：Kellenok/PP-OCRv6_manga v0.3（Apache-2.0）
 *   det  = PP-OCRv6 tiny（PPLCNetV4+RepLKFPN+DBHead），1.73 MB FP32
 *   rec  = PP-OCRv6 small（PPLCNetV4+lightSVTR+CTC），21.33 MB FP32
 *   dict = ppocrv6_dict.txt（19,053 符號，JIS X 0208 全覆蓋）
 *
 * 前處理 / 後處理完全依官方 examples/onnx_openvino.py：
 *   det：長邊 960、除以 255、ImageNet mean/std、輸出 >0.15 二值化、輪廓分數 >0.25、
 *        unclip 1.4（此處以面積/周長等價外擴實作）、長寬比 <2 的長條頁改用 3:4 像素預算。
 *   rec：裁軸對齊 bbox、直書轉 90° CCW、縮到高 48、[-1,1]、CTC greedy（blank=0）。
 *
 * 這裡**不依賴 OpenCV / freetype**；輪廓用連通分量 + 凸包 + 旋轉卡尺求最小外接矩形。
 */
class PpOcrV6(
    detPath: String,
    recPath: String,
    private val dictionary: List<String>,
    private val threads: Int = 4,
) : AutoCloseable {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val options = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(threads.coerceIn(1, 8))
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
    }
    private val detSession: OrtSession = env.createSession(detPath, options)
    private val recSession: OrtSession = env.createSession(recPath, options)
    private val detInput = detSession.inputNames.first()
    private val recInput = recSession.inputNames.first()

    val backend: String = "PP-OCRv6-manga(fp32/ORT)"

    init {
        EngineTrace.log("ppocr.load det=${detSession.inputNames} rec=${recSession.inputNames}")
        Log.i(TAG, "PP-OCRv6 loaded threads=$threads dict=${dictionary.size}")
    }

    // ------------------------------------------------------------------ detect

    /**
     * 全頁文字偵測。回傳的 quad 已換算回原圖座標；score = 該連通區平均機率。
     * 回傳結果可能為空（空白頁），呼叫端需自行處理。
     */
    fun detect(page: Bitmap, longSide: Int = 960): List<TextLine> {
        val w = page.width
        val h = page.height
        if (w < 16 || h < 16) return emptyList()
        val ratio = if (max(w, h).toFloat() / min(w, h) > 2f) {
            min(1.0f, sqrt(0.75f * longSide * longSide / (w.toFloat() * h)))
        } else {
            longSide.toFloat() / max(w, h)
        }
        val rh = max(32, (h * ratio / 32f).roundToInt() * 32)
        val rw = max(32, (w * ratio / 32f).roundToInt() * 32)
        val scaled = Bitmap.createScaledBitmap(page, rw, rh, true)
        val chw = FloatArray(3 * rw * rh)
        val px = IntArray(rw * rh)
        scaled.getPixels(px, 0, rw, 0, 0, rw, rh)
        if (scaled !== page) scaled.recycle()
        val mean = floatArrayOf(0.485f, 0.456f, 0.406f)
        val std = floatArrayOf(0.229f, 0.224f, 0.225f)
        var idx = 0
        for (c in 0..2) {
            val shift = 16 - c * 8
            for (i in px.indices) {
                val v = ((px[i] shr shift) and 0xFF) / 255f
                chw[idx++] = (v - mean[c]) / std[c]
            }
        }
        val shape = longArrayOf(1, 3, rh.toLong(), rw.toLong())
        val prob: Array<FloatArray>
        OnnxTensor.createTensor(env, FloatBuffer.wrap(chw), shape).use { input ->
            detSession.run(mapOf(detInput to input)).use { out ->
                val t = out.get(0) as OnnxTensor
                val v = t.value
                @Suppress("UNCHECKED_CAST")
                prob = (v as Array<Array<Array<FloatArray>>>)[0][0]
            }
        }
        val oh = prob.size
        val ow = if (oh > 0) prob[0].size else 0
        if (oh == 0 || ow == 0) return emptyList()

        val bin = BooleanArray(oh * ow)
        for (y in 0 until oh) {
            val row = prob[y]
            for (x in 0 until ow) if (row[x] > 0.15f) bin[y * ow + x] = true
        }
        val sx = w.toFloat() / ow
        val sy = h.toFloat() / oh
        val lines = ArrayList<TextLine>()
        val seen = BooleanArray(oh * ow)
        val queue = IntArray(oh * ow)
        val pts = ArrayList<FloatArray>()
        for (start in bin.indices) {
            if (!bin[start] || seen[start]) continue
            var qh = 0
            var qt = 0
            queue[qt++] = start
            seen[start] = true
            pts.clear()
            var sum = 0f
            var minX = Int.MAX_VALUE
            var maxX = -1
            var minY = Int.MAX_VALUE
            var maxY = -1
            while (qh < qt) {
                val i = queue[qh++]
                val x = i % ow
                val y = i / ow
                sum += prob[y][x]
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                pts.add(floatArrayOf(x + 0.5f, y + 0.5f))
                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = x + dx
                    val ny = y + dy
                    if (nx < 0 || ny < 0 || nx >= ow || ny >= oh) continue
                    val ni = ny * ow + nx
                    if (bin[ni] && !seen[ni]) {
                        seen[ni] = true
                        queue[qt++] = ni
                    }
                }
            }
            if (pts.size < 6) continue
            val boxW = (maxX - minX + 1).toFloat()
            val boxH = (maxY - minY + 1).toFloat()
            if (boxW < 3f || boxH < 3f) continue
            val score = sum / pts.size
            if (score < 0.25f) continue
            val rect = minAreaRect(pts) ?: continue
            if (rect.w < 3f || rect.h < 3f) continue
            // unclip（面積/周長等價外擴）
            val area = rect.w * rect.h
            val per = 2f * (rect.w + rect.h)
            if (area <= 0f || per <= 0f) continue
            val d = area * 1.45f / per
            val q = rect.expand(d).corners()
            // 換回原圖座標、夾在頁面內
            val quad = q.map { Pt((it[0] * sx).coerceIn(0f, w.toFloat()), (it[1] * sy).coerceIn(0f, h.toFloat())) }
            if (quad.isEmpty()) continue
            lines.add(TextLine(quad, score))
        }
        EngineTrace.log("ppocr.det lines=${lines.size} in=${rw}x$rh")
        return lines
    }

    // -------------------------------------------------------------- recognize

    /**
     * 並行辨識（ORT session 本身執行緒安全；每行是獨立的小推論）。
     * 手機 8 核上 4 路並行約可省 35-45% 時間。
     */
    suspend fun recognizeParallel(page: Bitmap, lines: List<TextLine>, concurrency: Int = 4) =
        coroutineScope {
            if (lines.size <= 1) {
                recognize(page, lines)
                return@coroutineScope
            }
            val sem = Semaphore(concurrency.coerceIn(1, 8))
            lines.map { line ->
                async(Dispatchers.Default) {
                    sem.withPermit {
                        val r = recognizeOne(page, line)
                        line.text = r.first
                        line.ocrProb = r.second
                    }
                }
            }.awaitAll()
        }

    /** 對已偵測的行做辨識（就地填入 text / ocrProb / direction）。單執行緒即可，CTC 很短。 */
    fun recognize(page: Bitmap, lines: List<TextLine>) {
        for (line in lines) {
            val r = recognizeOne(page, line)
            line.text = r.first
            line.ocrProb = r.second
        }
    }

    /** 回傳 (文字, 平均信心)。 */
    fun recognizeOne(page: Bitmap, line: TextLine): Pair<String, Float> {
        val q = line.quad
        if (q.isEmpty()) return "" to 0f
        var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE
        var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
        for (p in q) {
            x0 = min(x0, p.x); y0 = min(y0, p.y)
            x1 = max(x1, p.x); y1 = max(y1, p.y)
        }
        val ix0 = x0.toInt().coerceIn(0, page.width - 1)
        val iy0 = y0.toInt().coerceIn(0, page.height - 1)
        val ix1 = x1.toInt().coerceIn(ix0 + 1, page.width)
        val iy1 = y1.toInt().coerceIn(iy0 + 1, page.height)
        val cw = ix1 - ix0
        val ch = iy1 - iy0
        if (cw < 3 || ch < 3) return "" to 0f
        var crop = Bitmap.createBitmap(page, ix0, iy0, cw, ch)
        var vertical = false
        try {
            if (ch > cw) {
                val m = Matrix().apply { postRotate(-90f) }
                val rotated = Bitmap.createBitmap(crop, 0, 0, crop.width, crop.height, m, true)
                if (rotated !== crop) { crop.recycle(); crop = rotated }
                vertical = true
            }
            val h = crop.height
            val w = crop.width
            if (h < 4 || w < 4) return "" to 0f
            val tw = max(16, (48f * w / h).roundToInt())
            val strip = Bitmap.createScaledBitmap(crop, tw, 48, true)
            val px = IntArray(tw * 48)
            strip.getPixels(px, 0, tw, 0, 0, tw, 48)
            if (strip !== crop) strip.recycle()
            val data = FloatArray(3 * 48 * tw)
            var idx = 0
            for (c in 0..2) {
                val shift = 16 - c * 8
                for (i in px.indices) {
                    val v = ((px[i] shr shift) and 0xFF) / 255f
                    data[idx++] = (v - 0.5f) / 0.5f
                }
            }
            val shape = longArrayOf(1, 3, 48, tw.toLong())
            val text = StringBuilder()
            var sum = 0f
            var n = 0
            var prev = -1
            OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape).use { input ->
                recSession.run(mapOf(recInput to input)).use { out ->
                    val t = out.get(0) as OnnxTensor
                    @Suppress("UNCHECKED_CAST")
                    val logits = (t.value as Array<Array<FloatArray>>)[0]
                    for (row in logits) {
                        var best = 0
                        var bestV = row[0]
                        for (i in 1 until row.size) {
                            if (row[i] > bestV) { bestV = row[i]; best = i }
                        }
                        if (best != 0 && best != prev) {
                            if (best < dictionary.size) text.append(dictionary[best])
                            sum += bestV
                            n++
                        }
                        prev = best
                    }
                }
            }
            line.direction = if (vertical) "v" else "h"
            val conf = if (n > 0) sum / n else 0f
            return text.toString().trim() to conf
        } catch (t: Throwable) {
            Log.w(TAG, "rec 失敗: ${t.message}")
            return "" to 0f
        } finally {
            crop.recycle()
        }
    }

    override fun close() {
        runCatching { detSession.close() }
        runCatching { recSession.close() }
        runCatching { options.close() }
    }

    // --------------------------------------------------------------- geometry

    private data class Rect(var cx: Float, var cy: Float, var w: Float, var h: Float, var angle: Float) {
        fun corners(): List<FloatArray> {
            val a = Math.toRadians(angle.toDouble()).toFloat()
            val ca = cos(a); val sa = sin(a)
            val hw = w / 2f; val hh = h / 2f
            return listOf(
                floatArrayOf(cx - hw * ca + hh * sa, cy - hw * sa - hh * ca),
                floatArrayOf(cx + hw * ca + hh * sa, cy + hw * sa - hh * ca),
                floatArrayOf(cx + hw * ca - hh * sa, cy + hw * sa + hh * ca),
                floatArrayOf(cx - hw * ca - hh * sa, cy - hw * sa + hh * ca),
            )
        }

        fun expand(d: Float): Rect = Rect(cx, cy, w + 2 * d, h + 2 * d, angle)
    }

    /** 最小外接旋轉矩形：凸包 + 旋轉卡尺（對每條凸包邊取投影範圍）。 */
    private fun minAreaRect(points: List<FloatArray>): Rect? {
        if (points.size < 3) return null
        val hull = convexHull(points)
        if (hull.size < 3) return null
        var best: Rect? = null
        var bestArea = Float.MAX_VALUE
        for (i in hull.indices) {
            val a = hull[i]
            val b = hull[(i + 1) % hull.size]
            val dx = b[0] - a[0]
            val dy = b[1] - a[1]
            val len = hypot(dx, dy)
            if (len < 1e-4f) continue
            val ux = dx / len
            val uy = dy / len
            var minU = Float.MAX_VALUE; var maxU = -Float.MAX_VALUE
            var minV = Float.MAX_VALUE; var maxV = -Float.MAX_VALUE
            for (p in hull) {
                val u = p[0] * ux + p[1] * uy
                val v = -p[0] * uy + p[1] * ux
                if (u < minU) minU = u
                if (u > maxU) maxU = u
                if (v < minV) minV = v
                if (v > maxV) maxV = v
            }
            val w = maxU - minU
            val h = maxV - minV
            val area = w * h
            if (area < bestArea) {
                bestArea = area
                val cu = (minU + maxU) / 2f
                val cv = (minV + maxV) / 2f
                val cx = cu * ux - cv * uy
                val cy = cu * uy + cv * ux
                best = Rect(cx, cy, w, h, Math.toDegrees(atan2(uy.toDouble(), ux.toDouble())).toFloat())
            }
        }
        return best
    }

    private fun convexHull(input: List<FloatArray>): List<FloatArray> {
        val pts = input.sortedWith(compareBy({ it[0] }, { it[1] }))
        if (pts.size < 3) return pts
        fun cross(o: FloatArray, a: FloatArray, b: FloatArray): Float =
            (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0])
        val lower = ArrayList<FloatArray>()
        for (p in pts) {
            while (lower.size >= 2 && cross(lower[lower.size - 2], lower.last(), p) <= 0) lower.removeAt(lower.size - 1)
            lower.add(p)
        }
        val upper = ArrayList<FloatArray>()
        for (p in pts.asReversed()) {
            while (upper.size >= 2 && cross(upper[upper.size - 2], upper.last(), p) <= 0) upper.removeAt(upper.size - 1)
            upper.add(p)
        }
        lower.removeAt(lower.size - 1)
        upper.removeAt(upper.size - 1)
        lower.addAll(upper)
        return lower
    }

    companion object {
        private const val TAG = "PpOcrV6"

        /** 解析 ppocrv6_dict.txt（每行一個符號）；索引 0 是 CTC blank。 */
        fun loadDictionary(lines: Sequence<String>): List<String> {
            val dict = ArrayList<String>(19060)
            dict.add("blank")
            for (line in lines) dict.add(line.trimEnd('\r', '\n'))
            dict.add(" ")
            return dict
        }
    }
}
