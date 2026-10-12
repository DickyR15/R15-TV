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
    field_new = "    private int retry;\n    private int mpvDecodeFailuresForCurrentMedia;\n    private boolean fallbackExoForCurrentMedia;\n"
    if field_new not in text:
        text = replace_once(text, field_anchor, field_new, path)

    old_set_engine = """    public void setEngine(int targetEngine) {
        PlayerSetting.putEngine(targetEngine);"""
    new_set_engine = """    public void setEngine(int targetEngine) {
        fallbackExoForCurrentMedia = false;
        mpvDecodeFailuresForCurrentMedia = 0;
        PlayerSetting.putEngine(targetEngine);"""
    if new_set_engine not in text:
        text = replace_once(text, old_set_engine, new_set_engine, path)

    old_browse = """    public void browse(PlaySpec spec, long startPositionMs) {
        reset();"""
    new_browse = """    public void browse(PlaySpec spec, long startPositionMs) {
        fallbackExoForCurrentMedia = false;
        mpvDecodeFailuresForCurrentMedia = 0;
        reset();"""
    if new_browse not in text:
        text = replace_once(text, old_browse, new_browse, path)

    old_start = """    public void start(PlaySpec spec, long timeout, long startPositionMs) {
        this.spec = spec;"""
    new_start = """    public void start(PlaySpec spec, long timeout, long startPositionMs) {
        fallbackExoForCurrentMedia = false;
        mpvDecodeFailuresForCurrentMedia = 0;
        this.spec = spec;"""
    if new_start not in text:
        text = replace_once(text, old_start, new_start, path)

    old_parse = """    public void parse(String key, Result result, boolean useParse, MediaMetadata metadata, long startPositionMs) {
        stopParse();"""
    new_parse = """    public void parse(String key, Result result, boolean useParse, MediaMetadata metadata, long startPositionMs) {
        fallbackExoForCurrentMedia = false;
        mpvDecodeFailuresForCurrentMedia = 0;
        stopParse();"""
    if new_parse not in text:
        text = replace_once(text, old_parse, new_parse, path)

    old_error = """    private void handleDecodeError(PlaybackException e) {
        if (++retry > 1) callback.onError(engine.getErrorMessage(e));
        else retryDecode(isHard() ? PlayerEngine.SOFT : PlayerEngine.HARD);
    }"""
    new_error = """    private void handleDecodeError(PlaybackException e) {
        if (engine != null && engine.getType() == PlayerEngine.Type.MPV && !fallbackExoForCurrentMedia) {
            // Count across Player.STATE_READY resets so repeated MPV decoder errors actually trigger fallback.
            mpvDecodeFailuresForCurrentMedia++;
            boolean unsupportedFormat = e.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES
                    || e.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED;
            if (mpvDecodeFailuresForCurrentMedia >= 2 || unsupportedFormat) {
                // Keep MPV as the user's preference, but use ExoPlayer for this one failing source.
                fallbackExoForCurrentMedia = true;
                decode = PlayerEngine.HARD;
                PlaybackSnapshot snapshot = PlaybackSnapshot.capture(player);
                startCurrent(snapshot.positionMs());
                snapshot.restore(player);
                callback.onDecodeChanged();
                Notify.show("MPV 解碼失敗，已暫時切換 EXO 播放此來源");
            } else {
                retryDecode(isHard() ? PlayerEngine.SOFT : PlayerEngine.HARD);
            }
            return;
        }
        if (++retry > 1) callback.onError(engine.getErrorMessage(e));
        else retryDecode(isHard() ? PlayerEngine.SOFT : PlayerEngine.HARD);
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


def patch_player_setting(path: Path) -> None:
    text = path.read_text(encoding="utf-8")
    constants_anchor = "    private static final int MAX_BUFFER = 10;\n"
    constants_new = constants_anchor + """    public static final int PLAYBACK_QUALITY_STABLE_1080P = 0;
    public static final int PLAYBACK_QUALITY_ADAPTIVE = 1;
    public static final int PLAYBACK_QUALITY_MAXIMUM = 2;
