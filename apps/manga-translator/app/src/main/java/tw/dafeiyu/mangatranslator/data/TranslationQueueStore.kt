package tw.dafeiyu.mangatranslator.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 只負責多作品排程，不碰專案的原始圖片與翻譯流程。 */
data class QueueItem(
    val projectId: String,
    val state: String = WAITING,
    val autoExport: Boolean = true,
    val outputUri: String? = null,
    val message: String? = null,
) {
    companion object {
        const val WAITING = "WAITING"
        const val RUNNING = "RUNNING"
        const val PAUSED = "PAUSED"
        const val DONE = "DONE"
        const val ERROR = "ERROR"
    }

    fun toJson() = JSONObject().apply {
        put("projectId", projectId)
        put("state", state)
        put("autoExport", autoExport)
        put("outputUri", outputUri ?: JSONObject.NULL)
        put("message", message ?: JSONObject.NULL)
    }
}

class TranslationQueueStore(private val file: File) {
    fun load(): List<QueueItem> = runCatching {
        if (!file.isFile) return emptyList()
        val array = JSONArray(file.readText(Charsets.UTF_8))
        (0 until array.length()).map { index ->
            val obj = array.getJSONObject(index)
            QueueItem(
                projectId = obj.getString("projectId"),
                state = obj.optString("state", QueueItem.WAITING).let {
                    if (it == QueueItem.RUNNING) QueueItem.PAUSED else it
                },
                autoExport = obj.optBoolean("autoExport", true),
                outputUri = obj.optString("outputUri").takeIf { it.isNotBlank() && it != "null" },
                message = obj.optString("message").takeIf { it.isNotBlank() && it != "null" },
            )
        }
    }.getOrDefault(emptyList())

    fun save(items: List<QueueItem>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(JSONArray().apply { items.forEach { put(it.toJson()) } }.toString(), Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }
}
