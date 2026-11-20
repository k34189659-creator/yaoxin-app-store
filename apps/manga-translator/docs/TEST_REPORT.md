# 測試報告 — 瑤芯漫畫翻譯 0.2.0

日期：2026-11-19（Asia/Taipei）
建置環境：realme RMX3700（Android 16 / SDK 36 / arm64-v8a / 11.5 GB RAM）上的
Ubuntu proot（aarch64），JDK 17.0.21、Gradle 8.9、AGP 8.7.3、Kotlin 2.0.21、
Android SDK 35 / Build-Tools 35.0.0。

## 1. 建置結果

| 項目 | 結果 |
|---|---|
| `:app:assembleDebug` | ✅ 成功 |
| APK 大小 | 20 MB（19,970,093 bytes；含 `libyakuyomi_ncnn.so` 4.43 MB） |
| APK SHA-256 | `aac30f21ef25bedab452f201f589e0314e7bd7c166a78f628216721b1ad594ad`（交付檔；重新建置會因簽章/時間戳不同） |
| 套件 / 版本 | `tw.dafeiyu.mangatranslator` / 0.2.0 (2) |
| minSdk / targetSdk | 26 / 35 |
| ABI | arm64-v8a |
| 簽章 | Android Debug（與 0.1.0 同一把 debug keystore；`apksigner verify` 通過） |
| `aapt2 dump badging` | label「瑤芯漫畫翻譯」、`INTERNET`、`ACCESS_NETWORK_STATE` |
| Android Lint | ✅ 0 error；15 warning（依賴更新提示、arm64-only 不支援 ChromeOS x86_64、UsableSpace 建議等已知取捨） |
| 模型檔 | 7 個翻譯模型已在建置機下載，逐檔 SHA-256 與上游 manifest 相符（約 248 MB） |

## 2. 自動化測試（實際執行）

指令：
```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64
export ANDROID_HOME=/root/android-sdk
/opt/gradle/gradle-8.9/bin/gradle --no-daemon \
  -Pandroid.aapt2FromMavenOverride=/opt/aapt2/aapt2 \
  :app:testDebugUnitTest
```

**結果：34 項、0 失敗、0 錯誤、0 略過。**

| 測試類別 | 數量 | 覆蓋 |
|---|---:|---|
| `NaturalOrderTest` | 6 | 1/2/3/10、章節路徑、中文話數、前導零、超長數字、大小寫穩定性 |
| `ZipPipelineTest` | 10 | 自然頁序＋資料夾、中日文檔名、路徑穿越、絕對路徑、無圖片、專案 JSON round-trip、續傳、PARTIAL 頁匯出、失敗/原圖保留 |
| `ApiClientTest` | 6 | Venice/自訂 URL 推導、模型清單與價格、401 遮罩 Key、連線成功/失敗、無 Key 前置檢查 |
| `LlmTranslatorTest` | 6 | `<|n|>` 對應、cost 解析、缺 ID、重複 ID、多出 ID、HTTP 401 保留原文、code fence 解析 |
| `ManifestTest` | 2 | 打包 manifest 的 7 個翻譯檔、總大小、ModelSet 三角色解析 |
| `DiagCodecTest` | 2 | 診斷 JSON round-trip（階段、regionDiag、cache、override）、從 meta 建翻譯快取 |
| `PatchCodecTest` | 2 | 局部補救 metadata round-trip、空清單 |

0.1.0 的 30 項測試全部保留；新增 4 項（DiagCodec ×2、PatchCodec ×2、以及 ZipPipeline 的 PARTIAL 案例計入 10 項）。

### 已用 MockWebServer 驗證的 API 行為（與 0.1.0 相同）
- 請求帶 `Authorization: Bearer <key>`、`model`、`messages`、`temperature`；system prompt 含本 App 的
  成人忠實翻譯規則。
