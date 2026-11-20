import li.joye.yakuyomi.engine.typeset.Cjk
import li.joye.yakuyomi.engine.typeset.LayoutBox
import li.joye.yakuyomi.engine.typeset.MeasurerFactory
import li.joye.yakuyomi.engine.typeset.RectRegion
import li.joye.yakuyomi.engine.typeset.TextMeasurer
import li.joye.yakuyomi.engine.typeset.TextNormalizer
import li.joye.yakuyomi.engine.typeset.TypesetOptions
import li.joye.yakuyomi.engine.typeset.Typesetter
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 桌面驗證：用**與 App 完全相同**的排版核心（engine/typeset/Typesetter.kt）在真實漫畫頁上排版，
 * 並與 0.2.0 的排版策略並排比較。
 *
 * 這不是 Android 實機輸出（字型度量來自 AWT + Noto Sans CJK），
 * 但排版決策（字級、換行、欄數、是否超框）與 App 是同一份程式碼，因此可驗證 0.3.0 的改善方向。
 */

private data class Rect(val x0: Float, val y0: Float, val x1: Float, val y1: Float) {
    val w: Float get() = x1 - x0
    val h: Float get() = y1 - y0
}

private data class Case(
    val id: String,
    val page: String,
    val source: String,
    val box: Rect,
    val translation: String,
    val vertical: Boolean,
    val note: String,
)

private val CASES = listOf(
    Case(
        "A_bubble_short", "ch34_006.jpg",
        "その通りじゃ",
        Rect(164f, 203f, 286f, 461f),
        "正是如此",
        true,
        "短台詞：0.2.0 會把框放大 1.3×1.5 再縮 0.85，字反而偏小且可能壓到氣泡線",
    ),
    Case(
        "B_bubble_long", "ch34_006.jpg",
        "他の国に対して武器や防具の宣伝になる？",
        Rect(1018f, 124f, 1214f, 414f),
        "向外國宣傳武器與防具？",
        true,
        "四欄長句：需要真實字度量與欄高檢查",
    ),
    Case(
        "C_bubble_multi", "ch34_006.jpg",
        "それほどの自信がある武具……とても面白いですね 興味が湧きました",
        Rect(900f, 915f, 1200f, 1215f),
        "那麼有自信的武具……真是令人感興趣呢",
        true,
        "多欄＋省略號：直排標點與禁則",
    ),
    Case(
        "D_narration", "ch34_006.jpg",
        "さっき言った通りグランサンズには曖昧にアオイ君の噂が届いておるようなんじゃ！",
        Rect(100f, 1400f, 420f, 1720f),
        "正如剛才所說，格蘭桑茲那邊似乎也收到了關於青井同學的模糊傳聞！",
        true,
        "方形旁白框＋長句（第 72 頁類型）：字級與段落",
    ),
    Case(
        "E_bubble_right", "ch34_006.jpg",
        "待つのじゃアオイ君！",
        Rect(993f, 1398f, 1202f, 1664f),
        "等一下啊，青井同學！",
        true,
        "右下氣泡：兩欄短句",
    ),
    Case(
        "F_english", "demo06.jpg",
        "This is the strongest hero in the world",
        Rect(120f, 120f, 520f, 330f),
        "這就是世界上最強的勇者",
        false,
        "英文橫排頁（橫排路徑）",
    ),
)

private class AwtMeasurerFactory(private val base: Font, private val g: Graphics2D) : MeasurerFactory {
    fun fontAt(size: Float): Font = base.deriveFont(size)

    override fun at(size: Float): TextMeasurer {
        val f = base.deriveFont(size)
        g.font = f
        val fm = g.fontMetrics
        return object : TextMeasurer {
            override val size: Float = size
            override fun advance(text: String): Float = fm.stringWidth(text).toFloat()
            override fun ascent(): Float = fm.ascent.toFloat()
            override fun descent(): Float = fm.descent.toFloat()
            override fun inkWidth(text: String): Float = fm.stringWidth(text).toFloat()
        }
    }
}

