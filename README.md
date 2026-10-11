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

**已定案第一版：Whisper Base 多語 + Google ML Kit 本機英翻中 + OpenCC S2TWP。** 目前修正項目包括：把 AI 字幕／語音設定移到主設定頁最上方、播放畫面右上角高對比入口、可在 App 私有儲存空間下載 Whisper Base 多語模型，以及 EXO/HLS 預設「1080p 優先」並保留自動適應／最高畫質選項。這批最新 UI 與 1080p 策略修改正在第 61 輪建置，成功前不當成可下載版本。預設不強制選最高碼率；如果清單提供多種畫質，播放器可依頻寬調整。MPV 對不支援的 4K 解碼格式會嘗試直接回退到 EXO，不改變使用者永久的 MPV 偏好。單一路徑 4K 或單純渲染掉幀不保證會因這些設定自動修復，仍需依 codec、碼率和電視硬體解碼能力實測。最新 [第 58 輪建置](https://github.com/DickyR15/R15-TV/actions/runs/38143827319) 已成功，並產生 [R15 TV Whisper Base Subtitle POC-58 測試包](https://github.com/DickyR15/R15-TV/actions/runs/38143827319/artifacts/11703957666)。這是獨立套件的 debug 測試版，不會覆蓋正式 App；只完成 CI 建置與資產檢查，音訊擷取、字幕品質及 4K 播放仍需 TCL Google TV 實機驗證。Whisper Small 量化版只會在 Base 的實機效能數據證明值得升級後再加入。

## 優先研究的開源專案

- [Hearth](https://github.com/Sal7one/Hearth)：研究 Android 即時字幕／翻譯及 TV 介面。
- [SUBRIMA](https://github.com/y-haviv/android-subtitle-overlay)：研究 Vosk 即時語音辨識、音訊擷取與翻譯流程。
- [OpenTV](https://github.com/opentvproject/opentv)：評估 Android TV 播放器基底及遙控器導覽。
- [Android TV Live Captions](https://github.com/web-dev-akash/android-tv-live-captions)：研究 TV 即時語音轉文字方式。

候選專案均需再確認最新原始碼、授權、維護狀態、語言能力及實體電視相容性；列入清單不代表已整合或已建置成功。

## 下一步

候選專案建置已在 [GitHub Actions 第 4 輪](https://github.com/DickyR15/R15-TV/actions/runs/38124838006) 成功。Whisper Base Subtitle POC 固定 Whisper.cpp 上游 commit，建置 ARM64／ARMv7 原生庫，並由 App 內下載多語 Base 權重。第 58 輪已成功建置含 LiveActivity 字幕入口、主設定 AI 字幕頁、EXO/HLS 自動適應選項及 MPV 不支援 4K 格式時的 EXO 回退邏輯。舊 POC-43 與第 20／25 輪都是更早版本，不適合驗證目前功能。若系統擷取在 MPV 情境下不可用，才退回 [MPV 原生音訊接入與整合計畫](research/MPV-INTEGRATION-PLAN.md) 所列的 native tap 路線。

## 授權與上游來源

請以各檔案中的授權及上游致謝為準。整合第三方程式碼前，必須先確認其授權相容性並保留必要聲明。
