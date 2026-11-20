package tw.dafeiyu.mangatranslator.core

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import java.io.File

/**
 * 診斷預覽圖：所有圖都以「原圖座標」繪製/輸出，前端只做等比例縮放顯示，
 * 因此框、編號與 OCR/譯文位置不會因預覽縮放而錯位。
 */
object StageImageFactory {

    fun detection(original: Bitmap, regions: List<li.joye.yakuyomi.engine.TextRegion>): Bitmap {
        val out = original.copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(out)
        val line = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 2.5f; isAntiAlias = true }
        val label = Paint().apply { color = Color.WHITE; textSize = textSize(out); isAntiAlias = true }
        val bg = Paint().apply { color = 0x99000000.toInt() }
        regions.forEachIndexed { i, r ->
            line.color = Color.rgb(30, 120, 255)
            c.drawRect(r.x0, r.y0, r.x1, r.y1, line)
            r.lines.forEach { ln ->
                if (ln.quad.size < 4) return@forEach
                line.color = Color.rgb(255, 60, 60)
                val p = android.graphics.Path().apply {
                    moveTo(ln.quad[0].x, ln.quad[0].y)
                    for (k in 1 until ln.quad.size) lineTo(ln.quad[k].x, ln.quad[k].y)
                    close()
                }
                c.drawPath(p, line)
            }
            val tx = r.x0.coerceAtLeast(2f)
            val ty = (r.y0 - 6f).coerceAtLeast(label.textSize)
            val text = "#${i + 1}"
            val tw = label.measureText(text)
            c.drawRect(tx, ty - label.textSize, tx + tw + 6f, ty + 4f, bg)
            c.drawText(text, tx + 3f, ty, label)
        }
        return out
    }

    fun ocr(original: Bitmap, regions: List<li.joye.yakuyomi.engine.TextRegion>): Bitmap {
        val out = original.copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(out)
        val box = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 2f; color = Color.rgb(255, 140, 0); isAntiAlias = true }
        val text = Paint().apply { color = Color.WHITE; textSize = textSize(out); isAntiAlias = true }
        val bg = Paint().apply { color = 0xCC101010.toInt() }
        regions.forEachIndexed { i, r ->
            c.drawRect(r.x0, r.y0, r.x1, r.y1, box)
            val src = r.sourceText.ifBlank { "（空）" }
            drawLabel(c, "[$i] $src", r.x0, r.y0, out.width, text, bg)
        }
        return out
    }

    fun translation(original: Bitmap, regions: List<li.joye.yakuyomi.engine.TextRegion>): Bitmap {
        val out = original.copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(out)
        val box = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 2f; color = Color.rgb(46, 160, 67); isAntiAlias = true }
        val text = Paint().apply { color = Color.WHITE; textSize = textSize(out); isAntiAlias = true }
        val bg = Paint().apply { color = 0xCC101010.toInt() }
        regions.forEachIndexed { i, r ->
            c.drawRect(r.x0, r.y0, r.x1, r.y1, box)
            val tr = r.translatedText.ifBlank { "（未譯，保留原文）" }
            drawLabel(c, "[$i] $tr", r.x0, r.y0, out.width, text, bg)
        }
        return out
    }

    fun mask(original: Bitmap, mask: Bitmap): Bitmap {
        val out = original.copy(Bitmap.Config.ARGB_8888, true)
        val w = minOf(out.width, mask.width)
        val h = minOf(out.height, mask.height)
        val o = IntArray(w * h); out.getPixels(o, 0, w, 0, 0, w, h)
        val m = IntArray(w * h); mask.getPixels(m, 0, w, 0, 0, w, h)
        for (i in o.indices) {
            if ((m[i] and 0xFF) > 127) {
                val r = (o[i] shr 16) and 0xFF; val g = (o[i] shr 8) and 0xFF; val b = o[i] and 0xFF
                o[i] = (0xFF shl 24) or (((r + 255) / 2) shl 16) or ((g / 2) shl 8) or (b / 2)
            }
        }
        out.setPixels(o, 0, w, 0, 0, w, h)
        return out
    }

    fun save(bitmap: Bitmap, file: File) {
        file.parentFile?.mkdirs()
        ImageIo.save(bitmap, file, "jpg")
    }

    private fun textSize(bmp: Bitmap): Float = (bmp.width / 80f).coerceIn(14f, 34f)

    private fun drawLabel(c: Canvas, text: String, x: Float, y: Float, width: Int, paint: Paint, bg: Paint) {
        val maxW = width - 8f
        val lines = ArrayList<String>()
        var cur = StringBuilder()
        for (ch in text) {
            if (cur.isNotEmpty() && paint.measureText(cur.toString() + ch) > maxW) {
                lines.add(cur.toString()); cur = StringBuilder()
            }
            cur.append(ch)
            if (lines.size >= 3) break
        }
        if (cur.isNotEmpty() && lines.size < 3) lines.add(cur.toString())
        val lh = paint.textSize * 1.15f
        val top = (y + 2f).coerceAtLeast(lh)
        val h = lh * lines.size + 4f
        c.drawRect(2f, top - lh, (maxW + 4f).coerceAtLeast(20f), top - lh + h, bg)
        lines.forEachIndexed { i, ln -> c.drawText(ln, 4f, top + i * lh, paint) }
    }
}
