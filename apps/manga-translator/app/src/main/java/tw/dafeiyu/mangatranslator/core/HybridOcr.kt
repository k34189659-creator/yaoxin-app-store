package tw.dafeiyu.mangatranslator.core

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import li.joye.yakuyomi.engine.Detector
import li.joye.yakuyomi.engine.EngineConfig
import li.joye.yakuyomi.engine.Ocr
import li.joye.yakuyomi.engine.OcrRemedyMode
import li.joye.yakuyomi.engine.PpOcrV6
import li.joye.yakuyomi.engine.Pt
import li.joye.yakuyomi.engine.TextLine
import kotlin.math.max
import kotlin.math.min

/** 三種 OCR 品質模式（對應使用者要求：快速／標準／高品質）。 */
enum class OcrMode {
    /** 只跑預設 OCR（NCNN DBNet + 48px CTC），最快。 */
    FAST,

    /** PP-OCRv6 為主、低信心區用 NCNN 補救。 */
    STANDARD,

    /** 兩套偵測＋兩套辨識交叉驗證，並加切片補抓。 */
    HIGH;

    companion object {
        fun parse(s: String?): OcrMode = when (s?.uppercase()) {
            "FAST" -> FAST
            "HIGH" -> HIGH
            else -> STANDARD
        }
    }
}

/** 來源語言：0.4.0 由使用者手動選擇 JAPANESE / ENGLISH；AUTO 僅保留舊設定相容。 */
enum class SourceLanguage {
    AUTO, JAPANESE, ENGLISH;

    companion object {
        fun parse(s: String?): SourceLanguage = when (s?.uppercase()) {
            "ENGLISH", "EN" -> ENGLISH
            "JAPANESE", "JA" -> JAPANESE
            else -> JAPANESE
        }
    }
}

/**
 * 兩套 OCR 的逐行融合結果。
 *
 * 原則：**不比拼接**——同一條文字行只留一個結果，另一套的讀法進 alternatives 供診斷。
 * 低信心時才讓第二套覆蓋；長度互為前後綴時取較長者（避免尾字被截斷）。
 * 兩套完全不同時保留主引擎結果並標記 disagree，交由翻譯模型容錯，不自行拼字。
 */
object OcrFusion {

    data class Choice(
        val text: String,
        val prob: Float,
        val source: String,
        val disagree: Boolean,
    )

    fun normalize(s: String): String = s.filterNot { it.isWhitespace() }

    fun choose(ppText: String, ppProb: Float, ncText: String, ncProb: Float): Choice {
        val a = ppText.trim()
        val b = ncText.trim()
        if (a.isBlank()) {
            return if (b.isBlank()) Choice("", 0f, "none", false)
            else Choice(b, ncProb, "ncnn-only", false)
        }
        if (b.isBlank()) return Choice(a, ppProb, "pp-only", false)
        if (normalize(a) == normalize(b)) return Choice(a, max(ppProb, ncProb), "agree", false)
        // PP 低信心 → 改用 NCNN（PP 的低信心框多半是誤偵測或糊字）
        if (ppProb < 0.66f && ncProb >= 0.60f) return Choice(b, ncProb, "ncnn-lowconf", true)
        // 一方是另一方的延伸 → 取較長（典型：尾字被裁掉）
        if (a.length > b.length && (a.startsWith(b) || a.endsWith(b))) {
            return Choice(a, ppProb, "pp-longer", true)
        }
        if (b.length > a.length && (b.startsWith(a) || b.endsWith(a)) && ncProb >= ppProb - 0.18f) {
            return Choice(b, ncProb, "ncnn-longer", true)
        }
        return Choice(a, ppProb, "pp", true)
    }

    /** 明顯的誤偵測：空白、極短且低信心、整體信心過低。單字台詞（ま）信心很高，不會被誤刪。 */
    fun isJunk(text: String, prob: Float): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return true
        if (t.length <= 1 && prob < 0.42f) return true
        if (prob < 0.28f) return true
        return false
    }
}

/**
 * ML Kit Latin OCR（英文漫畫橫排）。
 *
 * 注意：ML Kit 的日文辨識器是另一個 artifact（且需要較大模型）；本 App 只用它處理英文頁，
 * 日文一律走 PP-OCRv6 / NCNN。裝置沒有 Google 服務時會丟例外，呼叫端需回退。
 */
