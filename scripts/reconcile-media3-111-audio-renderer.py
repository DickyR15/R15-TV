#!/usr/bin/env python3
"""Reconcile FongMi's timestamp adjustment with Media3 1.11.1 codec buffer handling."""
from __future__ import annotations

import argparse
from pathlib import Path


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--media-dir", type=Path, required=True)
    args = parser.parse_args()
    path = args.media_dir / "libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/MediaCodecAudioRenderer.java"
    source = path.read_text(encoding="utf-8")
    start_token = "    long audioPresentationTimeUs = getAudioPresentationTimeUs(bufferPresentationTimeUs);"
    end_token = "    if (fullyConsumed) {"
    start = source.find(start_token)
    if start < 0:
        raise SystemExit(f"{path}: missing FongMi audio presentation time anchor")
    end = source.find(end_token, start)
    if end < 0:
        raise SystemExit(f"{path}: missing fullyConsumed branch")

    block = '''    long audioPresentationTimeUs = getAudioPresentationTimeUs(bufferPresentationTimeUs);
    try {
      ThrowingRunnable<Exception> handleBufferOperation =
          () ->
              processOutputBufferResultHolder.set(
                  audioSink.handleBuffer(buffer, audioPresentationTimeUs, sampleCount));
      if (codec != null) {
        codec.useBuffer(handleBufferOperation);
      } else {
        handleBufferOperation.run();
      }
    } catch (InitializationException e) {
      throw createRendererException(
          e,
          inputFormat,
          e.isRecoverable,
          isBypassEnabled()
                  && getConfiguration().offloadModePreferred != AudioSink.OFFLOAD_MODE_DISABLED
              ? PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_INIT_FAILED
              : PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED);
    } catch (WriteException e) {
      throw createRendererException(
          e,
          format,
          e.isRecoverable,
          isBypassEnabled()
                  && getConfiguration().offloadModePreferred != AudioSink.OFFLOAD_MODE_DISABLED
              ? PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_WRITE_FAILED
              : PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED);
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }

    boolean fullyConsumed = processOutputBufferResultHolder.get();

'''
    source = source[:start] + block + source[end:]
    declaration = "boolean fullyConsumed = processOutputBufferResultHolder.get();"
    if source.count(declaration) != 1:
        raise SystemExit("Media3 audio result must be declared exactly once")
    if "codec.useBuffer(handleBufferOperation)" not in source:
        raise SystemExit("Media3 codec buffer serialization was not retained")
    if "getAudioPresentationTimeUs(bufferPresentationTimeUs)" not in source:
        raise SystemExit("FongMi audio timestamp adjustment was not retained")
    path.write_text(source, encoding="utf-8")
    print("Reconciled Media3 1.11.1 audio output-buffer serialization and retained FongMi timestamp adjustment.")


if __name__ == "__main__":
    main()
