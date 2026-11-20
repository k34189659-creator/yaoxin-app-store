package tw.dafeiyu.mangatranslator.core

import android.graphics.Bitmap
import li.joye.yakuyomi.engine.TextRegion
import kotlin.math.abs
import kotlin.math.max

/** 第一輪獨立輸出稽核：確認確有擦字變化，不將覆蓋式疊字誤報「完成」。 */
internal object EnglishQualityAudit {
    data class Verdict(val index: Int, val passed: Boolean, val note: String)

    /** 對未確實擦除的英文字行恢復原圖像素；不會覆蓋整個分組框。 */
    fun restoreUnverified(original: Bitmap, cleaned: Bitmap,
                          regions: List<TextRegion>, verdicts: List<Verdict>) {
        for (v in verdicts) {
            if (v.passed) continue
            regions.getOrNull(v.index)?.lines?.forEach { line ->
                if (line.quad.size < 4) return@forEach
                val x0 = line.quad.minOf { it.x }.toInt().coerceIn(0, original.width - 1)
                val y0 = line.quad.minOf { it.y }.toInt().coerceIn(0, original.height - 1)
                val x1 = line.quad.maxOf { it.x }.toInt().coerceIn(x0 + 1, original.width)
                val y1 = line.quad.maxOf { it.y }.toInt().coerceIn(y0 + 1, original.height)
                val w = x1 - x0; val h = y1 - y0
                if (w > 0 && h > 0 && w.toLong() * h <= 500_000L) {
                    val pixels = IntArray(w * h)
                    original.getPixels(pixels, 0, w, x0, y0, w, h)
                    cleaned.setPixels(pixels, 0, w, x0, y0, w, h)
                }
            }
        }
    }

    fun audit(original: Bitmap, cleaned: Bitmap, mask: Bitmap,
              regions: List<TextRegion>, indices: List<Int>): List<Verdict> {
        val result = ArrayList<Verdict>(indices.size)
        for (idx in indices) {
            val r = regions.getOrNull(idx) ?: continue
            val x0 = r.x0.toInt().coerceIn(0, original.width - 1)
            val y0 = r.y0.toInt().coerceIn(0, original.height - 1)
            val x1 = r.x1.toInt().coerceIn(x0 + 1, original.width)
            val y1 = r.y1.toInt().coerceIn(y0 + 1, original.height)
            val step = max(1, max(x1 - x0, y1 - y0) / 150)
            var masked = 0; var altered = 0
            for (y in y0 until y1 step step) for (x in x0 until x1 step step) {
                if ((mask.getPixel(x,y) and 255) < 128) continue
                masked++
                val a = original.getPixel(x,y); val b = cleaned.getPixel(x,y)
                val diff = abs(((a ushr 16) and 255) - ((b ushr 16) and 255)) +
                    abs(((a ushr 8) and 255) - ((b ushr 8) and 255)) + abs((a and 255) - (b and 255))
                if (diff > 36) altered++
            }
            val ok = masked >= 4 && altered >= max(2, masked / 12)
            result.add(Verdict(idx, ok,
                if (masked < 4) "遮罩不足，未確認原文已清除" else if (!ok) "擦字變化不足，原文可能殘留" else "已確認擦字像素有變化"))
        }
        return result
    }
}
