#!/usr/bin/env python3
"""Apply the isolated AudioPlaybackCapture POC to a pinned FongMi/TV tree."""
from __future__ import annotations

import argparse
import shutil
from pathlib import Path


def replace_once(text: str, old: str, new: str, path: Path) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one patch anchor, got {count}: {old!r}")
    return text.replace(old, new, 1)


def patch_video_activity(path: Path) -> None:
    text = path.read_text(encoding="utf-8")
    import_anchor = "import com.fongmi.android.tv.App;\n"
    import_line = "import com.fongmi.android.tv.ai.subtitle.AiSubtitleOverlay;\n"
    if import_line not in text:
        text = replace_once(text, import_anchor, import_anchor + import_line, path)

    init_anchor = "protected void initView(Bundle savedInstanceState) {"
    init_start = text.find(init_anchor)
    if init_start < 0:
        raise SystemExit(f"{path}: cannot locate initView")
    init_end = text.find("\n    }", init_start)
    init_body_end = init_end if init_end >= 0 else len(text)
    init_body = text[init_start:init_body_end]
    attach_line = "AiSubtitleOverlay.attach(this, mBinding.player);"
    if attach_line not in init_body:
        marker = "setVideoView();"
        marker_pos = text.find(marker, init_start, init_body_end)
        if marker_pos < 0:
            marker = "mBinding.player.setPlayer("
            marker_pos = text.find(marker, init_start, init_body_end)
            if marker_pos < 0:
                raise SystemExit(f"{path}: cannot find a safe player initialization anchor")
            line_end = text.find("\n", marker_pos)
            if line_end < 0:
                raise SystemExit(f"{path}: player initialization has no line ending")
            text = text[:line_end + 1] + "        " + attach_line + "\n" + text[line_end + 1:]
        else:
            line_end = marker_pos + len(marker)
            text = text[:line_end] + "\n        " + attach_line + text[line_end:]

    destroy_anchor = "protected void onDestroy() {"
    destroy_start = text.find(destroy_anchor)
    if destroy_start < 0:
        raise SystemExit(f"{path}: cannot locate onDestroy")
    body_start = destroy_start + len(destroy_anchor)
    destroy_end = text.find("\n    }", body_start)
    destroy_body = text[body_start:destroy_end if destroy_end >= 0 else len(text)]
    detach_line = "AiSubtitleOverlay.detach(mBinding.player);"
    if detach_line not in destroy_body:
        text = text[:body_start] + "\n        " + detach_line + text[body_start:]

    path.write_text(text, encoding="utf-8")


def patch_manifest(path: Path) -> None:
    text = path.read_text(encoding="utf-8")
    permission = '<uses-permission android:name="android.permission.RECORD_AUDIO" />'
    if permission not in text:
        text = replace_once(
            text,
            '<uses-permission android:name="android.permission.INTERNET" />',
            '<uses-permission android:name="android.permission.INTERNET" />\n    ' + permission,
            path,
        )
    attr = 'android:allowAudioPlaybackCapture="true"'
    if attr not in text:
        text = replace_once(
            text,
            'android:allowBackup="false"',
            'android:allowBackup="false"\n        ' + attr,
            path,
        )
    path.write_text(text, encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--tv-dir", required=True, type=Path)
    parser.add_argument("--patch-dir", required=True, type=Path)
    args = parser.parse_args()

    tv = args.tv_dir.resolve()
    patch_dir = args.patch_dir.resolve()
    leanback = tv / "app/src/leanback/java/com/fongmi/android/tv/ui/activity/VideoActivity.java"
    mobile = tv / "app/src/mobile/java/com/fongmi/android/tv/ui/activity/VideoActivity.java"
    manifest = tv / "app/src/main/AndroidManifest.xml"
    gradle = tv / "app/build.gradle"
    proguard = tv / "app/proguard-rules.pro"

    required = [leanback, mobile, manifest, gradle, proguard]
    for path in required:
        if not path.is_file():
            raise SystemExit(f"Missing required upstream source file: {path}")

    java_target = tv / "app/src/main/java/com/fongmi/android/tv/ai/subtitle"
    java_target.mkdir(parents=True, exist_ok=True)
    for name in (
        "AiSubtitleEngine.java",
        "AiSubtitleOverlay.java",
        "AiSubtitlePlaybackCapture.java",
        "AiSubtitleTranslationEngine.java",
        "WhisperCpp.java",
        "WhisperModelManager.java",
    ):
        source = patch_dir / name
        if not source.is_file():
            raise SystemExit(f"Missing POC source file: {source}")
        shutil.copy2(source, java_target / name)

    text = gradle.read_text(encoding="utf-8")
    old = 'implementation fileTree(dir: "libs", include: ["*.aar"])'
    new = 'implementation fileTree(dir: "libs", include: ["*.aar", "*.jar"])'
    if old in text:
        text = replace_once(text, old, new, gradle)
    elif new not in text:
        raise SystemExit(f"{gradle}: cannot locate local AAR dependency declaration")

    dependency_anchor = 'implementation fileTree(dir: "libs", include: ["*.aar", "*.jar"])'
    required_dependencies = [
        "implementation 'com.google.mlkit:translate:17.0.3'",
        "implementation 'com.github.qichuan:android-opencc:1.2.0'",
    ]
    missing_dependencies = [dep for dep in required_dependencies if dep not in text]
    if missing_dependencies:
        text = replace_once(
            text,
            dependency_anchor,
            dependency_anchor + "\n    " + "\n    ".join(missing_dependencies),
            gradle,
        )
    gradle.write_text(text, encoding="utf-8")

    text = proguard.read_text(encoding="utf-8")
    keep_rules = [
        "# Keep Whisper JNI names stable for native method resolution.",
        "-keep class com.fongmi.android.tv.ai.subtitle.WhisperCpp { *; }",
        "-keep class com.zqc.opencc.android.lib.** { *; }",
    ]
    missing_keep_rules = [rule for rule in keep_rules if rule not in text]
    if missing_keep_rules:
        text = text.rstrip() + "\n\n" + "\n".join(missing_keep_rules) + "\n"
        proguard.write_text(text, encoding="utf-8")

    patch_manifest(manifest)
    patch_video_activity(leanback)
    patch_video_activity(mobile)

    for path in (leanback, mobile):
        patched = path.read_text(encoding="utf-8")
        if "AiSubtitleOverlay.attach(this, mBinding.player);" not in patched:
            raise SystemExit(f"{path}: attach call missing after patch")
        if "AiSubtitleOverlay.detach(mBinding.player);" not in patched:
            raise SystemExit(f"{path}: detach call missing after patch")

    print("Applied MPV-compatible Whisper Base subtitle POC.")
    print("Scope: in-app playback audio capture + downloadable Whisper Base multilingual model + ML Kit on-device English-to-Chinese translation + OpenCC S2TWP.")
    print("ML Kit downloads its translation model on first use; translation does not require a paid API key.")
    print("Audio capture filters to the R15 TV process UID and requires Android 10+, RECORD_AUDIO and MediaProjection consent.")


if __name__ == "__main__":
    main()
