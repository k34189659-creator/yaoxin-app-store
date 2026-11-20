package li.joye.yakuyomi.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import kotlin.math.roundToInt
import kotlinx.coroutines.coroutineScope

/**
 * 去字（text removal）。真機 A/B 定案＝兩門別，皆純 NCNN（`.param`/`.bin`）：
 *  - **boxfill（快速去字）**：逐區把去字遮罩像素平塗成背景色。瞬間、不跑模型；乾淨白泡完美、壓畫面是平色塊。
 *  - **aot（AI 去字·預設）**：AOT-GAN（m-i-t 漫畫權重）整頁一次縮到 [InpainterConfig.tileSize]（768）重建背景、全區重建。
 *    全卷積 → 任意尺寸；768＝畫質/記憶體/藏在翻譯下（§8 去字‖翻譯重疊）的甜蜜點。
 *
 * AOT I/O 契約（對齊 parity/inpaint_parity.py）：img∈[-1,1] 且洞歸零（m-i-t `img*(1-mask)`）、mask∈{0,1}(1=擦)、輸出∈[-1,1]。
 * LaMa（整頁縮 512 必糊）與 AOT 逐格（原生解析度·CPU 太貴）皆已退役移除；GPU/Vulkan 實測算不對 AOT-GAN（見 memory ncnn-vulkan-fp16）。
 */
