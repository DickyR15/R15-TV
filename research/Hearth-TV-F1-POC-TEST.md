# Hearth on TCL Google TV — F1 subtitle proof-of-concept runbook

**Purpose:** verify whether the TV can capture the audio of an F1 stream, recognize English commentary locally, and attach a local Chinese translation before we change R15 TV's MPV pipeline.

**This test does not modify R15 TV's player code.** The official Hearth app is a temporary validation tool only. No paid/cloud API is needed when the local speech engine and local translator are selected.

## 1. Install the test app

Use the official Hearth 0.23.9 Cloud + Local universal QA APK:

- APK: <https://github.com/Sal7one/Hearth/releases/download/v0.23.9/hearth-0.23.9-play-qa-universal.apk>
- Release page and checksums: <https://github.com/Sal7one/Hearth/releases/tag/v0.23.9>

The Play flavor is recommended for the first test because model downloads are supported in the app. **Choose local models and do not configure a cloud provider or API key.** The separate FOSS QA build cannot download models from inside the app and is less convenient for this first TV test.

Install the APK on the TCL Google TV using a trusted USB/APK transfer method. Only install the official APK from the project release page. Keep the source URL/version; do not rename it and present it as an R15 TV build.

## 2. Configure fully local speech recognition

In Hearth:

1. Open **Settings → Models → Speech**.
2. Choose **Whisper** and download an English model, starting with `base.en` in a Q5/Q8 quantization if offered. Use a larger model only if the TV maintains real-time throughput.
3. Open **Live captions**. Set audio source to **Device audio**, speech engine to **Whisper**, and mode to original-language captions first.
4. Grant the system's playback-capture / MediaProjection consent when requested. If the OS provides an overlay permission, enable it so captions can appear over the video.
5. Start a non-DRM English F1 clip first. Return to the player and confirm that English captions appear over its sound.

If there is no usable playback sound, repeat once with a simple non-protected local video or YouTube clip. If those work but the F1 streaming app stays silent, that suggests the F1 app/content or TV firmware is blocking playback capture; it is not proof that ASR failed.

## 3. Turn on local translation

After original English captions work, start with the lower-complexity option:

1. Open **Settings → Models → Translation** and install the **on-device ML Kit English → Chinese language packs**. This is the simplest free local inference test in the Play flavor; it is not a fully open-source translator, but it does not require a paid cloud API or send each caption to a translation API.
2. In the caption translation settings, choose **ML Kit** as the local translator, set target language to Chinese (`zh`), and enable the translation bridge. Do not select Cloud/BYOK or enter any API key.
3. Retry the same clip. To verify offline inference, use a locally stored test video and turn off Wi-Fi after the language packs are installed and loaded; the captions and translation should continue. Do not turn off Wi-Fi during the actual live stream, because the stream itself needs an internet connection.
4. Only if ML Kit translation is too weak for F1 terms, additionally test **Hy-MT2 Q2_K** from the local model catalog (about 777 MB download, 1.8B-class model). This can use materially more RAM than the file size suggests, so do not make it the first test on the TV.

The 75C755 product listing identifies 3 GB RAM, so use one lightweight recognizer and the ML Kit translator first; evaluate the larger local model only after the baseline works. The exact RAM available to apps varies with firmware and background activity.

Hearth's documented UI normalizes Chinese script variants to `zh` and does not offer a dedicated Traditional Chinese output selector for these model families. Therefore, record whether the output is Simplified or Traditional. If it is Simplified, that is a known gap to fix in R15 TV's eventual pipeline with OpenCC's Taiwan conversion (`s2twp`); OpenCC performs script/wording conversion and is not the English translator.

## 4. Test the actual F1 live source

After the baseline succeeds:

- Start the intended F1 live stream, start Hearth's Device audio caption capture, then return to the live stream and observe the subtitle overlay.
- Test both normal commentary and a team-radio segment if available.
- Confirm that ordinary playback audio continues unchanged; stop subtitles and ensure capture/overlay terminates.
- Test with the network disconnected after model setup if practical. A cloud API or provider key must not be configured.

A failure on protected audio or on an app that opts out of capture is an Android capture-policy limitation. The final R15 TV integration should instead investigate a PCM tap inside the player's own MPV/libmpv audio path.

## 5. Results to record

Please record these fields in a test note. Do not infer success just because an overlay is visible. The initial goal is to validate audio capture and local pipeline operation, not to benchmark the final R15 TV build.

| Check | Result |
|---|---|
| TCL Google TV OS version / available free storage | |
| Hearth version / APK flavor | |
| Whisper model + quantization | |
| Local translation model | |
| Non-DRM clip: playback audio captured? | |
| Non-DRM clip: English captions accurate enough? | |
| F1 live app: playback audio captured? | |
| F1 live app: subtitles continue over the video? | |
| Chinese output: Simplified or Traditional? | |
| Approximate caption/translation delay | |
| CPU/RAM lag, audio stutter, or app crash | |
| Captions/translation continue when network is disabled? | |

## 6. Decision after the test

- **Audio capture works and local inference keeps up:** move to a minimal R15 TV MPV integration with separate stages: PCM tap → ASR → completed phrase → local translation → F1 terminology corrections → OpenCC Taiwan conversion → in-player subtitle overlay.
- **Hearth captures the clip but not the F1 app:** prioritize an in-player MPV PCM tap; do not waste time tuning the recognizer for a source whose audio cannot be captured externally.
- **ASR works but translation falls behind:** benchmark a smaller translator / reduce translation cadence / translate only finalized phrases.
- **TV compute is insufficient:** consider running the same free local models on a home PC over LAN, with the TV only rendering captions. This avoids paid APIs but does require a PC running during the session.

## Important limitations

Hearth's released TV screenshots document a TV UI demonstration, not a guarantee that the full speech-and-translation pipeline has been physically validated on this specific TCL model. This runbook is a real-device validation procedure. The CI build of Hearth/SUBRIMA/OpenTV passed, but that does not replace the on-TV capture, latency, language-quality and stability tests.

Primary docs: [Hearth local models](https://github.com/Sal7one/Hearth/blob/main/docs/models.md), [translation bridge](https://github.com/Sal7one/Hearth/blob/main/docs/local-translation.md), [TV support](https://github.com/Sal7one/Hearth/blob/main/docs/android-abi-support.md).
