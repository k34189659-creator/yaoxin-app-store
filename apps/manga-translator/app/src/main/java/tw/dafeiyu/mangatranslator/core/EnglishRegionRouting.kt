package tw.dafeiyu.mangatranslator.core

/**
 * 英文分型 v1：這四類擁有各自遮罩/修補/排版策略，不會重複掃描翻譯後的圖片。
 * 分類是啟發式的：遇到低確信度會在最後稽核標記，不能自稱已準確辨識漫畫氣泡。
 * 日文 0.2.0 不引用本檔。
 */
internal enum class EnglishRegionType(val label: String) {
    NORMAL_BUBBLE("普通框內"),
    PLAIN_ART("無框圖上"),
    COLORED_BUBBLE("彩色框內"),
    COLORED_ART("特殊彩字圖上"),
}

internal data class EnglishRegionFeatures(
    val backgroundSpread: Float,
    val backgroundSaturation: Float,
    val coloredInkRatio: Float,
    val borderScore: Float,
    val backgroundSamples: Int,
    val inkSamples: Int,
)

internal data class EnglishRegionRoute(
    val type: EnglishRegionType,
    val confidence: Float,
    val features: EnglishRegionFeatures,
)

/** 單純分類決策，不依賴 Android，可直接在 JVM 作單元測試。 */
internal object EnglishRegionRouting {
    fun classify(f: EnglishRegionFeatures): EnglishRegionRoute {
        // 色彩差異大的字可能是藝術字；但彩色對話框的背景本身不等於彩色字。
        val coloredInk = f.inkSamples >= 5 && f.coloredInkRatio >= 0.33f
        val framed = f.backgroundSamples >= 12 &&
            (f.backgroundSpread < 39f || (f.borderScore >= 0.63f && f.backgroundSpread < 68f))
        val coloredBg = f.backgroundSaturation >= 33f
        val type = when {
            framed && (coloredBg || coloredInk) -> EnglishRegionType.COLORED_BUBBLE
            framed -> EnglishRegionType.NORMAL_BUBBLE
            coloredInk -> EnglishRegionType.COLORED_ART
            else -> EnglishRegionType.PLAIN_ART
        }
        val conf = when {
            f.backgroundSamples < 12 -> 0.36f
            framed && f.backgroundSpread < 19f -> 0.87f
            framed -> 0.67f
            f.backgroundSpread > 70f -> 0.84f
            else -> 0.55f
        }
        return EnglishRegionRoute(type, conf, f)
    }
}
