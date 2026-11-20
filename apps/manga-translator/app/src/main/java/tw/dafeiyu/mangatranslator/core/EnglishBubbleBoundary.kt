package tw.dafeiyu.mangatranslator.core

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min

/** 英文 OCR 行間有橫向的深色分隔線時，避免把相鄰漫畫氣泡併成同一段話。 */
internal object EnglishBubbleBoundary {
    fun hasDivider(page: Bitmap, upper: EnglishDialogueGrouping.Box, lower: EnglishDialogueGrouping.Box): Boolean {
        val top = upper.bottom.toInt() + 1
        val bottom = lower.top.toInt() - 1
        if (bottom - top !in 2..60) return false
        val x0 = max(upper.left, lower.left).toInt().coerceIn(0, page.width - 1)
        val x1 = min(upper.right, lower.right).toInt().coerceIn(x0 + 1, page.width)
        if (x1 - x0 < 18) return false
        // 要有連續穿過文字列寬度的線段，單一文字筆畫不應視為氣泡邊界。
        val samples = 18
        for (y in top.coerceAtLeast(0)..bottom.coerceAtMost(page.height - 1)) {
            var dark = 0
            for (s in 0 until samples) {
                val x = x0 + (x1 - x0 - 1) * s / (samples - 1)
                val pixel = page.getPixel(x, y)
                val lum = ((pixel shr 16 and 255) * 299 + (pixel shr 8 and 255) * 587 + (pixel and 255) * 114) / 1000
                if (lum < 72) dark++
            }
            if (dark >= 15) return true
        }
        return false
    }
}
