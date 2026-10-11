# R15 TV — 即時字幕新研究方向

本分支開始一項新的研究：為 Android TV 播放器整合即時語音辨識字幕，並研究將字幕翻譯為繁體中文的可行性。

## 研究目標

- 優先驗證在 Android TV／Google TV 實體裝置上的可行性。
- 研究播放中的音訊如何安全、低延遲地送入語音辨識流程。
- 支援來源語言自動辨識，目標輸出繁體中文字幕。
- 保留 Android TV 遙控器導覽與 16:9 電視介面。
- 優先考慮本機辨識與翻譯；若雲端方案品質或速度較好，須明確列出網路、費用及隱私取捨。
- 不把尚未驗證的功能描述成已可用。

## 開源研究候選

| 專案 | 可研究內容 | 目前定位 |
|---|---|---|
| [Hearth](https://github.com/Sal7one/Hearth) | Android 即時字幕／翻譯、TV 介面與辨識引擎整合 | 優先檢查其 TV 支援及音訊擷取的實際限制 |
| [SUBRIMA](https://github.com/y-haviv/android-subtitle-overlay) | 音訊擷取、Vosk 串流辨識、翻譯及字幕覆蓋 | 參考辨識與翻譯流程；TV 適配需驗證 |
| [OpenTV](https://github.com/opentvproject/opentv) | Android TV 播放器架構與遙控器介面 | 評估是否適合作為播放器基底 |
| [Android TV Live Captions](https://github.com/web-dev-akash/android-tv-live-captions) | Android TV 即時語音轉文字 | 研究音訊串流與字幕呈現方式 |

以上為候選清單，不代表已完成建置、已適用本專案，或已通過實體電視驗證。採用任何程式碼前，必須確認其最新原始碼、維護狀態、授權條款、Android 版本要求、語言支援與建置方式。

## 技術決策原則

1. **先驗證音訊來源。** 優先研究在同一播放器內取得解碼音訊；跨 App 音訊擷取可能受 Android 播放音訊用途及擷取政策限制。
2. **分離辨識與翻譯。** 語音轉文字、文字分段／時間戳、翻譯及字幕繪製採分層設計，方便獨立測試。
3. **比較本機與雲端。** 至少評估辨識品質、延遲、CPU／記憶體、語言覆蓋、網路依賴與費用。
4. **先做最小可行原型。** 先在固定測試影音上產生即時字幕，再加入翻譯、語言自動偵測、字幕樣式與播放控制。
5. **保持可重現建置。** 對上游版本／commit、依賴、授權、建置結果及測試裝置作紀錄；未成功的建置要保留錯誤日誌。

## 驗收項目

- [ ] 可以在 Android TV／Google TV 上安裝並由遙控器操作
- [ ] 能由目標播放器取得播放音訊
- [ ] 能持續產生分段字幕，不需等影片結束
- [ ] 可將支援語言翻譯為繁體中文
- [ ] 字幕不遮擋必要的播放控制，且可調整位置、大小及背景
- [ ] 測量端到端延遲與資源使用情況
- [ ] 確認離線功能、雲端需求、隱私與成本
- [ ] GitHub Actions 可重現建置並保存 APK／日誌作為測試成果

## 範圍

這是新的研究分支；不代表既有 FongMi/TV 建置流程已成功，也不直接刪除或重寫原分支、上游致謝或授權檔。所有實作與授權確認完成前，請勿標記為正式版。


## 目前技術決策（2026-10-11）

- 第一版選定 App 內 AudioPlaybackCapture（僅自己的 UID）＋Whisper Base 多語＋ML Kit 本機英翻中＋OpenCC S2TWP；模型在 App 內下載，不包進 APK。需 RECORD_AUDIO 與 MediaProjection 授權。
- 新增 [MPV 原生音訊接入與整合計畫](MPV-INTEGRATION-PLAN.md) 與 [Audio Capture POC 建置工作流](../.github/workflows/build-audio-capture-poc.yml)。若 TV 不提供非靜音擷取，再回退到 native PCM tap。
- 使用 SUBRIMA 作為語音辨識與字幕流程的程式碼參考，不要求使用者安裝測試。
- OpenTV 只參考 TV 操作與建置流程，不作為播放器底層，因其使用 Media3/ExoPlayer，而 R15 TV 保留 MPV 是既有要求。
- 特別驗證繁體中文輸出：Hearth 與 SUBRIMA 的目前文件／語言路由都沒有證實能在所有翻譯模式明確強制輸出繁體中文。
- 已新增候選建置工作流：`.github/workflows/research-candidate-validation.yml`。它固定上游 commit 並產生建置記錄；工作流失敗若發生在環境初始化步驟，不能當成候選源碼的編譯失敗。
- 實機驗收要在 TCL Google TV Android 12 上進行，特別記錄音訊擷取權限、字幕延遲、語言準確度及資源使用量。


## F1 直播專用需求（2026-10-11）

新需求：即時辨識 F1 英文直播解說並翻譯為臺灣繁體中文字幕，優先免費、離線、無付費 API。

- 詳細方案與驗收標準：[F1 直播免費離線字幕方案](F1-OFFLINE-LIVE-TRANSLATION.md)。
- 第一版 ASR 基準：Whisper.cpp 多語 `ggml-base.bin`，不使用 English-only 的 `base.en`。先完成 Base，再依 Google TV 實測效能評估 Whisper Small Q5_1 量化版。
- 翻譯需使用本機 English→Chinese 模型；OpenCC `s2twp` 僅負責轉成臺灣正體與地區用詞，不取代翻譯模型。
- GitHub Actions [第 43 輪](https://github.com/DickyR15/R15-TV/actions/runs/38135426602) 已成功建置 Whisper.cpp JNI 與 Android TV debug APK，並通過 Whisper／OpenCC 資產檢查；[POC-43 Artifact](https://github.com/DickyR15/R15-TV/actions/runs/38135426602/artifacts/11701037822)。目前仍待 TV 實機驗證音訊擷取、翻譯品質與延遲。
- `setAudioOutputListener(effect::applyAudioEffect)` 是音效通知，不可當作 PCM callback。若 Android 擷取在 TV 的 MPV 路徑下回傳靜音，native MPV PCM tap 必須先確認能安全分流。
- 已將 Whisper Base 多語模型的 App 內下載器與 JNI wrapper 加入原型；後續依實機效能再決定是否加入 Whisper Small Q5_1 量化版，詳見 [WHISPER-BASE-MULTILINGUAL-PLAN.md](WHISPER-BASE-MULTILINGUAL-PLAN.md)。
- 若 TV SoC 無法維持可接受延遲，改評估本機區域網路 PC 執行模型；仍不以雲端 API 為必要條件。
