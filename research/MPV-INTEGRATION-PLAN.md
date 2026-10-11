# R15 TV：MPV 內建即時字幕整合調查

**狀態：** 原始碼調查與設計階段；尚未把 PCM tap、語音辨識或翻譯接進播放器，也尚未完成實機測試。  
**目標：** 使用者仍像平常一樣播放 M3U/M3U8；字幕在 R15 TV 播放畫面內顯示，不要求另開 Hearth，也不把 MPV 換掉。

## 為什麼不要求先測 Hearth

Hearth 是獨立 App，必須額外安裝、下載模型、授權音訊擷取，再測試是否能覆蓋 IPTV 畫面。這對使用者來說步驟多，也不能保證每個直播 App 都允許系統擷取音訊。

因此 Hearth 改為**可選參考工具**，不再是進入 R15 TV 開發的前置條件。新路線先在原始碼與 CI 中查證 MPV 音訊接入方式，之後再提供一個可安裝的 R15 TV 測試版給使用者驗證。

## 已核對的原始碼

1. [FongMi/TV 的 MpvPlayerEngine.java](https://github.com/FongMi/TV/blob/fongmi/app/src/main/java/com/fongmi/android/tv/player/mpv/MpvPlayerEngine.java) 註冊 `player.setAudioOutputListener(effect::applyAudioEffect)`。
2. [FongMi/TV 的 MpvPlayerEffect.java](https://github.com/FongMi/TV/blob/fongmi/app/src/main/java/com/fongmi/android/tv/player/mpv/MpvPlayerEffect.java) 中，`applyAudioEffect()` 會依聲道與音效設定呼叫 `player.setAudioFilter(...)`。從這個 Java 用法看，listener 是用來重新套用音效設定，**不是已確認的 PCM 樣本回呼**。
3. [FongMi/mpv-android 的 JNI main.cpp](https://github.com/FongMi/mpv-android/blob/fongmi/app/src/main/jni/main.cpp) 可見初始化、播放命令及非同步命令的 JNI 入口；[event.cpp](https://github.com/FongMi/mpv-android/blob/fongmi/app/src/main/jni/event.cpp) 負責將 MPV 事件、屬性與日誌通知 Java。檢查的這條介面路徑沒有顯示把解碼 PCM 音訊樣本送到 Java 的 callback。

**結論限制：** 這表示目前檢查到的公開 wrapper 路徑不足以直接接 ASR；不代表 libmpv 或其他未檢查的分支絕對沒有可用機制。需再確認 R15 TV 建置時實際使用的 MPV/Media3 AAR 版本與原始碼，才可選定最小侵入方案。

## 推薦實作方案

### 階段 1：確認 native 接入點（不需使用者操作）

- 盤點建置實際使用的 MPV wrapper、libmpv 與 JNI 原始碼版本。
- 確認是否存在可安全擴充的音訊 filter/output callback；不要把既有 EQ listener 誤用為 PCM tap。
- 若沒有現成 API，設計 native 音訊分流 tap，先只把音訊副本送去分析，不改變主音訊輸出。
- 決定要在上游來源上維護 patch，並把 patch 套用步驟納入 R15 TV 的可重現 CI；不能只在一次性工作目錄手動改檔。

### 階段 2：PCM 原型

- 使用獨立、可關閉的實驗開關，不預設啟用。
- 在 native 音訊執行緒中避免推論、翻譯、磁碟 I/O 或長時間 JNI 呼叫。
- 使用有界緩衝區／佇列傳出音訊副本；下游工作執行緒才做聲道混合、重取樣與語音活動偵測。
- 音訊格式先統一為單聲道、16 kHz PCM（以 ASR 引擎要求為準），處理播放暫停、seek、切台、斷流、音訊軌變更及播放器釋放。
- 驗收主音訊音量、聲道、延遲及穩定性完全不因字幕分析受到可察覺影響。

### 階段 3：免費本機語音辨識與翻譯

- 先測試量化的 Whisper `base.en`；只有持續低於即時處理速度時，才比較更小模型或替代引擎。
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
2. 以合法且可本機使用的短英文測試片段，確認 PCM tap 不干擾主音訊。
3. 確認 ASR 可長時間追上播放時間，且緩衝區不持續累積。
4. 加入本機翻譯與臺灣正體轉換，檢查字幕重複、漏字、F1 專有名詞與延遲。
5. 再於 Android TV 實機測試直播 M3U/M3U8，記錄記憶體、CPU、音訊中斷及長時間穩定性。

中位字幕端到端延遲 5 秒內、95 百分位 8 秒內是**待測的工程目標**，不是已證實的效能承諾。

## 本階段尚未完成的事

- 尚未確認最終可用的 native PCM callback/API。
- 尚未提交實際音訊 tap、ASR、翻譯或字幕 UI 程式碼。
- 尚未證實目標電視能在完全本機模式下維持即時翻譯。
- Hearth 的 QA APK 建置成功，只能證明上游候選在 CI 能編譯；不是 R15 TV 的功能建置或實機成功證據。
