# R15 TV — 即時字幕研究

本專案研究 Android TV／Google TV 播放器整合即時字幕的可行性，主要方向為即時語音辨識、來源語言自動偵測，以及翻譯成繁體中文字幕。

## 目前研究狀態

- 新研究分支：[`research/realtime-subtitles-tv`](https://github.com/DickyR15/R15-TV/tree/research/realtime-subtitles-tv)
- 研究計畫：[即時字幕研究計畫](https://github.com/DickyR15/R15-TV/blob/research/realtime-subtitles-tv/research/REALTIME-SUBTITLES-PLAN.md)
- 狀態：研究／驗證階段，尚未宣稱已完成可用的即時翻譯字幕播放器。
- 方向：優先驗證 Android TV 實體裝置、播放器內部音訊擷取、低延遲字幕與繁體中文翻譯。
- 舊有 FongMi/TV 建置方向暫不作為新研究主線；舊分支與 Git 歷史保留供追溯。

## 候選分析與決策

- [MPV 原生音訊接入與整合計畫](research/MPV-INTEGRATION-PLAN.md)
- [Whisper Base 多語模型下載與整合計畫](research/WHISPER-BASE-MULTILINGUAL-PLAN.md)
- [F1 直播免費離線字幕方案](research/F1-OFFLINE-LIVE-TRANSLATION.md)
- [免費本機翻譯與臺灣繁體轉換方案](research/LOCAL-TRANSLATION-ENGINE.md)
- [即時字幕 POC 最短測試步驟](research/R15-TV-AUDIO-CAPTURE-POC-QUICKSTART.md)
- [TCL Google TV／F1 即時字幕實機測試步驟](research/Hearth-TV-F1-POC-TEST.md)
- [候選專案技術比對與決策紀錄](research/CANDIDATE-COMPARISON.md)
- [候選 APK 建置工作流](.github/workflows/research-candidate-validation.yml)：固定上游 commit、嘗試建置 APK，並保存日誌與 SHA-256。

**不要求先安裝 Hearth。** 原型程式已加入 App 內音訊擷取、串流 ASR、ML Kit 本機英文→中文翻譯及 OpenCC 臺灣繁體轉換；保留 M3U/M3U8 與 MPV。實驗 APK 的原型程式已在 [GitHub Actions 第 20 輪](https://github.com/DickyR15/R15-TV/actions/runs/38132010463) 成功建置並通過模型／OpenCC 資產檢查；目前正在把測試包改成獨立套件名稱，避免覆蓋現有 R15 TV。最新版 workflow 手動重跑後再安裝，實機音訊擷取、翻譯品質與延遲仍待 TCL 電視驗證，暫時不能稱為正式版。首次使用會下載翻譯模型，之後翻譯在本機執行，無須付費 API。

## 優先研究的開源專案

- [Hearth](https://github.com/Sal7one/Hearth)：研究 Android 即時字幕／翻譯及 TV 介面。
- [SUBRIMA](https://github.com/y-haviv/android-subtitle-overlay)：研究 Vosk 即時語音辨識、音訊擷取與翻譯流程。
- [OpenTV](https://github.com/opentvproject/opentv)：評估 Android TV 播放器基底及遙控器導覽。
- [Android TV Live Captions](https://github.com/web-dev-akash/android-tv-live-captions)：研究 TV 即時語音轉文字方式。

候選專案均需再確認最新原始碼、授權、維護狀態、語言能力及實體電視相容性；列入清單不代表已整合或已建置成功。

## 下一步

候選專案建置已在 [GitHub Actions 第 4 輪](https://github.com/DickyR15/R15-TV/actions/runs/38124838006) 成功。新 POC 的 [第 20 輪建置](https://github.com/DickyR15/R15-TV/actions/runs/38132010463) 已成功；但該 APK 建於套件隔離修改前，若電視已有 R15 TV，不要直接安裝。請在 [Audio Capture POC workflow](https://github.com/DickyR15/R15-TV/actions/workflows/build-audio-capture-poc.yml) 手動重跑最新版，取得套件名稱為 com.dicky.r15tv.poc 的獨立測試版。建置與資產檢查通過仍不代表電視實機的 MPV 音訊擷取、翻譯品質或即時延遲已通過。若系統擷取在 MPV 情境下不可用，才退回 [MPV 原生音訊接入與整合計畫](research/MPV-INTEGRATION-PLAN.md) 所列的 native tap 路線。

## 授權與上游來源

請以各檔案中的授權及上游致謝為準。整合第三方程式碼前，必須先確認其授權相容性並保留必要聲明。
