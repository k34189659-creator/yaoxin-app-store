# 瑤芯應用商店發布紀錄

## 0.2.0（manga-v0.2.0，已發布）

- 發布時間：2026-11-19（Asia/Taipei）
- versionName / versionCode：`0.2.0` / `2`
- Release：<https://github.com/k34189659-creator/yaoxin-app-store/releases/tag/manga-v0.2.0>
- 下載：<https://github.com/k34189659-creator/yaoxin-app-store/releases/download/manga-v0.2.0/yaoxin-manga-0.2.0.apk>
- 資產：`yaoxin-manga-0.2.0.apk`（19,970,093 bytes）
- SHA-256：`aac30f21ef25bedab452f201f589e0314e7bd7c166a78f628216721b1ad594ad`
- 簽章：Android Debug（憑證 `e81d2e…`，與 0.1.0 相同，可直接覆蓋更新）
- catalog：`latest.versionCode` 已更新為 2；`versions` 保留 0.1.0 與 0.2.0
- 已重新下載 asset 核對 SHA-256、versionCode、簽章，與 catalog 一致
- 使用者指示：**每次版本更新後都要上架商店**（本專案自此列為發布流程固定步驟）

## 0.1.0（manga-v0.1.0）

- 商店 App：**瑤芯漫畫翻譯**
- 套件：`tw.dafeiyu.mangatranslator`
- versionName / versionCode：`0.1.0` / `1`
- Release：<https://github.com/k34189659-creator/yaoxin-app-store/releases/tag/manga-v0.1.0>
- 下載：<https://github.com/k34189659-creator/yaoxin-app-store/releases/download/manga-v0.1.0/yaoxin-manga-0.1.0.apk>
- 資產：`yaoxin-manga-0.1.0.apk`（19,762,894 bytes）
- SHA-256：`f126d90efc3946cbd81c766162c153cc70d06b7bfcea6105e67aaea8f0595338`
- 簽章：Android Debug
  - 憑證 SHA-256：`e81d2eb27ec26af5d90151e065cab45a4efd20181977dab765ba84738a99d9e8`
- catalog：`https://api.github.com/repos/k34189659-creator/yaoxin-app-store/contents/catalog.json?ref=main`
  - 已新增 `apps[]` 條目，`tagPrefix: manga`、`latest.versionCode: 1`
- 發布工具：`yaoxin-store/publish.py`（dry-run → 使用者確認 → `--yes`）
- 商店端：理論上重新整理即可看到；實際商店 UI 尚未由助手操作驗證。
- 下載檔已重新下載並核對 SHA-256 與 catalog 一致。

## 簽章與後續更新（重要）

首次上架使用的是本機 Android Debug keystore（憑證 `e81d2e…`）。
商店的更新流程會比對簽章；**後續版本必須用同一把金鑰簽署**，否則 Android 會拒絕覆蓋安裝。

- 備份位置（不進 repo）：`/root/Documents/deepseek-harness/default-workspace/yaoxin-manga/keys/manga-debug.keystore`
- alias：`androiddebugkey`；storepass：`android`（Android debug key 的公開預設值）
- 若遺失此金鑰，使用者需先解除安裝舊版才能安裝新簽章的版本。

若日後要改成正式簽章，建議：
1. 產生專屬 release keystore 並妥善備份（不要提交）。
2. 提高 versionCode。
3. 以新金鑰簽署並在 changelog 說明「需解除安裝舊版重裝」。
4. 重新發布；不要用舊 debug 金鑰冒充正式版。

## 下一次發布

1. 修改 `app/build.gradle.kts` 的 `versionCode` / `versionName`，更新 `CHANGELOG.md` 與
   `docs/STORE_CHANGELOG.md`。
2. 重新建置 Debug APK（見 README）。
3. 更新 `yaoxin-store/config.json` 已經有 `tw.dafeiyu.mangatranslator` 條目（name/tagPrefix）。
4. Dry-run：
   ```bash
   cd /root/Documents/deepseek-harness/default-workspace/yaoxin-store
   python3 publish.py \
     --apk /root/Documents/deepseek-harness/default-workspace/yaoxin-manga/translator/dist/yaoxin-manga-translator-<ver>-debug.apk \
     --changelog /root/Documents/deepseek-harness/default-workspace/yaoxin-manga/translator/docs/STORE_CHANGELOG.md \
     --dry-run
   ```
5. 向使用者確認後加 `--yes` 發布。
6. 下載 asset 核對 SHA-256，確認 catalog `latest.versionCode` 已更新。

## 0.3.0（2026-11-19）

- Tag：`manga-v0.3.0`
- APK：`yaoxin-manga-0.3.0.apk`（35,305,513 bytes）
- SHA-256：`05d5f030522996ab6ba9ae2388ae9a5b2f930d1df687a74f130f63029dc624a9`
- 簽章：Android Debug（憑證 SHA-256 `e81d2eb27ec26af5d90151e065cab45a4efd20181977dab765ba84738a99d9e8`，與 0.1.0/0.2.0 相同）
- 下載：https://github.com/k34189659-creator/yaoxin-app-store/releases/download/manga-v0.3.0/yaoxin-manga-0.3.0.apk
- Release：https://github.com/k34189659-creator/yaoxin-app-store/releases/tag/manga-v0.3.0

## 0.3.1（2026-11-19）

- Tag：`manga-v0.3.1`
- APK：`yaoxin-manga-0.3.1.apk`（35,146,015 bytes）
- SHA-256：`01ebbafd48027f63ed451a118837fb35e4c653434d60ddc10436700daeece3b1`
- 簽章：Android Debug（憑證 SHA-256 `e81d2eb2…`，與 0.1.0/0.2.0/0.3.0 相同）
- 下載：https://github.com/k34189659-creator/yaoxin-app-store/releases/download/manga-v0.3.1/yaoxin-manga-0.3.1.apk
- Release：https://github.com/k34189659-creator/yaoxin-app-store/releases/tag/manga-v0.3.1
