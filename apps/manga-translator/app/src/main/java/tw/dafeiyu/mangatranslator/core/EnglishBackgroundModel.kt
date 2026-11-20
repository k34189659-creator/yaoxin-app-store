package tw.dafeiyu.mangatranslator.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * 英文模式專用的「確信才修補」背景重建：
 *  - 從文字遮罩外的原畫面估算一致的純色／平滑漸層；
 *  - 先以穩健的 RGB 中位數排除文字、氣泡邊線等離群點；
 *  - 再對背景色做二維線性擬合，避免逐像素取樣產生色斑；
 *  - 若區域含多種顏色、插畫或無法可靠估計，完全不改動既有 AOT 修補結果。
 *
 * 本類只使用 IntArray，獨立於 Android Bitmap，可在 JVM 上單獨測試。
 */
internal object EnglishBackgroundModel {
    private data class Sample(val x: Double, val y: Double, val r: Double, val g: Double, val b: Double)

    /** 修改 out 的文字遮罩附近像素。返回實際重建像素數；0 代表不夠確定，保留原圖處理。 */
    fun refine(original: IntArray, out: IntArray, mask: IntArray, width: Int, height: Int): Int {
        if (width < 5 || height < 5 || original.size != width * height ||
            out.size != original.size || mask.size != original.size) return 0
        val affected = BooleanArray(original.size)
        var affectedCount = 0
        // 必須對齊英文擦字的 3px 膨脹半徑；不要只修補原始筆畫，留下外圍色暈。
        for (y in 0 until height) for (x in 0 until width) {
            val i = y * width + x
            if ((mask[i] and 255) <= 127) continue
            for (dy in -3..3) for (dx in -3..3) {
                val xx = x + dx; val yy = y + dy
                if (xx !in 0 until width || yy !in 0 until height) continue
                val j = yy * width + xx
                if (!affected[j]) { affected[j] = true; affectedCount++ }
            }
        }
        if (affectedCount == 0 || affectedCount > original.size * 0.48) return 0

        val step = max(1, sqrt((original.size / 1400.0)).toInt())
        val samples = ArrayList<Sample>(1500)
        val reds = ArrayList<Int>(1500)
        val greens = ArrayList<Int>(1500)
        val blues = ArrayList<Int>(1500)
        for (y in 1 until height - 1 step step) for (x in 1 until width - 1 step step) {
            val i = y * width + x
            if (affected[i]) continue
            val p = original[i]
            val r = (p ushr 16) and 255; val g = (p ushr 8) and 255; val b = p and 255
            samples.add(Sample(x.toDouble(), y.toDouble(), r.toDouble(), g.toDouble(), b.toDouble()))
            reds.add(r); greens.add(g); blues.add(b)
        }
        if (samples.size < 24) return 0
        reds.sort(); greens.sort(); blues.sort()
        val mr = reds[reds.size / 2]; val mg = greens[greens.size / 2]; val mb = blues[blues.size / 2]
        val inliers = samples.filter {
            abs(it.r - mr) + abs(it.g - mg) + abs(it.b - mb) <= 55.0
        }
        if (inliers.size < 24 || inliers.size < samples.size * 0.73) return 0

        val cx = inliers.map { it.x }.average()
        val cy = inliers.map { it.y }.average()
        val mean = doubleArrayOf(inliers.map { it.r }.average(), inliers.map { it.g }.average(), inliers.map { it.b }.average())
        var xx = 0.0; var xy = 0.0; var yy = 0.0
        val rx = DoubleArray(3); val ry = DoubleArray(3)
        for (s in inliers) {
            val dx = s.x - cx; val dy = s.y - cy
            xx += dx * dx; xy += dx * dy; yy += dy * dy
            val ds = doubleArrayOf(s.r - mean[0], s.g - mean[1], s.b - mean[2])
            for (c in 0..2) { rx[c] += dx * ds[c]; ry[c] += dy * ds[c] }
        }
        val det = xx * yy - xy * xy
        val gx = DoubleArray(3); val gy = DoubleArray(3)
        if (det > 1e-5) for (c in 0..2) {
            gx[c] = (rx[c] * yy - ry[c] * xy) / det
            gy[c] = (ry[c] * xx - rx[c] * xy) / det
        }
        // 即使主要顏色一致，若局部是網點或多重陰影，也不要強行畫成平滑色塊。
        var residual = 0.0
        for (s in inliers) {
            val dx = s.x - cx; val dy = s.y - cy
            residual += abs(s.r - mean[0] - gx[0] * dx - gy[0] * dy)
            residual += abs(s.g - mean[1] - gx[1] * dx - gy[1] * dy)
            residual += abs(s.b - mean[2] - gx[2] * dx - gy[2] * dy)
        }
        if (residual / inliers.size > 19.0) return 0

        for (i in out.indices) {
            if (!affected[i]) continue
            val dx = i % width - cx; val dy = i / width - cy
            fun value(c: Int): Int = (mean[c] + gx[c] * dx + gy[c] * dy).toInt().coerceIn(0, 255)
            out[i] = (0xff shl 24) or (value(0) shl 16) or (value(1) shl 8) or value(2)
        }
        return affectedCount
    }
}
