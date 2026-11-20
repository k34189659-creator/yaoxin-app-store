# NOTICE / 第三方元件與授權

「瑤芯漫畫翻譯」（tw.dafeiyu.mangatranslator）以 **GNU General Public License v3.0** 釋出。
完整對應原始碼隨交付提供；本專案是獨立 App，沒有修改瑤芯系列其他 App。

## 引擎與原始碼

- **Yakuyomi Engine** — <https://github.com/joyeli/yakuyomi-engine>
  - 版權：Copyright (C) joyeli 與 Yakuyomi contributors
  - 授權：GPL-3.0
  - 本專案使用/修改的原始碼位於 `engine/`、`inference-core/`，對應 commit
    `883737078aecb92ed796efac571bf066e217b1ab`（Yakuyomi v0.23.0 所用的引擎版本）。
  - 修改內容詳見 `docs/UPSTREAM.md`。
- **libyakuyomi_ncnn.so** — 從 Yakuyomi v0.23.0 官方 arm64 APK
  （`app-arm64-v8a-release.apk`）取出，僅作為 ABI 相符的預編譯原生層使用。
  它包含 NCNN 與 Yakuyomi 的 JNI 程式；授權 GPL-3.0。若要自行重建，請用上游
  `inference-core` + NDK 28.2 編譯。

## 模型（執行時下載，不打包在 APK）

- DBNet 文字偵測、48px CTC OCR、AOT-GAN 圖片修復權重：來自
  [manga-image-translator](https://github.com/zyddnys/manga-image-translator)，GPL-3.0。
- 下載網址與 SHA-256 見 `app/src/main/assets/models.json`（取自上游 `models-v5` manifest，
  本 App 只下載 detector / OCR / inpainter 共 7 個檔，約 248 MB）。
- 夜讀人物分割模型（charseg）授權不同（AGPL / research-only），本 App **不下載、不使用**。

## 其他程式庫

- **NCNN** — BSD-3-Clause，Tencent
- **Kotlin / kotlinx.coroutines** — Apache-2.0
- **AndroidX / Jetpack Compose** — Apache-2.0
- **OkHttp / Okio** — Apache-2.0
- **org.json** — JSON License（僅測試使用；Android 平台內建實作）

## 字型

不打包字型；嵌字使用 Android 系統預設 CJK 字型。
