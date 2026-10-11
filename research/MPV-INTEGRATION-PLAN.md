# R15 TV：MPV 內建即時字幕整合調查

**狀態：** 第一版已定為 Whisper Base 多語 + ML Kit 本機英翻中 + OpenCC S2TWP。POC-43 已成功建置，但複核後確認字幕程式只接到 VOD 的 VideoActivity，未接到 M3U/M3U8 直播的 LiveActivity，故舊包不適合用來驗證直播字幕。研究分支已修正為同時接入 LiveActivity，並將 ExoPlayer 的 HLS 選擇改為自適應碼率，而非固定強制最高碼率；新包建置完成前不建議再測舊 POC-43。Whisper 模型不包進 APK，首次由使用者下載。MPV 4K 無法播放的原因仍未完全確認，尚待針對實際串流錯誤驗證。  
**目標：** 使用者仍像平常一樣播放 M3U/M3U8；字幕在 R15 TV 播放畫面內顯示，不要求另開 Hearth，也不把 MPV 換掉。

## 為什麼不要求先測 Hearth

Hearth 是獨立 App，必須額外安裝、下載模型、授權音訊擷取，再測試是否能覆蓋 IPTV 畫面。這對使用者來說步驟多，也不能保證每個直播 App 都允許系統擷取音訊。

因此 Hearth 改為**可選參考工具**。第一個實作改採 Android `AudioPlaybackCapture`，只擷取 R15 TV 自己 UID 的播放音訊，並把副本送進語音辨識佇列；這樣暫時不必重編 MPV native library。它仍需要一次系統擷取授權及錄音權限，音訊是否真的可被捕捉須經 TV 實測。若失敗，再回到 MPV 原生 PCM tap。

## 本次實機回報的兩個問題

1. **找不到 AI 字幕／語音設定：已找到程式接線缺漏。** 原有套用腳本只修改點播用 `VideoActivity`，M3U/M3U8 頻道實際使用 `LiveActivity`。舊 POC 因此沒有在直播播放器畫面顯示設定面板。研究分支現在將相同面板接至 Leanback 與 Mobile 的 `LiveActivity`，並加上明確的「AI 字幕／語音設定」標題。
2. **預設 4K 卡頓：已找到一個可修正的 HLS 策略。** 舊 ExoPlayer 程式使用 `setForceHighestSupportedBitrate(true)`，會忽略一般的頻寬自適應決策而偏向強制最高碼率。研究版已改為 `false`，讓多畫質 HLS/M3U8 依估計頻寬調整；這不會對固定單路 4K 串流降碼率，也不能替不支援的編碼格式補上硬體解碼。
3. **MPV 單一來源解碼失敗：新增自動備援。** 已核對 MPV 預設先使用 Android MediaCodec 硬體解碼（`mediacodec,mediacodec-copy`），並會在解碼錯誤時嘗試軟解。最新 POC 再加一層保護：若 MPV 的硬解／軟解兩次嘗試都回報解碼錯誤，則對目前這個來源暫時切換 ExoPlayer，並顯示提示；使用者原本的 MPV 偏好不會永久更改。若 MPV 是「畫面掉幀但沒有解碼錯誤」，此保護不會觸發，渲染瓶頸仍需要實機診斷。

## 已核對的原始碼

