# R15 TV AI Subtitle Companion — POC

This is an isolated experiment for the working R15 TV 5.8.2 FIX3 APK. It deliberately does not modify the player APK or its playback code.

## What it tests

- Android AudioPlaybackCapture targeting the installed `com.dicky.r15tv` UID after the user grants system consent.
- Local multilingual Whisper Base speech recognition.
- On-device ML Kit translation for supported source languages, then OpenCC `s2twp` Taiwan Traditional Chinese conversion.
- A non-focusable subtitle overlay above R15 TV.

## How to test on onn. 4K Pro

1. Keep R15 TV FIX3 installed; install the separate `R15-TV-AI-Subtitle-Companion-POC-arm64.apk` test APK.
2. Open **R15 TV AI 字幕測試** and allow its overlay permission.
3. Tap **下載／檢查 Whisper Base 多語模型**. This downloads a model of roughly 150 MiB into the companion app's private storage. First use of each translation language may download another model.
4. Tap **開始字幕擷取**, approve Android's system capture prompt, then press Home and open R15 TV FIX3.
5. Play a live stream with clear speech. Watch for the audio-level/status text and the subtitle overlay. Stop via the notification's **停止字幕** action or the companion app.

## Important limits

- The first POC uses local processing; no Groq key is configured or required.
- Android device/vendor audio-capture policy may still prevent audio capture. If the overlay reports little/no audio, keep the logcat; do not change FIX3 playback settings.
- This is a separate companion app, not yet a button inside R15 TV's own settings. The stable FIX3 APK remains the fallback.
- Build success only confirms packaging. The Android TV device must validate actual capture, language detection, subtitle latency and remote navigation before this is considered usable.
