# 架構與設計決策

## 為什麼用 Yakuyomi Engine 而不是自己寫 OCR
- 偵測（DBNet）、OCR（48px CTC）、擦字（AOT-GAN）都是成熟且已針對手機 CPU
  調過的 NCNN 模型；自己重寫會同時失去品質與速度。
- 引擎的 `translatePage` 已經處理「偵測→OCR→分群→翻譯→擦字→排版」與
  「失敗時保留原文」的不變式；本 App 只需要補上檔案、ZIP、續傳與 UI。
- 引擎是 GPL-3.0，因此本 App 也以 GPL-3.0 釋出並提供完整原始碼。

## 預編譯 libyakuyomi_ncnn.so
本機是 aarch64 Android proot，Android NDK 的 clang 是 x86_64，無法直接執行；
因此在 `inference-core/src/main/jniLibs/arm64-v8a/` 放入 Yakuyomi v0.23.0 官方 APK
中同版本的 `.so`。JNI 符號與 Kotlin `NcnnBackend` 已逐一核對。這也讓 build
不需要 NDK，PC 上可直接編譯。

## 為什麼一次只翻一頁
- 引擎一次載入三顆模型（約 248 MB 權重）與多張全解析度 bitmap。
- 一般手機 heap 雖有 largeHeap，但 native 記憶體與 bitmap 峰值仍高；
  單頁可把峰值控制在合理範圍，且每頁完成立即釋放 bitmap、寫入磁碟。
- 需求允許「低並行」；第一版選擇 1 頁，換取穩定與續傳。

## 頁序
`NaturalOrder` 對完整相對路徑逐段比較：
- 數字段按數值（任意長度、前導零不影響），所以 1 < 2 < 3 < 10。
- 非數字段用 zh-TW Collator（PRIMARY），中文/日文/英文混排穩定。
- 最後以原始字串決勝，排序完全確定。
ZIP 的中央目錄順序不被信任；頁序一律由此比較器決定。匯出時則按原始 ZIP
條目順序寫回（名稱與結構不變），因此閱讀器看到的資料夾/檔名與原本一致。

## 失敗語義
- `PageResult.Translated`：引擎認為成功。本 App 再檢查
  `stats.kept == 可翻文字區數`；若少了任何一區，視為 **FAILED**，保留原圖。
- `PageResult.Skipped`：沒有文字或全被過濾 → **SKIPPED**，不重試。
- `PageResult.Failed`：網路/格式錯誤 → 重試（有限次），仍失敗則 **FAILED**。
- 匯出時只有 `DONE` 頁換成譯圖；其他狀態一律複製原始壓縮條目。

## 重新排版
`meta/*.json` 保存每個文字區的：
- 原文（每行文字＋四邊形）
- 譯文
- 方向、角度、中心、boxW/boxH、onArt
- 遮罩檔名 `meta/*.mask.png`

修改譯文後，只需要：
1. 載入原圖 + 遮罩；
2. `Inpainter.inpaint` 重建背景；
3. `Renderer.render` 重畫文字。
不會呼叫 LLM，也不會重跑 OCR。

## 安全
- ZIP：`safePath` + canonical path 檢查 + 大小/壓縮比限制。
- API Key：Keystore AES/GCM；只出現在 `Authorization` header。
- 錯誤字串中的 key 會遮罩。
- 原始 ZIP 只讀；輸出走 SAF 新檔。
