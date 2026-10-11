#!/usr/bin/env python3
"""Apply the isolated R15 TV AI-subtitle prototype to a checked-out FongMi/TV tree."""
from __future__ import annotations

import argparse
import shutil
from pathlib import Path


def require_replace(text: str, old: str, new: str, path: Path) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected exactly one patch anchor, found {count}: {old!r}")
    return text.replace(old, new, 1)


def patch_video_activity(path: Path) -> None:
    text = path.read_text(encoding="utf-8")
    import_anchor = "import com.fongmi.android.tv.App;\n"
    if "import com.fongmi.android.tv.ai.subtitle.AiSubtitleOverlay;" not in text:
        text = require_replace(
            text,
            import_anchor,
            import_anchor + "import com.fongmi.android.tv.ai.subtitle.AiSubtitleOverlay;\n",
            path,
        )

    init_anchor = "protected void initView(Bundle savedInstanceState) {"
    init_start = text.find(init_anchor)
    if init_start < 0:
        raise SystemExit(f"{path}: cannot locate initView")
    init_end = text.find("\n    }", init_start)
    init_body = text[init_start:init_end if init_end >= 0 else len(text)]
    attach_line = "AiSubtitleOverlay.attach(this, mBinding.player);"
    if attach_line not in init_body:
        marker = "setVideoView();"
        marker_pos = text.find(marker, init_start)
        if marker_pos < 0 or (init_end >= 0 and marker_pos > init_end):
            # The mobile variant has a different view initialization order in some upstream revisions.
            marker = "mBinding.player.setPlayer("
            marker_pos = text.find(marker, init_start)
            if marker_pos < 0:
                raise SystemExit(f"{path}: cannot find a safe player initialization anchor")
            line_end = text.find("\n", marker_pos)
            text = text[:line_end + 1] + "        " + attach_line + "\n" + text[line_end + 1:]
        else:
            line_end = marker_pos + len(marker)
            text = text[:line_end] + "\n        " + attach_line + text[line_end:]

    destroy_anchor = "protected void onDestroy() {"
    destroy_start = text.find(destroy_anchor)
    if destroy_start < 0:
        raise SystemExit(f"{path}: cannot locate onDestroy")
    destroy_body_start = destroy_start + len(destroy_anchor)
    destroy_body_end = text.find("\n    }", destroy_body_start)
    destroy_body = text[destroy_body_start:destroy_body_end if destroy_body_end >= 0 else len(text)]
    detach_line = "AiSubtitleOverlay.detach(mBinding.player);"
    if detach_line not in destroy_body:
        text = text[:destroy_body_start] + "\n        " + detach_line + text[destroy_body_start:]

    path.write_text(text, encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--tv-dir", required=True, type=Path)
    parser.add_argument("--patch-dir", required=True, type=Path)
    args = parser.parse_args()

    tv = args.tv_dir.resolve()
    patch_dir = args.patch_dir.resolve()
    required = [
        tv / "app/src/main/java/com/fongmi/android/tv/player/effect/audio/AudioEffectProcessor.java",
        tv / "app/src/main/java/com/fongmi/android/tv/ui/activity/VideoActivity.java",
        tv / "app/src/leanback/java/com/fongmi/android/tv/ui/activity/VideoActivity.java",
        tv / "app/src/mobile/java/com/fongmi/android/tv/ui/activity/VideoActivity.java",
        tv / "app/build.gradle",
        tv / "app/proguard-rules.pro",
        tv / "app/src/main/assets/ai-subtitle-model/encoder-epoch-99-avg-1.int8.onnx",
        tv / "app/src/main/assets/ai-subtitle-model/decoder-epoch-99-avg-1.onnx",
        tv / "app/src/main/assets/ai-subtitle-model/joiner-epoch-99-avg-1.int8.onnx",
        tv / "app/src/main/assets/ai-subtitle-model/tokens.txt",
    ]
    for path in required:
        if not path.is_file():
            raise SystemExit(f"Missing required upstream input/model: {path}")

    java_target = tv / "app/src/main/java/com/fongmi/android/tv/ai/subtitle"
    java_target.mkdir(parents=True, exist_ok=True)
    for name in ("AiSubtitleEngine.java", "AiSubtitleOverlay.java"):
        shutil.copy2(patch_dir / name, java_target / name)

    processor = required[0]
    text = processor.read_text(encoding="utf-8")
    import_anchor = "import java.nio.ByteBuffer;\n"
    if "import com.fongmi.android.tv.ai.subtitle.AiSubtitleEngine;" not in text:
        text = require_replace(
            text,
            import_anchor,
            import_anchor + "import com.fongmi.android.tv.ai.subtitle.AiSubtitleEngine;\n",
            processor,
        )
    tap_anchor = "        int remaining = inputBuffer.remaining();\n"
    tap_call = (
        tap_anchor
        + "        try {\n"
        + "            AiSubtitleEngine.offerPcm(inputBuffer.asReadOnlyBuffer(), inputAudioFormat.sampleRate, inputAudioFormat.channelCount, inputAudioFormat.encoding);\n"
        + "        } catch (Throwable ignored) {\n"
        + "            // AI captions must never interrupt ordinary audio playback.\n"
        + "        }\n"
    )
    if "AiSubtitleEngine.offerPcm(" not in text:
        text = require_replace(text, tap_anchor, tap_call, processor)
    processor.write_text(text, encoding="utf-8")

    gradle = tv / "app/build.gradle"
    text = gradle.read_text(encoding="utf-8")
    old = 'implementation fileTree(dir: "libs", include: ["*.aar"])'
    new = 'implementation fileTree(dir: "libs", include: ["*.aar", "*.jar"])'
    if old in text:
        text = require_replace(text, old, new, gradle)
    elif new not in text:
        raise SystemExit(f"{gradle}: cannot locate local library dependency declaration")
    gradle.write_text(text, encoding="utf-8")

    proguard = tv / "app/proguard-rules.pro"
    text = proguard.read_text(encoding="utf-8")
    keep = "\n# Keep sherpa-onnx Java/JNI config classes and fields used by native reflection.\n-keep class com.k2fsa.sherpa.onnx.** { *; }\n"
    if "-keep class com.k2fsa.sherpa.onnx.**" not in text:
        proguard.write_text(text.rstrip() + keep, encoding="utf-8")

    for path in (
        tv / "app/src/leanback/java/com/fongmi/android/tv/ui/activity/VideoActivity.java",
        tv / "app/src/mobile/java/com/fongmi/android/tv/ui/activity/VideoActivity.java",
    ):
        patch_video_activity(path)

    print("Applied experimental AI speech-to-text subtitle patch.")
    print("Scope: decoded ExoPlayer PCM only; MPV playback remains unchanged and is not yet tapped.")
    print("Translation is not included in this prototype.")


if __name__ == "__main__":
    main()
