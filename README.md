# R15-TV — 公開原始碼建置基準

這個分支把 R15-TV 的 GitHub Actions 改為使用可公開讀取的 [FongMi/TV](https://github.com/FongMi/TV) 原始碼，目的是先建立不需要私人原始碼 Token 的可重現 APK 建置流程。

> **目前定位：公開原始碼建置基準，不是完整的 TV-Taiwan 客製正式版。** 本分支以官方播放器功能為基礎；台灣繁體中文完整介面、AI 即時字幕及其他額外解碼器尚未在這個基準中實作或驗證。

## 直播源

APK **不內附直播源或播放清單**。官方 FongMi/TV 專案也說明應用程式本身不提供內容來源；安裝後由使用者自行設定外部配置即可。

## 原始碼與建置

- 公開原始碼：[FongMi/TV](https://github.com/FongMi/TV)
- 使用分支：`fongmi`
- 每次建置會解析該分支的實際 commit SHA，並記錄在 `SOURCE_REFS.json`、`BUILD_INFO.json` 及 release notes。
- 建置環境：JDK 21、Python 3.10、Android SDK API 37、Build Tools 36.0.0、NDK 28.2.13676358。
- 產出四個 APK：Android TV（Leanback）及手機（Mobile），各自提供 `arm64-v8a` 與 `armeabi-v7a` 版本。
- 建置流程會執行單元測試、檢查 APK 簽章、套件版本與 ABI，並產生 SHA-256 校驗碼。

## 如何編譯

1. 開啟 GitHub 的 **Actions**。
2. 選擇 **Build R15-TV APKs from public FongMi source**。
3. 按 **Run workflow**。測試分支先將 `publish_release` 保持為 `false`。
4. 建置完成後，從該次 workflow run 的 Artifacts 下載 `R15-TV-public-build-<run number>`，查看 APK 及建置資訊。

**不需要建立或提供私人原始碼 Token。** 工作流程會公開讀取 FongMi/TV 的指定分支，再固定到此次解析到的 commit 建置。

## 簽章設定

可先不設定簽章 Secrets 來測試編譯；這種情況會產生一次性的測試簽章，只上傳 Actions Artifact，不會發布 GitHub Release。每次測試使用的金鑰不同，所以測試 APK 之間不能直接覆蓋更新；重新安裝前可能需要先解除安裝上一個測試版本。

要發布可長期更新的正式 APK，請在 Repository → **Settings → Secrets and variables → Actions** 設定：

- `R15TV_KEYSTORE_B64`：你自己的 Android release keystore 之 Base64 內容。
- `R15TV_STORE_PASSWORD`：keystore 密碼；依目前上游 Gradle 設定，金鑰密碼也必須相同。
- `R15TV_KEY_ALIAS`：可選，預設為 `r15tv`。

請勿把 keystore、密碼或其他私密資料提交到 Git。若你使用不同密碼的 key entry，需先調整上游簽章設定；此工作流程目前依照上游設定要求相同密碼。

## 重要限制

- 目前 APK 保留上游的套件識別碼 `com.fongmi.android.tv` 與應用程式顯示名稱 `TV`，檔名使用 R15-TV。不同簽章的 APK 不能直接覆蓋原本由其他簽章簽署的同套件應用程式。
- 這個基準先驗證公開原始碼能否編譯；不代表台灣繁體中文、AI 即時字幕、專用品牌圖示或其他客製功能已完成。
- 發布時會附上上游 GPL-3.0 授權、此次來源 commit、建置資訊、版本修改 patch 與 APK SHA-256。
- 本倉庫現有建置腳本保留原有授權與來源聲明；FongMi/TV 應用程式原始碼依上游 GPL-3.0 授權。發行衍生 APK 時仍需遵循該授權及相依元件的條款。
