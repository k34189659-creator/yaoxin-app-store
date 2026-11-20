package tw.dafeiyu.mangatranslator.core

import android.graphics.Bitmap
import android.graphics.Color
import li.joye.yakuyomi.engine.TextLine
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/**
 * 英文專用筆畫遮罩。只允許 ML Kit 找到的字行內的前景像素進入擦字，
 * 不再把 NCNN 在人物、氣泡邊框上找到的雜訊當作需要擦除的文字。
 * 不會作用於日文 0.2.0 流程。
 */
internal object EnglishInkMask {
    fun build(page: Bitmap, lines: List<TextLine>, detectorMask: Bitmap): Bitmap {
        val w = page.width; val h = page.height
        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.eraseColor(Color.BLACK)
        for (line in lines) {
            if (line.quad.size < 4) continue
            val x0 = floor(line.quad.minOf { it.x }).toInt().coerceIn(0, w - 1)
            val y0 = floor(line.quad.minOf { it.y }).toInt().coerceIn(0, h - 1)
            val x1 = ceil(line.quad.maxOf { it.x }).toInt().coerceIn(x0 + 1, w)
            val y1 = ceil(line.quad.maxOf { it.y }).toInt().coerceIn(y0 + 1, h)
            val bw = x1 - x0; val bh = y1 - y0
            if (bw < 3 || bh < 3 || bw.toLong() * bh > 450_000) continue
            val p = IntArray(bw * bh)
            val nc = IntArray(bw * bh)
            page.getPixels(p, 0, bw, x0, y0, bw, bh)
            detectorMask.getPixels(nc, 0, bw, x0, y0, bw, bh)

            // 背景從四邊採樣，不以整框均值取代背景，避免原本黑字把顏色拉暗。
            val edge = ArrayList<Int>(2 * (bw + bh))
            for (x in 0 until bw) { edge.add(luma(p[x])); edge.add(luma(p[(bh - 1) * bw + x])) }
            for (y in 1 until bh - 1) { edge.add(luma(p[y * bw])); edge.add(luma(p[y * bw + bw - 1])) }
            edge.sort()
            val bg = edge[edge.size / 2]
            // 同一個 ML Kit 行框可能跨越有色聊天框與白底：不能將整片背景色判成文字。
            val mixedBackground = edge[edge.size * 85 / 100] - edge[edge.size * 15 / 100] > 48
            val dark = bg >= 110
            val threshold = if (dark) max(27, (bg * 0.17).toInt()) else max(27, ((255 - bg) * 0.17).toInt())
            val on = BooleanArray(p.size)
            var n = 0
            for (i in p.indices) {
                val l = luma(p[i])
                val contrastInk = !mixedBackground &&
                    (if (dark) l + threshold <= bg else l - threshold >= bg)
                val detectorInk = (nc[i] and 0xff) > 150 && abs(l - bg) >= 25
                if (contrastInk || detectorInk) { on[i] = true; n++ }
            }
            // 像人物或彩色紋理的框不應被大面積擦除。
            if (n < 2 || n.toFloat() / p.size > 0.72f) {
                n = 0
                for (i in p.indices) {
                    on[i] = (nc[i] and 0xff) > 150 && abs(luma(p[i]) - bg) >= 32
                    if (on[i]) n++
                }
                if (n < 2 || n.toFloat() / p.size > 0.72f) continue
            }
            // 避免有色框、邊框、陰影形成大面積連通塊，被當成整片需要擦掉的字。
            discardLargePatches(on, bw, bh)
            val pixels = IntArray(p.size) { if (on[it]) Color.WHITE else Color.BLACK }
            result.setPixels(pixels, 0, bw, x0, y0, bw, bh)
        }
        return result
    }

    /** 真正英文字筆畫通常是分離的細連通塊；跨大面積的連通塊多半不是文字。 */
    private fun discardLargePatches(on: BooleanArray, w: Int, h: Int) {
        val seen = BooleanArray(on.size)
        val queue = IntArray(on.size)
        for (i in on.indices) {
            if (!on[i] || seen[i]) continue
            var head = 0; var tail = 1
            queue[0] = i; seen[i] = true
            var minX = i % w; var maxX = minX; var minY = i / w; var maxY = minY
            while (head < tail) {
                val cur = queue[head++]; val x = cur % w; val y = cur / w
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                if (x > 0 && on[cur - 1] && !seen[cur - 1]) {
                    seen[cur - 1] = true; queue[tail++] = cur - 1
                }
                if (x + 1 < w && on[cur + 1] && !seen[cur + 1]) {
                    seen[cur + 1] = true; queue[tail++] = cur + 1
                }
                if (y > 0 && on[cur - w] && !seen[cur - w]) {
                    seen[cur - w] = true; queue[tail++] = cur - w
                }
                if (y + 1 < h && on[cur + w] && !seen[cur + w]) {
                    seen[cur + w] = true; queue[tail++] = cur + w
                }
            }
            val cw = maxX - minX + 1; val ch = maxY - minY + 1
            if (tail > on.size * 0.12f && (cw > w * 0.45f || ch > h * 0.65f)) {
                for (k in 0 until tail) on[queue[k]] = false
            }
        }
    }

    private fun luma(p: Int): Int = (((p shr 16 and 255) * 299 + (p shr 8 and 255) * 587 + (p and 255) * 114) / 1000)
}
