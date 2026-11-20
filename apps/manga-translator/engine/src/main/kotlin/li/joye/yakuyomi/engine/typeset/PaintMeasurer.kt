package li.joye.yakuyomi.engine.typeset

import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface

/** Android Paint 版的文字度量（給 [Typesetter] 用）。 */
class PaintMeasurer(private val paint: Paint, override val size: Float) : TextMeasurer {
    override fun advance(text: String): Float = paint.measureText(text)
    override fun ascent(): Float = -paint.fontMetrics.ascent
    override fun descent(): Float = paint.fontMetrics.descent
    override fun inkWidth(text: String): Float {
        val b = Rect()
        paint.getTextBounds(text, 0, text.length, b)
        return b.width().toFloat()
    }
}

/**
 * 共用一個 Paint 反覆改字級量測（同一時間只會有一個排版在跑）。
 * 快取 8 個字級的 Paint 以免每次重新配置度量。
 */
class PaintMeasurerFactory(private val typeface: Typeface?) : MeasurerFactory {
    private val paints = HashMap<Int, Paint>()

    override fun at(size: Float): TextMeasurer {
        val key = size.toInt()
        val p = paints.getOrPut(key) {
            Paint().apply {
                isAntiAlias = true
                this.typeface = typeface
                textSize = key.toFloat()
            }
        }
        if (p.textSize != size) p.textSize = size
        return PaintMeasurer(p, size)
    }
}
