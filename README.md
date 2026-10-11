# R15 TV — 即時字幕研究

本專案研究 Android TV／Google TV 播放器整合即時字幕的可行性，主要方向為即時語音辨識、來源語言自動偵測，以及翻譯成繁體中文字幕。

## 目前研究狀態

- 新研究分支：[`research/realtime-subtitles-tv`](https://github.com/DickyR15/R15-TV/tree/research/realtime-subtitles-tv)
- 研究計畫：[即時字幕研究計畫](https://github.com/DickyR15/R15-TV/blob/research/realtime-subtitles-tv/research/REALTIME-SUBTITLES-PLAN.md)
- 狀態：研究／驗證階段，尚未宣稱已完成可用的即時翻譯字幕播放器。
- 方向：優先驗證 Android TV 實體裝置、播放器內部音訊擷取、低延遲字幕與繁體中文翻譯。
- 舊有 FongMi/TV 建置方向暫不作為新研究主線；舊分支與 Git 歷史保留供追溯。

## 候選分析與決策

- [候選專案技術比對與決策紀錄](research/CANDIDATE-COMPARISON.md)
- [候選 APK 建置工作流](.github/workflows/research-candidate-validation.yml)：固定上游 commit、嘗試建置 APK，並保存日誌與 SHA-256。

**目前首選 Hearth 作為電視音訊擷取 POC；R15 TV 的正式播放器整合仍需保留 MPV，尚未開始移植。**

## 優先研究的開源專案

- [Hearth](https://github.com/Sal7one/Hearth)：研究 Android 即時字幕／翻譯及 TV 介面。
- [SUBRIMA](https://github.com/y-haviv/android-subtitle-overlay)：研究 Vosk 即時語音辨識、音訊擷取與翻譯流程。
- [OpenTV](https://github.com/opentvproject/opentv)：評估 Android TV 播放器基底及遙控器導覽。
- [Android TV Live Captions](https://github.com/web-dev-akash/android-tv-live-captions)：研究 TV 即時語音轉文字方式。

候選專案均需再確認最新原始碼、授權、維護狀態、語言能力及實體電視相容性；列入清單不代表已整合或已建置成功。

## 下一步

先比較各候選的音訊來源、Android TV 相容性、辨識語言、翻譯方式、延遲、記憶體需求與授權，再選擇最適合的技術基底。詳細驗收清單見研究計畫。

## 授權與上游來源

請以各檔案中的授權及上游致謝為準。整合第三方程式碼前，必須先確認其授權相容性並保留必要聲明。