1. [FongMi/TV 的 MpvPlayerEngine.java](https://github.com/FongMi/TV/blob/fongmi/app/src/main/java/com/fongmi/android/tv/player/mpv/MpvPlayerEngine.java) 註冊 `player.setAudioOutputListener(effect::applyAudioEffect)`。
2. [FongMi/TV 的 MpvPlayerEffect.java](https://github.com/FongMi/TV/blob/fongmi/app/src/main/java/com/fongmi/android/tv/player/mpv/MpvPlayerEffect.java) 中，`applyAudioEffect()` 會依聲道與音效設定呼叫 `player.setAudioFilter(...)`。從這個 Java 用法看，listener 是用來重新套用音效設定，**不是已確認的 PCM 樣本回呼**。
3. [FongMi/mpv-android 的 JNI main.cpp](https://github.com/FongMi/mpv-android/blob/fongmi/app/src/main/jni/main.cpp) 可見初始化、播放命令及非同步命令的 JNI 入口；[event.cpp](https://github.com/FongMi/mpv-android/blob/fongmi/app/src/main/jni/event.cpp) 負責將 MPV 事件、屬性與日誌通知 Java。檢查的這條介面路徑沒有顯示把解碼 PCM 音訊樣本送到 Java 的 callback。

**結論限制：** 這表示目前檢查到的公開 wrapper 路徑不足以直接接 ASR；不代表 libmpv 或其他未檢查的分支絕對沒有可用機制。需再確認 R15 TV 建置時實際使用的 MPV/Media3 AAR 版本與原始碼，才可選定最小侵入方案。

### 不採用的簡易替代方式：`ao=pcm`

MPV 官方手冊有 PCM/WAVE 檔案輸出模式，但它屬於音訊輸出 driver，主要是把聲音寫成檔案，不是向應用程式回傳即時記憶體中的 PCM callback。把它當主要輸出可能取代正常的音訊播放；另外再輪詢檔案也會增加 I/O、延遲及清理問題。因此它可作為離線除錯工具，不作為保留正常電視聲音的正式即時字幕架構。參考：[MPV 官方手冊：Audio Output Drivers](https://mpv.io/manual/stable/#audio-output-drivers)。

## 推薦實作方案

### 階段 1：Whisper Base＋ML Kit＋OpenCC POC（CI 建置成功，待實機驗證）

- 透過 Android `AudioPlaybackCaptureConfiguration` 過濾 `Process.myUid()` 與 `USAGE_MEDIA`／`UNKNOWN`／`GAME`，避免擷取其他 App 的聲音。
- 使用 [Whisper.cpp](https://github.com/ggml-org/whisper.cpp) 多語 Base 權重 ggml-base.bin；權重由字幕面板在 App 內下載，APK 只包含 native runtime。F1 第一版指定英文辨識，再交給 ML Kit 翻譯。
- 採用 `AudioRecord` 讀取 48 kHz stereo PCM16；背景執行緒只量測音量、轉送資料，語音辨識在另一條 worker thread 執行，不阻塞播放。
- 在 `AndroidManifest.xml` 宣告 `RECORD_AUDIO` 與 `android:allowAudioPlaybackCapture="true"`；由使用者明確按下字幕測試後才要求系統 MediaProjection 同意。
- 最新原型會在句段結束後使用 ML Kit 本機 English → Chinese 翻譯，再以 OpenCC S2TWP 轉成臺灣繁體；首次需下載翻譯模型。此整合仍待建置與實機測試。
- 原型程式與建置流程：[patches/audio-capture](../patches/audio-capture)、[套用腳本](../scripts/apply-audio-capture-poc.py)、[Audio Capture POC workflow](../.github/workflows/build-audio-capture-poc.yml)。

### 階段 2：必要時才做 MPV native PCM tap

- 若 Android capture 在 TV 韌體或 MPV 音訊路徑下回傳靜音，重新確認使用的 wrapper、libmpv 與 native build 版本。
- 不把既有 `setAudioOutputListener(effect::applyAudioEffect)` 當成 PCM callback；它的已知用途是重新套用音效設定。
- 如確實需要原生 tap，讓 native 音訊執行緒只複製資料到有界佇列，不要做推論、翻譯或磁碟 I/O；將 patch 和 native rebuild 固定納入 CI，不能只修改一次性工作目錄。

### 階段 2：PCM 原型

- 使用獨立、可關閉的實驗開關，不預設啟用。
- 在 native 音訊執行緒中避免推論、翻譯、磁碟 I/O 或長時間 JNI 呼叫。
- 使用有界緩衝區／佇列傳出音訊副本；下游工作執行緒才做聲道混合、重取樣與語音活動偵測。
- 音訊格式先統一為單聲道、16 kHz PCM（以 ASR 引擎要求為準），處理播放暫停、seek、切台、斷流、音訊軌變更及播放器釋放。
- 驗收主音訊音量、聲道、延遲及穩定性完全不因字幕分析受到可察覺影響。

### 階段 3：免費本機語音辨識與翻譯

- 第一版以 Whisper Base 多語權重作為基準；模型權重不隨 APK 發佈。若電視效能不足，先調整音訊視窗與執行緒，再量測是否有必要換 Whisper Small Q5_1 量化版。
- 翻譯獨立成可更換元件，英文句段穩定後才送翻譯，避免每個臨時辨識結果都重翻。
- 翻譯模型需明確支援 English → Chinese、能在 Android 裝置本機執行並核對模型授權。模型應可另行下載，避免初始 APK 過大。
- 最後用 OpenCC 臺灣正體／詞彙轉換（例如 `s2twp`）調整字形與用詞；OpenCC 本身不負責英翻中。
- 建立 F1 車手、車隊、輪胎、進站策略與賽事用語詞庫，避免無條件取代一般句子中的相同字串。

### 階段 4：整合播放器內字幕

- 在 R15 TV 自己的播放畫面疊加字幕，不依賴跨 App 浮窗或 MediaProjection。
- 第一版只做最必要控制：字幕開／關、翻譯開／關、字幕大小和底色；保留原本 MPV、M3U/M3U8 播放、字幕軌與音訊設定。
- 初期不更改預設播放行為；只有使用者明確開啟即時字幕時，才啟動音訊分析及模型。
- 若電視無法即時跑完 ASR 與翻譯，再評估家中電腦執行免費模型、電視只接收字幕的備援方案；不預設使用付費 API。

## 驗收順序

1. CI 可重現建置，提供測試 APK、來源 commit、patch、日誌與 SHA-256。
2. 以合法且可本機使用的短英文測試片段，確認音訊擷取不干擾主音訊。
3. 確認 ASR 可長時間追上播放時間，且緩衝區不持續累積。
4. 加入本機翻譯與臺灣正體轉換，檢查字幕重複、漏字、F1 專有名詞與延遲。
5. 再於 Android TV 實機測試直播 M3U/M3U8，記錄記憶體、CPU、音訊中斷及長時間穩定性。

中位字幕端到端延遲 5 秒內、95 百分位 8 秒內是**待測的工程目標**，不是已證實的效能承諾。

## 本階段尚未完成的事

- GitHub Actions [第 43 輪](https://github.com/DickyR15/R15-TV/actions/runs/38135426602) 已通過 native 編譯、APK 封裝與 Whisper／OpenCC 資產檢查；[POC-43 Artifact](https://github.com/DickyR15/R15-TV/actions/runs/38135426602/artifacts/11701037822)。接下來才是 TV 實機音訊檢查。
- 原型已成功建置，包含本機英翻中與 OpenCC 字詞轉換；TV 實機驗收仍未完成。
- 尚未證實目標電視能在完整本機 ASR＋翻譯模式維持即時效能。
- 即使 APK 成功建置，也不等於目標 TV 的 MPV 聲音會被捕捉；這必須實測。