private fun antialias(g: Graphics2D) {
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
    g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
}

/** 0.2.0 的排版策略（原樣移植；只是把 android.graphics 換成 AWT）。 */
private fun renderOld(page: BufferedImage, c: Case, factory: AwtMeasurerFactory): String {
    val g = page.createGraphics()
    antialias(g)
    g.color = Color.BLACK
    val text = TextNormalizer.normalize(c.translation)
    val box = c.box
    val cx = (box.x0 + box.x1) / 2f
    val cy = (box.y0 + box.y1) / 2f
    val bw = box.w * 1.3f
    val colRoom = box.h * 1.5f
    val minSize = 9
    val cells = text.map { it.toString() }
    var chosen = -1
    var s = min(60, colRoom.toInt().coerceAtLeast(minSize))
    while (s >= minSize) {
        val lh = s * 1.06f
        val cpc = max(1, (colRoom / lh).toInt())
        val cols = ceil(cells.size / cpc.toFloat()).toInt()
        if (cols * (s * 1.08f) <= bw) { chosen = s; break }
        s--
    }
    val overflow = chosen < 0
    if (chosen < 0) chosen = minSize
    var size = max(minSize, (chosen * 0.85f).roundToInt())
    if (size > 60) size = 60
    g.font = factory.fontAt(size.toFloat())
    val m = factory.at(size.toFloat())
    val lh = size * 1.06f
    val cw = size * 1.08f
    val cpc = max(1, (colRoom / lh).toInt())
    val cols = cells.chunked(cpc)
    val rightCx = cx + cols.size * cw / 2f - cw / 2f
    val blockH = (cols.maxOfOrNull { it.size } ?: 0) * lh
    val startCy = cy - blockH / 2f
    cols.forEachIndexed { col, list ->
        val x = rightCx - col * cw
        list.forEachIndexed { i, ch ->
            val y = startCy + i * lh + lh / 2f
            val baseline = y + (m.ascent() - m.descent()) / 2f
            val tw = m.advance(ch)
            val punct = Cjk.isVerticalPunct(ch.first())
            val px = if (punct) x - tw / 2f + size * 0.20f else x - tw / 2f
            val py = if (punct) baseline - size * 0.16f else baseline
            if (Cjk.isRotate(ch.first())) {
                val old = g.transform
                g.rotate(Math.PI / 2, x.toDouble(), y.toDouble())
                g.drawString(ch, x - tw / 2f, y + (m.ascent() - m.descent()) / 2f)
                g.transform = old
            } else {
                g.drawString(ch, px, py)
            }
        }
    }
    g.dispose()
    val inside = textInside(text.length, size, cols.size, cw, lh, box)
    return "0.2.0：字級 ${size}px、${cols.size} 欄、${if (overflow) "已溢出" else "未溢出"}、" +
        "文字塊 ${(cols.size * cw).roundToInt()}×${(blockH).roundToInt()}px（框 ${box.w.roundToInt()}×${box.h.roundToInt()}）、" +
        "超出框線 ${if (inside) "否" else "是"}"
}

private fun textInside(len: Int, size: Int, cols: Int, cw: Float, lh: Float, box: Rect): Boolean =
    cols * cw <= box.w + 1f && len * lh <= box.h * 1.5f + 1f

