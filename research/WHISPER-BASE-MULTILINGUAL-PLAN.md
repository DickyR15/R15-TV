# Whisper.cpp Base 多語模型：R15 TV 整合設計

**需求來源：** 在 R15 TV 的語音字幕選項中提供 Whisper Base 多語模型，讓使用者可自行點選下載，不把約 148 MB 的模型塞進 APK。  
**狀態：** 設計與來源核對；尚未整合 Whisper.cpp native runtime、模型下載器或實機效能測試。當前 Audio Capture POC 先用較輕的 sherpa-onnx 中英串流模型驗證音訊擷取與字幕管線；這不能視為已實作 Whisper Base。

## 官方來源

- Whisper.cpp 官方專案：<https://github.com/ggml-org/whisper.cpp>
- 官方 Android Java 範例：<https://github.com/ggml-org/whisper.cpp/tree/master/examples/whisper.android.java>
- 官方模型下載腳本：<https://github.com/ggml-org/whisper.cpp/blob/master/models/download-ggml-model.sh>
- 官方 Android 範例 README 建議 Android 裝置先測 tiny 或 base 模型。

官方下載腳本將多語 Base 模型命名為 `ggml-base.bin`，來源是 Hugging Face 上的 `ggerganov/whisper.cpp` 模型倉庫，下載地址為：

`https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.bin`

使用 `base`，不要誤選 `base.en`，後者是 English-only 模型。標準 `ggml-base.bin` 是本需求的多語模型。官方另有 `base-q5_1`、`base-q8_0` 等量化版本；先保留標準 Base 作為明確基準，之後才比較量化版本的速度、記憶體和識別品質。

## 使用者介面需求

在 R15 TV 的 AI 字幕設定加入獨立的「語音辨識模型」欄位，不要把模型選擇和辨識語言混成同一個選項：

- **快速串流（sherpa-onnx 中英）**：小型模型可隨測試 APK 先行驗證，偏向即時性。
- **Whisper Base 多語**：顯示模型大小、下載進度、已下載狀態和「下載／重新下載／刪除模型」操作。
- **辨識語言**：提供自動偵測或指定語言。多語 Base 不能以固定 English-only 的 JNI 設定執行，應把選擇傳到 whisper.cpp，並預設自動偵測。
- 首次下載需網路；下載完成後模型檔保存在 App 私有儲存空間，推論不需要付費 API。
- 下載先寫入 `.part` 暫存檔，確認 HTTP 狀態及下載完成後再原子改名；中斷／失敗不能把半套模型標成已安裝。下載完成後嘗試初始化模型，成功才顯示可用。
- 模型沒有預先包含在 APK 中，避免增加約 148 MB 的 APK 體積。

## Native 整合方向

官方 Android Java 範例已展示 JNI 呼叫與 `WhisperContext` 包裝，但它是示範 App，不可整個範例原封不動搬入 TV 專案。整合前應固定 Whisper.cpp commit、把其 CMake/native library 建置納入 GitHub Actions，並只帶入所需 JNI wrapper／原始碼及必要授權聲明。

現有字幕音訊流程能產生 16 kHz 單聲道 float PCM。Whisper.cpp 的示範 JNI 支援把 float 音訊資料傳入推論，因此可以在不更動 MPV 的前提下，共用目前的音訊擷取與 PCM 佇列；但 Whisper Base 本身不是低延遲串流引擎，需要額外做音訊視窗與結果去重。

## 建議推論流程

1. 從 App 內音訊擷取取得 PCM，重取樣為 16 kHz 單聲道。
2. Whisper Base 運行在獨立工作執行緒，不可阻塞 AudioRecord 讀取或播放主線程。
3. 先量測 8–12 秒音訊視窗、適度重疊及其處理時間；精確視窗需依電視實機 benchmark 調整。
4. 以時間戳／文字重疊去重，避免每個視窗都重複輸出上一段字幕；限制佇列容量，若推論跟不上要顯示狀態，不能無限制累積延遲。
5. 對穩定的辨識句段做本機英文→中文翻譯，再以 OpenCC `s2twp` 轉成臺灣繁體與常用詞；OpenCC 不是翻譯引擎。
6. 如果實機顯示 Whisper Base 無法追上直播，保留快速串流模型作為可選 fallback，讓使用者能在設定中切換。

## 驗收條件

- [ ] TV 端可以從 AI 字幕設定下載／刪除 `ggml-base.bin`，下載失敗可以重試。
- [ ] 模型可成功由 whisper.cpp 初始化；APK 不需要內含模型檔。
- [ ] 能選擇自動偵測或指定辨識語言，多語模型不是寫死 English-only。
- [ ] 短英文測試片段輸出合理文字，接著能把穩定句段翻譯為臺灣繁體中文。
- [ ] 在 F1 英文直播上，量測字幕延遲中位數、95 百分位、模型載入時間、RAM、CPU／溫度與音訊是否卡頓。
- [ ] 推論慢於直播時，緩衝區不持續增加；可以切換到較快的串流引擎。
- [ ] Whisper.cpp 原始碼、GGML 模型及其他依賴的授權聲明分別核對。

這是下一階段實作規格。當前的 Audio Capture POC 仍需先建置成功並在 Google TV 實測擷取聲音；尚未宣稱 Whisper Base 已在 R15 TV 內可用。
