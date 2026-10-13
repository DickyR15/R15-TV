# R15 TV — AutoBuild 170 移植規格（臺灣繁體輸出）

## 基底與來源

- Reference release: TV-AutoBuild `autobuild-170`, app version `6.4.0-autobuild.170`.
- Public build base: FongMi/TV public source; do not copy private signing materials.
- Speech runtime: sherpa-onnx `v1.13.8`, commit `11afbd009a7f8c08f4bcf2fc1b265d0df4670fbf`.
- Model files are pinned in `config/ai-subtitle-models.json` by URL, exact byte count and SHA-256. Downloads must fail closed if validation fails.
- Preserve the MPV player. Subtitle capture must never block the playback/audio thread.

## Required language choices

Exactly these source-language choices should appear in the UI, in this order:

1. 粵語 — `cantonese-wenetspeech-ctc`
2. 德語 — `german-kroko-streaming`
3. 英語 — `english-kroko-streaming`
4. 法語 — `french-kroko-streaming`
5. 西班牙語 — `spanish-kroko-streaming`
6. 日語 — `japanese-moonshine-tiny`
7. 中文（普通話） — `mandarin-zipformer-ctc`
8. 韓語 — `sense-voice` with language forced to `ko`

Model artifacts and hashes are recorded in `config/ai-subtitle-models.json`. Each selected language pack must be independently downloadable/deletable; never pack large model weights inside the APK.

## Translation output policy

- UI must say **「臺灣繁體中文字幕翻譯」**, never 「簡體中文字幕翻譯」.
- Only translation output target: `zh-TW`.
- Keep the AutoBuild-style translation settings and options except remove **有道翻譯** and **騰訊翻譯** completely. The remaining configurable providers are Bing、DeepL、Google、MTranServer and OpenAI 相容大模型; 「僅顯示辨識原文」 remains a display mode rather than a translation provider. The allowlist is `config/ai-subtitle-translation-policy.json`.
- Remove 有道 and Tencent translation from the selector, settings forms, dispatch/routing code, retries, fallbacks, and cached provider state. Do not merely hide their labels.
- After every allowed provider returns text, convert using OpenCC `S2TWP` before showing or saving a caption. This applies to single-line, bilingual and cached results.
- No Simplified Chinese output mode, provider output mode or UI option is allowed.
- Chinese ASR output is also passed through OpenCC `S2TWP`; this is script and Taiwan-term conversion, not translation.
- Source-language labels and messages use Traditional Chinese/Taiwan wording.
- If translation fails, show a clear translation-error state. In source-plus-translation mode, keep the original source-language caption as the source line; never label it as a completed translation and never fall back to Simplified Chinese.

## Settings UI

Mirror AutoBuild 170's layout/interaction flow: enable switch, source-language selector, selected language model status, download/delete language pack, translation provider selector (without Youdao/Tencent), subtitle display mode, provider-specific settings for allowed providers only, translation self-test, and save/apply. Keep TV remote/D-pad navigation predictable. Localize the UI to Traditional Chinese and remove the Mainland Simplified output semantics.

## Runtime and reliability

- Use sherpa-onnx streaming recognizers for German/English/French/Spanish and appropriate offline recognizers for Cantonese/Japanese/Mandarin/Korean.
- PCM capture is bounded and processed on a dedicated worker; never run ASR in the AudioRecord callback.
- Expose model-load, audio-level, queued audio duration, ASR duration, last-result time and failures in diagnostic status.
- Keep queues bounded and discard stale segments when the recognizer cannot keep up; do not let the queue grow indefinitely.
- Add a watchdog that can reset the recognizer after a stalled inference without crashing or blocking video playback.
- Translate finalized text only; de-duplicate overlapping offline segments.
- Keep all changes on this branch and do not overwrite main or the stable app.

## POC 102 cleanup

This branch is intentionally reset to the clean `public-source-build` baseline before migration. Do not carry over the old Whisper.cpp POC 102 implementation, Whisper-native wrapper, old model manager, or old build artifact naming. GitHub's historical commits/old branches are not deleted as a recovery mechanism; the new build pipeline must not depend on or package those files.

## Acceptance criteria (do not call it fixed until all pass)

- [ ] Clean build succeeds with pinned source and sherpa-onnx v1.13.8.
- [ ] All eight model packs download with SHA-256 and byte-size validation.
- [ ] All eight languages produce recognized source text on actual device audio.
- [ ] The provider selector matches AutoBuild's layout and remaining options, with Youdao/Tencent removed from UI and dispatch.
- [ ] All translations displayed by the UI are Taiwan Traditional Chinese after S2TWP conversion.
- [ ] No Simplified Chinese caption display option exists.
- [ ] No infinite “still processing” state: watchdog reports and recovers from stalled inference.
- [ ] MPV playback audio/video remains normal while captions run.
- [ ] App passes arm64-v8a and armeabi-v7a build/asset checks.
