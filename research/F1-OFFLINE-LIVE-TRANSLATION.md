# F1 直播：免費離線即時字幕與繁體中文翻譯

**目標：** 在 R15 TV 播放 F1 直播時，即時辨識英文解說並翻譯成臺灣繁體中文字幕；不依賴付費雲端 API，模型下載後盡量全程離線。

## 建議方案

### 第一階段：英文 ASR + 離線翻譯 + 臺灣正體字詞轉換

1. **音訊來源：優先從 R15 TV 自己的 MPV 播放管線取得 PCM。** 這樣不必依賴別的 App 是否允許 Android `AudioPlaybackCapture`。先確認目前的 MPV wrapper 是否公開 PCM/audio tap；現有 `setAudioOutputListener(effect::applyAudioEffect)` 是一個調查線索，但還不能證明可以安全地分流 PCM 給 ASR。
2. **英文語音辨識：先評估 [whisper.cpp](https://github.com/ggml-org/whisper.cpp)。** 它使用 MIT 授權，支援 Android，提供連續取樣的示範流程。由於 F1 國際轉播解說通常以英文為主，先測 `base.en` 量化模型；若速度與 RAM 允許，再比較 `small.en`。Android 上模型越大不代表能維持即時，必須以實際 SoC 跑測試。
3. **離線翻譯：選可本機執行、明確支援 English → Chinese 的翻譯模型。** 翻譯需在穩定句段完成後執行，不要每來一個字就重新翻譯。先比較字幕品質、延遲、RAM／CPU 與模型授權；不採用需要 API 金鑰或按量付費的翻譯服務。
4. **臺灣繁體中文輸出：使用 [OpenCC](https://github.com/BYVoid/OpenCC) 的 `s2twp`／臺灣正體流程做字形與常用詞轉換。** OpenCC 是離線簡繁／地區詞轉換，不是英翻中的翻譯器；必須先有翻譯模型，再做字詞轉換。
5. **F1 詞庫：** 為車手、車隊、賽道與常用戰術詞建立可維護的字典／譯名表，例如 Leclerc、Verstappen、Norris、Piastri、undercut、overcut、tyre degradation、DRS、Safety Car、Virtual Safety Car、track limits、box、pit wall。只能用於術語校正，不應盲目替換一般句子中的相似字串。

## 離線／免費的界線

- **免費指不使用付費雲端 ASR／翻譯 API。** 公開模型的下載與本機推論通常不收 API 費，但仍可能有首次下載、儲存空間、CPU／RAM 及耗電成本。
- 模型權重與原始碼是不同授權項目；發佈 APK 前要逐一確認 Whisper 程式碼、翻譯模型權重、OpenCC 與其他依賴的授權及聲明要求。
- 完全離線模式不能假設一開箱就可用。需要先將語音模型、翻譯模型和詞庫安裝／匯入裝置。
- 不建議把固定雲端 API 當主方案：它可能要金鑰或費用，也會將聲音／文字送出裝置。

## 即時延遲與音訊流程

建議概念流程：

```text
MPV decoded PCM
  → mono / 16 kHz resampling
  → short rolling audio buffer + VAD
  → English ASR (stable interim/final segments)
  → sentence/phrase segmentation
  → offline English→Chinese translation
  → F1 term correction
  → OpenCC Taiwan Traditional conversion
  → TV subtitle overlay
```

先以 2–4 秒音訊視窗與少量重疊做原型，對已穩定的句段才送翻譯；字幕要支援更正，以免同一句的中途辨識結果重複顯示。具體視窗長度和延遲應經測試調整。第一個工程目標是 **端到端延遲中位數不超過 5 秒、95 百分位不超過 8 秒**；這是驗收目標，不是預先承諾可達成的效能。

## TCL Google TV / Android TV 的效能風險

- 電視 SoC 可能無法同時快速執行 ASR 和翻譯模型；不得只靠桌面 PC 的 benchmark 推估電視效能。
- 先測 `base.en` 與最小可用翻譯模型，紀錄辨識即時因子、字幕延遲、CPU、RAM、掉字、重複句與長時間熱降頻。
- 若電視端無法持續趕上直播，第二選擇是同一區域網路內的本機 PC 執行免費模型，TV 只接收字幕。此方案仍可避免雲端費用，但需有一台觀看時持續運行的電腦。
- 若 F1 是在另一個 App 播放，Android `AudioPlaybackCapture` 是否有效取決於系統版本、來源 App 的 manifest／capture policy 及音訊用途；不能保證每個播放器都能被擷取。自有 R15 TV 播放管線較適合作為整合點。

## 驗收清單

- [ ] 在 R15 TV 的 MPV 播放 F1 測試片段時取得正確 PCM；不影響主音訊、聲道與音量。
- [ ] 在英文評論與較吵雜的 team radio 中持續產生字幕。
- [ ] 翻譯成自然、可讀的臺灣繁體中文；OpenCC 不應被誤當成翻譯。
- [ ] F1 車手、車隊、輪胎、進站策略與賽事術語不頻繁誤認。
- [ ] 顯示中英文字幕切換、開關字幕、字幕大小／底色，並可用電視遙控器操作。
- [ ] 模型下載完成後可不連網使用；執行期間不呼叫付費 API。
- [ ] 長時間測試不持續積累音訊緩衝，不嚴重掉幀或讓聲音卡頓。
- [ ] 產生建置日誌、APK SHA-256、依賴／模型版本與授權清單。

## 原始資料與專案

- whisper.cpp（MIT、Android、stream 範例）：<https://github.com/ggml-org/whisper.cpp>
- whisper.cpp Android 範例（README 建議 Android 先試 tiny/base）：<https://github.com/ggml-org/whisper.cpp/tree/master/examples/whisper.android>
- OpenCC（Apache-2.0、支援臺灣地區詞轉換）：<https://github.com/BYVoid/OpenCC>
- Argos Translate（MIT／CC0 選擇授權的離線翻譯框架；以 Python 為主，較適合作為比較基準，不直接假設可輕鬆整合進 Android APK）：<https://github.com/argosopentech/argos-translate>
- Hearth（可參考 Android TV 字幕 UI、裝置音訊擷取與本機翻譯管線）：<https://github.com/Sal7one/Hearth>

此文件是設計與測試計畫；尚未表示 R15 TV 已整合或已成功建置離線 F1 翻譯字幕。
