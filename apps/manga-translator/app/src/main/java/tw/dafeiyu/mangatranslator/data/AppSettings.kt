package tw.dafeiyu.mangatranslator.data

import android.content.Context
import android.content.SharedPreferences
import tw.dafeiyu.mangatranslator.net.ApiConfig

data class AppSettings(
    val providerId: String = "venice",
    val baseUrl: String = ApiConfig.VENICE_BASE,
    val modelId: String = ApiConfig.DEFAULT_MODEL,
    /** "smart"（智慧，預設）/ "aot"（AI 背景修復）/ "boxfill"（快速擦字）。 */
    val inpaint: String = "smart",
    /** OFF / BALANCED / HIGH：OCR 漏字補救強度。 */
    val remedy: String = "BALANCED",
    /** FAST / STANDARD / HIGH：0.3.0 多引擎 OCR 模式。 */
    val ocrMode: String = "FAST",
    /** AUTO / JAPANESE / ENGLISH：來源語言（英文走 ML Kit，日文走 PP-OCRv6/NCNN）。 */
    val ocrLang: String = "JAPANESE",
    /** original＝原版風格優先；readable＝清楚易讀優先。 */
    val styleMode: String = "original",
    /** original / png / webp / jpeg97：匯出圖片格式。 */
    val exportFormat: String = "original",
    /** AUTO / VERTICAL / HORIZONTAL */
    val orientation: String = "AUTO",
    /** zh-TW / zh-HK / zh-CN（輸出語言；預設台灣繁體） */
    val targetLang: String = "zh-TW",
    val fontBorder: Boolean = true,
    val retries: Int = 2,
    /** 僅 debug 版可用：不呼叫 API，用固定繁中句子驗證流程。 */
    val mockTranslation: Boolean = false,
    val lastProjectId: String? = null,
    /** 單本預設不自動匯出；多本預設完成後自動匯出。 */
    val singleAutoExport: Boolean = false,
    val batchAutoExport: Boolean = true,
    /** null 表示 Download/瑤心翻譯；非 null 為使用者透過 SAF 授權的資料夾 URI。 */
    val exportTreeUri: String? = null,
) {
    fun toApiConfig(apiKey: String): ApiConfig = ApiConfig(
        providerId = providerId,
        baseUrl = if (providerId == "venice") ApiConfig.VENICE_BASE else baseUrl,
        apiKey = apiKey,
        modelId = modelId,
    )
}

/**
 * 一般設定走 SharedPreferences；API Key 單獨用 [SecureKeyStore] 加密。
 * 刻意不把 key 放進 [AppSettings]，避免被 toString/序列化/日誌帶出去。
 */
class SettingsStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("yaoxin_manga_settings", Context.MODE_PRIVATE)

    fun load(): AppSettings = AppSettings(
        providerId = prefs.getString("provider_id", "venice") ?: "venice",
        baseUrl = prefs.getString("base_url", ApiConfig.VENICE_BASE) ?: ApiConfig.VENICE_BASE,
        modelId = prefs.getString("model_id", ApiConfig.DEFAULT_MODEL) ?: ApiConfig.DEFAULT_MODEL,
        inpaint = "smart",
        remedy = "BALANCED",
        ocrMode = "FAST",
        ocrLang = "JAPANESE", // 不沿用舊版英文實驗模式；新匯入預設穩定的 0.4.1 日文管線
        styleMode = "original",
        exportFormat = "original",
        orientation = "AUTO",
        targetLang = "zh-TW",
        fontBorder = true,
        retries = 2,
        mockTranslation = false,
        lastProjectId = prefs.getString("last_project_id", null),
        singleAutoExport = prefs.getBoolean("single_auto_export", false),
        batchAutoExport = prefs.getBoolean("batch_auto_export", true),
        exportTreeUri = prefs.getString("export_tree_uri", null),
    )

    fun save(s: AppSettings) {
        prefs.edit()
            .putString("provider_id", s.providerId)
            .putString("base_url", s.baseUrl)
            .putString("model_id", s.modelId)
            .putString("inpaint", s.inpaint)
            .putString("remedy", s.remedy)
            .putString("ocr_mode", s.ocrMode)
            .putString("ocr_lang", s.ocrLang)
            .putString("style_mode", s.styleMode)
            .putString("export_format", s.exportFormat)
            .putString("orientation", s.orientation)
            .putString("target_lang", s.targetLang)
            .putBoolean("font_border", s.fontBorder)
            .putInt("retries", s.retries)
            .putBoolean("mock_translation", s.mockTranslation)
            .putString("last_project_id", s.lastProjectId)
            .putBoolean("single_auto_export", s.singleAutoExport)
            .putBoolean("batch_auto_export", s.batchAutoExport)
            .putString("export_tree_uri", s.exportTreeUri)
            .apply()
    }

    fun apiKey(): String? = SecureKeyStore.decrypt(prefs.getString("api_key_enc", null))

    fun setApiKey(key: String?) {
        if (key.isNullOrBlank()) {
            prefs.edit().remove("api_key_enc").apply()
        } else {
            val enc = SecureKeyStore.encrypt(key)
            if (enc != null) prefs.edit().putString("api_key_enc", enc).apply()
        }
    }

    fun hasApiKey(): Boolean = !apiKey().isNullOrBlank()
}