private fun renderNew(page: BufferedImage, c: Case, factory: AwtMeasurerFactory): String {
    val g = page.createGraphics()
    antialias(g)
    g.color = Color.BLACK
    val text = TextNormalizer.normalize(c.translation)
    val region = RectRegion(LayoutBox(c.box.x0 + 6f, c.box.y0 + 6f, c.box.x1 - 6f, c.box.y1 - 6f))
    val opts = TypesetOptions(
        maxFontSize = 60, minFontSize = 9, strokeWidth = 1f,
        columnGap = 1.09f, cellHeight = 1.03f, horizontalPadding = 1.5f,
        measurer = factory,
    )
    if (c.vertical) {
        val layout = Typesetter.layoutVertical(text, region, opts)
        g.font = factory.fontAt(layout.fontSize)
        layout.columns.forEach { col ->
            col.cells.forEach { cell ->
                val m = factory.at(layout.fontSize)
                var px = cell.cx
                var py = cell.cy
                if (cell.punctOffset) { px += layout.fontSize * 0.20f; py -= layout.fontSize * 0.16f }
                val baseline = py + (m.ascent() - m.descent()) / 2f
                if (cell.tcy) {
                    val tw = m.advance(cell.text)
                    g.drawString(cell.text, px - tw / 2f, baseline)
                    return@forEach
                }
                val tw = m.advance(cell.text)
                if (cell.rotate) {
                    val old = g.transform
                    g.rotate(Math.PI / 2, px.toDouble(), py.toDouble())
                    g.drawString(cell.text, px - tw / 2f, baseline)
                    g.transform = old
                } else {
                    g.drawString(cell.text, px - tw / 2f, baseline)
                }
            }
        }
        g.dispose()
        val usedW = layout.usedWidth.roundToInt()
        val usedH = layout.usedHeight.roundToInt()
        return "0.3.0：字級 ${layout.fontSize.roundToInt()}px、${layout.columns.size} 欄、" +
            "${if (layout.overflow) "已溢出" else "未溢出"}、文字塊 ${usedW}×${usedH}px（可用 ${region.maxWidth.roundToInt()}×${region.maxHeight.roundToInt()}）"
    } else {
        val layout = Typesetter.layoutHorizontal(text, region, opts)
        g.font = factory.fontAt(layout.fontSize)
        layout.lines.forEach { line ->
            g.drawString(line.text, line.x, line.baseline)
        }
        g.dispose()
        return "0.3.0：字級 ${layout.fontSize.roundToInt()}px、${layout.lines.size} 行、" +
            "${if (layout.overflow) "已溢出" else "未溢出"}、文字塊 ${layout.usedWidth.roundToInt()}×${layout.usedHeight.roundToInt()}px"
    }
}

/** 桌面樣張沒有跑擦字模型；用白底填掉原文，模擬 App 在純白氣泡上的擦字結果。 */
private fun fillWhite(img: BufferedImage, box: Rect) {
    val g = img.createGraphics()
    g.color = Color.WHITE
    g.fillRect(box.x0.toInt(), box.y0.toInt(), box.w.toInt(), box.h.toInt())
    g.dispose()
}

private fun crop(src: BufferedImage, box: Rect, pad: Int): BufferedImage {
    val x0 = (box.x0 - pad).toInt().coerceIn(0, src.width - 1)
    val y0 = (box.y0 - pad).toInt().coerceIn(0, src.height - 1)
    val x1 = (box.x1 + pad).toInt().coerceIn(x0 + 1, src.width)
    val y1 = (box.y1 + pad).toInt().coerceIn(y0 + 1, src.height)
    return src.getSubimage(x0, y0, x1 - x0, y1 - y0)
}

private var labelFont: Font = Font(Font.SANS_SERIF, Font.PLAIN, 18)

private fun label(g: Graphics2D, text: String, x: Int, y: Int, size: Int = 18, color: Color = Color.DARK_GRAY) {
    g.font = labelFont.deriveFont(size.toFloat())
    g.color = color
    g.drawString(text, x, y)
}

