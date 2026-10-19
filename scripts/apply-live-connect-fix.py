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
            android:text="字幕翻譯：關" />'''
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
        listener = anchor + "        mBinding.control.actions.r15Subtitle.setOnClickListener(view -> AiSubtitleSettingsActivity.start(this));\n"
        if "mBinding.control.actions.r15Subtitle.setOnClickListener" not in source:
            if source.count(anchor) != 1:
                raise SystemExit(f"{activity}: cannot add subtitle settings click handler")
            source = source.replace(anchor, listener, 1)

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
                label = 'if (mBinding != null) mBinding.control.actions.r15Subtitle.setText(AiSubtitleSettingsActivity.isAutoStartEnabled(this) ? "字幕翻譯：開" : "字幕翻譯：關");'
                if "mBinding.control.actions.r15Subtitle.setText" not in body:
                    super_call = "super.onResume();"
                    pos = body.find(super_call)
                    insert_pos = body_start + pos + len(super_call) if pos >= 0 else body_start
                    source = source[:insert_pos] + "\n        " + label + source[insert_pos:]
        activity.write_text(source, encoding="utf-8")
    print("Added the subtitle settings entry to the bottom live-player control row.")



def patch_live_group_selection_and_switch(tv: Path) -> None:
    """Handle single-group playlists and retain the last frame during source changes."""
    old_position = """    private void setPosition(int[] position) {
        if (position[0] == -1) return;
        int size = mGroupAdapter.getItemCount();
        if (size == 1 || position[0] >= size) return;
        mGroup = mGroupAdapter.get(position[0]);
        mBinding.group.setSelectedPosition(position[0]);
        mGroup.setPosition(position[1]);
        mLive.selectGroup(mGroup);
        mLive.selectChannel(mGroup.current());
    }"""
    new_position = """    private void setPosition(int[] position) {
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
        mBinding.group.setSelectedPosition(groupPosition);
        mGroup.setPosition(channelPosition);
        mLive.selectGroup(mGroup);
        mLive.selectChannel(mGroup.current());
    }"""
    old_reset = """        player().reset();
        player().clear();
        player().stop();
        resetAdapter();"""
    new_reset = """        // Keep the current frame while the replacement channel loads.
        // Media3 is configured to retain content on reset to avoid a black flash.
        resetAdapter();"""
    for flavor in ("leanback", "mobile"):
        activity = tv / "app" / "src" / flavor / "java" / "com" / "fongmi" / "android" / "tv" / "ui" / "activity" / "LiveActivity.java"
        text = activity.read_text(encoding="utf-8")
        if old_position in text:
            text = text.replace(old_position, new_position, 1)
        elif new_position not in text:
            raise SystemExit(f"{activity}: setPosition() patch anchor missing")
        if old_reset in text:
            text = text.replace(old_reset, new_reset, 1)
        elif new_reset not in text:
            raise SystemExit(f"{activity}: source-switch reset patch anchor missing")
        activity.write_text(text, encoding="utf-8")
    print("Fixed single-group source selection and stopped clearing the current frame before the replacement channel is ready.")

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
    patch_live_player_keep_frame(tv)
    enable_jetifier(gradle_properties)

    # Sanity checks: menu label/icon, default wallpaper and duplicate-ID fix.
    if "連線" not in home_strings.read_text(encoding="utf-8"):
        raise SystemExit("home_push was not localized to 連線")
    if "R.drawable.ic_home_connect" not in func_java.read_text(encoding="utf-8"):
        raise SystemExit("home menu does not use the connect icon")
    if 'Prefers.getInt("wall", 4)' not in wallpaper_setting.read_text(encoding="utf-8"):
        raise SystemExit("wallpaper_4 is not the default wallpaper")
    if 'android:id="@+id/r15Subtitle"' not in (tv / "app/src/leanback/res/layout/view_control_live_action.xml").read_text(encoding="utf-8"):
        raise SystemExit("Bottom subtitle settings control is missing")
    if "android.enableJetifier=true" not in gradle_properties.read_text(encoding="utf-8"):
        raise SystemExit("Jetifier compatibility setting is missing")
    print("Applied home label/icon, Aurora default wallpaper and live-action duplicate-ID workaround.")
    print("Kept stock hardware acceleration and PlayerSetting renderer behavior; no old-Android renderer override is applied.")


if __name__ == "__main__":
    main()
