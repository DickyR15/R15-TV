# 候選專案技術比對與決策紀錄

**研究日期：2026-10-11（Asia/Taipei）**  
**分支：** `research/realtime-subtitles-tv`  
**狀態：** 已檢查公開 README、部分建置設定與關鍵程式碼；尚未在 TCL 電視上完成實機測試，也尚未把候選專案的程式碼移植到 R15 TV。

## 結論摘要

**先用 Hearth 驗證電視端字幕擷取和翻譯，再決定要如何整合回 R15 TV。** 這是目前風險最低的起點：它已經有 Android TV 導覽與字幕浮窗、Android 10+ 裝置播放音訊擷取，以及本機／雲端辨識與翻譯的分層流程。Hearth 0.23.9 的 Android TV emulator 證據目前偏重字幕介面示範，並不等於已在實體電視上證明音訊擷取、辨識和翻譯全流程成功。

本研究保留先前的專案需求：**R15 TV 之後若做播放器內整合，應保留 MPV，不可在未評估下改用另一個播放引擎。** OpenTV 以 Media3/ExoPlayer 為核心，因此只作為 TV 介面／架構參考，不作為直接替換基底。

## 候選比較

| 候選 | 已能從公開文件確認的能力 | 主要限制／風險 | 本研究用途 |
|---|---|---|---|
| [Hearth](https://github.com/Sal7one/Hearth) | Apache-2.0；0.23.9；Android 9+；裝置播放音訊擷取需 Android 10+；支援字幕浮窗、即時辨識、本機／雲端翻譯；支援多種 CPU APK。原始碼 README 提供 QA 建置命令。 | 官方 TV 螢幕擷取是 Android 14 emulator 的 UI fixture，不代表 ASR／翻譯已實機驗證。其本機翻譯文件指出中文 script variants 使用共用 `zh` 路由，沒有獨立的繁體中文輸出選項，必須測試輸出字形與翻譯品質。部分 App／受保護音訊可能不允許被擷取。 | **第一優先：電視實機 POC／音訊擷取可行性測試。** 後續考慮採用其流程或以 Apache-2.0 程式碼為參考；整合前仍須核對全部第三方授權。 |
| [SUBRIMA](https://github.com/y-haviv/android-subtitle-overlay) | GPL-3.0；Android API 29+；以 `MediaProjection + AudioPlaybackCapture` 擷取音訊；Silero 自動偵測語言、Vosk 串流辨識、可選 Whisper 校正、ML Kit 翻譯、浮窗字幕。程式的語言對照把 `zh-TW` 映射為 `zh`。 | 官方文件沒有足夠證據證明其 UI 已完成 Android TV/D-pad 適配；較重的辨識模型可能超出部分 TV 的資源能力；`zh-TW` 映射到 `zh` 不保證繁體輸出。移植其 GPL-3.0 程式碼時要確認整體發佈遵守 GPL-3.0。 | **第二優先：研究音訊與辨識管線。** 用作替代引擎比較，不先搬進播放器。 |
| [OpenTV](https://github.com/opentvproject/opentv) | GPL-3.0-or-later；提供 Android TV/Fire TV IPTV 播放器、D-pad 介面；可用 JDK 17+ 與 Android SDK API 35 建置。其 app Gradle 設定明確使用 Media3 ExoPlayer。 | 不符合先前保留 MPV 的方向；目前沒有證據顯示內建即時語音辨識／翻譯字幕。 | 只參考 TV UI、遙控器導覽與公開 CI 建置方式；**不作為 R15 TV 播放引擎基底**。 |
| [Android TV Live Captions](https://github.com/web-dev-akash/android-tv-live-captions) | 專案文件描述 TV/Google TV 即時語音轉文字、VAD、gRPC 串流辨識；主打英文與 12 種印度語言。 | 其公開架構偏向麥克風／手機麥克風輸入和雲端辨識，而非直接擷取播放器音訊；語言清單不符合繁體中文目標。檢查的根目錄列表中沒有明顯的 LICENSE 檔，未確認授權前不複製程式碼。 | 僅作技術參考，不當成主要基底。 |

## 保留 MPV 的整合方向

FongMi/TV 公開 README 確認它同時支援 Media3/ExoPlayer 與 MPV。檢查 `MpvPlayerEngine.java` 可見 MPV 包裝器已有音訊輸出效果 listener，但目前看到的程式碼只證實它用於音效處理，**沒有證據證明它提供原始 PCM 音訊回呼**。因此不能只靠現有 listener 就假設能直接接 ASR。

實作時依序評估：

1. **先用獨立 POC 驗證**：以 Hearth QA APK 在目標 TV 測試裝置播放音訊擷取、辨識、翻譯及字幕顯示，快速確認裝置／系統限制。
2. **評估播放器內部擷取**：研究 Android `AudioPlaybackCapture` 能否在 R15 TV 的 MPV 播放情境可靠取得音訊。此法可能仍需要系統 MediaProjection 同意，且受音訊政策／受保護內容限制。
3. **必要時才研究 native PCM tap**：若 Android 播放擷取不可靠，再評估在 MPV/libmpv 音訊輸出路徑加入低開銷 PCM 分流，直接把 PCM 片段傳給 ASR。這會碰到 native build、同步、緩衝區、音訊格式與授權維護等問題，應獨立成小型原型。
4. **播放器內字幕 UI**：最終盡量在 R15 TV 現有播放畫面疊加字幕，不依賴外部 App 的 overlay；保留 MPV 原有硬解／軟解、字幕軌與音訊輸出行為。

## TCL Google TV 的驗證清單

目標測試裝置是 Android 12 的 TCL Google TV。即使 OS 版本符合 Android 10+ 音訊擷取前提，也仍要實測 TV 韌體、音訊來源與每個播放器的擷取政策。

- [ ] 安裝 Hearth 的 Universal QA APK；記錄 APK 來源與 SHA-256。
- [ ] 先播放無 DRM 的本機影片／測試影片，確認可取得聲音並產生來源語言字幕。
- [ ] 分別測試 R15 TV 的 MPV 播放與另一個常見播放器；記錄哪些來源音訊可被擷取。
- [ ] 檢查英文／日文／韓文辨識結果，再確認翻譯為繁體中文而非簡體中文；若字形／語意不理想，列為翻譯引擎選型問題。
- [ ] 量測字幕延遲、漏字、重複字、換行及長時間運行的記憶體／CPU。
- [ ] 用電視遙控器操作授權、開始／暫停、字幕位置和設定。
- [ ] 確認本機模式是否真正離線；雲端模式需記錄資料送往哪個服務、所需金鑰、費用與隱私取捨。

## 已核對的官方來源

- Hearth README 與版本：<https://github.com/Sal7one/Hearth>
- Hearth TV／CPU 支援：<https://github.com/Sal7one/Hearth/blob/main/docs/android-abi-support.md>
- Hearth 本機翻譯路由與語言限制：<https://github.com/Sal7one/Hearth/blob/main/docs/local-translation.md>
- SUBRIMA README：<https://github.com/y-haviv/android-subtitle-overlay>
- SUBRIMA 語言對照：<https://github.com/y-haviv/android-subtitle-overlay/blob/main/SubtitlesAppSubrimaProject/app/src/main/assets/google_dict.json>
- OpenTV README 與建置：<https://github.com/opentvproject/opentv>
- FongMi/TV README：<https://github.com/FongMi/TV>

所有上述項目都是根據公開文件與部分原始碼做的桌面研究結論，並非在 TCL 電視上完成的測試結果。後續建置工作流會固定候選 commit 並記錄建置日誌、APK 與 SHA-256；只有實際 CI 成功才標記為「建置成功」。


## CI 驗證結果（2026-10-11）

GitHub Actions 第 4 輪成功完成三個候選的建置；各 Gradle 任務輸出 `BUILD SUCCESSFUL`。這是上游程式碼在 GitHub runner 上的建置證據，**不是** TV 實機或 F1 直播辨識／翻譯的驗收證據。

- [完整工作流與日誌](https://github.com/DickyR15/R15-TV/actions/runs/38124838006)
- [Hearth QA APK、日誌與 SHA-256（約 502 MB，artifact 暫存 14 天）](https://github.com/DickyR15/R15-TV/actions/runs/38124838006/artifacts/11697605150)
- [SUBRIMA debug APK、日誌與 SHA-256（約 65 MB，artifact 暫存 14 天）](https://github.com/DickyR15/R15-TV/actions/runs/38124838006/artifacts/11696653814)
- [OpenTV debug APK、日誌與 SHA-256（約 29 MB，artifact 暫存 14 天）](https://github.com/DickyR15/R15-TV/actions/runs/38124838006/artifacts/11696980988)

下一步優先使用 Hearth 的離線／FOSS QA APK，在目標 TV 測試可用性與音訊擷取；同時保留 SUBRIMA 以供對照。不應把這些候選 APK 直接重命名為 R15 TV 或當作 R15 TV 正式版發布。
