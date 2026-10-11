# R15 TV — TV-AutoBuild feature integration research

This branch is an isolated research branch. It does not replace `main` or the established MPV build branch.

Reference project: https://github.com/iptvorganization/TV-AutoBuild

## Goals

Investigate the following feature groups for R15 TV while preserving the existing MPV/ExoPlayer architecture:

1. **Real-time AI subtitles** — investigate sherpa-onnx on-device speech recognition first. Speech recognition, subtitle timing/rendering, and translation into Traditional Chinese are separate tasks. Do not advertise translation as supported until a translation backend and end-to-end validation are in place.
2. **Codec compatibility** — investigate AV3A, HEVC-FLV and FFmpeg/Media3 integration for the existing supported ABIs. Check codec licence/source availability, native dependencies, decoder registration, performance and actual playback.
3. **Dolby Vision** — investigate container/bitstream handling, decoder capability negotiation, display/HDR output, and fallback behavior. Successful APK packaging alone does not mean Dolby Vision playback has been validated.
4. **MPV preservation** — maintain the current MPV source/dependency path. Do not remove the MPV modules or silently force all content through ExoPlayer.

## Findings from the public TV-AutoBuild repository

The public build orchestrator declares these monitored inputs in `config/sources.json`:

- `iptvorganization/TV` branch `fongmi`
- `iptvorganization/media` branch `codec-support`
- `iptvorganization/VividLib` branch `master`
- `iptvorganization/sherpa-onnx` branch `master`

Its source-resolution script requires a `PRIVATE_SOURCE_TOKEN`; therefore, the public orchestration repository is not the same thing as public access to every customised source input. This branch must use public sources or source dependencies that we have lawful access to and must not assume the private custom code can be fetched.

The orchestrator also shows useful build practices worth adapting: pin exact source revisions, compile JNI/runtime dependencies per ABI, run tests before assembling, verify APK contents/signatures, and publish source revision metadata/checksums.

## Implementation order

### Stage 0 — baseline and guardrails
- Keep this work on the dedicated research branch.
- Record exact TV/media/MPV source commits and source licenses.
- Preserve four existing output variants and package identity behavior.
- Add static/build checks before changing runtime behavior.

### Stage 1 — AI subtitle feasibility
- Identify app's player lifecycle, audio access, subtitle overlay and preferences.
- Evaluate on-device sherpa-onnx support for arm64-v8a and armeabi-v7a.
- Prototype opt-in speech transcription, keeping playback responsive and providing a clear stop/off setting.
- Treat translation as a separate plug-in/backend; avoid hard-coding a cloud service or API key.
- Validate language models, memory footprint, latency, offline behavior, licensing and TV remote usability.

### Stage 2 — audio/video codec compatibility
- Inspect available public AV3A decoder implementation and licensing before integrating.
- Establish a reproducible FFmpeg/Media3 build with the required decoders per ABI.
- Add format and native-library checks to CI.
- Test codecs in controlled samples on emulator/device; record unsupported combinations clearly.

### Stage 3 — Dolby Vision and integration
- Determine which Dolby Vision profiles, containers and Android hardware can be supported.
- Confirm fallback behavior on unsupported displays/decoders.
- Test MPV and ExoPlayer paths separately, including subtitle and audio behavior.
- Only after successful testing, expose the settings/features as stable.

## Status

Research plan only. It does not claim that AI subtitles, translation, AV3A, HEVC-FLV or Dolby Vision have already been implemented or tested. Use successful CI builds plus actual device playback tests before calling any feature complete.
