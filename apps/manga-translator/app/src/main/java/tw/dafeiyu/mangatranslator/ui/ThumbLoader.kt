package tw.dafeiyu.mangatranslator.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 小縮圖快取（不把整頁 ARGB 留在記憶體；預覽用 inSampleSize 解碼）。 */
object ThumbLoader {
    private const val MAX = 80
    private val cache = object : LinkedHashMap<String, Bitmap>(MAX, 0.75f, true) {
        // 不在 eviction 時 recycle：Compose 可能還持有舊 ImageBitmap，回收會讓畫面崩潰。
        // 這些縮圖很小（RGB_565），交給 GC 即可。
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean = size > MAX
    }
    suspend fun load(file: File, targetWidth: Int): ImageBitmap? = withContext(Dispatchers.IO) {
        val key = "${file.absolutePath}:$targetWidth:${file.lastModified()}:${file.length()}"
        synchronized(cache) { cache[key] }?.let { if (!it.isRecycled) return@withContext it.asImageBitmap() }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0) return@withContext null
        var sample = 1
        while (bounds.outWidth / sample > targetWidth * 1.6) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        val bmp = runCatching { BitmapFactory.decodeFile(file.absolutePath, opts) }.getOrNull()
            ?: return@withContext null
        synchronized(cache) { cache[key] = bmp }
        bmp.asImageBitmap()
    }

    fun clear() {
        synchronized(cache) { cache.clear() }
    }
}

@android.annotation.SuppressLint("ProduceStateDoesNotAssignValue")
@Composable
fun rememberThumb(file: File?, targetWidth: Int = 360): ImageBitmap? {
    val f = file
    return produceState<ImageBitmap?>(initialValue = null, f?.absolutePath, f?.lastModified(), targetWidth) {
        value = if (f != null) ThumbLoader.load(f, targetWidth) else null
    }.value
}
