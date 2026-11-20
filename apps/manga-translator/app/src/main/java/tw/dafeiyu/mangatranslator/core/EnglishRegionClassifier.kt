package tw.dafeiyu.mangatranslator.core

import android.graphics.Bitmap
import li.joye.yakuyomi.engine.TextRegion
import kotlin.math.abs
import kotlin.math.max

/** 只從原始漫畫採樣，不把上一輪已經擦字／嵌字的畫面重新辨識。 */
internal object EnglishRegionClassifier {
    fun classify(page: Bitmap, mask: Bitmap, regions: List<TextRegion>): Map<Int, EnglishRegionRoute> {
        return regions.mapIndexed { i, region -> i to classifyOne(page, mask, region) }.toMap()
    }

    private fun classifyOne(page: Bitmap, mask: Bitmap, r: TextRegion): EnglishRegionRoute {
        val width = page.width
        val height = page.height
        val x0 = r.x0.toInt().coerceIn(0, width - 1)
        val y0 = r.y0.toInt().coerceIn(0, height - 1)
        val x1 = r.x1.toInt().coerceIn(x0 + 1, width)
        val y1 = r.y1.toInt().coerceIn(y0 + 1, height)
        val pad = max(5, (minOf(x1 - x0, y1 - y0) * .13f).toInt()).coerceAtMost(16)
        val surrounding = ArrayList<Int>(144)
        val stride = max(1, max(x1 - x0, y1 - y0) / 30)
        // 上下左右的環形取樣，不把 OCR bbox 內的英文筆畫當成背景。
        for (x in x0 until x1 step stride) {
            if (y0 - pad >= 0) surrounding.add(page.getPixel(x, y0 - pad))
            if (y1 + pad < height) surrounding.add(page.getPixel(x, y1 + pad))
        }
        for (y in y0 until y1 step stride) {
            if (x0 - pad >= 0) surrounding.add(page.getPixel(x0 - pad, y))
            if (x1 + pad < width) surrounding.add(page.getPixel(x1 + pad, y))
        }
        val color = if (surrounding.isNotEmpty()) medianRgb(surrounding) else 0xffeeeeee.toInt()
        val spread = if (surrounding.isEmpty()) 99f else surrounding.map { distance(it, color) }.average().toFloat()
        val bgSat = saturation(color).toFloat()
        var ink = 0
        var colored = 0
        val step = max(1, max(x1 - x0, y1 - y0) / 85)
        for (y in y0 until y1 step step) for (x in x0 until x1 step step) {
            if ((mask.getPixel(x, y) and 255) < 128) continue
            ink++
            val px = page.getPixel(x, y)
            if (saturation(px) > 54 &&
                saturation(px) - saturation(color) > 23 && distance(px, color) > 48) colored++
        }
        // 取樣線被單一邊線穿過並不代表有完整氣泡；暫以背景一致性為主。
        val edgeVariation = if (surrounding.size < 12) 0f else {
            surrounding.count { distance(it, color) > 65 }.toFloat() / surrounding.size
        }
        val border = if (edgeVariation in .08f..0.35f) .66f else .1f
        return EnglishRegionRouting.classify(EnglishRegionFeatures(
            spread, bgSat,
            if (ink == 0) 0f else colored.toFloat() / ink,
            border, surrounding.size, ink,
        ))
    }

    private fun medianRgb(colors: List<Int>): Int {
        val r = colors.map { (it ushr 16) and 255 }.sorted()
        val g = colors.map { (it ushr 8) and 255 }.sorted()
        val b = colors.map { it and 255 }.sorted()
        val m = colors.size / 2
        return (0xff shl 24) or (r[m] shl 16) or (g[m] shl 8) or b[m]
    }
    private fun saturation(p: Int): Int {
        val r = (p ushr 16) and 255
        val g = (p ushr 8) and 255
        val b = p and 255
        return maxOf(r,g,b) - minOf(r,g,b)
    }
    private fun distance(a: Int, b: Int): Int {
        val dr = abs(((a ushr 16) and 255) - ((b ushr 16) and 255))
        val dg = abs(((a ushr 8) and 255) - ((b ushr 8) and 255))
        val db = abs((a and 255) - (b and 255))
        return (dr + dg + db) / 3
    }
}
