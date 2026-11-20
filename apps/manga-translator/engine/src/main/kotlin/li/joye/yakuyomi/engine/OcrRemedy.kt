package li.joye.yakuyomi.engine

/**
 * 漏字補救強度。
 * - OFF：只跑 0.1.0 的單一 OCR（最快）。
 * - BALANCED：先正常 OCR；空讀/低信心行才加跑較大外擴、反色、Otsu 候選（預設）。
 * - HIGH：額外做局部重新偵測（對空白行框附近裁切放大）與整頁高解析補抓（較慢）。
 */
enum class OcrRemedyMode { OFF, BALANCED, HIGH }
