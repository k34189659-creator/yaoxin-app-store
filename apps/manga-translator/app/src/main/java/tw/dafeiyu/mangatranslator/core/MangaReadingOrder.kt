package tw.dafeiyu.mangatranslator.core

import li.joye.yakuyomi.engine.TextRegion

/** 英文橫排依上→下、同行左→右；日文漫畫通常由右欄往左、同欄上→下。 */
object MangaReadingOrder {
    data class Bounds(val x0: Float, val y0: Float, val x1: Float, val y1: Float) {
        val cx: Float get() = (x0 + x1) / 2f
        val cy: Float get() = (y0 + y1) / 2f
        val height: Float get() = y1 - y0
        val width: Float get() = x1 - x0
    }

    fun order(bounds: List<Bounds>, source: SourceLanguage): List<Int> {
        if (bounds.size <= 1) return bounds.indices.toList()
        return if (source == SourceLanguage.ENGLISH) {
            val sorted = bounds.indices.sortedWith(compareBy<Int> { bounds[it].cy }.thenBy { bounds[it].cx })
            val rows = ArrayList<MutableList<Int>>()
            for (idx in sorted) {
                val rect = bounds[idx]
                val current = rows.lastOrNull()
                val medianY = current?.map { bounds[it].cy }?.average()?.toFloat()
                val meanH = current?.map { bounds[it].height }?.average()?.toFloat() ?: rect.height
                if (current != null && medianY != null && kotlin.math.abs(rect.cy - medianY) <=
                    kotlin.math.min(meanH, rect.height).coerceAtLeast(1f) * 0.55f) {
                    current.add(idx)
                } else {
                    rows.add(mutableListOf(idx))
                }
            }
            rows.flatMap { row -> row.sortedWith(compareBy<Int> { bounds[it].cx }.thenBy { bounds[it].cy }) }
        } else {
            // 將相近的文字框歸到同一垂直欄，避免逐個比較產生不穩定排序。
            val rightToLeft = bounds.indices.sortedWith(compareByDescending<Int> { bounds[it].cx }.thenBy { bounds[it].cy })
            val cols = ArrayList<MutableList<Int>>()
            for (idx in rightToLeft) {
                val current = cols.lastOrNull()
                val medianX = current?.map { bounds[it].cx }?.average()?.toFloat()
                val meanW = current?.map { bounds[it].width }?.average()?.toFloat() ?: bounds[idx].width
                if (current != null && medianX != null && kotlin.math.abs(bounds[idx].cx - medianX) <=
                    kotlin.math.min(meanW, bounds[idx].width).coerceAtLeast(1f) * 0.45f) {
                    current.add(idx)
                } else {
                    cols.add(mutableListOf(idx))
                }
            }
            cols.flatMap { col -> col.sortedWith(compareBy<Int> { bounds[it].cy }.thenByDescending { bounds[it].cx }) }
        }
    }

    fun sort(regions: List<TextRegion>, source: SourceLanguage): List<TextRegion> {
        val b = regions.map { Bounds(it.x0,it.y0,it.x1,it.y1) }
        return order(b,source).map(regions::get)
    }
}