fun main(args: Array<String>) {
    val pagesDir = File(args.getOrElse(0) { "testpages" })
    val outDir = File(args.getOrElse(1) { "deskcheck-out" }).apply { mkdirs() }
    // TTC 第 0 個 face 是 JP；繁中請用抽出的 TC face（沒有就用 JP face，字形略異但佈局相同）。
    val tcFile = File("/tmp/NotoSansCJK-TC-Regular.otf")
    val jpFile = File("/system/fonts/NotoSansCJK-Regular.ttc")
    val fontFile = if (tcFile.isFile) tcFile else jpFile
    val base = Font.createFont(Font.TRUETYPE_FONT, fontFile)
    println("font: ${base.fontName} (${fontFile.name})")
    labelFont = base

    val report = StringBuilder()
    for (case in CASES) {
        val srcFile = File(pagesDir, case.page)
        if (!srcFile.isFile) {
            report.appendLine("${case.id}: 缺少頁面 ${case.page}")
            continue
        }
        val original = ImageIO.read(srcFile)
        val oldImg = BufferedImage(original.width, original.height, BufferedImage.TYPE_INT_RGB)
        val g0 = oldImg.createGraphics(); g0.drawImage(original, 0, 0, null); g0.dispose()
        val newImg = BufferedImage(original.width, original.height, BufferedImage.TYPE_INT_RGB)
        val g1 = newImg.createGraphics(); g1.drawImage(original, 0, 0, null); g1.dispose()

        val scratch = BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB).createGraphics()
        val factory = AwtMeasurerFactory(base, scratch)
        // 桌面樣張沒有跑擦字模型；用白底填掉原文，模擬 App 在純白氣泡上的擦字結果。
        fillWhite(oldImg, case.box)
        fillWhite(newImg, case.box)
        val oldInfo = renderOld(oldImg, case, factory)
        val newInfo = renderNew(newImg, case, factory)
        scratch.dispose()

        ImageIO.write(oldImg, "png", File(outDir, "${case.id}_old.png"))
        ImageIO.write(newImg, "png", File(outDir, "${case.id}_new.png"))

        val pad = 40
        val a = crop(original, case.box, pad)
        val b = crop(oldImg, case.box, pad)
        val c = crop(newImg, case.box, pad)
        val scale = min(1.0, 900.0 / (a.width * 3))
        val cw = (a.width * scale).toInt()
        val ch = (a.height * scale).toInt()
        val sheet = BufferedImage(cw * 3 + 40, ch + 90, BufferedImage.TYPE_INT_RGB)
        val g = sheet.createGraphics()
        antialias(g)
        g.color = Color(245, 245, 245)
        g.fillRect(0, 0, sheet.width, sheet.height)
        g.drawImage(a, 10, 50, cw, ch, null)
        g.drawImage(b, 20 + cw, 50, cw, ch, null)
        g.drawImage(c, 30 + cw * 2, 50, cw, ch, null)
        label(g, "原圖", 10, 32, 20)
        label(g, "0.2.0 排版", 20 + cw, 32, 20, Color(160, 40, 40))
        label(g, "0.3.0 排版", 30 + cw * 2, 32, 20, Color(30, 110, 60))
        g.color = Color(120, 120, 120)
        g.stroke = BasicStroke(1f)
        g.drawRect(9, 49, cw + 1, ch + 1)
        g.drawRect(19 + cw, 49, cw + 1, ch + 1)
        g.drawRect(29 + cw * 2, 49, cw + 1, ch + 1)
        label(g, "${case.id}：${case.note}", 10, sheet.height - 12, 16)
        g.dispose()
        ImageIO.write(sheet, "png", File(outDir, "${case.id}_compare.png"))

        report.appendLine("## ${case.id}")
        report.appendLine("- 原文：${case.source}")
        report.appendLine("- 譯文：${case.translation}")
        report.appendLine("- 框：${case.box.w.roundToInt()}×${case.box.h.roundToInt()}px")
        report.appendLine("- $oldInfo")
        report.appendLine("- $newInfo")
        report.appendLine()
        println("${case.id}: rendered")
    }
    File(outDir, "report.md").writeText(
        "# 桌面排版驗證\n\n" +
            "同一份排版核心（engine/typeset/Typesetter.kt）以 AWT + Noto Sans CJK 度量在真實頁面上排版。\n" +
            "0.2.0 欄位是原演算法移植（expandW 1.3 / expandH 1.5 / fontScale 0.85）。\n\n" +
            report,
    )
    println("output: ${outDir.absolutePath}")
}
