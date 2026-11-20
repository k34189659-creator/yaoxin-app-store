package tw.dafeiyu.mangatranslator.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 英文漫畫專用的行→對話塊前處理。只看英文 ML Kit 的原始行框，不引入日文 OCR 分群。
 *
 * 先利用文字高度、水平重疊、行距，把同一氣泡的連續橫排句子合併；
 * 對空行/欄距過大的地方保持分開，避免把鄰近的兩個氣泡黏住。
 * 這是保守的幾何重建，不宣稱能辨識所有藝術字或複雜不規則氣泡。
 */
object EnglishDialogueGrouping {
    data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top
        val centerX: Float get() = (left + right) / 2f
        val centerY: Float get() = (top + bottom) / 2f
    }

    /** 每個輸出是一組原始行索引，組內由上而下排列，組與組由上到下、同列左到右。 */
    fun group(input: List<Box>, separatedByBubbleBorder: (Box, Box) -> Boolean = { _, _ -> false }): List<List<Int>> {
        val valid = input.indices.filter { input[it].width > 1f && input[it].height > 1f }
        if (valid.isEmpty()) return emptyList()
        val ordered = valid.sortedWith(compareBy<Int> { input[it].top }.thenBy { input[it].left })
        val components = mutableListOf<MutableList<Int>>()
        for (idx in ordered) {
            val b = input[idx]
            // 僅能接到一個已有群的最後一行，避免跨越別的對話框連成大塊。
            val candidates = components.mapIndexedNotNull { groupIndex, group ->
                val previous = input[group.last()]
                val gap = b.top - previous.bottom
                val height = min(b.height, previous.height).coerceAtLeast(1f)
                val textHeightRatio = max(b.height, previous.height) / height
                val overlap = (min(b.right, previous.right) - max(b.left, previous.left)).coerceAtLeast(0f)
                val minWidth = min(b.width, previous.width).coerceAtLeast(1f)
                val centerDistance = abs(b.centerX - previous.centerX)
                val closeEnough = gap >= -0.3f * height && gap <= 1.35f * height
                val aligned = overlap / minWidth >= 0.38f && centerDistance < max(b.width, previous.width) * 0.56f
                val sameFontScale = textHeightRatio <= 1.9f
                if (closeEnough && aligned && sameFontScale && !separatedByBubbleBorder(previous, b))
                    groupIndex to (gap / height + centerDistance / max(b.width, previous.width))
                else null
            }
            val best = candidates.minByOrNull { it.second }?.first
            if (best == null) components.add(mutableListOf(idx)) else components[best].add(idx)
        }
        // 可能一顆氣泡中出現兩個相距較遠的組，但寧可保留兩個區，也不要誤擦另一顆氣泡。
        val groupBounds = components.map { group ->
            Box(group.minOf { input[it].left }, group.minOf { input[it].top },
                group.maxOf { input[it].right }, group.maxOf { input[it].bottom })
        }
        val sorted = MangaReadingOrder.order(
            groupBounds.map { MangaReadingOrder.Bounds(it.left, it.top, it.right, it.bottom) },
            SourceLanguage.ENGLISH,
        )
        return sorted.map { components[it].sortedWith(compareBy<Int> { i -> input[i].top }.thenBy { i -> input[i].left }) }
    }
}
