# R15-TV — 台灣繁體中文電視版

這是個人維護的 Android TV 播放器建置／客製化專案，目標是建立可重現的 APK 建置流程，並逐步整合台灣繁體中文介面、AI 即時字幕與字幕外觀調整。

> **目前狀態：建置骨架已建立；尚未完成可在此倉庫獨立執行的完整建置。**  
> 官方 TV-AutoBuild 的公開倉庫主要提供建置流程，部分來源倉庫與發布簽章金鑰需要額外權限。請勿把任何 token、私鑰或 keystore 密碼提交到 Git。

## 目標功能

- 全介面台灣繁體中文用語（例如「壁紙」→「桌布」、「普通話」顯示為「中文」）。
- 移除不需要的第三方修改聲明。
- AI 即時字幕：位置、大小、文字顏色、描邊／背景與分段模式可調。
- 啟動狀態提示不遮擋畫面；串流閒置指示不應長時間停留。
- 保留 M3U／直播播放與原有播放器功能。
- GitHub Actions 建置 ARMv7 Leanback APK，並產生校驗碼。

## 官方來源與版本基準

- 官方建置流程：[iptvorganization/TV-AutoBuild](https://github.com/iptvorganization/TV-AutoBuild)
- 官方版本參考：[autobuild-170](https://github.com/iptvorganization/TV-AutoBuild/releases/tag/autobuild-170)
- 該版 TV 原始碼提交：`94118276821ecbec16cf26a6dd47729e6e346792`
- 該版 media 原始碼提交：`f35d04172f570aed7c09ed9c5d0c0cd5c11f0615`
- 該版 VividLib 原始碼提交：`10a5c7140a15f179a7d94fbff76f9176063f7a97`
- 該版 sherpa-onnx 原始碼提交：`40b75e98a0cd5b3f961e73ae158305b3447b5ebb`

官方 AutoBuild 的 workflow 會透過 `PRIVATE_SOURCE_TOKEN` 取回部分來源，因此不能只複製公開 workflow 就視為完成。先確認授權和來源權限，再接入完整編譯。

## 建置前需要的 GitHub Secrets

只有在確定有權存取來源並採用自己的發行簽章後，才設定以下 Secrets：

- `PRIVATE_SOURCE_TOKEN`：可讀取所需來源倉庫的 token。
- `R15TV_KEYSTORE_B64`：個人發行 keystore 的 Base64 內容。
- `R15TV_STORE_PASSWORD`
- `R15TV_KEY_PASSWORD`

**不要使用或複製官方的私人簽章金鑰；也不要將秘密寫入檔案或 commit。** 尚未配置所需 Secrets 前，workflow 應明確停止並提示原因。

## 版本原則

1. 先以官方原始碼鎖定版本建立可重現的乾淨基準。
2. 每次只合併一組修改，先跑單元測試及 APK 簽章驗證。
3. 再由使用者在雷電模擬器實測：啟動、直播播放、字幕啟動、Bing 翻譯、字幕位置與分段。
4. 不把「ZIP／簽章檢查通過」宣稱為「已經實機測試」。

## 授權

此倉庫的建置腳本與本專案新增文件依 MIT License 提供；上游 TV、media、VividLib、sherpa-onnx 及其依賴仍各自依其原始授權條款。請在發佈 APK 前檢查所有上游授權與第三方元件要求。
