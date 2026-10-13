# R15 TV：Whisper Base 多語模型實作計畫

**預設基準：Whisper Base 多語模型 + 固定來源語言 + Google ML Kit 裝置端翻譯 + OpenCC S2TWP。** 另加入 Caption Action 使用的 whisper.cpp Tiny Q5_1 權重做可選比較；Base 保留作為精度基準，Tiny 作為較低資源負擔的候選，實際效能與品質仍需在 Google TV 實測。

## 目前實作狀態

- 已加入 Whisper.cpp JNI wrapper，建置流程固定上游 commit 7e26d3b0d5bced6c8217ef09710ec2f969001241。
- 已加入 App 內 Whisper Base 模型下載器，來源為官方轉換模型倉庫的 ggml-base.bin；模型權重不包進 APK。
- 播放音訊仍由既有 R15 TV 播放流程擷取，MPV、M3U／M3U8 不更換；ASR 只在背景工作執行緒執行。
- 翻譯維持 Google ML Kit 裝置端 English → Chinese，接著由 OpenCC s2twp 轉為臺灣繁體與地區詞。
- GitHub Actions [第 43 輪](https://github.com/DickyR15/R15-TV/actions/runs/38135426602) 已成功完成 native 編譯、APK 封裝，並通過 Whisper JNI 與 OpenCC 資產檢查；[POC-43 測試包](https://github.com/DickyR15/R15-TV/actions/runs/38135426602/artifacts/11701037822)。**目前沒有證據證明已在 TCL Google TV 上成功擷取 MPV 音訊或達到可接受延遲。**

## 官方來源與模型選擇

- Whisper.cpp 官方專案：<https://github.com/ggml-org/whisper.cpp>
- 官方 Android Java 範例：<https://github.com/ggml-org/whisper.cpp/tree/master/examples/whisper.android.java>
- 官方模型下載腳本：<https://github.com/ggml-org/whisper.cpp/blob/master/models/download-ggml-model.sh>
- 官方模型文件：<https://github.com/ggml-org/whisper.cpp/blob/master/models/README.md>
- 第一版模型：ggml-base.bin，即 **多語 Base**，不是 English-only 的 ggml-base.en.bin。
- 應用內模型下載網址：<https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.bin>

第一版以 F1 英文解說為主要場景，所以 Whisper 推論先指定英文辨識，後續再評估是否要將語言自動偵測做成 UI 選項。這與選用多語權重並不衝突；未來可以沿用同一個 Base 權重辨識其他語言。

## App 內模型管理

播放畫面字幕面板提供 Whisper Base 多語模型的下載／刪除操作：

1. 第一次按下載時連接官方模型倉庫，顯示下載進度。
2. 模型下載到 App 私有目錄，不放進 APK，也不需要手動複製模型檔。
3. 使用 .part 暫存檔寫入；下載失敗或檔案不完整不會被當成已完成模型。
4. 下載完成後才可啟動字幕。真正的模型載入發生在啟動辨識時；若載入失敗，可刪除模型再下載。
5. Whisper Base 與 ML Kit 翻譯模型各自下載。兩者第一次下載需要網路；下載完成後，辨識與翻譯預期在本機執行，不需付費 API。

## 辨識與字幕管線

1. AudioPlaybackCapture 擷取同一個 R15 TV UID 的播放音訊副本，維持正常播放聲音。
2. PCM 轉成單聲道 16 kHz float，送入有界佇列，不在 AudioRecord 執行緒做推論。
3. Whisper Base 在獨立 worker 上處理重疊滑動音訊視窗；第一版使用約 6 秒起始視窗，每約 3 秒更新一次，最後視窗上限約 10 秒。
4. 以相鄰辨識視窗的文字重疊去重，再把新句段交給 ML Kit 英翻中。
5. ML Kit 結果由 OpenCC s2twp 轉成臺灣正體，再顯示於 R15 TV 播放畫面內。

這些視窗間隔是原型初始參數，不是實機效能承諾。Whisper Base 為非串流推論；若模型處理速度慢於直播，還需調整視窗、執行緒數、字幕去重或緩衝區策略。

## 後續升級：Whisper Small 量化版

**暫時不把 Small 放進第一版。** 先測 Base 的辨識準確率、端到端延遲、模型載入時間、峰值 RAM、CPU 與長時間熱降頻。若 Base 不夠準確，且 TV 資源足以容納更大權重，再嘗試官方支援的多語量化模型，例如 ggml-small-q5_1.bin；小型量化權重不保證在電視上更快，必須量測後再決策。保留 Base 作為可回復的基準。

## 驗收條件

- [ ] 可直接在字幕面板下載 Whisper Base，顯示進度、完成狀態與失敗訊息。
- [ ] APK 不含 Whisper 模型權重；下載後能成功載入 ggml-base.bin。
- [ ] 可從 MPV 播放音訊取得非靜音 PCM，不干擾主音訊。
- [ ] 能持續辨識英文 F1 解說，重疊視窗不造成字幕大量重複。
- [ ] ML Kit 能完成英翻中，OpenCC 產出臺灣繁體。
- [ ] 在 Google TV 上量測中位延遲、95 百分位延遲、峰值 RAM、CPU、溫度及音訊是否卡頓。
- [ ] 只有實測顯示值得升級時，才加入 Whisper Small Q5_1 量化版的下載與選擇 UI。

第三方原始碼、模型權重與翻譯 SDK 採各自授權及聲明；Whisper.cpp 的 MIT 程式碼授權不應被當作模型權重條款的替代。
