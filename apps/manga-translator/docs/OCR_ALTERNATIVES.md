# 第二套 OCR 引擎評估（0.2.0）

0.2.0 **沒有**直接替換 Yakuyomi 的 48px CTC OCR。先做了同一模型的多策略補救，並把第二引擎列為待實機 A/B 的選項。

## 已實作：同模型多策略（預設）

- 原偵測框（pad 0）、外擴 4/8/12px。
- 低信心/空讀時：反色（深底白字）、Otsu 二值化、反色+Otsu。
- 只接受「比原結果多 1–3 字且原結果是前/後綴」的候選，避免把正確標點/助詞改壞。
- 成本：只增加同一顆 CTC 的 forward，不需要下載第二個模型；BALANCED 對每行多跑 2–3 次，HIGH 才加整頁切片。
- 限制：同一模型的盲點仍在（手寫、極度變形、部分漢字誤讀）；無法保證比 0.1.0 的「字元準確率」更高，只確定減少了尾字漏讀且沒有把原本正確的結果改壞（桌面 probe）。

## 候選：ML Kit Text Recognition v2（Japanese）

官方文件（Android）：
<https://developers.google.com/ml-kit/vision/text-recognition/v2/android>

查證到的事實（2026-11）：

- 有 Japanese 專用模型：
  - bundled：`com.google.mlkit:text-recognition-japanese:16.0.1`，每個 script/ABI 約 +4 MB；
  - unbundled：`com.google.android.gms:play-services-mlkit-text-recognition-japanese:16.0.2`，APK 約 +260 KB，模型由 Google Play 服務首次使用時下載。
- minSdk 23。
- 回傳 `Text`/`Text.Line`/`Text.Element` 與 bounding box，可做為第二套 OCR。
- **官方頁面沒有保證日文直排（vertical）辨識**；漫畫直排是主要場景，必須實機 A/B 才能判斷。
- unbundled 版本依賴 Google Play 服務；部分中國版/無 GMS 裝置可能無法下載模型。

**結論：** 不在 0.2.0 直接加入。原因：
1. 沒有實機與人工標註，無法證明它比現有 CTC 好；使用者明確要求「不要直接假設第二套一定更好」。
2. 直排支援未經官方明示，貿然加入可能讓 APK 變大、首次使用多一次模型下載，卻沒有改善。
3. 目前先以同模型多策略 + 多階段偵測解決「尾字漏讀/局部漏抓」，成本最低。

若後續實機 A/B 顯示 CTC 在特定頁型（例如手寫/傾斜）明顯不足，建議：
- 先做 bundled Japanese 版（避免 GMS 下載失敗），以「只對 CTC 空讀/低信心行」的方式呼叫；
- 用同一組人工標註頁比較召回率、字元正確率、每頁時間、記憶體；
- 若沒有穩定勝出，就不加入。

## 候選：Tesseract4Android / PaddleOCR

- Tesseract：日文直排與漫畫字體表現通常不如專用 CTC；日文 traineddata 與執行檔會增加 APK/模型體積。
- PaddleOCR：可轉 NCNN，但需要新的偵測/辨識模型與前後處理，等於第二條完整管線；在手机上記憶體與速度風險高。
- 兩者都沒有在 0.2.0 整合；先保留 CTC，避免一次更換所有模型。

## 目前建議

1. 用 0.2.0 診斷頁確認是「偵測漏框」「OCR 空讀」「API 失敗」還是「排版/擦字」。
2. 只有當診斷顯示 CTC 在多數頁型都失敗時，才引入第二套 OCR；且先做離線 A/B，不直接上線。
