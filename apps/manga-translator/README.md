> 目前交付原始碼版本：**0.5.6（versionCode 18）**。本次修正英文異常長文、氣泡邊線損傷與不安全排版；日文 Legacy020 三個核心檔保留。當前變更、驗證及實機待測範圍見 [`UPDATE_0.5.6.md`](UPDATE_0.5.6.md)。以下版本歷史不代表本次全部重新驗收。

# 瑤芯漫畫翻譯（YaoXin Manga Translator）

> 匯入整理好的漫畫 ZIP，選好 API，按開始翻譯，得到順序正確、文字換成繁體中文的漫畫 ZIP。

Android 原生 App（Kotlin + Jetpack Compose），本機使用
[Yakuyomi Engine](https://github.com/joyeli/yakuyomi-engine) 的 NCNN 流程做文字偵測、日文 OCR、
擦字與嵌字；只有「翻譯」這一步會呼叫雲端 LLM。預設 Venice AI，也可換成任何 OpenAI 相容端點。

**版本：0.5.6（versionCode 18）｜套件：`tw.dafeiyu.mangatranslator`｜授權：GPL-3.0**

**0.2.0（可回退版本）：**
[下載 0.2.0 APK](https://github.com/k34189659-creator/yaoxin-app-store/releases/download/manga-v0.2.0/yaoxin-manga-0.2.0.apk)｜
[Release](https://github.com/k34189659-creator/yaoxin-app-store/releases/tag/manga-v0.2.0)

---

## 0.3.0 歷史驗證紀錄（本次驗證請看 UPDATE_0.5.6.md）

| 項目 | 狀態 |
|---|---|
| 原始碼、Gradle 建置 | ✅ 完成 |
| Debug APK 編譯 | ✅ 完成（34 MB，arm64-v8a；含 ONNX Runtime 與 ML Kit） |
| JVM 單元測試 | ✅ 61 項全數通過（0.2.0 的 34 項全部保留） |
| Android Lint | ✅ 0 error |
| OCR 雙引擎比較 | ✅ 11 頁桌面實測（PP-OCRv6 多抓 74 框、NCNN 獨有 1 框） |
| 排版前後對照 | ✅ 6 案例桌面驗證（0.2.0 5 例超框、0.3.0 0 例） |
| 瑤芯應用商店 | 0.3.0 建置完成後上架 `manga-v0.3.0` |
| 實機安裝、UI 操作 | ⚠️ **未驗證**：DSHA 裝置命令政策禁止 `pm install`，助手無法安裝 APK |
| 真實 Venice API 呼叫 | ⚠️ **未驗證**：沒有使用者的 API Key；已用 MockWebServer 驗證請求/解析/錯誤處理，並查詢過官方 `/models` |
| 實機 OCR／擦字／嵌字 | ⚠️ **未驗證**：同上，需使用者安裝後用內建「本機引擎自測」確認 |

詳見 [`docs/RELEASE_0.3.0.md`](docs/RELEASE_0.3.0.md)（0.3.0）與 [`docs/TEST_REPORT.md`](docs/TEST_REPORT.md)（0.2.0）。

---

## 0.3.0 品質升級（相對 0.2.0）

- **多引擎 OCR**：新增 PP-OCRv6 Manga（ONNX Runtime、Apache-2.0、23 MB 按需下載），
  `FAST / STANDARD / HIGH` 三模式；PP 補偵測召回、NCNN 提供遮罩與第二意見，逐行融合不拼接。
- **來源語言**：日文走 PP/NCNN、英文走 ML Kit Latin，可手動指定避免自動判斷錯誤。
- **漫畫文字類型**：對話／旁白／音效／彩色藝術字／不確定；不確定區**不擦不蓋**。
- **對話框輪廓**：flood fill 找氣泡內緣，橢圓/不規則框依逐列可用寬度排版。
- **排版重寫**：真實字度量、禁則、直排標點/旋轉/縱中橫、絕不截斷（0.2.0 只檢查寬度、5/6 案例超框）。
- **擦字**：依字級調整膨脹、逐區容錯、殘留自動改 AOT。
- **整頁稽核**：輸出與原圖相同時明確標失敗，不再默默當完成。
- **無損匯出**：PNG / 無損 WebP / JPEG 97；未處理頁面原樣複製。

---

## 0.2.0 品質升級（相對 0.1.0）

0.1.0 實機回報「簡單白底對話框也會漏字／整頁失敗」。先做的不是換模型，而是把問題拆開：

### 1. 五階段診斷
- 將流程拆成：偵測 → OCR → API 翻譯 → 擦字/背景修復 → 排版。
- 每頁保存 `meta/<page>.diag.json`：各階段耗時、偵測輪數、補抓行數、OCR 策略、每區原文/譯文/信心/擦字策略/排版字級/overflow、API 錯誤、翻譯快取。
- 檢視頁新增「診斷」：原圖／偵測框＋編號／OCR 原文／API 譯文／擦字遮罩／擦字後／最終嵌字；全部以原圖座標輸出，預覽只等比例縮放。
- 「匯出診斷報告」JSON：裝置、頁面、設定、階段數據、錯誤；**不含 API Key**；原文/譯文預設不含，需勾選。

### 2. 不再因一個對話框失敗丟掉整頁
- 0.1.0：`kept < 可翻區數` 就整頁 FAILED、保留原圖 → 使用者看到整頁日文。
- 0.2.0：新增 **PARTIAL** 狀態。成功區照常擦字/嵌字，失敗區**保留原始日文**、標記待檢查；只有全部失敗/API 錯誤才 FAILED。
- 失敗區不擦、不重畫，避免「擦掉後又貼回日文」的半成品。
- 翻譯快取：重試、重做擦字/排版只請求缺少的區，不重複扣 API。

### 3. OCR 漏字補救
- 每行 OCR 候選：原偵測框（exact）、外擴 4/8/12px、低信心時反色與 Otsu 二值化；只接受「比原結果多 1–3 字且原結果是它的前/後綴」的候選，避免把正確的 `ー/！/を/が` 改壞。
- 平衡模式：對空讀/低信心行框附近裁切放大重新偵測；高品質模式：再加整頁 2×2 重疊切片；第一輪完全沒偵測到文字時也會自動跑切片。
- 局部重新辨識仍保留在編輯頁（既有文字行重跑 OCR）。

### 4. 智慧擦字
- 逐區依背景標準差分類：
  - 純色白泡 → 只填文字筆畫遮罩，不塗整框、不跑 AOT。
  - 平滑/漸層 → 由遮罩邊界向內擴散補色。
  - 複雜背景（人物/網點/線條）→ 以區域為中心取方形 context 跑 AOT-GAN，再把遮罩像素貼回。
- 不再對整頁拉伸成 768×768 再放回（0.1.0 的方形失真來源）。
- 擦字失敗會退回擴散填色或保留原圖；遮罩可預覽；可在診斷頁單獨重做擦字，不需 API。

### 5. 繁中排版
- 新 `SmartRenderer`：以實際欄/行數驗證是否放得進框；放不進時保留全文、標記 overflow，不截斷。
- 短句不套用全域縮小；長句才逐步降字級。
- 直排句讀（、。，．）右上定位、既有旋轉標點與縱中橫保留。
- 依去字後背景亮度選黑/白字（含壓畫面區），不再無條件黑字。
- 文字框外擴縮小（1.15/1.20），降低壓到對話框邊線的機率。

### 6. 局部框選補救
- 在單頁檢視開啟「框選補救」，用手指拖曳漏譯區域：
  - 自動偵測/OCR → 翻譯 → 擦字 → 嵌字，只處理該框，不重翻整本。
  - OCR 失敗可手動輸入原文再翻譯。
  - 補救紀錄可單獨「重新辨識／重新翻譯／重新嵌字」；只有重新翻譯會呼叫 API。
- 補救小圖存為 patch PNG，套回頁面譯圖；重新翻譯整頁或重新排版時會自動重貼補救。

### 7. 桌面模型探針（非實機）
用引擎自帶的 `ch34_006`（白底直排、橢圓/方框）與 `demo02` 在桌面 NCNN 跑同一組 DBNet+CTC：
- `ch34_006`：17 個文字行框，OCR 17/17 有文字，平均信心 0.96；0.1.0 的 OCR 本身並不是這頁的瓶頸。
- `demo02`：30/30 有文字，0.2.0 的保守候選多救回 4 行尾字（体内か→体内から、とした→としたら、…？、…。），沒有因候選而少字。
- 這是桌面模型探針，**不是實機品質驗證**；沒有真人標註，不能計算真实召回率/準確率。詳見 [`docs/QUALITY_PROBE.md`](docs/QUALITY_PROBE.md)。

> 0.2.0 的目標是：把「為什麼失敗」變成可看的資料，並讓部分成功的頁面不再整頁丟棄。實機 UI、真機 OCR/擦字/排版仍需安裝後用診斷頁與自測驗證。

---

## 功能

### 1. ZIP / CBZ 匯入
- 系統檔案選擇器（SAF），不要求儲存權限。
- 支援 `.zip`、`.cbz`，圖片格式 JPG / JPEG / PNG / WEBP。
- **自然排序**：1, 2, 3, 10（不是 1, 10, 2, 3），逐段比較路徑，`第1話/1.jpg … 第1話/10.jpg` 之後才接 `第2話`。
- 保留完整資料夾結構與原檔名（含中文、日文）；匯出時原樣重建。
- 安全解壓：拒絕 `../`、絕對路徑、NUL、磁碟代號；限制檔案數、單檔/總量、異常壓縮比；目標 canonical path 檢查。
- 解密失敗、損毀、沒有圖片的 ZIP 會給出明確錯誤。

### 2. 本機文字辨識（日文 → 繁體中文）
- DBNet（ResNet34 + DB head）文字行偵測 + 筆畫遮罩。
- 48px CTC OCR（NCNN，混合精度 fp16/fp32，arm64）。
- 同一對話框的文字行自動分組；保留原文、方向、座標、角度。
- 頁面資料落地成 `meta/*.json` + `meta/*.mask.png`，可區域重新辨識、重新排版，不用重跑偵測。
- 全部在本機 CPU 執行，不上傳圖片。

### 3. 翻譯（Venice AI / 自訂 OpenAI 相容）
- 預設端點：`https://api.venice.ai/api/v1`（`/chat/completions`）。
- 預設模型：`venice-uncensored-1-2`（以官方 `/models` 即時清單確認；清單中所有 `uncensored` 模型會置頂）。
- 可抓取模型清單、顯示輸入/輸出單價（Venice `model_spec.pricing`）、測試連線、顯示 token 用量與 `cost.usd`。
- API Key 由使用者輸入，存在 **Android Keystore AES/GCM** 加密區；不寫死在程式、不寫入日誌、不放在 URL。
- 自訂 OpenAI 相容端點：可填 base URL（自動接 `/chat/completions`）或完整端點；支援 HTTP 自架服務（會顯示明文警告）。
- 輸出語言可選：繁體中文（台灣，預設）／繁體中文（香港）／簡體中文；預設與驗收目標都是台灣繁體。
- 逐頁一次請求，使用固定 `<|n|>` 行 ID 對應原文/譯文；**嚴格驗證 ID 集合**：
  - 缺 ID → 該行保留原文，整頁標記失敗（不把半成品當成功）。
  - 重複 ID → 整頁保留原文，標記失敗（避免錯位）。
  - 多出未知 ID → 記錄警告，但不影響已對上的行。
- 翻譯規則以「忠實還原」為核心：成人語境、粗俗詞、擬聲詞不柔化、不刪減；保留人物語氣與稱呼。
- 失敗重試：429 / 5xx / 網路錯誤會退避重試（預設 2 次）；400/401/403/404 直接失敗。
- 暫停/續傳：每頁完成立刻原子寫入 `project.json`；中斷後重開 App 可按「繼續翻譯」。

### 4. 擦字與嵌字
- 兩種擦字模式：
  - **AI 背景修復**（AOT-GAN，預設）：重建文字下的線條與背景。
  - **快速擦字**（BoxFill）：以鄰近背景色平塗，最快。
- 失敗時保留原圖；部分對話框漏譯時，整頁保留原圖並標記失敗。
- Canvas 排版：自動字級、對話框內換行、垂直置中、直排/橫排、描邊、跟隨文字區角度、縱中橫（短 ASCII）。
- 手動修改譯文 →「儲存並重新嵌字」：只重跑擦字 + 排版，**不呼叫付費 API**。
- 局部重新辨識：對單一文字區的既有行框重跑 OCR，更新原文。

### 5. 匯出
- 輸出新的 ZIP，**不覆蓋原始檔**。
- 頁序、資料夾、檔名沿用原始 ZIP；已完成的頁替換成繁中版。
- 失敗/略過/未處理頁面直接複製原始壓縮條目（不重新編碼，畫質零損失）。
- 非圖片檔（txt、json…）原樣保留。
- 可查看完成/失敗/略過頁數；失敗頁在 App 內標紅並保留原因。

### 6. 介面
全繁體中文，瑤芯系列櫻花粉色系：
1. 首頁：匯入 ZIP、模型/API 狀態、最近專案。
2. 頁面預覽：3 欄縮圖、頁序、狀態篩選（全部/待翻/完成/失敗）。
3. 設定：API、Key、模型清單/價格、模型下載與 SHA-256、擦字/方向/描邊、重試次數。
4. 翻譯進度：百分比、目前頁、完成/失敗/略過/待處理、token/費用、錯誤紀錄、暫停/重試。
5. 單頁檢視：原文/繁中切換、尺寸、耗時、token、費用、編輯/重新翻譯。
6. 編輯譯文：逐區原文 + 譯文輸入、局部重新辨識、重新嵌字。
7. 匯出：統計、輸出說明、上次匯出結果。
8. 本機引擎自測：選一張圖跑 OCR→擦字→嵌字（固定模擬譯文，不呼叫 API）。

---

## 使用流程

1. 安裝 APK（見 `dist/`）。
2. 首次開啟 → 設定 →「下載模型（約 248 MB）」；等待進度與 SHA-256 校驗完成。
3. 設定 → 輸入 **Venice API Key** →「取得模型清單」→ 選模型（預設 `venice-uncensored-1-2`）→「測試連線」。
4. 首頁 →「選擇漫畫 ZIP / CBZ」。
5. 檢查頁面縮圖與順序 →「開始翻譯」。
6. 可隨時暫停；關閉 App 後再開，專案仍在，按「繼續翻譯」。
7. 失敗頁可單頁「重新翻譯」；譯文可在「編輯譯文」修改後重新嵌字（不再花 API）。
8. 「匯出」→ 選擇新 ZIP 位置。

> **建議先做本機自測**：設定 → 進階 → 本機引擎自測 → 選一張日文漫畫頁。
> 它不需要 API Key，能先確認這台手機的 NCNN／OCR／擦字／嵌字正常。

---

## 架構

```
app/                      Compose UI、ViewModel、專案/狀態、API 設定
 ├─ core/                 ZIP 匯入/匯出、自然排序、專案落地、翻譯 runner、analysis codec
 ├─ data/                 Settings + Keystore、模型 manifest/下載
 ├─ net/                  Venice/自訂端點的模型清單與連線測試
 └─ ui/                   Theme、6+ 畫面、縮圖快取
engine/                   Yakuyomi 翻譯引擎（Kotlin；本專案 fork/修改）
inference-core/           NCNN JNI（預編譯 .so）＋ DBNet 偵測＋分群＋模型下載
```

### 單頁流程

```
原圖 Bitmap
  → DBNet 偵測（文字行 + 筆畫遮罩）
  → OCR（48px CTC，逐行）
  → 分群（對話框）
  → LLM 翻譯（固定 <|n|> 行 ID；嚴格驗證）
  → 擦字（AOT-GAN 或 BoxFill，與翻譯並行）
  → Canvas 嵌字（直排/橫排/描邊/自動字級）
  → 儲存譯圖 + meta + mask（每頁立即落地）
```

### 專案資料夾

```
filesDir/projects/<id>/
  project.json         專案與逐頁狀態（tmp → fsync → rename）
  source.zip           匯入的原始 ZIP（只讀）
  original/000001.jpg  解壓後原圖
  translated/000001.jpg 翻譯後頁面
  meta/000001.json     文字區、原文/譯文、四邊形、方向、角度、onArt
  meta/000001.mask.png DBNet 筆畫遮罩（重新排版用）
```

---

## 建置

### 需求
- JDK 17
- Android SDK Platform 35、Build-Tools 35.0.0
- Gradle 8.9（repo 已附 wrapper）
- 不需要 NDK：`libyakuyomi_ncnn.so` 已放在
  `inference-core/src/main/jniLibs/arm64-v8a/`，ABI 為 arm64-v8a。

### 一般 x86_64 PC
```bash
# 建立 local.properties（不要提交）
echo "sdk.dir=/path/to/android-sdk" > local.properties
./gradlew :app:assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

### aarch64 手機 / proot Ubuntu（本專案的建置環境）
```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64
export ANDROID_HOME=/root/android-sdk
/opt/gradle/gradle-8.9/bin/gradle --no-daemon \
  -Pandroid.aapt2FromMavenOverride=/opt/aapt2/aapt2 \
  :app:assembleDebug
```
`android.aapt2FromMavenOverride` 是 arm64 專用（Termux aapt2 包裝），已在
`gradle.properties` 中註解；PC 不需要。

### 測試
```bash
./gradlew :app:testDebugUnitTest
```
測試報告：`app/build/reports/tests/testDebugUnitTest/index.html`。

---

## 隱私與安全
- 圖片、OCR、擦字、排版全部在本機；只有「原文/譯文」會送到你設定的 API。
- API Key 用 Android Keystore AES/GCM 加密；`allowBackup=false`。
- Key 不寫入程式碼、專案 JSON、log；錯誤訊息中的 key 會遮罩。
- 自訂 HTTP 端點會在 UI 顯示明文警告。
- App 只宣告 `INTERNET` 與 `ACCESS_NETWORK_STATE`；不需要儲存/相機/位置權限。
- 原始 ZIP 只讀；匯出到新檔。

---

## 已知限制
- **0.2.0 實機端到端尚未驗證**（安裝政策限制）；請依 `docs/MANUAL_TEST.md` 驗收，並用診斷頁回報。
- 一次只處理一頁（低並行）；翻譯期間建議保持前景。程序被殺後可續傳，但當前頁會重做。
- 不支援加密 ZIP、RAR、PDF、GIF；圖片上限 120 MP。
- 翻譯品質取決於模型；App 會把「漏譯/重複 ID/HTTP 錯誤」明確標成失敗，不會偽裝成功。
- 術語表、跨頁上下文、自動氣泡重繪尚未實作（第一版範圍）。
- 引擎的 `Renderer` 使用系統字型；不同裝置的字形可能略有差異。

---

## 授權
本專案與修改後的 Yakuyomi Engine 原始碼以 **GPL-3.0** 釋出，見 [`LICENSE`](LICENSE)。
第三方元件、模型與 `libyakuyomi_ncnn.so` 的來源與授權見 [`NOTICE.md`](NOTICE.md)。
引擎修改紀錄見 [`docs/UPSTREAM.md`](docs/UPSTREAM.md)。
