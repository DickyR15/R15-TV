# R15 TV — 免費本機翻譯與臺灣繁體輸出方案

**用途：** 在字幕 ASR 原型成功後，加入英文→中文本機翻譯與臺灣繁體字詞轉換。  
**狀態：** 方案已調查；尚未整合、建置或在 TCL 電視實測。不能把這份規劃視為已支援翻譯。

## 建議的第一版：ML Kit 本機翻譯 + Android OpenCC

### 1. 翻譯引擎

使用 Google ML Kit Translate Android 套件，固定語言方向 English → Chinese：

- Maven 套件：`com.google.mlkit:translate:17.0.3`
- 模型下載後，字幕文字在裝置本機推論；不需要自行申請付費 API key。
- 第一次使用需透過 Google 的模型下載機制下載翻譯模型，因此第一次啟動需要網路；下載完後再以離線文字輸入驗證本機翻譯。
- ML Kit 是免費的本機 SDK，但不是開源翻譯模型。若未來需要完整開源堆疊，再比較可離線執行的開放權重翻譯模型。

官方文件：[ML Kit Android Translation](https://developers.google.com/ml-kit/language/translation/android?hl=zh-tw)。套件版本與按需模型下載方式依該官方文件固定，不採用付費／BYOK 雲端翻譯服務。

### 2. 轉成臺灣繁體中文

ML Kit 的中文語言路由為 `zh`，不能把它當成明確的臺灣繁體輸出保證。翻譯結果再呼叫 Android OpenCC 的 `S2TWP`（簡體轉臺灣正體並轉換臺灣慣用詞）：

- 套件：`com.github.qichuan:android-opencc:1.2.0`
- API：`ChineseConverter.convert(text, ConversionType.S2TWP, context)`
- 上游專案：<https://github.com/qichuan/android-opencc>
- OpenCC 是簡繁與地區用詞轉換，不是英翻中引擎。

OpenCC 專案與 API / 授權需在實際整合時重新核對；模型翻譯與 OpenCC 轉換必須分開處理。

### 3. 即時字幕流程

```text
MPV 播放音訊
  → App 內音訊擷取
  → 串流 ASR 產生英文 interim 字幕
  → 句段穩定後才送翻譯
  → ML Kit 本機 English → Chinese
  → OpenCC S2TWP
  → R15 TV 播放畫面中的繁體中文字幕
```

不要將 ASR 每一個暫時性 partial result 都送翻譯。應在辨識句段結束或結果短暫穩定時才翻譯，避免大量重複工作、字幕回跳與佇列積壓。若某段翻譯失敗，保留原文字幕，不要中斷播放。

## 驗收要求

- [ ] 第一次啟動顯示「下載離線翻譯模型」狀態，不要求使用者手動找檔案。
- [ ] 模型完成下載後，英文→中文翻譯可在關閉網路的本機測試文字上成功。
- [ ] 翻譯結果經 S2TWP 轉為臺灣繁體與常用詞彙。
- [ ] ASR 原文先顯示；翻譯完成時覆蓋／更新同一字幕，不一行行累加重複字幕。
- [ ] 翻譯任務串列執行，不阻塞播放／音訊擷取執行緒。
- [ ] 量測直播延遲與電視記憶體、CPU；不可預先承諾實機速度。
- [ ] 標示 ML Kit 的 SDK / 模型下載依賴與 OpenCC MIT 授權，不把 ML Kit 描述為開源模型。

## 目前限制

即使翻譯元件可用，也不代表 TV 已能穩定擷取 MPV 音訊。第一階段仍必須先確認本機 ASR POC 能從 R15 TV 直播播放器取得非靜音音訊；翻譯整合應建立在這個測試通過之後。
