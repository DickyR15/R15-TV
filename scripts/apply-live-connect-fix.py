#!/usr/bin/env python3
"""Apply R15 TV home-connect wording, live-action ID fix and wallpaper defaults."""

from __future__ import annotations

import argparse
from pathlib import Path
import re
import shutil
import xml.etree.ElementTree as ET


def replace_exact(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    if new in text:
        return
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected exactly one patch anchor, found {count}: {old[:100]!r}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


def patch_home_label(path: Path) -> None:
    ET.register_namespace("xliff", "http://schemas.android.com/apk/res-auto")
    try:
        tree = ET.parse(path)
    except ET.ParseError as exc:
        raise SystemExit(f"Invalid localized strings XML {path}: {exc}") from exc
    matches = [
        node for node in tree.getroot().iter()
        if node.tag.rsplit("}", 1)[-1] == "string" and node.attrib.get("name") == "home_push"
    ]
    if len(matches) != 1:
        raise SystemExit(f"{path}: expected exactly one home_push string, found {len(matches)}")
    node = matches[0]
    if (node.text or "").strip() != "連線" or list(node):
        node.text = "連線"
        for child in list(node):
            node.remove(child)
        ET.indent(tree, space="    ")
        tree.write(path, encoding="utf-8", xml_declaration=True)


def patch_default_wallpaper(path: Path) -> None:
    replace_exact(
        path,
        'return Math.clamp(Prefers.getInt("wall", 1), MIN_WALL, MAX_WALL);',
        'return Math.clamp(Prefers.getInt("wall", 4), MIN_WALL, MAX_WALL);',
    )


def patch_force_exoplayer_diagnostic(tv: Path) -> None:
    """Disable MPV in this isolated diagnostic build to test the official ExoPlayer path."""
    path = tv / "app/src/main/java/com/fongmi/android/tv/player/engine/PlayerEngineFactory.java"
    if not path.is_file():
        raise SystemExit(f"Missing expected upstream player engine factory: {path}")
    text = path.read_text(encoding="utf-8")
    # This override is intentionally limited to the diagnostic branch, never the production branch.
    old = """    private static boolean isMpvReady() {
        return PlayerSetting.isMpv() && MpvPlayerEngine.isAvailable();
    }"""
    new = """    private static boolean isMpvReady() {
        // Diagnostic-only: force the upstream ExoPlayer route to isolate MPV/native crashes.
        return false;
    }"""
    if new not in text:
        count = text.count(old)
        if count != 1:
            raise SystemExit(
                f"{path}: expected exactly one MPV availability guard, found {count}"
            )
        text = text.replace(old, new, 1)
        path.write_text(text, encoding="utf-8")
    if new not in path.read_text(encoding="utf-8"):
        raise SystemExit("Diagnostic ExoPlayer-only guard was not applied")
    print("DIAGNOSTIC BUILD: MPV disabled; playback engine forced to official ExoPlayer.")
    print("Saved MPV preference is ignored in this diagnostic APK; ExoPlayer is selected for every source.")


def patch_force_static_wallpaper_diagnostic(tv: Path) -> None:
    """Force the built-in static wallpaper to isolate the background video player."""
    path = tv / "app/src/main/java/com/fongmi/android/tv/setting/Setting.java"
    if not path.is_file():
        raise SystemExit(f"Missing wallpaper settings source: {path}")
    text = path.read_text(encoding="utf-8")
    old = """    public static int getWallType() {
        return Math.clamp(Prefers.getInt("wall_type", 0), MIN_WALL_TYPE, MAX_WALL_TYPE);
    }"""
    new = """    public static int getWallType() {
        // Diagnostic-only: avoid initializing the separate animated-video wallpaper player.
        return MIN_WALL_TYPE;
    }"""
    if new not in text:
        count = text.count(old)
        if count != 1:
            raise SystemExit(f"{path}: expected exactly one wallpaper-type getter, found {count}")
        text = text.replace(old, new, 1)
        path.write_text(text, encoding="utf-8")
    if new not in path.read_text(encoding="utf-8"):
        raise SystemExit("Static-wallpaper diagnostic override was not applied")
    print("DIAGNOSTIC BUILD: video wallpaper disabled; built-in static wallpaper forced.")
    print("Hardware acceleration remains enabled; LiveActivity still uses official ExoPlayer.")


def patch_live_action_binding(tv: Path) -> None:
    """Avoid the duplicate action ID that can crash LiveActivity on some devices."""
    include_pattern = re.compile(
        r'(<include\s*\n\s*)android:id="@\+id/action"'
        r'(\s*\n\s*layout="@layout/view_control_live_action")'
    )
    for flavor in ("leanback", "mobile"):
        layout = tv / "app" / "src" / flavor / "res" / "layout" / "view_control_live.xml"
        activity = tv / "app" / "src" / flavor / "java" / "com" / "fongmi" / "android" / "tv" / "ui" / "activity" / "LiveActivity.java"
        if not layout.is_file() or not activity.is_file():
            raise SystemExit(f"Missing expected {flavor} live controls or LiveActivity source")

        xml = layout.read_text(encoding="utf-8")
        if 'android:id="@+id/actions"' not in xml:
            xml, count = include_pattern.subn(
                r'\1android:id="@+id/actions"\2',
                xml,
                count=1,
            )
            if count != 1:
                raise SystemExit(f"{layout}: expected one live-control include with duplicate action ID, found {count}")
            layout.write_text(xml, encoding="utf-8")

        source = activity.read_text(encoding="utf-8")
        old_prefix = "mBinding.control.action"
        new_prefix = "mBinding.control.actions"
        if old_prefix in source:
            source = source.replace(old_prefix, new_prefix)
            activity.write_text(source, encoding="utf-8")
        if re.search(r"\bmBinding\.control\.action(?=\.|$)", source):
            raise SystemExit(f"{activity}: stale duplicate live-control binding references remain")
        if 'android:id="@+id/actions"' not in layout.read_text(encoding="utf-8"):
            raise SystemExit(f"{layout}: unique actions include ID was not applied")
    print("Renamed the outer live-control include to actions in Leanback and Mobile, preserving inner action button IDs.")




def patch_bottom_subtitle_control(tv: Path) -> None:
    """Put the subtitle settings entry in the bottom live-player control row."""
    video_anchor = 'android:text="@string/play_track_video" />'
    subtitle_control = '''android:text="@string/play_track_video" />

        <com.google.android.material.textview.MaterialTextView
            android:id="@+id/r15Subtitle"
            style="@style/Control"
            android:layout_marginEnd="8dp"
            android:text="字幕翻譯：關" />

        <com.google.android.material.textview.MaterialTextView
            android:id="@+id/r15Language"
            style="@style/Control"
            android:layout_marginEnd="8dp"
            android:text="來源語種：英語" />'''
    for flavor in ("leanback", "mobile"):
        layout = tv / "app" / "src" / flavor / "res" / "layout" / "view_control_live_action.xml"
        activity = tv / "app" / "src" / flavor / "java" / "com" / "fongmi" / "android" / "tv" / "ui" / "activity" / "LiveActivity.java"
        if not layout.is_file() or not activity.is_file():
            raise SystemExit(f"Missing subtitle bottom-row resources for {flavor}")
        xml = layout.read_text(encoding="utf-8")
        if 'android:id="@+id/r15Subtitle"' not in xml:
            if xml.count(video_anchor) != 1:
                raise SystemExit(f"{layout}: expected one video-track button")
            layout.write_text(xml.replace(video_anchor, subtitle_control, 1), encoding="utf-8")

        source = activity.read_text(encoding="utf-8")
        anchor = "mBinding.control.actions.video.setOnClickListener(this::onTrack);\n"
        listener = anchor + "        mBinding.control.actions.r15Subtitle.setOnClickListener(view -> AiSubtitleOverlay.handleLiveSubtitleClick(this, mBinding.player, mBinding.control.actions.r15Subtitle));\n"
        if "mBinding.control.actions.r15Subtitle.setOnClickListener" not in source:
            if source.count(anchor) != 1:
                raise SystemExit(f"{activity}: cannot add subtitle settings click handler")
            source = source.replace(anchor, listener, 1)

        # Migrate an older bottom subtitle control from "open settings" to a quick action.
        source = source.replace(
            "mBinding.control.actions.r15Subtitle.setOnClickListener(view -> AiSubtitleSettingsActivity.start(this));",
            "mBinding.control.actions.r15Subtitle.setOnClickListener(view -> AiSubtitleOverlay.handleLiveSubtitleClick(this, mBinding.player, mBinding.control.actions.r15Subtitle));"
        )
        language_listener = "        mBinding.control.actions.r15Language.setOnClickListener(view -> AiSubtitleOverlay.handleLiveLanguageClick(this, mBinding.player, mBinding.control.actions.r15Language));\n"
        subtitle_listener = "        mBinding.control.actions.r15Subtitle.setOnClickListener(view -> AiSubtitleOverlay.handleLiveSubtitleClick(this, mBinding.player, mBinding.control.actions.r15Subtitle));\n"
        if "mBinding.control.actions.r15Language.setOnClickListener" not in source:
            if subtitle_listener.strip() not in source:
                raise SystemExit(f"{activity}: cannot add source-language click handler")
            source = source.replace(subtitle_listener, subtitle_listener + language_listener, 1)
        refresh_line = "AiSubtitleOverlay.refreshLiveControls(this, mBinding.player, mBinding.control.actions.r15Subtitle, mBinding.control.actions.r15Language);"
        if refresh_line not in source:
            if language_listener.strip() not in source:
                raise SystemExit(f"{activity}: source-language handler was not installed")
            source = source.replace(language_listener, language_listener + "\n        " + refresh_line, 1)

        # State refresh is optional because some upstream flavors do not override
        # onResume(). Do not fail the entire build just because the override is absent.
        signature = "protected void onResume() {"
        start = source.find(signature)
        if start < 0:
            signature = "public void onResume() {"
            start = source.find(signature)
        if start >= 0:
            body_start = start + len(signature)
            body_end = source.find("\n    }", body_start)
            if body_end >= 0:
                body = source[body_start:body_end]
                label = 'if (mBinding != null) AiSubtitleOverlay.refreshLiveControls(this, mBinding.player, mBinding.control.actions.r15Subtitle, mBinding.control.actions.r15Language);'
                if "mBinding.control.actions.r15Subtitle.setText" not in body:
                    super_call = "super.onResume();"
                    pos = body.find(super_call)
                    insert_pos = body_start + pos + len(super_call) if pos >= 0 else body_start
                    source = source[:insert_pos] + "\n        " + label + source[insert_pos:]
        activity.write_text(source, encoding="utf-8")
    print("Added the subtitle settings entry to the bottom live-player control row.")



def patch_live_group_selection_and_switch(tv: Path) -> None:
    """Handle single-group playlists and retain the last frame during source changes."""
    new_position_leanback = """    private void setPosition(int[] position) {
        int size = mGroupAdapter.getItemCount();
        if (size == 0) return;
        if (size == 1) {
            mGroup = mGroupAdapter.get(0);
            if (mGroup.getChannel().isEmpty()) return;
            mGroup.setPosition(0);
            mBinding.group.setSelectedPosition(0);
            mLive.selectGroup(mGroup);
            return; // Do not auto-start a channel while entering a single-group playlist.
        }
        int groupPosition = position == null || position.length == 0 ? -1 : position[0];
        if (groupPosition < 0 || groupPosition >= size) groupPosition = size > 1 ? 1 : 0;
        mGroup = mGroupAdapter.get(groupPosition);
        if (mGroup.getChannel().isEmpty()) {
            boolean found = false;
            for (int i = 0; i < size; i++) {
                Group candidate = mGroupAdapter.get(i);
                if (!candidate.getChannel().isEmpty()) {
                    groupPosition = i;
                    mGroup = candidate;
                    found = true;
                    break;
                }
            }
            if (!found) return;
        }
        int channelPosition = position != null && position.length > 1 ? position[1] : 0;
        if (channelPosition < 0 || channelPosition >= mGroup.getChannel().size()) channelPosition = 0;
        mBinding.group.setSelectedPosition(groupPosition);
        mGroup.setPosition(channelPosition);
        mLive.selectGroup(mGroup);
        // Do not autoplay on entering Live; an explicit channel click starts playback.
    }"""
    new_position_mobile = """    private void setPosition(int[] position) {
        int size = mGroupAdapter.getItemCount();
        if (size == 0) return;
        int groupPosition = position == null || position.length == 0 ? -1 : position[0];
        if (groupPosition < 0 || groupPosition >= size) groupPosition = size > 1 ? 1 : 0;
        mGroup = mGroupAdapter.get(groupPosition);
        if (mGroup.getChannel().isEmpty()) {
            boolean found = false;
            for (int i = 0; i < size; i++) {
                Group candidate = mGroupAdapter.get(i);
                if (!candidate.getChannel().isEmpty()) {
                    groupPosition = i;
                    mGroup = candidate;
                    found = true;
                    break;
                }
            }
            if (!found) return;
        }
        int channelPosition = position != null && position.length > 1 ? position[1] : 0;
        if (channelPosition < 0 || channelPosition >= mGroup.getChannel().size()) channelPosition = 0;
        mBinding.group.scrollToPosition(groupPosition);
        mGroup.setPosition(channelPosition);
        mLive.selectGroup(mGroup);
        // Do not autoplay on entering Live; an explicit channel click starts playback.
    }"""
    for flavor in ("leanback", "mobile"):
        activity = tv / "app" / "src" / flavor / "java" / "com" / "fongmi" / "android" / "tv" / "ui" / "activity" / "LiveActivity.java"
        text = activity.read_text(encoding="utf-8")
        method_start = text.find("    private void setPosition(int[] position) {")
        method_end = text.find("\n    private void setPosition() {", method_start)
        if method_start < 0 or method_end < 0:
            raise SystemExit(f"{activity}: cannot locate setPosition(int[]) method bounds")
        new_position = new_position_leanback if flavor == "leanback" else new_position_mobile
        text = text[:method_start] + new_position + text[method_end:]
        activity.write_text(text, encoding="utf-8")
    print("Fixed single-group source selection in Leanback and Mobile and retained the current frame during source switching.")


def patch_refresh_without_clearing(tv: Path) -> None:
    """Pause and retain the current video surface while a new live URL resolves."""
    for flavor in ("leanback", "mobile"):
        activity = tv / "app" / "src" / flavor / "java" / "com" / "fongmi" / "android" / "tv" / "ui" / "activity" / "LiveActivity.java"
        text = activity.read_text(encoding="utf-8")

        # The Mobile flavor has an extra checkControl() call in stopPlayer().
        # Patch only the teardown calls so flavor-specific behavior is retained.
        method_start = text.find("    private void stopPlayer() {")
        method_end = text.find("\n    }", method_start)
        if method_start < 0 or method_end < 0:
            raise SystemExit(f"{activity}: cannot locate stopPlayer()")
        method = text[method_start:method_end]
        old_pair = "        player().clear();\n        player().stop();"
        new_pair = "        // Retain the existing decoder/surface while the new URL resolves.\n        player().pause();"
        if old_pair in method:
            method = method.replace(old_pair, new_pair, 1)
        elif "player().pause();" not in method:
            raise SystemExit(f"{activity}: cannot safely patch stopPlaybackForRefresh()")
        text = text[:method_start] + method + text[method_end:]

        # Source activation calls setLive(); the base implementation also stopped
        # and cleared the player. Patch this method separately.
        method_start = text.find("    public void setLive(Live item) {")
        method_end = text.find("\n    }", method_start)
        if method_start < 0 or method_end < 0:
            raise SystemExit(f"{activity}: cannot locate setLive(Live item)")
        method = text[method_start:method_end]
        old_teardown = """        player().reset();
        player().clear();
        player().stop();"""
        if old_teardown in method:
            method = method.replace(old_teardown, "        player().pause();", 1)
        # Older revisions may already contain a reset inserted by a previous patch.
        method = method.replace("        player().pause();\n        player().reset();\n        resetAdapter();",
                                "        player().pause();\n        resetAdapter();")
        method = method.replace("        player().reset();\n        resetAdapter();",
                                "        player().pause();\n        resetAdapter();")
        before_adapter = method.split("        resetAdapter();", 1)[0]
        if "player().pause();" not in before_adapter:
            if "        resetAdapter();" not in method:
                raise SystemExit(f"{activity}: cannot locate setLive() adapter reset")
            method = method.replace("        resetAdapter();", "        player().pause();\n        resetAdapter();", 1)
        text = text[:method_start] + method + text[method_end:]
        activity.write_text(text, encoding="utf-8")
    print("Updated live channel refresh and source activation to pause without clearing/stopping the active media surface.")



def patch_android9_renderer_compat(tv: Path) -> None:
    """Use TextureView only on Android 9 and older; keep stock rendering on newer TVs."""
    path = tv / "app" / "src" / "main" / "java" / "com" / "fongmi" / "android" / "tv" / "ui" / "activity" / "PlaybackActivity.java"
    if not path.is_file():
        raise SystemExit(f"Missing shared PlaybackActivity source: {path}")
    text = path.read_text(encoding="utf-8")

    if "import android.os.Build;" not in text:
        anchor = "import android.os.Bundle;\n"
        if text.count(anchor) != 1:
            raise SystemExit(f"{path}: cannot safely add Build import")
        text = text.replace(anchor, "import android.os.Build;\n" + anchor, 1)

    old = "playerView.setRender(PlayerSetting.getRender());"
    new = "playerView.setRender(Build.VERSION.SDK_INT <= Build.VERSION_CODES.P ? PlayerSetting.RENDER_TEXTURE : PlayerSetting.getRender());"
    if new not in text:
        count = text.count(old)
        if count != 1:
            raise SystemExit(f"{path}: expected one renderer selection point, found {count}")
        text = text.replace(old, new, 1)
    path.write_text(text, encoding="utf-8")
    if new not in path.read_text(encoding="utf-8"):
        raise SystemExit("Android 9 TextureView compatibility setting was not applied")
    print("Applied TextureView compatibility on Android 9 and older; Android 10+ keeps the saved renderer.")

def patch_live_player_keep_frame(tv: Path) -> None:
    """Keep the previous decoded frame visible while a live channel/source resets."""
    for flavor in ("leanback", "mobile"):
        layout = tv / "app" / "src" / flavor / "res" / "layout" / "activity_live.xml"
        if not layout.is_file():
            raise SystemExit(f"Missing live playback layout: {layout}")
        text = layout.read_text(encoding="utf-8")
        attr = 'app:keep_content_on_player_reset="true"'
        if attr not in text:
            old = 'app:surface_type="none" />'
            count = text.count(old)
            if count != 1:
                raise SystemExit(f"{layout}: expected one Media3 PlayerView surface declaration, found {count}")
            text = text.replace(
                old,
                'app:surface_type="none"\n            app:keep_content_on_player_reset="true" />',
                1,
            )
            layout.write_text(text, encoding="utf-8")
        if attr not in layout.read_text(encoding="utf-8"):
            raise SystemExit(f"{layout}: keep_content_on_player_reset was not applied")
    print("Enabled Media3 keep-content-on-reset for Leanback and Mobile to reduce black flashes when live playback resets.")

def enable_jetifier(path: Path) -> None:
    text = path.read_text(encoding="utf-8") if path.exists() else ""
    lines = text.splitlines()
    additions = []
    for key, value in (("android.useAndroidX", "true"), ("android.enableJetifier", "true")):
        prefix = key + "="
        existing = [line.strip() for line in lines if line.strip().startswith(prefix)]
        if existing and existing[-1] != prefix + value:
            raise SystemExit(f"{path}: conflicting setting for {key}: {existing[-1]}")
        if not existing:
            additions.append(prefix + value)
    if additions:
        suffix = text
        if suffix and not suffix.endswith("\n"):
            suffix += "\n"
        path.write_text(suffix + "\n".join(additions) + "\n", encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--tv-dir", required=True, type=Path)
    parser.add_argument("--patch-dir", required=True, type=Path)
    args = parser.parse_args()
    tv = args.tv_dir
    patch_dir = args.patch_dir

    home_strings = tv / "app/src/leanback/res/values-zh-rTW/strings.xml"
    func_java = tv / "app/src/leanback/java/com/fongmi/android/tv/bean/Func.java"
    wallpaper_setting = tv / "app/src/main/java/com/fongmi/android/tv/setting/Setting.java"
    gradle_properties = tv / "gradle.properties"
    icon_source = patch_dir / "ic_home_connect.xml"
    icon_target = tv / "app/src/leanback/res/drawable/ic_home_connect.xml"

    for path in (home_strings, func_java, wallpaper_setting, icon_source):
        if not path.is_file():
            raise SystemExit(f"Missing expected R15 TV patch input: {path}")

    patch_home_label(home_strings)
    replace_exact(
        func_java,
        "else if (resId == R.string.home_push) this.drawable = R.drawable.ic_home_push;",
        "else if (resId == R.string.home_push) this.drawable = R.drawable.ic_home_connect;",
    )
    icon_target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(icon_source, icon_target)

    patch_default_wallpaper(wallpaper_setting)
    patch_live_action_binding(tv)
    patch_bottom_subtitle_control(tv)
    patch_live_group_selection_and_switch(tv)
    patch_force_exoplayer_diagnostic(tv)
    patch_force_static_wallpaper_diagnostic(tv)
    # Preserve upstream player stop/reset/clear behavior; pause-only teardown left stale state.
    patch_android9_renderer_compat(tv)
    patch_live_player_keep_frame(tv)
    enable_jetifier(gradle_properties)

    # Sanity checks: menu label/icon, default wallpaper and duplicate-ID fix.
    if "連線" not in home_strings.read_text(encoding="utf-8"):
        raise SystemExit("home_push was not localized to 連線")
    if "R.drawable.ic_home_connect" not in func_java.read_text(encoding="utf-8"):
        raise SystemExit("home menu does not use the connect icon")
    if 'Prefers.getInt("wall", 4)' not in wallpaper_setting.read_text(encoding="utf-8"):
        raise SystemExit("wallpaper_4 is not the default wallpaper")
    if "return MIN_WALL_TYPE;" not in wallpaper_setting.read_text(encoding="utf-8"):
        raise SystemExit("static wallpaper diagnostic override is missing")
    if "Build.VERSION_CODES.P ? PlayerSetting.RENDER_TEXTURE" not in (tv / "app/src/main/java/com/fongmi/android/tv/ui/activity/PlaybackActivity.java").read_text(encoding="utf-8"):
        raise SystemExit("Android 9 renderer compatibility setting is missing")
    if 'android:id="@+id/r15Subtitle"' not in (tv / "app/src/leanback/res/layout/view_control_live_action.xml").read_text(encoding="utf-8"):
        raise SystemExit("Bottom subtitle settings control is missing")
    if "android.enableJetifier=true" not in gradle_properties.read_text(encoding="utf-8"):
        raise SystemExit("Jetifier compatibility setting is missing")
    if 'android:id="@+id/r15Language"' not in (tv / "app/src/leanback/res/layout/view_control_live_action.xml").read_text(encoding="utf-8"):
        raise SystemExit("Bottom source-language control is missing")
    if "AiSubtitleOverlay.handleLiveSubtitleClick" not in (tv / "app/src/leanback/java/com/fongmi/android/tv/ui/activity/LiveActivity.java").read_text(encoding="utf-8"):
        raise SystemExit("Quick subtitle toggle is not wired")
    print("Applied home label/icon and quick subtitle controls.")
    print("Applied the Android 9 TextureView fallback; Android 10+ keeps the configured renderer.")


if __name__ == "__main__":
    main()
