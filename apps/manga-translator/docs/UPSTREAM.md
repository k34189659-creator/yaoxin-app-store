# 上游引擎整合與修改紀錄

本專案以 [joyeli/yakuyomi-engine](https://github.com/joyeli/yakuyomi-engine) 為引擎，
checkout 到 **`883737078aecb92ed796efac571bf066e217b1ab`**（Yakuyomi v0.23.0 使用的
submodule 指標），只保留翻譯需要的 `:inference-core` + `:engine`，移除 `:nightread-android`、
`nightread` submodule 與 `:app-sandbox`。

原生層 `libyakuyomi_ncnn.so` 直接取自 Yakuyomi v0.23.0 官方 arm64 APK
（`app-arm64-v8a-release.apk`）的 `lib/arm64-v8a/`，沒有在本機重建（本機為 aarch64
proot，NDK 的 x86_64 clang 無法執行）。JNI 符號已核對：

```
Java_li_joye_yakuyomi_engine_NcnnBackend_createNetNative
Java_li_joye_yakuyomi_engine_NcnnBackend_createNetExNative
Java_li_joye_yakuyomi_engine_NcnnBackend_initThreadPoolNative
Java_li_joye_yakuyomi_engine_NcnnBackend_releaseNet
Java_li_joye_yakuyomi_engine_NcnnBackend_cpuSupportsFp16Native
Java_li_joye_yakuyomi_engine_NcnnBackend_detectDbnetNative
Java_li_joye_yakuyomi_engine_NcnnBackend_inpaintAotNative
Java_li_joye_yakuyomi_engine_NcnnBackend_extractNative
Java_li_joye_yakuyomi_engine_NcnnBackend_ocrCtcNative
```

## Kotlin / 功能修改

| 檔案 | 修改 | 原因 |
|---|---|---|
| `LlmProviders.kt` | 新增 `venice` provider preset（chat/models URL、預設 `venice-uncensored-1-2`） | 本 App 預設 Venice；模型仍以官方 `/models` 即時清單為準 |
| `Config.kt` | `TranslatorConfig.extraInstructions` | 加入本 App 的忠實成人翻譯規則（不柔化、擬聲詞要翻、語氣一致） |
| `LlmTranslator.kt` | system prompt 追加 `extraInstructions`（明寫 override） | 不改上游預設 prompt 的其他使用者 |
| `LlmTranslator.kt` | 嚴格驗證 `<|ID|>` 集合（缺 ID/重複/多出） | 防止模型漏行或重複時把譯文套到錯的對話框 |
| `LlmTranslator.kt` | 重複 ID 時整頁保留原文 | 重複 ID 的映射不可信，寧可失敗也不要錯位 |
| `LlmTranslator.kt` | 解析 Venice `cost.usd` / `cost.diem` | 顯示可取得的費用資訊 |
| `LlmTranslator.kt` | 錯誤 body 遮罩 API Key | 避免代理回顯 key 時外洩 |
| `Pipeline.kt` | `PageStats` 新增 `costUsd` / `costDiem`，從 `Usage` 帶入 | 逐頁與總費用統計 |
| `LlmTranslator.kt` / `LlmModels.kt` / `ModelDownloader.kt` | `resp.body!!.string()` | 本專案用 OkHttp 4.12（上游為 5.x，body 非空）；只改相容性 |
| `inference-core/src/main/jniLibs/` | 放入上游 APK 的 `libyakuyomi_ncnn.so` | 免 NDK 建置；ABI 與 JNI 符號已核對 |
| `build.gradle.kts` | AGP 8.7.3 / Kotlin 2.0.21 / compileSdk 35 | 本機工具鏈可穩定建置（上游為 AGP 9.3.1 / Kotlin 2.4.10 / compileSdk 37） |
| `inference-core/build.gradle.kts` | 移除 `externalNativeBuild` / `ndkVersion` | 使用預編譯 `.so` |
| `engine/build.gradle.kts` | 移除 nightread 依賴 | 本 App 不需要夜讀 |

## App 層（非上游）

`app/` 全部為本專案新寫：ZIP 安全匯入/匯出、自然排序、專案落地與續傳、
Venice/自訂 API 設定與 Keystore、Compose UI、逐頁 runner、手動修正/重排版、
本機自測。沒有修改瑤芯系列其他 App。

## 授權

上游與本專案皆為 GPL-3.0；修改後的完整原始碼隨本專案提供。


## 0.2.0 引擎新增（向後相容）

| 檔案 | 新增/修改 | 用途 |
|---|---|---|
| `Detector.kt` | `detect(page, cfg: DetectorConfig)` overload | 局部裁切可用 1024 重新偵測，不改預設值 |
| `TextLine.kt` | `ocrProb`、`ocrStrategy` | 診斷、低信心補救、OCR 策略統計 |
| `Ocr.kt` | `recognizeBest`、`recognizeAllBest`、`OcrAttempt`；`stripToChw` 支援反色/Otsu；`betterAttempt` 只接受延長原結果的候選 | 尾字漏讀補救，不增加模型下載 |
| `OcrRemedy.kt` | `OcrRemedyMode`（OFF/BALANCED/HIGH） | 速度/品質模式 |
| `Inpainter.kt` | `inpaintSmart`、`RegionEraseInfo`、`SmartInpaintResult`、`aotRegion`、`diffuseFill`、`runAot` | 純色填色/漸層擴散/per-region AOT；只動文字遮罩 |
| `SmartRenderer.kt` | 新檔：`RegionOverride`、`RegionLayout`、`DetailedRenderResult` | 0.2.0 排版：overflow 不截斷、直排句讀、短句不縮、手動微調 |
| `Ocr.kt` | 保留 `recognize` 舊路徑 | 0.1.0 相容 |

App 層新增 `SmartTranslator`、`DiagCodec`、`PatchCodec`、`StageImageFactory`、診斷/補救 UI；
舊 `Pipeline` 仍保留，App 改用 `SmartTranslator`。
