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


def patch_player_activity(path: Path) -> None:
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



def patch_player_manager(path: Path) -> None:
    """Temporarily fall back to ExoPlayer if MPV fails both HW and SW decoder attempts."""
    text = path.read_text(encoding="utf-8")
    field_anchor = "    private int retry;\n"
    field_new = "    private int retry;\n    private boolean fallbackExoForCurrentMedia;\n"
    if field_new not in text:
        text = replace_once(text, field_anchor, field_new, path)

    old_set_engine = """    public void setEngine(int targetEngine) {
        PlayerSetting.putEngine(targetEngine);"""
    new_set_engine = """    public void setEngine(int targetEngine) {
        fallbackExoForCurrentMedia = false;
        PlayerSetting.putEngine(targetEngine);"""
    if new_set_engine not in text:
        text = replace_once(text, old_set_engine, new_set_engine, path)

    old_browse = """    public void browse(PlaySpec spec, long startPositionMs) {
        reset();"""
    new_browse = """    public void browse(PlaySpec spec, long startPositionMs) {
        fallbackExoForCurrentMedia = false;
        reset();"""
    if new_browse not in text:
        text = replace_once(text, old_browse, new_browse, path)

    old_start = """    public void start(PlaySpec spec, long timeout, long startPositionMs) {
        this.spec = spec;"""
    new_start = """    public void start(PlaySpec spec, long timeout, long startPositionMs) {
        fallbackExoForCurrentMedia = false;
        this.spec = spec;"""
    if new_start not in text:
        text = replace_once(text, old_start, new_start, path)

    old_parse = """    public void parse(String key, Result result, boolean useParse, MediaMetadata metadata, long startPositionMs) {
        stopParse();"""
    new_parse = """    public void parse(String key, Result result, boolean useParse, MediaMetadata metadata, long startPositionMs) {
        fallbackExoForCurrentMedia = false;
        stopParse();"""
    if new_parse not in text:
        text = replace_once(text, old_parse, new_parse, path)

    old_error = """    private void handleDecodeError(PlaybackException e) {
        if (++retry > 1) callback.onError(engine.getErrorMessage(e));
        else retryDecode(isHard() ? PlayerEngine.SOFT : PlayerEngine.HARD);
    }"""
    new_error = """    private void handleDecodeError(PlaybackException e) {
        if (++retry > 1) {
            if (engine != null && engine.getType() == PlayerEngine.Type.MPV && !fallbackExoForCurrentMedia) {
                // Keep MPV as the user's preference, but use ExoPlayer for this one failing source.
                fallbackExoForCurrentMedia = true;
                decode = PlayerEngine.HARD;
                PlaybackSnapshot snapshot = PlaybackSnapshot.capture(player);
                startCurrent(snapshot.positionMs());
                snapshot.restore(player);
                Notify.show("MPV 解碼失敗，已暫時切換 EXO 播放此來源");
            } else {
                callback.onError(engine.getErrorMessage(e));
            }
        } else {
            retryDecode(isHard() ? PlayerEngine.SOFT : PlayerEngine.HARD);
        }
    }"""
    if new_error not in text:
        text = replace_once(text, old_error, new_error, path)

    old_ensure = """    private void ensureEngine(PlaySpec spec) {
        if (PlayerEngineFactory.matches(engine, spec)) return;
        PlayerEngine old = engine;
        player.removeListener(listener);
        engine = PlayerEngineFactory.create(decode, spec, listener);
        setPlayer(engine.getPlayer());
        old.release();
    }"""
    new_ensure = """    private void ensureEngine(PlaySpec spec) {
        if (fallbackExoForCurrentMedia) {
            if (engine != null && engine.getType() == PlayerEngine.Type.EXO) return;
            PlayerEngine old = engine;
            player.removeListener(listener);
            engine = PlayerEngineFactory.create(decode, PlayerEngine.Type.EXO, listener);
            setPlayer(engine.getPlayer());
            old.release();
            return;
        }
        if (PlayerEngineFactory.matches(engine, spec)) return;
        PlayerEngine old = engine;
        player.removeListener(listener);
        engine = PlayerEngineFactory.create(decode, spec, listener);
        setPlayer(engine.getPlayer());
        old.release();
    }"""
    if new_ensure not in text:
        text = replace_once(text, old_ensure, new_ensure, path)

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




