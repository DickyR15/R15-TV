# R15 TV 即時繁體字幕 POC：最短測試步驟

> 這是研究分支的實驗測試版，不是正式發行版。Whisper Base Subtitle POC-43 已成功建置並通過 APK 資產檢查，但尚未在 Google TV 實機驗收音訊擷取與字幕延遲。

## 安裝與第一次設定

1. 直接下載 [R15 TV Whisper Base Subtitle POC-43 ZIP（約 76 MB）](https://github.com/DickyR15/R15-TV/actions/runs/38135426602/artifacts/11701037822)；也可以先看[第 43 輪建置紀錄](https://github.com/DickyR15/R15-TV/actions/runs/38135426602)。若連結過期，請從 [Audio Capture POC 工作流](https://github.com/DickyR15/R15-TV/actions/workflows/build-audio-capture-poc.yml)下載最新成功的 Artifact。
2. 解壓縮後安裝 R15 TV-POC-leanback-arm64-v8a.apk。測試版套件名稱應為 com.dicky.r15tv.poc、顯示名稱為「R15 TV 字幕測試」，不會覆蓋既有 R15 TV 正式 App。若最新工作流失敗，先不要安裝。
3. 在測試版內重新載入 M3U／M3U8 播放清單；由於這是獨立測試 App，原 App 的清單與設定不會自動複製過來。播放器仍保留 MPV。
4. 播放英文 F1 直播或一般英文影片，在畫面底部字幕面板先按 **「下載 Whisper Base 多語模型（約 148 MB）」**。下載完成前不要啟動字幕。模型權重不在 APK 裡，而是直接下載到 App 私有儲存空間；APK 內已驗證包含 `libr15whisper.so`，沒有把 Base 模型權重打包進去。
5. 第一次翻譯時，Google ML Kit 也會下載英翻中模型。第一次下載需要網路；模型就緒後語音辨識與翻譯設計為本機執行，不需 API 金鑰或付費雲端翻譯。
6. 按 **「啟動即時字幕測試」**，允許錄音權限及 Android 系統音訊擷取。MediaProjection 是擷取工作階段的系統授權，重新啟動擷取時可能再次詢問。

## 怎樣判斷測試結果？

- 音量數值會隨播放聲音變化，表示可能擷取到音訊；還必須看是否有英文辨識文字，再觀察繁體中文字幕。
- 若長時間貼近 -120 dBFS、完全沒有英文文字，先記下狀態訊息；這時不要急著換模型，應先調查電視韌體是否允許擷取 MPV 音訊。
- 若英文文字出現、中文未出現，查看 ML Kit 翻譯模型是否正在下載或報錯。
- 若字幕明顯落後直播、音訊卡頓或電視變慢，記錄延遲、運作時間與狀態；這些數據會用來決定是否要調整 Base 視窗或之後比較 Small 量化版。
- 測試完成按同一個按鈕停止字幕；主播放音量、聲道與播放流暢度不應受到明顯影響。

## 重要限制

目前的 POC 先使用多語 Whisper Base；Whisper Small 量化版是依據實機效能與準確率結果再決定，不會先行塞入第一版。成功建置 APK 仍不等於已完成 Google TV 實機驗收；音訊擷取、延遲、翻譯品質及長時間穩定性都需要在電視上測試。