"""
    if "PLAYBACK_QUALITY_STABLE_1080P" not in text:
        text = replace_once(text, constants_anchor, constants_new, path)

    method_anchor = "    public static int getScale() {\n"
    methods = """    public static int getPlaybackQuality() {
        return Math.clamp(Prefers.getInt("r15_playback_quality", PLAYBACK_QUALITY_STABLE_1080P),
                PLAYBACK_QUALITY_STABLE_1080P, PLAYBACK_QUALITY_MAXIMUM);
    }

    public static void putPlaybackQuality(int mode) {
        Prefers.put("r15_playback_quality", Math.clamp(mode,
                PLAYBACK_QUALITY_ADAPTIVE, PLAYBACK_QUALITY_MAXIMUM));
    }

    public static String getPlaybackQualityText() {
        return switch (getPlaybackQuality()) {
            case PLAYBACK_QUALITY_ADAPTIVE -> "自動適應（允許 4K）";
            case PLAYBACK_QUALITY_MAXIMUM -> "最高畫質（可能卡頓）";
            default -> "1080p 優先（建議，減少卡頓）";
        };
    }

"""
    if "public static int getPlaybackQuality()" not in text:
        text = replace_once(text, method_anchor, methods + method_anchor, path)
    path.write_text(text, encoding="utf-8")


def patch_setting_activity(path: Path) -> None:
    text = path.read_text(encoding="utf-8")
    listener_anchor = "        mBinding.player.setOnClickListener(this::onPlayer);\n"
    listener_new = listener_anchor + "        mBinding.aiSubtitle.setOnClickListener(this::onAiSubtitle);\n"
    if listener_new not in text:
        text = replace_once(text, listener_anchor, listener_new, path)
    method_anchor = "    private void onDanmaku(View view) {\n"
    method = """    private void onAiSubtitle(View view) {
        AiSubtitleSettingsActivity.start(this);
    }

"""
    if "private void onAiSubtitle(View view)" not in text:
        text = replace_once(text, method_anchor, method + method_anchor, path)
    path.write_text(text, encoding="utf-8")


def patch_setting_activity_layout(path: Path) -> None:
    text = path.read_text(encoding="utf-8")
    root_anchor = '''    <androidx.appcompat.widget.LinearLayoutCompat
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:orientation="vertical"
        android:padding="24dp">'''
    row = '''        
        <androidx.appcompat.widget.LinearLayoutCompat
            android:id="@+id/aiSubtitle"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginBottom="16dp"
            android:background="@drawable/selector_item"
            android:contentDescription="AI 即時字幕／語音設定"
            android:focusable="true"
            android:focusableInTouchMode="true"
            android:orientation="vertical"
            android:padding="14dp">

            <com.google.android.material.textview.MaterialTextView
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:text="AI 即時字幕／語音設定"
                android:textColor="@color/white"
                android:textSize="20sp" />

            <com.google.android.material.textview.MaterialTextView
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="4dp"
                android:text="Whisper Tiny Q5_1 · Google ML Kit · OpenCC 臺灣繁體"
                android:textColor="#FFBFD7FF"
                android:textSize="14sp" />

        </androidx.appcompat.widget.LinearLayoutCompat>
'''
    if 'android:id="@+id/aiSubtitle"' not in text:
        text = replace_once(text, root_anchor, root_anchor + "\n" + row, path)
    path.write_text(text, encoding="utf-8")


def patch_setting_decode_activity(path: Path) -> None:
    text = path.read_text(encoding="utf-8")
    event_anchor = "        mBinding.dolbyVisionOutput.setOnClickListener(this::setDolbyVisionOutput);\n"
    event_new = event_anchor + "        mBinding.playbackQuality.setOnClickListener(this::setPlaybackQuality);\n"
    if event_new not in text:
        text = replace_once(text, event_anchor, event_new, path)
    refresh_anchor = """        mBinding.dolbyVisionOutputText.setText(ResUtil.getStringArray(R.array.select_dolby_vision_output)[DecodeSetting.getDolbyVisionOutputPolicy()]);
        mBinding.audioPassThroughText.setText(Setting.getSwitch(DecodeSetting.isAudioPassThrough()));"""
    refresh_new = """        mBinding.dolbyVisionOutputText.setText(ResUtil.getStringArray(R.array.select_dolby_vision_output)[DecodeSetting.getDolbyVisionOutputPolicy()]);
        mBinding.playbackQualityText.setText(PlayerSetting.getPlaybackQualityText());
        mBinding.audioPassThroughText.setText(Setting.getSwitch(DecodeSetting.isAudioPassThrough()));"""
    if refresh_new not in text:
        text = replace_once(text, refresh_anchor, refresh_new, path)
    method_anchor = "    private void setAAC(View view) {\n"
    method = """    private void setPlaybackQuality(View view) {
        int next = (PlayerSetting.getPlaybackQuality() + 1)
                % (PlayerSetting.PLAYBACK_QUALITY_MAXIMUM + 1);
        PlayerSetting.putPlaybackQuality(next);
        mBinding.playbackQualityText.setText(PlayerSetting.getPlaybackQualityText());
    }

