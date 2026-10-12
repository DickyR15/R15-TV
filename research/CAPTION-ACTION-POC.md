# Caption Action POC — R15 TV / F1

## Decision
Prioritize evaluating [Hatsunama/Caption-Action](https://github.com/Hatsunama/Caption-Action) for English F1 live commentary to Traditional Chinese subtitles.

## Keep stable work untouched
- Do not modify or merge into `main`.
- Keep the existing R15 TV player and MPV support.
- This branch is for research and validation only.

## Why it is promising
Upstream README currently describes source version 0.3.28 (versionCode 51), minSdk 26, playback audio capture through Android MediaProjection/AudioPlaybackCapture, on-device whisper.cpp ASR, on-device Google ML Kit translation, and a movable/resizable overlay. The live model gate defaults to Whisper Tiny/Balanced; this is not the same as the previously planned Whisper Base path.

## Important release caveat
The upstream README's bootstrap notes that its promoted APK may still point to the older `v0.1.0-mvp` release while the source tree is newer. Do not assume a release APK matches source version 0.3.28; inspect the release assets/version before installing. If needed, build the pinned source in CI and keep the APK as a separate test artifact.

## Validation order
1. First try Caption Action as a standalone overlay on the onn. 4K Pro while R15 TV is playing an English F1 stream.
2. Grant overlay and device-audio/screen-capture permissions. Confirm that it captures playback audio without microphone fallback.
3. Test English -> Traditional Chinese translation, subtitle continuity, latency, and remote-control usability for at least 10 minutes.
4. Check 4K playback stability and CPU/memory pressure.
5. If system playback capture is blocked for the chosen player/device, do not keep tuning Whisper; investigate native audio access inside the R15 TV/MPV pipeline instead.
6. Only after standalone validation, decide whether to port selected modules into R15 TV. Do not copy the entire app blindly.

## Acceptance criteria
- Correctly captures audio from the actual IPTV/MPV playback path.
- Traditional Chinese translations remain visible and readable.
- No persistent subtitle freeze or runaway backlog during a 10-minute session.
- No unacceptable 4K playback degradation.
- App can be controlled or stopped with the TV remote.
- Build and license notices are recorded before any source reuse/distribution.

## Upstream
- Repository: https://github.com/Hatsunama/Caption-Action
- Current README reviewed: 2026-10-12
