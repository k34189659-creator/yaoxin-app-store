package tw.dafeiyu.mangatranslator.net

import org.json.JSONArray
import org.json.JSONObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

data class ApiConfig(
    val providerId: String,
    val baseUrl: String,
    val apiKey: String,
    val modelId: String,
) {
    val isVenice: Boolean get() = providerId == "venice"
    val chatUrl: String get() = if (isVenice) VENICE_CHAT else deriveChat(baseUrl)
    val modelsUrl: String get() = if (isVenice) VENICE_MODELS else deriveModels(baseUrl)

    companion object {
        const val VENICE_BASE = "https://api.venice.ai/api/v1"
        const val VENICE_CHAT = "$VENICE_BASE/chat/completions"
        const val VENICE_MODELS = "$VENICE_BASE/models"
        const val DEFAULT_MODEL = "venice-uncensored-1-2"

        fun deriveChat(base: String): String {
            var b = base.trim().trimEnd('/')
            if (b.isEmpty()) return ""
            if (b.contains("/chat/completions")) return b
            if (b.endsWith("/v1")) return "$b/chat/completions"
            if (b.endsWith("/api")) return "$b/v1/chat/completions"
            return "$b/v1/chat/completions"
        }

        fun deriveModels(base: String): String {
            var b = base.trim().trimEnd('/')
            if (b.isEmpty()) return ""
            if (b.contains("/chat/completions")) b = b.substringBefore("/chat/completions").trimEnd('/')
            if (b.endsWith("/models")) return b
            if (b.endsWith("/v1")) return "$b/models"
            if (b.endsWith("/api")) return "$b/v1/models"
            return "$b/v1/models"
        }
    }
}

data class ModelOption(
    val id: String,
    val inputUsdPerMillion: Double? = null,
    val outputUsdPerMillion: Double? = null,
    val contextLength: Long? = null,
) {
    val isUncensored: Boolean get() = id.contains("uncensored", ignoreCase = true)
    fun priceLabel(): String = when {
        inputUsdPerMillion != null && outputUsdPerMillion != null ->
            "輸入 $%.2f / 輸出 $%.2f USD（每百萬 token）".format(inputUsdPerMillion, outputUsdPerMillion)
        inputUsdPerMillion != null -> "輸入 $%.2f USD（每百萬 token）".format(inputUsdPerMillion)
        else -> "費用未提供"
    }
}

data class ApiTestResult(
    val ok: Boolean,
    val httpCode: Int,
    val latencyMs: Long,
    val message: String,
    val reply: String? = null,
    val costUsd: Double? = null,
)

/**
 * 直接呼叫 provider 的 `/models` 與 `/chat/completions`，用於：
 *  - 抓模型清單與（Venice 提供的）單價
 *  - 「測試連線」：確認 key、端點、模型三者可用
 * 翻譯本身仍由 Yakuyomi 引擎的 LlmTranslator 執行（同一組 base/model/key）。
 *
 * ★ key 只放在 Authorization header，不寫 log、不放在 URL、不塞進錯誤訊息。
 */
object ApiClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    fun listModels(cfg: ApiConfig): List<ModelOption> {
        val url = cfg.modelsUrl
        if (url.isBlank()) throw IllegalArgumentException("API Base URL 不合法")
        val builder = Request.Builder().url(url).get()
        if (cfg.apiKey.isNotBlank()) builder.addHeader("Authorization", "Bearer ${cfg.apiKey}")
        val req = builder.build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}：${sanitize(text, cfg.apiKey)}")
            val root = JSONObject(text)
            val arr = root.optJSONArray("data") ?: root.optJSONArray("models") ?: JSONArray()
            val out = ArrayList<ModelOption>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id").ifBlank {
                    o.optString("name").removePrefix("models/")
                }
                if (id.isBlank()) continue
                val pricing = o.optJSONObject("model_spec")?.optJSONObject("pricing")
                    ?: o.optJSONObject("pricing")
                val input = pricing?.optJSONObject("input")?.optDoubleOrNull("usd")
                val output = pricing?.optJSONObject("output")?.optDoubleOrNull("usd")
                val ctx = o.optJSONObject("model_spec")?.optLongOrNull("availableContextTokens")
                    ?: o.optLongOrNull("context_length")
                out.add(ModelOption(id, input, output, ctx))
            }
            return out.sortedWith(compareByDescending<ModelOption> { it.isUncensored }.thenBy { it.id })
        }
    }

    fun testConnection(cfg: ApiConfig): ApiTestResult {
        val started = System.currentTimeMillis()
        if (cfg.apiKey.isBlank()) return ApiTestResult(false, 0, 0, "尚未輸入 API Key")
        if (cfg.modelId.isBlank()) return ApiTestResult(false, 0, 0, "尚未選擇模型")
        val url = cfg.chatUrl
        if (url.isBlank()) return ApiTestResult(false, 0, 0, "API Base URL 不合法")
        val body = JSONObject().apply {
            put("model", cfg.modelId)
            put("messages", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("content", "Reply with the single word OK.")
            }))
            put("max_tokens", 16)
            put("stream", false)
        }.toString().toRequestBody(JSON)
        val req = Request.Builder().url(url)
            .addHeader("Authorization", "Bearer ${cfg.apiKey}")
            .post(body)
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                val ms = System.currentTimeMillis() - started
                if (!resp.isSuccessful) {
                    ApiTestResult(false, resp.code, ms, "HTTP ${resp.code}：${sanitize(text, cfg.apiKey)}")
                } else {
                    val root = JSONObject(text)
                    val reply = root.optJSONArray("choices")?.optJSONObject(0)
                        ?.optJSONObject("message")?.optString("content").orEmpty()
                    val cost = root.optJSONObject("cost")?.optDoubleOrNull("usd")
                    val usage = root.optJSONObject("usage")
                    val tokens = usage?.optInt("prompt_tokens", 0)?.plus(usage.optInt("completion_tokens", 0)) ?: 0
                    ApiTestResult(
                        true, resp.code, ms,
                        "連線成功（${ms} ms，${tokens} tokens${cost?.let { "，花費 $%.6f USD".format(it) } ?: ""}）",
                        reply.take(80), cost,
                    )
                }
            }
        } catch (t: Throwable) {
            ApiTestResult(false, 0, System.currentTimeMillis() - started, "連線失敗：${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /** 錯誤訊息裡若出現 key（有些代理會回顯）一律遮掉；只保留前 300 字。 */
    private fun sanitize(s: String, key: String = ""): String {
        var out = s
        if (key.length >= 6) out = out.replace(key, "***")
        out = out.replace(Regex("(?i)sk-[A-Za-z0-9_-]{8,}"), "sk-***")
        return out.take(300)
    }

    private fun JSONObject.optDoubleOrNull(key: String): Double? {
        if (!has(key) || isNull(key)) return null
        val v = optDouble(key, Double.NaN)
        return if (v.isNaN()) null else v
    }

    private fun JSONObject.optLongOrNull(key: String): Long? {
        if (!has(key) || isNull(key)) return null
        val v = optLong(key, -1)
        return if (v < 0) null else v
    }
}