"""
    if "private void setPlaybackQuality(View view)" not in text:
        text = replace_once(text, method_anchor, method + method_anchor, path)
    path.write_text(text, encoding="utf-8")


def patch_setting_decode_layout(path: Path) -> None:
    text = path.read_text(encoding="utf-8")
    anchor = '''        <androidx.appcompat.widget.LinearLayoutCompat
            android:id="@+id/aac"'''
    row = '''        <androidx.appcompat.widget.LinearLayoutCompat
            android:id="@+id/playbackQuality"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginBottom="16dp"
            android:background="@drawable/selector_item"
            android:contentDescription="切換 EXO/HLS 畫質策略"
            android:focusable="true"
            android:focusableInTouchMode="true"
            android:orientation="horizontal">

            <com.google.android.material.textview.MaterialTextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_marginEnd="16dp"
                android:text="EXO／HLS 畫質策略"
                android:textColor="@color/white"
                android:textSize="18sp" />

            <com.google.android.material.textview.MaterialTextView
                android:id="@+id/playbackQualityText"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:gravity="end"
                android:textColor="@color/white"
                android:textSize="18sp"
                tools:text="自動適應（建議）" />

        </androidx.appcompat.widget.LinearLayoutCompat>

'''
    if 'android:id="@+id/playbackQuality"' not in text:
        text = replace_once(text, anchor, row + anchor, path)
    path.write_text(text, encoding="utf-8")


def patch_mpv_engine(path: Path) -> None:
    text = path.read_text(encoding="utf-8")
    old = "case PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED, PlaybackException.ERROR_CODE_DECODING_FAILED -> ErrorAction.DECODE;"
    new = "case PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED, PlaybackException.ERROR_CODE_DECODING_FAILED, PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES, PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> ErrorAction.DECODE;"
    if new not in text:
        text = replace_once(text, old, new, path)
    path.write_text(text, encoding="utf-8")


def patch_manifest(path: Path) -> None:
    text = path.read_text(encoding="utf-8")
    permissions = [
        '<uses-permission android:name="android.permission.RECORD_AUDIO" />',
        '<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />',
        '<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION" />',
    ]
    for permission in permissions:
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
    crash_anchor = '        <activity\n            android:name=".ui.activity.CrashActivity"'
    activity = '        <activity android:name=".ui.activity.AiSubtitleSettingsActivity" android:exported="false" />'
    if activity not in text:
        text = replace_once(text, crash_anchor, activity + '\n\n' + crash_anchor, path)
    service = ('        <service android:name=".ai.subtitle.AiSubtitleProjectionService" '
               'android:exported="false" android:foregroundServiceType="mediaProjection" />')
    if service not in text:
        text = replace_once(text, crash_anchor, service + '\n\n' + crash_anchor, path)
    path.write_text(text, encoding="utf-8")


def patch_exo_adaptive_bitrate(path: Path) -> None:
    """Cap EXO/HLS at 1080p by default; allow adaptive and maximum quality options."""
    text = path.read_text(encoding="utf-8")
    if "case PlayerSetting.PLAYBACK_QUALITY_STABLE_1080P:" in text:
        return
    old = "builder.setForceHighestSupportedBitrate(true);"
    new = """switch (PlayerSetting.getPlaybackQuality()) {
            case PlayerSetting.PLAYBACK_QUALITY_ADAPTIVE:
                builder.clearVideoSizeConstraints();
                builder.setForceHighestSupportedBitrate(false);
                break;
            case PlayerSetting.PLAYBACK_QUALITY_MAXIMUM:
                builder.clearVideoSizeConstraints();
                builder.setForceHighestSupportedBitrate(true);
                break;
            case PlayerSetting.PLAYBACK_QUALITY_STABLE_1080P:
            default:
                builder.setMaxVideoSize(1920, 1080);
                builder.setForceHighestSupportedBitrate(false);
                break;
        }"""
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
    mpv_engine = tv / "app/src/main/java/com/fongmi/android/tv/player/mpv/MpvPlayerEngine.java"
    player_setting = tv / "app/src/main/java/com/fongmi/android/tv/setting/PlayerSetting.java"
    setting_activity = tv / "app/src/leanback/java/com/fongmi/android/tv/ui/activity/SettingActivity.java"
    setting_activity_layout = tv / "app/src/leanback/res/layout/activity_setting.xml"
    setting_decode_activity = tv / "app/src/leanback/java/com/fongmi/android/tv/ui/activity/SettingDecodeActivity.java"
    setting_decode_layout = tv / "app/src/leanback/res/layout/activity_setting_decode.xml"

    activities = [leanback_video, mobile_video, leanback_live, mobile_live]
    required = activities + [manifest, gradle, proguard, exo_util, player_manager, mpv_engine,
                             player_setting, setting_activity, setting_activity_layout,
                             setting_decode_activity, setting_decode_layout]
    for path in required:
        if not path.is_file():
            raise SystemExit(f"Missing required upstream source file: {path}")

    java_target = tv / "app/src/main/java/com/fongmi/android/tv/ai/subtitle"
    java_target.mkdir(parents=True, exist_ok=True)
    settings_activity_target = tv / "app/src/leanback/java/com/fongmi/android/tv/ui/activity/AiSubtitleSettingsActivity.java"
    settings_activity_target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(patch_dir / "AiSubtitleSettingsActivity.java", settings_activity_target)
    settings_layout_target = tv / "app/src/leanback/res/layout/activity_ai_subtitle_settings.xml"
    settings_layout_target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(patch_dir / "activity_ai_subtitle_settings.xml", settings_layout_target)
    for name in (
        "AiSubtitleEngine.java",
        "AiSubtitleOverlay.java",
        "AiSubtitlePlaybackCapture.java",
        "AiSubtitleProjectionService.java",
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
    patch_player_setting(player_setting)
    patch_setting_activity(setting_activity)
    patch_setting_activity_layout(setting_activity_layout)
    patch_setting_decode_activity(setting_decode_activity)
    patch_setting_decode_layout(setting_decode_layout)
    # VideoActivity is VOD; LiveActivity is the M3U/M3U8 live-TV route.
    for activity_path in activities:
        patch_player_activity(activity_path)
    patch_exo_adaptive_bitrate(exo_util)
    patch_mpv_engine(mpv_engine)
    patch_player_manager(player_manager)

    for path in activities:
        patched = path.read_text(encoding="utf-8")
        if "AiSubtitleOverlay.attach(this, mBinding.player);" not in patched:
            raise SystemExit(f"{path}: attach call missing after patch")
        if "AiSubtitleOverlay.detach(mBinding.player);" not in patched:
            raise SystemExit(f"{path}: detach call missing after patch")

    exo_text = exo_util.read_text(encoding="utf-8")
    if "PLAYBACK_QUALITY_MAXIMUM" not in exo_text or "setForceHighestSupportedBitrate(false)" not in exo_text:
        raise SystemExit(f"{exo_util}: adaptive quality policy was not applied")
    manager_text = player_manager.read_text(encoding="utf-8")
    if "fallbackExoForCurrentMedia" not in manager_text or "MPV 解碼失敗，已暫時切換 EXO" not in manager_text:
        raise SystemExit(f"{player_manager}: per-source MPV-to-Exo fallback was not applied")
    if "ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES" not in mpv_engine.read_text(encoding="utf-8"):
        raise SystemExit(f"{mpv_engine}: unsupported 4K format errors were not handled")
    if "mBinding.aiSubtitle.setOnClickListener(this::onAiSubtitle);" not in setting_activity.read_text(encoding="utf-8"):
        raise SystemExit(f"{setting_activity}: AI subtitle entry was not applied")
    if "PLAYBACK_QUALITY_STABLE_1080P" not in player_setting.read_text(encoding="utf-8"):
        raise SystemExit(f"{player_setting}: playback quality setting was not applied")

    print("Applied Whisper Tiny Q5_1 subtitle POC with first-row Settings entry and high-contrast playback shortcut.")
    print("EXO/HLS quality defaults to a 1080p cap for multi-variant HLS, with adaptive and maximum options.")
    print("MPV unsupported 4K formats can fall back to EXO.")
    print("Scope: in-app playback audio capture + downloadable Whisper Tiny Q5_1 multilingual model + ML Kit on-device English-to-Chinese translation + OpenCC S2TWP.")
    print("ML Kit downloads its translation model on first use; translation does not require a paid API key.")
    print("Audio capture filters to the R15 TV process UID and requires Android 10+, RECORD_AUDIO and MediaProjection consent.")


if __name__ == "__main__":
    main()
