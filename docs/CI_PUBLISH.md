# 自動發布流程（GitHub Actions）

## 流程

1. 開發者把新版原始碼提交到 `apps/manga-translator/` 並推到 `main`。
2. `.github/workflows/publish-manga-translator.yml` 自動觸發：
   - 安裝 JDK 17 與 Android SDK 35
   - 從 Secret `MANGA_KEYSTORE_BASE64` 還原簽章金鑰到 `~/.android/debug.keystore`
   - 跑單元測試 → 建置 debug APK
   - 驗證版本號（`tools/ci_guard.py`）：只有 versionCode 高於 catalog 最新版才發布
   - `tools/publish.py` 建立 GitHub Release、上傳 APK、更新 `catalog.json`
3. 商店讀 `catalog.json`，使用者即可收到更新。

## 必要的 Secret

| 名稱 | 內容 |
|---|---|
| `MANGA_KEYSTORE_BASE64` | 漫畫翻譯簽章金鑰（`manga-debug.keystore`）的 base64 |

金鑰憑證 SHA-256 必須是 `e81d2eb2…`，否則已安裝的使用者無法覆蓋更新。

## 手動觸發

Actions → Publish Manga Translator → Run workflow → 可勾選 `force` 強制發布。

## 其他 App

之後要支援其他瑤芯 App，複製同一份 workflow，改 `paths`、`working-directory`、`APK` 路徑與對應的 Secret 名稱即可。
