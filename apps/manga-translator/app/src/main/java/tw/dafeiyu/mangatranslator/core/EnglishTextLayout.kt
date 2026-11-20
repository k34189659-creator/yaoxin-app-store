package tw.dafeiyu.mangatranslator.core

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/** Bounded horizontal layout. No Android dependencies, so failure cases can be tested on the JVM. */
internal object EnglishTextLayout {
    const val MAX_TEXT_LENGTH = 2048
    const val MIN_FONT_SIZE = 8
    private const val MAX_ROWS = 256
    private const val MAX_FONT_SIZE = 72
    private const val EPSILON = .01f

    data class Metrics(val top: Float, val bottom: Float, val leading: Float = 0f)
    data class Fit(
        val fitted: Boolean,
        val fontSize: Int = 0,
        val rows: List<String> = emptyList(),
        val glyphTop: Float = 0f,
        val glyphBottom: Float = 0f,
        val lineAdvance: Float = 0f,
        val height: Float = 0f,
        val reason: String? = null,
    )

    /**
     * Font scale changes the candidate sizes before measuring. A failed result has no rows to draw.
     * Both the number of characters and rows are bounded before any expensive text measurement.
     */
    fun fit(
        text: String,
        width: Float,
        height: Float,
        fontCap: Int,
        fontScale: Float = 1f,
        measure: (String, Int) -> Float,
        metrics: (Int) -> Metrics,
    ): Fit {
        if (text.length > MAX_TEXT_LENGTH) return Fit(false, reason = "譯文長度異常，未嵌字")
        if (text.isBlank()) return Fit(false, reason = "沒有有效譯文，未嵌字")
        if (!width.isFinite() || !height.isFinite() || width <= 0f || height <= 0f) {
            return Fit(false, reason = "沒有安全的排版範圍，未嵌字")
        }
        val scale = if (fontScale.isFinite()) fontScale.coerceIn(.55f, 1.4f) else 1f
        val cap = (fontCap.coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE) * scale)
            .roundToInt().coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE)
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
            .replace(Regex("[\\t\\x0B\\f ]+"), " ").trim()
        for (size in cap downTo MIN_FONT_SIZE) {
            val font = metrics(size)
            val glyphHeight = font.bottom - font.top
            if (!font.top.isFinite() || !font.bottom.isFinite() || !font.leading.isFinite() ||
                glyphHeight <= 0f || glyphHeight > height + EPSILON) continue
            val advance = max(glyphHeight, glyphHeight + font.leading) * 1.08f
            if (!advance.isFinite() || advance <= 0f) continue
            val rowLimit = (floor(((height - glyphHeight) / advance).toDouble()).toInt() + 1)
                .coerceIn(1, MAX_ROWS)
            val rows = wrap(normalized, width, rowLimit) { value -> measure(value, size) } ?: continue
            val actualHeight = glyphHeight + (rows.size - 1) * advance
            if (actualHeight <= height + EPSILON) {
                return Fit(true, size, rows, font.top, font.bottom, advance, actualHeight)
            }
        }
        return Fit(false, reason = "最小可讀字級仍放不進安全範圍，未嵌字")
    }

    private val cannotStart = setOf('，', '。', '、', '；', '：', '！', '？', '…', '）', '］', '｝',
        '」', '』', '】', '》', '〉', '〕', '〗', '〙', '〛', '”', '’', ',', '.', ';', ':', '!', '?', ')', ']', '}', '%', '％')
    private val cannotEnd = setOf('（', '［', '｛', '「', '『', '【', '《', '〈', '〔', '〖', '〘', '〚', '“', '‘', '(', '[', '{')

    /** Greedy wrapping with legal CJK punctuation breaks; long Latin words may break by code point. */
    private fun wrap(text: String, width: Float, maxRows: Int, measure: (String) -> Float): List<String>? {
        val rows = ArrayList<String>()
        for (paragraph in text.split('\n')) {
            if (paragraph.isBlank()) continue
            val points = ArrayList<String>()
            var at = 0
            while (at < paragraph.length) {
                val next = at + Character.charCount(paragraph.codePointAt(at))
                points += paragraph.substring(at, next)
                at = next
            }
            var start = 0
            while (start < points.size) {
                while (start < points.size && points[start].isBlank()) start++
                if (start >= points.size) break
                if (rows.size >= maxRows) return null
                val builder = StringBuilder()
                var end = start
                while (end < points.size) {
                    builder.append(points[end])
                    val measured = measure(builder.toString())
                    if (!measured.isFinite() || measured < 0f) return null
                    if (measured > width + EPSILON) break
                    end++
                }
                if (end == start) return null
                var split = end
                if (end < points.size) {
                    while (split > start && !legalBreak(points, split, keepLatinWord = true)) split--
                    if (split == start) {
                        split = end
                        while (split > start && !legalBreak(points, split, keepLatinWord = false)) split--
                    }
                    if (split == start) return null
                }
                val row = points.subList(start, split).joinToString("").trim()
                if (row.isNotEmpty()) rows += row
                start = split
            }
        }
        return rows.takeIf { it.isNotEmpty() }
    }

    private fun legalBreak(points: List<String>, position: Int, keepLatinWord: Boolean): Boolean {
        if (position <= 0 || position >= points.size) return true
        val previous = points[position - 1].last()
        val next = points[position].first()
        if (previous in cannotEnd || next in cannotStart) return false
        if (keepLatinWord && isLatinWordPart(previous) && isLatinWordPart(next)) return false
        return true
    }

    private fun isLatinWordPart(c: Char): Boolean = c in 'A'..'Z' || c in 'a'..'z' ||
        c in '0'..'9' || c == '\'' || c == '’'
}