- Venice 風格的 `cost: {usd, diem}` 與 `usage: {prompt_tokens, completion_tokens}` 可正確解析。
- 缺少 `<|2|>` → 該行保留原文、`error` 標記「缺少 2」。
- 重複 `<|1|>` → 整頁保留原文、`error` 標記「重複」。
- 多出 `<|3|>` → 記錄警告。
- HTTP 401 → 保留原文、`error` 含 `HTTP 401`、不含完整 Key。

## 3. 桌面 NCNN 模型探針（非實機）

用引擎自帶的 3 頁與同一組 DBNet+CTC 模型，在桌面 aarch64 跑推論；詳見
[`QUALITY_PROBE.md`](QUALITY_PROBE.md)。

| 頁面 | DBNet 行框 | OCR 有文字 | 平均信心 | 0.2.0 候選改動 |
|---|---:|---:|---:|---:|
| `ch34_006`（白底直排） | 17 | 17/17 | 0.960 | 0 |
| `demo02`（白底框/網點/傾斜） | 30 | 30/30 | 0.979 | 4 行（+4 字，0 減字） |
| `demo01`（文字壓人物） | 31 | 31/31 | 0.971 | 0 |

重要限制：
- 這不是 Android 實機、不是人工標註，**不能計算真實召回率/字元正確率/整頁成功率**。
- 沒有執行擦字、Canvas 排版、翻譯 API；不能從這裡推論最終畫面品質。
- 只證明：白底簡單頁的 DBNet+CTC 本身可以正常運作，0.1.0 的「整頁失敗」較可能來自
  「一區失敗就丟整頁」政策與整頁 AOT/排版，而不是 OCR 模型完全壞掉。

## 4. 實機安裝與端到端（未執行）

裝置可用（`adb-shell` 可查詢），但：

```
$ adb-shell pm install -r /sdcard/Download/yaoxin-manga-0.2.0-debug.apk
[POLICY_BLOCKED] pm 只允許查詢；安裝、解除安裝、清資料和授權不開放給裝置命令
```

助手也無法以其他方式在裝置上執行本 App 的程式：
- `adb-shell app_process` → `[POLICY_BLOCKED] app_process 仅允许原生受管虚拟屏/实体屏入口`。
- 直接從 proot 執行 Android `app_process` 失敗：ColorOS 的 `libandroid_runtime.so` 依賴
  `liboplusplugin.so` / `libguiextimpl.so`，而 `/system_ext`、`/odm`、`/vendor` 在 proot 中被隱藏，
  無法載入；因此不能在不安裝 APK 的情況下跑引擎自測。

**未驗證項目（不得宣稱通過）：**
- 實機 UI 啟動、診斷頁、框選補救手勢。
- 實機 NCNN 偵測 / OCR（日文直排/橫排）/ 智慧擦字 / SmartRenderer 繁中排版。
- 真實 Venice `/chat/completions` 成功與錯誤（401/402/429）。
- 實機 ZIP 匯入/匯出、暫停/續傳、記憶體峰值與大頁品質。
- 多階段 OCR（BALANCED/HIGH）的實際速度與成功率。
- 部分完成頁（PARTIAL）在實機上的視覺品質。

> APK 會放至 `/sdcard/Download/yaoxin-manga-0.2.0-debug.apk`，可由使用者手動安裝後
> 依 [`MANUAL_TEST.md`](MANUAL_TEST.md) 驗收；App 內建「本機引擎自測」與「診斷」可在不花 API 的情況下
> 先驗證 OCR/擦字/排版。

## 5. 0.2.0 需求對照

