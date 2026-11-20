# 瑤芯漫畫翻譯器 0.4.0 — 櫻織 → 小蘭（Android 編譯交接）

- **來源**：`yaoxin-all-sources-20261119.zip` 的 `yaoxin-manga/`（0.3.3、versionCode 6），不是憑空重建專案。
- **交付版本**：`0.4.0`，`versionCode 7`；applicationId `tw.dafeiyu.mangatranslator` 不變。
- **定位**：完整原始碼；目前 **未通過 Android SDK 編譯／真機安裝驗收**。必須由小蘭在 PC 編譯、使用者手機安裝驗收。
- **來源分支**：不直接回退 0.3.3 既有安全修復；參照 `CHANGELOG.md` 中 0.2.0 的功能、品質原則進行最小侵入修正。尚未取得 0.2.0 完整 Android 真機測試樣本，無法客觀宣稱 0.4.0 翻譯品質已優於 0.2.0。

## 已實作

1. **UI Kit**：引入原始 `Tokens.kt`、`YaoXinTheme.kt`，保留 `YaoXinMangaTheme()` 使用介面；以此提供統一 Material3 色彩／字型／形狀；管理頁底層加柔和櫻花裝飾。首頁使用同系列的 Logo 和銀紫髮女孩與小希；不更動原圖、譯圖、Canvas 圖片資料。
2. **來源語言**：新增日文／英文明確選擇；每個漫畫專案的 `project.json` 保存 `sourceLang`。只有未翻譯的專案能切換來源語言；舊專案、舊設定中的 AUTO 會安全對應為日文。英文走 ML Kit Latin，不能混入日文 NCNN 的未辨識行；日文走 NCNN（選用 PP），兩者分開排序、保留標點和翻譯示例。
3. **擦字**：以原像素估計有色純色區背景，彩色／紋理區仍用原有擴散和 AOT。遮罩擴張後裁回允許區域；AOT 失敗保留原圖且返回 `failed-kept`，後續排版不覆蓋該區，結果在診斷標記 PARTIAL。
4. **排版**：用 `InsetRegion` 對框保留安全邊距；無法容納的長句不強行畫出框，恢復該處原文，保留該區 overflow 診斷與人工補救入口。
5. **翻譯品質**：保守跳過繪製性擬聲詞；沒有從 0.3.1 硬套所有 PreservePolicy。新增日英不同 few-shot；修補簡體舊目標示例對不上源文的問題。保留 API、密鑰、快取和原有資料。

## PC 編譯與驗收

1. 解壓完整 ZIP，進入 `yaoxin-manga/`。
2. Android Studio / JDK 17、Android SDK 35、NDK/arm64、依 Gradle Wrapper 取得依賴。**不要**將原始 `gradlew` 換為不相容版本；須在正常連線的 PC 上建置。
3. Windows 命令：`gradlew.bat :app:assembleDebug :app:testDebugUnitTest :engine:testDebugUnitTest`。macOS/Linux: `./gradlew :app:assembleDebug :app:testDebugUnitTest :engine:testDebugUnitTest`。
4. 如果 Compose Kotlin、Model/Canvas API 編譯報錯，先按報錯定位修復並提高 patch/build 版本，勿把未編譯版當 release；使用與舊 App 一樣的 release 金鑰簽章。不要清除 app data。
5. 真機至少使用日文 0.2.0 已驗收漫畫、英文橫排漫畫、粉色純色泡、彩色漸層／網點、有插圖的對話背景、直排長句、橫排長句測試。逐頁對照遮罩、去字、嵌字、最終圖；確認沒有白洞／溢字／大面積誤擦。
6. 驗證 ZIP/CBZ 匯入匯出、Venice/自訂 OpenAI 相容 API、Key 安全儲存、模型狀態、暫停恢復、手動框選、快取、診斷、原生 arm64 記憶體與頁面連續運作。

## 已執行的檢查

- 原始 ZIP 已在讀取階段正常解壓；原始碼修改是直接套在該完整專案。
- Kotlin CLI (`kotlinc` 1.9)：`engine/.../Typesetter.kt` 編譯成功（1 個既存 unused-assignment 警告）；Standalone JVM 冒煙測試：`InsetRegion` 可用寬高與中文字換行正常、極長文字顯示 overflow ✅；`MangaReadingOrder` 日文右至左／英文左至右案例 ✅。
- 原專案 Gradle JUnit 中新增 `MangaReadingOrderTest` 和 `TypesetterTest` 安全內距／超框案例，以及藝術字保護案例；Gradle/Android SDK 不可用，**JUnit 尚未跑**。
- 對 Android/Compose 檔案執行缺依賴的 Kotlin CLI 靜態解析，未偵測到 `expecting`／未閉合字串／意外 token，但 4,000+ 未解析 Android/Compose/專案參照是預期結果，**不能視為 Android 編譯通過**。

