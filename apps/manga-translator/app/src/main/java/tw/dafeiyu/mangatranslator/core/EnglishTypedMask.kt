package tw.dafeiyu.mangatranslator.core

import android.graphics.Bitmap
import android.graphics.Color
import li.joye.yakuyomi.engine.TextRegion
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/**
 * 英文四種分支各自限制擦字遮罩。只處理 OCR 原始行內像素，絕不以翻譯分組大框直接擦畫面。
 * 仍使用已部署的輕量 Latin OCR + NCNN 筆畫遮罩，第一輪不強制安裝新權重。
 */
internal object EnglishTypedMask {
    fun build(page: Bitmap, original: Bitmap, regions: List<TextRegion>, routes: Map<Int, EnglishRegionRoute>): Bitmap {
        val w = page.width; val h = page.height
        val output = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        output.eraseColor(Color.BLACK)
        for ((i, region) in regions.withIndex()) {
            val type = routes[i]?.type ?: EnglishRegionType.PLAIN_ART
            for (line in region.lines) {
                if (line.quad.size < 4) continue
                val left = floor(line.quad.minOf { it.x }).toInt().coerceIn(0, w - 1)
                val top = floor(line.quad.minOf { it.y }).toInt().coerceIn(0, h - 1)
                val right = ceil(line.quad.maxOf { it.x }).toInt().coerceIn(left + 1, w)
                val bottom = ceil(line.quad.maxOf { it.y }).toInt().coerceIn(top + 1, h)
                val bw = right - left; val bh = bottom - top
                if (bw * bh < 9 || bw.toLong() * bh > 450_000L) continue
                val m = IntArray(bw * bh); val p = IntArray(bw * bh)
                original.getPixels(m, 0, bw, left, top, bw, bh)
                page.getPixels(p, 0, bw, left, top, bw, bh)
                val result = IntArray(bw * bh)
                // 對繪畫背景只接受既有文字筆畫；彩字需明顯有色且與鄰近背景有差距。
                val color = p[(bh / 2) * bw + (bw / 2)]
                for (k in m.indices) {
                    if ((m[k] and 255) < 128) continue
                    val pix = p[k]
                    val chroma = maxOf((pix ushr 16) and 255, (pix ushr 8) and 255, pix and 255) -
                        minOf((pix ushr 16) and 255, (pix ushr 8) and 255, pix and 255)
                    val mark = when (type) {
                        EnglishRegionType.NORMAL_BUBBLE -> true
                        EnglishRegionType.COLORED_BUBBLE -> true // 讓原文白字/深色字都能清除，避免疊字
                        EnglishRegionType.PLAIN_ART -> true  // 原掩膜已限制為高對比文字，禁止額外整框填白
                        EnglishRegionType.COLORED_ART -> chroma > 32 || abs((pix and 255) - (color and 255)) > 30
                    }
                    if (mark) result[k] = Color.WHITE
                }
                // 不膨脹；AOT/Legacy 擦字內部仍有可控的少量膨脹。
                output.setPixels(result, 0, bw, left, top, bw, bh)
            }
        }
        return output
    }
}