| 需求 | 實作 | 驗證狀態 |
|---|---|---|
| 五階段診斷（偵測/OCR/翻譯/擦字/排版） | ✅ diag JSON + 診斷頁 | JVM 測 diag JSON；UI 未實機 |
| 診斷預覽 7 種圖、同座標 | ✅ 原圖座標輸出 | 未實機 |
| 匯出診斷報告、Key 不入報告、文字/圖片可選 | ✅ JSON；預設不含文字/圖 | JVM 測資料；UI 未實機 |
| 多階段偵測（局部 + 整頁切片） | ✅ BALANCED/HIGH/OFF | 未實機 |
| 對話框合併/拆分 | 沿用引擎 Grouping（未改） | 未實機；診斷可看 |
| OCR 多策略/反色/Otsu | ✅ | 桌面 probe 部分驗證 |
| 第二套 OCR（ML Kit） | 評估未整合（見 OCR_ALTERNATIVES.md） | 未實機比較 |
| 漏字檢查（只標記不破壞） | ✅ 診斷頁「漏字檢查」 | 未實機 |
| 智慧擦字（純色/漸層/AOT） | ✅ Inpainter.inpaintSmart | 未實機 |
| 不塗整框、保護邊線 | ✅ 只填文字遮罩 | 未實機 |
| 擦字遮罩預覽/單獨重做 | ✅ | 未實機 |
| 手動指定擦字範圍 | ✅ 框選補救 | 未實機 |
| 自動字級/直橫/行距/描邊/不超出 | ✅ SmartRenderer | 未實機 |
| 短句不縮太小、長句不截斷 | ✅ overflow 標記 | 未實機 |
| 手動調整位置/字級/方向 | ✅ 編輯頁微調 | 未實機 |
| 局部框選 OCR→翻譯→擦字→嵌字 | ✅ | 未實機 |
| OCR 失敗手動輸入原文 | ✅ | 未實機 |
| 單獨重跑其中一步 | ✅ 補救紀錄：辨識/翻譯/嵌字 | 未實機 |
| 翻譯快取、不重複扣費 | ✅ diag cache + meta | JVM 測 cache 讀取；runner 未實機 |
| 失敗不完全自動重試 | ✅ 只對可恢復錯誤重試 | 未實機 |
| 每本花費統計 | ✅ token/cost 累計 | 未實機 |
| 四難度測試/指標 | 部分：桌面 probe 3 頁；無標註真值 | 未達完整驗收 |
| 既有 0.1.0 功能 | ✅ 測試保留、ZIP/頁序/API/續傳/匯出未移除 | 34 項測試 |

## 6. 已知風險與未修復

1. **沒有實機品質數據**：0.2.0 的擦字/排版/診斷 UI 均未在 realme 上跑過；不能說「簡單漫畫已穩定」。
2. **沒有真人標註漫畫頁**：無法計算真實 OCR/偵測召回率；桌面 probe 只能做 sanity check。
3. **Grouping 未改**：仍沿用 Yakuyomi 上游的合併/拆分；若診斷顯示不同氣泡被合併，需要再有標註資料調整。
4. **同長度 OCR 換字未處理**：保守策略只修尾字，`機/様`、`を/が` 這類同長度誤讀仍留給翻譯模型容錯。
5. **ML Kit 未整合**：官方未明示日文直排支援，沒有實機 A/B，不貿然加 4MB/模型下載。
6. **PARTIAL 是行為變更**：部分頁現在會輸出已翻區域與原始日文並存；若使用者要求「失敗頁整頁原圖」，可用設定/後續選項調整（0.2.0 未提供切換）。
7. **大頁 AOT**：智慧擦字對複雜區用 per-region 768 crop；比整頁 AOT 更清晰，但多區時可能較慢，尚未實機量測。
8. **APK 仍為 Debug 簽章**：商店更新必須沿用同一把 debug keystore；若要正式簽章需另立金鑰並處理安裝升級。

## 7. 手動驗收

見 [`MANUAL_TEST.md`](MANUAL_TEST.md)。優先順序：
1. 安裝 0.2.0（同簽章可直接覆蓋 0.1.0）。
2. 設定 → 模型（已下載者跳過）→ API Key → 測試連線。
3. 設定 → 本機引擎自測（不花 API）。
4. 匯入一本含白底對話框的 ZIP；翻譯 2–3 頁後暫停/續傳。
5. 失敗/部分頁 → 診斷 → 匯出報告（含原文/譯文與預覽圖）回報。
6. 用手框選一個漏譯區，確認只補該區、不重翻整本。
7. 匯出 ZIP，檢查頁序/中文檔名/失敗頁原圖。
