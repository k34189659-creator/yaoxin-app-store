# 瑤芯漫畫翻譯器 0.5.2 / versionCode 14

小範圍介面修復，基於 v0.5.1 完整原始碼，**未修改 OCR / 翻譯 API / 英文分組 / 擦字 / 嵌字引擎**。

1. `PagesScreen.kt`：語言模式以高對比雙按鈕呈現，選中時顯示勾選、粉紫底色與「目前選擇」。透過 `vm.revision` 觀察語言切換後的畫面更新。
2. `AppViewModel.kt`：單本匯入、批次匯入成功、語言切換不再發送遮擋按鈕的底部 Snackbar；批次佇列結果仍可在首頁查看，錯誤仍有對話框。
3. `MainActivity.kt`：其他必要 Snackbar 改放 Scaffold 的 topBar 排版位置，不遮擋底部可操作區，背景改成可讀的粉白色。

## 編譯與驗證

完整 ZIP 結構與版本資訊可做靜態確認；此環境沒有 Android SDK，尚未進行 `assembleDebug` / 真機測試。

請小蘭執行 `./gradlew :app:assembleDebug :app:testDebugUnitTest` 並回報結果。驗收：選擇日英選項即時切換勾選；新匯入不出現底部黑色遮擋條；必要錯誤提示不蓋住底部按鈕。
