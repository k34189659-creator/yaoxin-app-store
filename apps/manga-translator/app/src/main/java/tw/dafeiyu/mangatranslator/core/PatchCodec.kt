package tw.dafeiyu.mangatranslator.core

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 手動框選補救的一個區塊；patchFile 是「矩形大小」的最終小圖，套回頁面時直接貼上。 */
data class ManualPatch(
    val id: String,
    val pageIndex: Int,
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
    val source: String,
    val translated: String,
    val patchFile: String,
    val status: String,
    val createdAt: Long,
    /** 0 = legacy image; 1 = verified English image pipeline. */
    val renderRevision: Int = 0,
) {
    val rect: RectF get() = RectF(x, y, x + w, y + h)
}

object PatchCodec {
    fun file(projectDir: File, page: PageEntry): File = File(projectDir, "meta/%06d.patches.json".format(page.index))

    fun patchPath(page: PageEntry, id: String): String = "meta/%06d.patch.%s.png".format(page.index, id)

    fun load(projectDir: File, page: PageEntry): List<ManualPatch> {
        val f = file(projectDir, page)
        if (!f.isFile) return emptyList()
        return runCatching {
            val arr = JSONArray(f.readText(Charsets.UTF_8))
            val out = ArrayList<ManualPatch>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    ManualPatch(
                        id = o.getString("id"), pageIndex = o.optInt("page", page.index),
                        x = o.optDouble("x", 0.0).toFloat(), y = o.optDouble("y", 0.0).toFloat(),
                        w = o.optDouble("w", 0.0).toFloat(), h = o.optDouble("h", 0.0).toFloat(),
                        source = o.optString("source", ""), translated = o.optString("translated", ""),
                        patchFile = o.optString("patchFile", ""), status = o.optString("status", ""),
                        createdAt = o.optLong("createdAt"),
                        renderRevision = o.optInt("renderRevision", 0),
                    )
                )
            }
            out
        }.getOrDefault(emptyList())
    }

    fun save(projectDir: File, page: PageEntry, patches: List<ManualPatch>) {
        val f = file(projectDir, page)
        f.parentFile?.mkdirs()
        val arr = JSONArray()
        patches.forEach { p ->
            arr.put(JSONObject().apply {
                put("id", p.id); put("page", p.pageIndex)
                put("x", p.x.toDouble()); put("y", p.y.toDouble()); put("w", p.w.toDouble()); put("h", p.h.toDouble())
                put("source", p.source); put("translated", p.translated)
                put("patchFile", p.patchFile); put("status", p.status); put("createdAt", p.createdAt)
                put("renderRevision", p.renderRevision)
            })
        }
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(arr.toString(), Charsets.UTF_8)
        if (!tmp.renameTo(f)) { tmp.copyTo(f, overwrite = true); tmp.delete() }
    }

    /** 把補救小圖貼回頁面（依 rect 原座標）。 */
    fun apply(projectDir: File, base: Bitmap, patches: List<ManualPatch>): Bitmap {
        if (patches.isEmpty()) return base
        val out = base.copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(out)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        patches.forEach { p ->
            val f = File(projectDir, p.patchFile)
            if (!f.isFile) return@forEach
            val bmp = android.graphics.BitmapFactory.decodeFile(f.absolutePath) ?: return@forEach
            val dst = RectF(p.x, p.y, p.x + p.w, p.y + p.h)
            c.drawBitmap(bmp, null, dst, paint)
            bmp.recycle()
        }
        base.recycle()
        return out
    }
}