def patch_exo_adaptive_bitrate(path: Path) -> None:
    """Let ExoPlayer adapt HLS quality to measured throughput instead of forcing 4K."""
    text = path.read_text(encoding="utf-8")
    old = "builder.setForceHighestSupportedBitrate(true);"
    new = "builder.setForceHighestSupportedBitrate(false);"
    if new in text:
        return
    text = replace_once(text, old, new, path)
    path.write_text(text, encoding="utf-8")

def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--tv-dir", required=True, type=Path)
    parser.add_argument("--patch-dir", required=True, type=Path)
    args = parser.parse_args()

    tv = args.tv_dir.resolve()
    patch_dir = args.patch_dir.resolve()
    leanback_video = tv / "app/src/leanback/java/com/fongmi/android/tv/ui/activity/VideoActivity.java"
    mobile_video = tv / "app/src/mobile/java/com/fongmi/android/tv/ui/activity/VideoActivity.java"
    leanback_live = tv / "app/src/leanback/java/com/fongmi/android/tv/ui/activity/LiveActivity.java"
    mobile_live = tv / "app/src/mobile/java/com/fongmi/android/tv/ui/activity/LiveActivity.java"
    manifest = tv / "app/src/main/AndroidManifest.xml"
    gradle = tv / "app/build.gradle"
    proguard = tv / "app/proguard-rules.pro"
    exo_util = tv / "app/src/main/java/com/fongmi/android/tv/player/exo/ExoUtil.java"
    player_manager = tv / "app/src/main/java/com/fongmi/android/tv/player/PlayerManager.java"

    activities = [leanback_video, mobile_video, leanback_live, mobile_live]
    required = activities + [manifest, gradle, proguard, exo_util, player_manager]
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
    # VideoActivity is the VOD/detail route. LiveActivity is the M3U/M3U8 live-TV route.
    # Attach to both so the AI subtitle controls are available while watching live channels.
    for activity_path in activities:
        patch_player_activity(activity_path)
    # On adaptive HLS streams, avoid forcing the highest bitrate when it causes rebuffering.
    patch_exo_adaptive_bitrate(exo_util)
    # If MPV still fails after HW/SW decode retry, fall back to Exo for this item only.
    patch_player_manager(player_manager)

    for path in activities:
        patched = path.read_text(encoding="utf-8")
        if "AiSubtitleOverlay.attach(this, mBinding.player);" not in patched:
            raise SystemExit(f"{path}: attach call missing after patch")
        if "AiSubtitleOverlay.detach(mBinding.player);" not in patched:
            raise SystemExit(f"{path}: detach call missing after patch")

    exo_text = exo_util.read_text(encoding="utf-8")
    if "builder.setForceHighestSupportedBitrate(false);" not in exo_text:
        raise SystemExit(f"{exo_util}: adaptive HLS bitrate selection was not enabled")
    manager_text = player_manager.read_text(encoding="utf-8")
    if "fallbackExoForCurrentMedia" not in manager_text or "MPV 解碼失敗，已暫時切換 EXO" not in manager_text:
        raise SystemExit(f"{player_manager}: per-source MPV-to-Exo fallback was not applied")

    print("Applied Whisper Base subtitle POC to VOD and LiveActivity (M3U/M3U8).")
    print("ExoPlayer adaptive HLS bitrate selection enabled to avoid forcing a high-bitrate variant.")
    print("Scope: in-app playback audio capture + downloadable Whisper Base multilingual model + ML Kit on-device English-to-Chinese translation + OpenCC S2TWP.")
    print("ML Kit downloads its translation model on first use; translation does not require a paid API key.")
    print("Audio capture filters to the R15 TV process UID and requires Android 10+, RECORD_AUDIO and MediaProjection consent.")


if __name__ == "__main__":
    main()
