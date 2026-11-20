package tw.dafeiyu.mangatranslator.core

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import java.io.File
import java.io.FileOutputStream

object ImageIo {
    private const val MAX_PIXELS = 120_000_000L

    class ImageTooLargeException(val w: Int, val h: Int) :
        Exception("圖片過大（${w}x${h}），為避免記憶體耗盡已跳過")

    /** 全解析度解碼（不縮圖：保留原始解析度是硬需求）。失敗回 null。 */
    fun decode(file: File): Bitmap? {
        if (!file.isFile) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        if (bounds.outWidth.toLong() * bounds.outHeight.toLong() > MAX_PIXELS) {
            throw ImageTooLargeException(bounds.outWidth, bounds.outHeight)
        }
        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = 1
        }
        return try {
            BitmapFactory.decodeFile(file.absolutePath, opts)
        } catch (oom: OutOfMemoryError) {
            throw ImageTooLargeException(bounds.outWidth, bounds.outHeight)
        }
    }

    fun formatFor(ext: String): Bitmap.CompressFormat = when (ext.lowercase()) {
        "png" -> Bitmap.CompressFormat.PNG
        "webp" -> if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSLESS else Bitmap.CompressFormat.WEBP
        else -> Bitmap.CompressFormat.JPEG
    }

    /**
     * 依使用者選擇決定輸出副檔名。
     *  - original：沿用原檔格式（JPEG 以 q=97 重新編碼，避免 0.2.0 的 q=95 二次損失）
     *  - png / webp / jpeg97：轉成指定格式（ZIP 內的檔名會同步換副檔名）
     */
    fun extensionFor(originalExt: String, format: String): String = when (format.lowercase()) {
        "png" -> "png"
        "webp", "webp_lossless" -> "webp"
        "jpeg97", "jpeg", "jpg" -> "jpg"
        else -> originalExt.lowercase().ifBlank { "jpg" }
    }

    /** 依格式存檔；[ext] 是實際要寫的副檔名（已由 [extensionFor] 決定）。 */
    fun saveAs(bitmap: Bitmap, file: File, ext: String, format: String) {
        val fmt = when (format.lowercase()) {
            "png" -> Bitmap.CompressFormat.PNG
            "webp", "webp_lossless" ->
                if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSLESS else Bitmap.CompressFormat.WEBP
            else -> formatFor(ext)
        }
        val quality = when {
            fmt == Bitmap.CompressFormat.JPEG -> 97
            fmt == Bitmap.CompressFormat.WEBP -> 100
            else -> 100
        }
        saveWith(bitmap, file, fmt, quality)
    }

    fun savePng(bitmap: Bitmap, file: File) = save(bitmap, file, "png")

    /** 先寫 `.part` 再 rename：中途崩潰不會留下半張圖被當成完成品。 */
    fun save(bitmap: Bitmap, file: File, ext: String) {
        saveWith(bitmap, file, formatFor(ext), if (ext.lowercase() == "jpeg" || ext.lowercase() == "jpg") 97 else 100)
    }

    fun saveWith(bitmap: Bitmap, file: File, fmt: Bitmap.CompressFormat, quality: Int) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".part")
        try {
            FileOutputStream(tmp).use { out ->
                val ok = bitmap.compress(fmt, quality, out)
                if (!ok) throw IllegalStateException("圖片編碼失敗（${file.name}）")
                out.flush()
                out.fd.sync()
            }
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        } catch (t: Throwable) {
            tmp.delete()
            throw t
        }
    }

    fun saveMask(bitmap: Bitmap, file: File) = savePng(bitmap, file)

    fun loadMask(file: File): Bitmap? = try {
        BitmapFactory.decodeFile(file.absolutePath)
    } catch (oom: OutOfMemoryError) {
        null
    }
}