class MlKitLatinOcr {

    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    suspend fun recognize(page: Bitmap): List<TextLine> = withContext(Dispatchers.IO) {
        val image = InputImage.fromBitmap(page, 0)
        val result = Tasks.await(recognizer.process(image))
        val out = ArrayList<TextLine>()
        for (block in result.textBlocks) {
            for (line in block.lines) {
                val bb = line.boundingBox ?: continue
                if (bb.width() < 4 || bb.height() < 4) continue
                val q = line.cornerPoints?.takeIf { it.size >= 4 }?.map { Pt(it.x.toFloat(), it.y.toFloat()) } ?: listOf(
                    Pt(bb.left.toFloat(), bb.top.toFloat()),
                    Pt(bb.right.toFloat(), bb.top.toFloat()),
                    Pt(bb.right.toFloat(), bb.bottom.toFloat()),
                    Pt(bb.left.toFloat(), bb.bottom.toFloat()),
                )
                val tl = TextLine(q, 1f)
                tl.text = line.text.trim()
                tl.ocrProb = 0.9f
                tl.direction = "h"
                tl.ocrStrategy = "mlkit-latin"
                if (tl.text.isNotBlank()) out.add(tl)
            }
        }
        out
    }

    fun close() {
        runCatching { recognizer.close() }
    }
}

/**
 * 多引擎 OCR 管線：偵測（NCNN 遮罩＋PP 召回）→ 辨識（PP 主、NCNN 補）→ 融合 → 過濾誤偵測。
 *
 * 為什麼偵測仍保留 NCNN：去字需要「筆畫級遮罩」，PP 只給行框；
 * 因此 NCNN 偵測固定跑一次當遮罩來源，PP 只用來補「NCNN 沒看到的行」。
 * PP 獨有的行沒有筆畫遮罩，會把整條行框補進遮罩（氣泡內為純色，平塗不會傷背景）。
 */