/** 瑤芯漫畫翻譯 0.2.0 原始擦字實作，供舊版優先模式使用。 */
class Legacy020Inpainter(
    modelPath: String,
    private val cfg: InpainterConfig = InpainterConfig(),
) : AutoCloseable {

    private var ncnnHandle: Long = 0L
    /** 實際生效的後端；無 adb 時由呼叫端寫進 log/圖確認。 */
    val ep: String = "NCNN-CPU"

    init {
        check(modelPath.endsWith(".param")) { "去字需 NCNN `.param` 模型（AOT-GAN）：$modelPath" }
        check(NcnnBackend.available) { "NCNN 原生庫未載入，無法去字" }
        val bin = modelPath.removeSuffix(".param") + ".bin"
        ncnnHandle = NcnnBackend.createNet(modelPath, bin)
        check(ncnnHandle != 0L) { "NCNN AOT 模型載入失敗：$modelPath" }
    }

    suspend fun inpaint(page: Bitmap, regions: List<TextRegion>, textMask: Bitmap): Bitmap = coroutineScope {
        val w = page.width
        val h = page.height
        val result = page.copy(Bitmap.Config.ARGB_8888, true)
        // seg 細筆畫遮罩 ∩ 保留區 bbox（外擴 bboxPad）再膨脹：只動筆畫、限制在翻譯過的區（SFX/未譯留原圖，§11）。
        val maskPx = buildSegMask(regions, textMask, w, h)

        if (cfg.method == "boxfill") {
            // 逐區平塗背景色：白泡乾淨無殘留、忙碌區是平色塊（要品質用 aot）。
            val px = IntArray(w * h); result.getPixels(px, 0, w, 0, 0, w, h)
            val tightPx = IntArray(w * h); textMask.getPixels(tightPx, 0, w, 0, 0, w, h)
            for (r in regions) {
                val s = bgStats(px, tightPx, r, w, h)
                r.onArt = false; r.dbgStd = s.std; r.dbgWhite = s.meanLum // dbg 值給 sandbox 去背比較標框
                flatFill(result, maskPx, r, s.color, cfg.bboxPad, w, h)
            }
            return@coroutineScope result
        }

        // aot（預設）：全區都跑 AOT-GAN 整頁重建；標 onArt 讓 Renderer 給黑字粗白邊。
        regions.forEach { it.onArt = true }
        val maskBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        maskBmp.setPixels(maskPx, 0, w, 0, 0, w, h)
        runWholeAot(page, maskBmp, w, h)?.let { compositePixels(result, maskPx, it) }
        maskBmp.recycle()
        result
    }

    /**
     * 0.2.0 智慧擦字：
     *  - 純色/近純色區（白泡、規則旁白框）→ 只用文字筆畫遮罩填背景色，不跑 AOT、不塗整框。
     *  - 平滑/漸層區 → 由遮罩邊界向內擴散補色（避免 AOT 的低解析方形失真）。
     *  - 複雜區（人物/網點/線條）→ 以區域為中心取方形 context，跑 AOT-GAN，再把遮罩像素貼回。
     * 只有真的需要的區域才跑 AOT；失敗時退回擴散填色，不留下日文殘留。
     */
    suspend fun inpaintSmart(
        page: Bitmap,
        regions: List<TextRegion>,
        textMask: Bitmap,
        mode: String = "smart",
    ): SmartInpaintResult = coroutineScope {
        val w = page.width
        val h = page.height
        val result = page.copy(Bitmap.Config.ARGB_8888, true)
        val maskPx = buildSegMask(regions, textMask, w, h)
        val infos = ArrayList<RegionEraseInfo>(regions.size)

        if (mode == "boxfill" || mode == "fill") {
            val pagePx = IntArray(w * h); page.getPixels(pagePx, 0, w, 0, 0, w, h)
            val tightPx = IntArray(w * h); textMask.getPixels(tightPx, 0, w, 0, 0, w, h)
            regions.forEachIndexed { i, r ->
                val s = bgStats(pagePx, tightPx, r, w, h)
                r.onArt = false; r.dbgStd = s.std; r.dbgWhite = s.meanLum
                flatFill(result, maskPx, r, s.color, cfg.bboxPad, w, h)
                infos.add(RegionEraseInfo(i, "fill", s.std, s.meanLum))
            }
            return@coroutineScope SmartInpaintResult(result, infos)
        }

        if (mode == "aot") {
            regions.forEach { it.onArt = true }
            val maskBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            maskBmp.setPixels(maskPx, 0, w, 0, 0, w, h)
            val out = runWholeAot(page, maskBmp, w, h)
            maskBmp.recycle()
            if (out != null) compositePixels(result, maskPx, out)
            regions.forEachIndexed { i, r -> infos.add(RegionEraseInfo(i, "aot", 0f, 0f)) }
            return@coroutineScope SmartInpaintResult(result, infos)
        }

        // smart：逐區分類
        val pagePx = IntArray(w * h); page.getPixels(pagePx, 0, w, 0, 0, w, h)
        val tightPx = IntArray(w * h); textMask.getPixels(tightPx, 0, w, 0, 0, w, h)
        regions.forEachIndexed { i, r ->
            val s = bgStats(pagePx, tightPx, r, w, h)
            r.dbgStd = s.std; r.dbgWhite = s.meanLum
            val strategy = when {
                s.std < 14f -> "fill"
                s.std < 34f -> "diffuse"
                else -> "aot"
            }
            when (strategy) {
                "fill" -> {
                    r.onArt = false
                    flatFill(result, maskPx, r, s.color, cfg.bboxPad, w, h)
                    infos.add(RegionEraseInfo(i, "fill", s.std, s.meanLum))
                }
                "diffuse" -> {
                    r.onArt = false
                    diffuseFill(result, maskPx, r, cfg.bboxPad, s.color, w, h)
                    infos.add(RegionEraseInfo(i, "diffuse", s.std, s.meanLum))
                }
                else -> {
                    r.onArt = true
                    val ok = aotRegion(result, page, maskPx, r, w, h)
                    if (!ok) {
                        diffuseFill(result, maskPx, r, cfg.bboxPad, s.color, w, h)
                        r.onArt = false
                        infos.add(RegionEraseInfo(i, "diffuse-fallback", s.std, s.meanLum))
                    } else {
                        infos.add(RegionEraseInfo(i, "aot", s.std, s.meanLum))
                    }
                }
            }
        }
        SmartInpaintResult(result, infos)
    }

    /** 以區域為中心裁方形 context 跑 AOT，只把遮罩像素貼回；保留區域外的原圖。 */
    private fun aotRegion(result: Bitmap, page: Bitmap, maskPx: IntArray, r: TextRegion, w: Int, h: Int): Boolean {
        val rw = (r.x1 - r.x0).coerceAtLeast(8f)
        val rh = (r.y1 - r.y0).coerceAtLeast(8f)
        val pad = maxOf(24, (maxOf(rw, rh) * 0.45f).toInt())
        var x0 = (r.x0.toInt() - pad).coerceIn(0, w - 1)
        var y0 = (r.y0.toInt() - pad).coerceIn(0, h - 1)
        var x1 = (r.x1.toInt() + pad).coerceIn(x0 + 1, w)
        var y1 = (r.y1.toInt() + pad).coerceIn(y0 + 1, h)
        // 取正方形 context（AOT 輸入是方形）；置中在區域中心
        val cx = (r.x0 + r.x1) / 2f
        val cy = (r.y0 + r.y1) / 2f
        val side = maxOf(x1 - x0, y1 - y0).coerceAtLeast(64)
        x0 = (cx - side / 2f).toInt().coerceIn(0, maxOf(0, w - side))
        y0 = (cy - side / 2f).toInt().coerceIn(0, maxOf(0, h - side))
        x1 = (x0 + side).coerceIn(x0 + 1, w)
        y1 = (y0 + side).coerceIn(y0 + 1, h)
        val cw = x1 - x0; val ch = y1 - y0
        if (cw < 16 || ch < 16) return false
        val crop = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888)
        Canvas(crop).drawBitmap(page, -x0.toFloat(), -y0.toFloat(), null)
        val cmask = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888)
        val cm = IntArray(cw * ch)
        for (yy in 0 until ch) for (xx in 0 until cw) {
            cm[yy * cw + xx] = maskPx[(y0 + yy) * w + (x0 + xx)]
        }
        cmask.setPixels(cm, 0, cw, 0, 0, cw, ch)
        val out = runAot(crop, cmask, cw, ch, minOf(cfg.tileSize, 768))
        crop.recycle(); cmask.recycle()
        if (out == null) return false
        // out 的座標是「crop 內」；要貼回 page 的 (x0,y0)，不能直接 compositePixels。
        val sub = IntArray(cw * ch)
        result.getPixels(sub, 0, cw, x0, y0, cw, ch)
        for (yy in 0 until ch) {
            for (xx in 0 until cw) {
                if ((maskPx[(y0 + yy) * w + (x0 + xx)] and 0xFF) > 127) {
                    sub[yy * cw + xx] = out.px[yy * out.ww + xx]
                }
            }
        }
        result.setPixels(sub, 0, cw, x0, y0, cw, ch)
        return true
    }

    /** 由遮罩邊界向內擴散補色（平滑/漸層背景），只動遮罩像素。 */
    private fun diffuseFill(result: Bitmap, maskPx: IntArray, region: TextRegion, pad: Int, fallback: Int, w: Int, h: Int) {
        val x0 = (region.x0.toInt() - pad).coerceIn(0, w - 1)
        val y0 = (region.y0.toInt() - pad).coerceIn(0, h - 1)
        val x1 = (region.x1.toInt() + pad).coerceIn(x0 + 1, w)
        val y1 = (region.y1.toInt() + pad).coerceIn(y0 + 1, h)
        val bw = x1 - x0; val bh = y1 - y0
        if (bw < 3 || bh < 3) return
        var cur = IntArray(bw * bh); result.getPixels(cur, 0, bw, x0, y0, bw, bh)
        val masked = BooleanArray(bw * bh)
        var any = false
        for (y in 0 until bh) for (x in 0 until bw) {
            if ((maskPx[(y0 + y) * w + (x0 + x)] and 0xFF) > 127) { masked[y * bw + x] = true; any = true }
        }
        if (!any) return
        var filled = BooleanArray(bw * bh) { !masked[it] }
        val iterations = (maxOf(bw, bh) / 3).coerceIn(8, 80)
        repeat(iterations) {
            val next = cur.copyOf()
            val nextFilled = filled.copyOf()
            var changed = false
            for (y in 1 until bh - 1) for (x in 1 until bw - 1) {
                val i = y * bw + x
                if (filled[i]) continue
                var r = 0; var g = 0; var b = 0; var n = 0
                // 8 鄰居
                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val j = (y + dy) * bw + (x + dx)
                    if (filled[j]) {
                        val p = cur[j]; r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF; n++
                    }
                }
                if (n > 0) {
                    next[i] = (0xFF shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
                    nextFilled[i] = true
                    changed = true
                }
            }
            cur = next
            filled = nextFilled
            if (!changed) return@repeat
        }
        for (i in cur.indices) if (masked[i] && !filled[i]) cur[i] = fallback
        result.setPixels(cur, 0, bw, x0, y0, bw, bh)
    }

    /**
     * 暖機：aot 方法對空白小圖跑一次 NCNN AOT session（首次 lazy 初始化在單緒完成）。
     * boxfill 只平塗、不跑該 session → 無需暖（也不會冷撞）。併發翻多頁前先呼叫一次。
     */
    fun warmUp() {
        if (cfg.method == "boxfill") return
        val w = 64
        val h = 64
        val page = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val mask = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        try {
            runWholeAot(page, mask, w, h)
        } catch (t: Throwable) {
            Log.w(TAG, "去字暖機失敗：${t.message}")
        } finally {
            page.recycle()
            mask.recycle()
        }
    }

    /** 去字遮罩 Bitmap（白＝要去字）。給重繪素材/視覺化用；與 inpaint 同一份 seg 細遮罩。 */
    fun buildMask(page: Bitmap, regions: List<TextRegion>, textMask: Bitmap): Bitmap {
        val w = page.width; val h = page.height
        val maskPx = buildSegMask(regions, textMask, w, h)
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { setPixels(maskPx, 0, w, 0, 0, w, h) }
    }

    /**
     * seg 細遮罩＝seg 細筆畫 ∩ 已保留區的「區域 bbox 矩形（外擴 bboxPad）」（allow），再膨脹。回傳 ARGB 像素（白＝要去字）。
     * ★ 用 bbox 矩形(非緊的文字行框)＝涵蓋漢字旁的注音假名；pad 再外擴涵蓋貼 bbox 邊界的假名（桌面 auto_diag.py 實證）。
     */
    private fun buildSegMask(regions: List<TextRegion>, textMask: Bitmap, w: Int, h: Int): IntArray {
        val pad = cfg.bboxPad
        val allow = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(allow).apply {
            drawColor(Color.BLACK)
            val p = Paint().apply { color = Color.WHITE; style = Paint.Style.FILL }
            for (region in regions) drawRect(region.x0 - pad, region.y0 - pad, region.x1 + pad, region.y1 + pad, p)
        }
        val mask = IntArray(w * h)
        allow.getPixels(mask, 0, w, 0, 0, w, h)
        allow.recycle()
        val seg = IntArray(w * h)
        textMask.getPixels(seg, 0, w, 0, 0, w, h)
        for (i in mask.indices) {
            mask[i] = if ((mask[i] and 0xFF) > 127 && (seg[i] and 0xFF) > 127) Color.WHITE else Color.BLACK
        }
        dilate(mask, w, h, (cfg.maskDilate / 2f).roundToInt().coerceAtLeast(1))
        return mask
    }

    /** 二值遮罩可分離膨脹（先橫後縱 max-filter），radius 像素。覆蓋筆畫抗鋸齒邊緣、給去字餘裕。 */
    private fun dilate(px: IntArray, w: Int, h: Int, radius: Int) {
        if (radius <= 0) return
        val tmp = IntArray(px.size)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                var on = false; var k = -radius
                while (k <= radius) { val xx = x + k; if (xx in 0 until w && (px[row + xx] and 0xFF) > 127) { on = true; break }; k++ }
                tmp[row + x] = if (on) Color.WHITE else Color.BLACK
            }
        }
        for (x in 0 until w) {
            for (y in 0 until h) {
                var on = false; var k = -radius
                while (k <= radius) { val yy = y + k; if (yy in 0 until h && (tmp[yy * w + x] and 0xFF) > 127) { on = true; break }; k++ }
                px[y * w + x] = if (on) Color.WHITE else Color.BLACK
            }
        }
    }

    private class BgStat(val meanLum: Float, val std: Float, val color: Int)

    /**
     * 區 bbox 內「非文字(背景)」像素的亮度均值+std+平均色。tightPx＝未膨脹 textMask（量得到筆畫間的白）。
     * boxfill 用 [BgStat.color] 平塗；std/meanLum 只給 sandbox 去背比較標框（對照用）。用行框多邊形局部遮罩避開軸對齊 bbox 角落雜訊。
     */
    private fun bgStats(px: IntArray, tightPx: IntArray, region: TextRegion, w: Int, h: Int): BgStat {
        val x0 = region.x0.toInt().coerceIn(0, w - 1)
        val y0 = region.y0.toInt().coerceIn(0, h - 1)
        val x1 = region.x1.toInt().coerceIn(x0 + 1, w)
        val y1 = region.y1.toInt().coerceIn(y0 + 1, h)
        val bw = x1 - x0; val bh = y1 - y0
        val qmBmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        Canvas(qmBmp).apply {
            drawColor(Color.BLACK)
            val p = Paint().apply { color = Color.WHITE; style = Paint.Style.FILL }
            for (line in region.lines) {
                val q = line.quad
                if (q.size < 4) continue
                val path = android.graphics.Path().apply {
                    moveTo(q[0].x - x0, q[0].y - y0)
                    for (i in 1..3) lineTo(q[i].x - x0, q[i].y - y0)
                    close()
                }
                drawPath(path, p)
            }
        }
        val qm = IntArray(bw * bh)
        qmBmp.getPixels(qm, 0, bw, 0, 0, bw, bh)
        qmBmp.recycle()
        var n = 0; var sl = 0.0; var sl2 = 0.0; var sr = 0L; var sg = 0L; var sb = 0L
        for (y in 0 until bh) {
            for (x in 0 until bw) {
                if ((qm[y * bw + x] and 0xFF) <= 127) continue
                val gi = (y0 + y) * w + (x0 + x)
                if ((tightPx[gi] and 0xFF) > 127) continue // 文字像素
                val p = px[gi]
                val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
                val lum = 0.299 * r + 0.587 * g + 0.114 * b
                sl += lum; sl2 += lum * lum; sr += r; sg += g; sb += b; n++
            }
        }
        if (n < 16) return BgStat(255f, 0f, Color.WHITE)
        val mean = sl / n
        val std = kotlin.math.sqrt((sl2 / n - mean * mean).coerceAtLeast(0.0))
        return BgStat(mean.toFloat(), std.toFloat(), Color.rgb((sr / n).toInt(), (sg / n).toInt(), (sb / n).toInt()))
    }

    /** 白泡去字＝把區域 bbox(外擴 pad)內的去字遮罩像素直接平塗成背景色。均勻白泡保證無殘留。 */
    private fun flatFill(result: Bitmap, maskPx: IntArray, region: TextRegion, color: Int, pad: Int, w: Int, h: Int) {
        val x0 = (region.x0.toInt() - pad).coerceIn(0, w - 1)
        val y0 = (region.y0.toInt() - pad).coerceIn(0, h - 1)
        val x1 = (region.x1.toInt() + pad).coerceIn(x0 + 1, w)
        val y1 = (region.y1.toInt() + pad).coerceIn(y0 + 1, h)
        val bw = x1 - x0; val bh = y1 - y0
        val sub = IntArray(bw * bh)
        result.getPixels(sub, 0, bw, x0, y0, bw, bh)
        for (y in 0 until bh) {
            val mrow = (y0 + y) * w + x0
            val row = y * bw
            for (x in 0 until bw) if ((maskPx[mrow + x] and 0xFF) > 127) sub[row + x] = color
        }
        result.setPixels(sub, 0, bw, x0, y0, bw, bh)
    }

    private class WinOut(val x0: Int, val y0: Int, val ww: Int, val wh: Int, val px: IntArray)

    /**
     * 整頁跑一次 NCNN AOT-GAN：整張縮到方形 [cfg.tileSize] → 推論 → 放回原尺寸。只讀 page/maskBmp ⇒ 併發安全。
     * NCNN net 固定方形輸入、同尺寸復用安全（跨尺寸 reuse 會崩，故一律整頁 tileSize）。
     */
    private fun runWholeAot(page: Bitmap, maskBmp: Bitmap, w: Int, h: Int): WinOut? =
        runAot(page, maskBmp, w, h, cfg.tileSize)

    /** 對任意尺寸影像跑 AOT：縮到方形 [tile] 推論，再縮回輸出尺寸。 */
    private fun runAot(img: Bitmap, maskBmp: Bitmap, w: Int, h: Int, tile: Int): WinOut? {
        val t = tile.coerceAtLeast(64)
        val imgScaled = Bitmap.createScaledBitmap(img, t, t, true)
        val maskScaled = Bitmap.createScaledBitmap(maskBmp, t, t, false)
        return try {
            val imgChw = aotImageChw(imgScaled, maskScaled, t)
            val maskArr = maskArr(maskScaled, t)
            val out = FloatArray(3 * t * t)
            val rc = NcnnBackend.inpaintAot(ncnnHandle, imgChw, maskArr, t, out)
            check(rc == 0) { "NCNN AOT 推論失敗 rc=$rc" }
            val resScaled = aotArrToBitmap(out, t)
            if (w == t && h == t) {
                val px = IntArray(t * t); resScaled.getPixels(px, 0, t, 0, 0, t, t)
                resScaled.recycle()
                WinOut(0, 0, t, t, px)
            } else {
                val resWin = Bitmap.createScaledBitmap(resScaled, w, h, true)
                val px = IntArray(w * h)
                resWin.getPixels(px, 0, w, 0, 0, w, h)
                if (resWin !== resScaled) resScaled.recycle()
                resWin.recycle()
                WinOut(0, 0, w, h, px)
            }
        } catch (t2: Throwable) {
            Log.w(TAG, "AOT 去字失敗：${t2.message}"); null
        } finally {
            if (imgScaled !== img) imgScaled.recycle()
            if (maskScaled !== maskBmp) maskScaled.recycle()
        }
    }

    /** AOT 影像的裸 NCHW 陣列 [3*n*n]：RGB→[-1,1]，遮罩處歸零（m-i-t `img*(1-mask)`）。 */
    private fun aotImageChw(imgBmp: Bitmap, maskBmp: Bitmap, n: Int): FloatArray {
        val area = n * n
        val px = IntArray(area); imgBmp.getPixels(px, 0, n, 0, 0, n, n)
        val mp = IntArray(area); maskBmp.getPixels(mp, 0, n, 0, 0, n, n)
        val chw = FloatArray(3 * area)
        for (i in 0 until area) {
            if ((mp[i] and 0xFF) > 127) continue // 洞＝0
            val p = px[i]
            chw[i] = ((p shr 16) and 0xFF) / 127.5f - 1f
            chw[area + i] = ((p shr 8) and 0xFF) / 127.5f - 1f
            chw[2 * area + i] = (p and 0xFF) / 127.5f - 1f
        }
        return chw
    }

    /** 遮罩的裸陣列 [n*n]（1=擦）。 */
    private fun maskArr(bmp: Bitmap, n: Int): FloatArray {
        val px = IntArray(n * n); bmp.getPixels(px, 0, n, 0, 0, n, n)
        val m = FloatArray(n * n)
        for (i in px.indices) m[i] = if ((px[i] and 0xFF) > 127) 1f else 0f
        return m
    }

    /** AOT 輸出陣列 [3*n*n]∈[-1,1] → Bitmap（(x+1)*127.5）。 */
    private fun aotArrToBitmap(arr: FloatArray, n: Int): Bitmap {
        val area = n * n
        val px = IntArray(area)
        for (i in 0 until area) {
            val r = ((arr[i] + 1f) * 127.5f).toInt().coerceIn(0, 255)
            val g = ((arr[area + i] + 1f) * 127.5f).toInt().coerceIn(0, 255)
            val b = ((arr[2 * area + i] + 1f) * 127.5f).toInt().coerceIn(0, 255)
            px[i] = Color.rgb(r, g, b)
        }
        return Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888)
    }

    /** 把 AOT 輸出貼回 result，只換遮罩內像素（序列呼叫、寫入安全）。 */
    private fun compositePixels(result: Bitmap, maskPx: IntArray, o: WinOut) {
        val w = result.width
        val cur = IntArray(o.ww * o.wh)
        result.getPixels(cur, 0, o.ww, o.x0, o.y0, o.ww, o.wh)
        for (y in 0 until o.wh) {
            val maskRow = (o.y0 + y) * w + o.x0
            val row = y * o.ww
            for (x in 0 until o.ww) if ((maskPx[maskRow + x] and 0xFF) > 127) cur[row + x] = o.px[row + x]
        }
        result.setPixels(cur, 0, o.ww, o.x0, o.y0, o.ww, o.wh)
    }

    override fun close() {
        if (ncnnHandle != 0L) { NcnnBackend.releaseNet(ncnnHandle); ncnnHandle = 0L }
    }

    companion object {
        private const val TAG = "Inpainter"
    }
}