## 風險／仍待驗證

- 0.2.0 翻譯品質只能依交接文件和歷史 CHANGELOG 做設計參考；未持有實際可比較的輸出頁與模型實機結果。
- ML Kit 在特定 Android 裝置／缺 Google 元件可能不可用；英文 OCR 沒辨識到文字會報失敗而不靜默跳過。
- AOT-GAN 對彩色／網點的還原精確度及長期使用記憶體峰值需真機測試，不能承諾 100% 無白塊。
- 保護原文優先，失敗區暫時保持原文並標記 PARTIAL；可從人工編輯／診斷處理。
- 原 ZIP 裡非本專案的其他 App 不包含在這份交付，七款版號不受影響。

## 變更檔案（32 個，交付時可能新增本手冊）

- `CHANGELOG.md`
- `app/build.gradle.kts`
- `app/src/main/java/tw/dafeiyu/mangatranslator/AppViewModel.kt`
- `app/src/main/java/tw/dafeiyu/mangatranslator/MainActivity.kt`
- `app/src/main/java/tw/dafeiyu/mangatranslator/core/HybridOcr.kt`
- `app/src/main/java/tw/dafeiyu/mangatranslator/core/MangaReadingOrder.kt`
- `app/src/main/java/tw/dafeiyu/mangatranslator/core/Project.kt`
- `app/src/main/java/tw/dafeiyu/mangatranslator/core/PromptRules.kt`
- `app/src/main/java/tw/dafeiyu/mangatranslator/core/SmartTranslator.kt`
- `app/src/main/java/tw/dafeiyu/mangatranslator/core/TranslationRunner.kt`
- `app/src/main/java/tw/dafeiyu/mangatranslator/data/AppSettings.kt`
- `app/src/main/java/tw/dafeiyu/mangatranslator/ui/Components.kt`
- `app/src/main/java/tw/dafeiyu/mangatranslator/ui/screens/HomeScreen.kt`
- `app/src/main/java/tw/dafeiyu/mangatranslator/ui/screens/PagesScreen.kt`
- `app/src/main/java/tw/dafeiyu/mangatranslator/ui/screens/SettingsScreen.kt`
- `app/src/main/java/tw/dafeiyu/mangatranslator/ui/theme/SakuraBackground.kt`
- `app/src/main/java/tw/dafeiyu/mangatranslator/ui/theme/Theme.kt`
- `app/src/main/java/tw/dafeiyu/mangatranslator/ui/theme/Tokens.kt`
- `app/src/main/java/tw/dafeiyu/mangatranslator/ui/theme/YaoXinTheme.kt`
- `app/src/main/res/drawable-nodpi/yx_manga_character.png`
- `app/src/main/res/drawable-nodpi/yx_manga_logo.png`
- `app/src/main/res/drawable-nodpi/yx_manga_sakura.png`
- `app/src/test/java/tw/dafeiyu/mangatranslator/core/MangaReadingOrderTest.kt`
- `app/src/test/java/tw/dafeiyu/mangatranslator/core/OcrFusionTest.kt`
- `engine/src/main/kotlin/li/joye/yakuyomi/engine/Inpainter.kt`
- `engine/src/main/kotlin/li/joye/yakuyomi/engine/PreservePolicy.kt`
- `engine/src/main/kotlin/li/joye/yakuyomi/engine/RegionAnalyzer.kt`
- `engine/src/main/kotlin/li/joye/yakuyomi/engine/SmartRenderer.kt`
- `engine/src/main/kotlin/li/joye/yakuyomi/engine/typeset/Typesetter.kt`
- `engine/src/test/kotlin/li/joye/yakuyomi/engine/PreservePolicyTest.kt`
- `engine/src/test/kotlin/li/joye/yakuyomi/engine/typeset/TypesetterTest.kt`
- `inference-core/src/main/kotlin/li/joye/yakuyomi/engine/Grouping.kt`