class HybridOcr(
    private val detector: Detector,
    private val ncnn: Ocr,
    private val pp: PpOcrV6?,
    private val mlkit: MlKitLatinOcr?,
    private val mode: OcrMode,
    private val language: SourceLanguage,
    private val config: EngineConfig,
    private val remedy: OcrRemedyMode,
) {
    data class Result(
        val lines: List<TextLine>,
        val mask: Bitmap,
        val passes: Int,
        val engine: String,
        val warnings: List<String>,
        val ppOnly: Int,
        val ncnnOnly: Int,
        val disagreements: Int,
    )

    suspend fun detectAndRecognize(page: Bitmap, useMaskFrom: Bitmap? = null): Result {
        val warnings = ArrayList<String>()
        var passes = 1
        val base = try {
            detector.detect(page)
        } catch (t: Throwable) {
            warnings.add("偵測失敗：${t.message ?: t.javaClass.simpleName}")
            null
        }
        val mask = useMaskFrom?.copy(Bitmap.Config.ARGB_8888, true)
            ?: base?.textMask?.copy(Bitmap.Config.ARGB_8888, true)
            ?: blankMask(page)
        base?.textMask?.recycle()
        var lines = ArrayList(base?.lines ?: emptyList())

        if (language == SourceLanguage.ENGLISH) {
            // ML Kit 是英文管線唯一 OCR；NCNN 在此只供筆畫遮罩，絕不可混入未辨識的日文框。
            val english = try {
                mlkit?.recognize(page).orEmpty()
            } catch (c: kotlinx.coroutines.CancellationException) {
                mask.recycle()
                throw c
            } catch (t: Throwable) {
                warnings.add("英文 OCR 無法使用：${t.message ?: t.javaClass.simpleName}")
                emptyList()
            }
            if (english.isEmpty()) {
                warnings.add("英文 OCR 沒有辨識到文字；保留原圖，請重試或手動處理")
                return Result(emptyList(), mask, passes, "MLKit-Latin", warnings, 0, 0, 0)
            }
            // Keep detector evidence intact. EnglishImagePipeline builds per-region masks from
            // original pixels and closed boundaries; the old per-line filter destroyed this evidence.
            return Result(english, mask, passes, "MLKit-Latin", warnings, 0, 0, 0)
        }

        var ppOnlyCount = 0
        var ncnnOnlyCount = 0
        var disagree = 0

        if (pp != null && mode != OcrMode.FAST) {
            val ppLines = try {
                pp.detect(page)
            } catch (t: Throwable) {
                warnings.add("PP-OCRv6 偵測失敗：${t.message ?: t.javaClass.simpleName}")
                emptyList()
            }
            if (ppLines.isNotEmpty()) {
                passes++
                val merged = ArrayList<TextLine>(ppLines)
                val ppSet = ppLines.toHashSet()
                for (nl in lines) {
                    if (ppLines.none { overlap(it, nl) }) {
                        merged.add(nl)
                        ncnnOnlyCount++
                    }
                }
                // PP 只認自己的行；NCNN 只認 PP 沒抓到的行
                pp.recognizeParallel(page, ppLines)
                val foreign = merged.filter { it !in ppSet }
                if (foreign.isNotEmpty()) {
                    ncnn.recognizeAllBest(page, foreign, remedy)
                    ncnnOnlyCount = foreign.count { it.text.isNotBlank() }
                }
                // 低信心 PP 行 → 第二套辨識
                val weak = ppLines.filter { it.text.isBlank() || it.ocrProb < 0.75f || mode == OcrMode.HIGH }
                if (weak.isNotEmpty()) {
                    val copies = weak.map { TextLine(it.quad, it.score) }
                    ncnn.recognizeAllBest(page, copies, remedy)
                    weak.forEachIndexed { i, line ->
                        val c = copies[i]
                        val choice = OcrFusion.choose(line.text, line.ocrProb, c.text, c.ocrProb)
                        if (choice.disagree) disagree++
                        line.text = choice.text
                        line.ocrProb = choice.prob
                        line.ocrStrategy = choice.source
                    }
                }
                // PP 獨有的行：把行框補進遮罩，否則去字會留下日文
                ppOnlyCount = merged.count { l -> ppSet.contains(l) && base?.lines?.none { overlap(it, l) } == true }
                fillQuads(mask, merged.filter { l -> ppSet.contains(l) && base?.lines?.none { overlap(it, l) } == true })
                lines = merged
            } else {
                warnings.add("PP-OCRv6 沒偵測到文字，使用 NCNN 結果")
                ncnn.recognizeAllBest(page, lines, remedy)
            }
        } else {
            ncnn.recognizeAllBest(page, lines, remedy)
        }

        // 過濾誤偵測（保留單字台詞）
        val before = lines.size
        lines = ArrayList(lines.filter { !OcrFusion.isJunk(it.text, it.ocrProb) })
        if (lines.size < before) warnings.add("濾除 ${before - lines.size} 個低信心誤偵測")
        val engine = when {
            pp != null && mode != OcrMode.FAST -> "PP-OCRv6+NCNN"
            else -> "NCNN-CTC"
        }
        return Result(lines, mask, passes, engine, warnings, ppOnlyCount, ncnnOnlyCount, disagree)
    }

    /** 對補救階段新抓到的行做融合辨識。 */
    suspend fun recognizeFused(page: Bitmap, lines: List<TextLine>) {
        if (lines.isEmpty()) return
        if (pp == null || mode == OcrMode.FAST) {
            ncnn.recognizeAllBest(page, lines, remedy)
            return
        }
        try {
            pp.recognizeParallel(page, lines)
        } catch (t: Throwable) {
            ncnn.recognizeAllBest(page, lines, remedy)
            return
        }
        val weak = lines.filter { it.text.isBlank() || it.ocrProb < 0.78f }
        if (weak.isEmpty()) return
        val copies = weak.map { TextLine(it.quad, it.score) }
        ncnn.recognizeAllBest(page, copies, remedy)
        weak.forEachIndexed { i, line ->
            val c = copies[i]
            val choice = OcrFusion.choose(line.text, line.ocrProb, c.text, c.ocrProb)
            line.text = choice.text
            line.ocrProb = choice.prob
            line.ocrStrategy = choice.source
        }
    }

    // ---------------------------------------------------------------- helpers

    /** 英文 ML Kit 獨有行：只補與局部背景有足夠色差的文字筆畫，不把整個框塗白。 */
    private fun fillEnglishInk(mask: Bitmap, page: Bitmap, lines: List<TextLine>) {
        val w = page.width
        val h = page.height
        for (line in lines) {
            val b = bbox(line) ?: continue
            val x0 = kotlin.math.floor(b[0]).toInt().coerceIn(0, w - 1)
            val y0 = kotlin.math.floor(b[1]).toInt().coerceIn(0, h - 1)
            val x1 = kotlin.math.ceil(b[2]).toInt().coerceIn(x0 + 1, w)
            val y1 = kotlin.math.ceil(b[3]).toInt().coerceIn(y0 + 1, h)
            val bw = x1 - x0
            val bh = y1 - y0
            if (bw < 4 || bh < 4 || bw.toLong() * bh > 1_000_000L) continue
            val pixels = IntArray(bw * bh)
            page.getPixels(pixels, 0, bw, x0, y0, bw, bh)
            // 由四邊估計相鄰色；取亮度中位數，減少黑色字本身對背景取樣的干擾。
            val edge = ArrayList<Int>(2 * (bw + bh))
            for (x in 0 until bw) {
                edge.add(luma(pixels[x])); edge.add(luma(pixels[(bh - 1) * bw + x]))
            }
            for (y in 1 until bh - 1) {
                edge.add(luma(pixels[y * bw])); edge.add(luma(pixels[y * bw + bw - 1]))
            }
            if (edge.size < 8) continue
            edge.sort()
            val bg = edge[edge.size / 2]
            val out = IntArray(pixels.size)
            mask.getPixels(out, 0, bw, x0, y0, bw, bh)
            var changed = 0
            for (i in pixels.indices) {
                if (kotlin.math.abs(luma(pixels[i]) - bg) >= 47) {
                    out[i] = android.graphics.Color.WHITE
                    changed++
                }
            }
            // 排除極端雜訊／彩色插畫區，避免被 OCR 誤框後擦掉大片人物或網點。
            if (changed >= 3 && changed.toFloat() / pixels.size < 0.38f) {
                mask.setPixels(out, 0, bw, x0, y0, bw, bh)
            }
        }
    }

    private fun luma(px: Int): Int = (
        (((px shr 16) and 255) * 299 + ((px shr 8) and 255) * 587 + (px and 255) * 114) / 1000
    )

    private fun fillQuads(mask: Bitmap, lines: List<TextLine>) {
        if (lines.isEmpty()) return
        val c = Canvas(mask)
        val p = Paint().apply {
            color = android.graphics.Color.WHITE
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        for (line in lines) {
            if (line.quad.size < 3) continue
            val path = Path()
            path.moveTo(line.quad[0].x, line.quad[0].y)
            for (i in 1 until line.quad.size) path.lineTo(line.quad[i].x, line.quad[i].y)
            path.close()
            c.drawPath(path, p)
        }
    }

    private fun blankMask(page: Bitmap): Bitmap {
        val b = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
        Canvas(b).drawColor(android.graphics.Color.BLACK)
        return b
    }

    private fun overlap(a: TextLine, b: TextLine): Boolean {
        val b1 = bbox(a) ?: return false
        val b2 = bbox(b) ?: return false
        val ix = max(0f, min(b1[2], b2[2]) - max(b1[0], b2[0]))
        val iy = max(0f, min(b1[3], b2[3]) - max(b1[1], b2[1]))
        val inter = ix * iy
        if (inter <= 0f) return false
        val union = (b1[2] - b1[0]) * (b1[3] - b1[1]) + (b2[2] - b2[0]) * (b2[3] - b2[1]) - inter
        if (union <= 0f) return false
        return inter / union > 0.25f
    }

    private fun bbox(l: TextLine): FloatArray? {
        if (l.quad.isEmpty()) return null
        var x0 = Float.MAX_VALUE
        var y0 = Float.MAX_VALUE
        var x1 = -Float.MAX_VALUE
        var y1 = -Float.MAX_VALUE
        for (p in l.quad) {
            x0 = min(x0, p.x); y0 = min(y0, p.y)
            x1 = max(x1, p.x); y1 = max(y1, p.y)
        }
        return floatArrayOf(x0, y0, x1, y1)
    }
}
