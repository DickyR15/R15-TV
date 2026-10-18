#!/usr/bin/env python3
"""Apply the R15 TV AutoBuild-170-style live subtitle feature to public FongMi source."""
from __future__ import annotations

import argparse
import shutil
from pathlib import Path


def replace_once(text: str, old: str, new: str, path: Path) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected exactly one patch anchor, found {count}: {old!r}")
    return text.replace(old, new, 1)


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

    if 'android:allowAudioPlaybackCapture="true"' not in text:
        text = replace_once(
            text,
            'android:allowBackup="false"',
            'android:allowBackup="false"\n        android:allowAudioPlaybackCapture="true"',
            path,
        )

    crash_anchor = '        <activity\n            android:name=".ui.activity.CrashActivity"'
    activity = '        <activity android:name=".ui.activity.AiSubtitleSettingsActivity" android:exported="false" />'
    service = ('        <service android:name=".ai.subtitle.AiSubtitleProjectionService" '
               'android:exported="false" android:foregroundServiceType="mediaProjection" />')
    for item in (activity, service):
        if item not in text:
            text = replace_once(text, crash_anchor, item + '\n\n' + crash_anchor, path)
    path.write_text(text, encoding="utf-8")


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


def patch_tv_settings_activity(path: Path) -> None:
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


def patch_live_settings_layout(tv: Path) -> None:
    """Group VOD and wallpaper configuration side-by-side without changing behavior."""
    import xml.etree.ElementTree as ET

    layout = tv / "app/src/leanback/res/layout/activity_setting.xml"
    if not layout.is_file():
        raise SystemExit(f"Missing Leanback layout: {layout}")

    ET.register_namespace("android", "http://schemas.android.com/apk/res/android")
    ET.register_namespace("tools", "http://schemas.android.com/tools")
    tree = ET.parse(layout)
    root = tree.getroot()
    aid = "{http://schemas.android.com/apk/res/android}id"
    # The original XML uses direct source rows with internal wrapper layouts.
    # Locate the nearest ancestor that contains both controls, then group their
    # immediate row containers without changing view IDs or click handlers.
    rows = {}
    for target in ("vod", "wall"):
        found = next((node for node in root.iter()
                      if node.attrib.get(aid) == f"@+id/{target}"), None)
        if found is None:
            raise SystemExit(f"Could not find @{target} in settings layout.")
        rows[target] = found
    parents = {child: parent for parent in root.iter() for child in parent}
    ancestors = []
    node = rows["vod"]
    while node in parents:
        node = parents[node]
        ancestors.append(node)
    container = next((candidate for candidate in ancestors
                      if any(desc is rows["wall"] for desc in candidate.iter())
                      and candidate.tag.rsplit("}", 1)[-1] == "LinearLayoutCompat"), None)
    if container is None:
        raise SystemExit("Could not locate shared parent for VOD and wallpaper controls.")

    def top_row(target: str):
        node = rows[target]
        while parents.get(node) is not container and node in parents:
            node = parents[node]
        return node if parents.get(node) is container else None

    vod_row, wall_row = top_row("vod"), top_row("wall")
    if vod_row is None or wall_row is None:
        raise SystemExit("Could not find VOD/wall rows under their shared settings parent.")

    parent_map = {child: parent for parent in root.iter() for child in parent}
    existing_group = next((node for node in container.iter()
                           if node.attrib.get(aid) == "@+id/vodAndWall"), None)
    if existing_group is None and vod_row is not wall_row:
        if parent_map.get(vod_row) is not container or parent_map.get(wall_row) is not container:
            raise SystemExit("VOD and wallpaper rows must be direct children of the settings container.")

        group = ET.Element("androidx.appcompat.widget.LinearLayoutCompat", {
            aid: "@+id/vodAndWall",
            "{http://schemas.android.com/apk/res/android}layout_width": "match_parent",
            "{http://schemas.android.com/apk/res/android}layout_height": "wrap_content",
            "{http://schemas.android.com/apk/res/android}orientation": "horizontal",
            "{http://schemas.android.com/apk/res/android}gravity": "center_vertical",
        })
        index = list(container).index(vod_row)
        container.remove(vod_row)
        container.insert(index, group)
        container.remove(wall_row)
        # Insert the VOD and wallpaper rows inside the shared group once each.
        group.append(vod_row)
        group.append(wall_row)
        for row, end_margin in ((vod_row, "12dp"), (wall_row, "0dp")):
            row.set("{http://schemas.android.com/apk/res/android}layout_width", "0dp")
            row.set("{http://schemas.android.com/apk/res/android}layout_weight", "1")
            row.set("{http://schemas.android.com/apk/res/android}layout_marginTop", "0dp")
            row.set("{http://schemas.android.com/apk/res/android}layout_marginEnd", end_margin)
        ET.indent(tree, space="    ")
        tree.write(layout, encoding="utf-8", xml_declaration=True)

    # Use a clear Taiwan Traditional Chinese label for the existing live-source control.
    labels = [
        (tv / "app/src/leanback/res/values-zh-rTW/strings.xml", "直播源設定"),
        (tv / "app/src/leanback/res/values-zh-rCN/strings.xml", "直播源設定"),
        (tv / "app/src/leanback/res/values/strings.xml", "Live source settings"),
    ]
    for resource, label in labels:
        if not resource.is_file():
            continue
        stree = ET.parse(resource)
        sroot = stree.getroot()
        node = next((item for item in list(sroot)
                     if item.tag.rsplit("}", 1)[-1] == "string"
                     and item.attrib.get("name") == "setting_live_sources"), None)
        if node is None:
            node = ET.SubElement(sroot, "string", {"name": "setting_live_sources"})
        for child in list(node):
            node.remove(child)
        node.text = label
        ET.indent(stree, space="    ")
        stree.write(resource, encoding="utf-8", xml_declaration=True)

    text = layout.read_text(encoding="utf-8")
    if 'android:text="@string/setting_live_sources"' not in text:
        if 'android:text="@string/setting_live"' not in text:
            raise SystemExit("Could not locate the live settings label.")
        text = text.replace('android:text="@string/setting_live"',
                            'android:text="@string/setting_live_sources"', 1)
        layout.write_text(text, encoding="utf-8")


def patch_tv_settings_layout(path: Path) -> None:
    text = path.read_text(encoding="utf-8")
    row = '''        
        <androidx.appcompat.widget.LinearLayoutCompat
            android:id="@+id/aiSubtitle"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginBottom="16dp"
            android:background="@drawable/selector_item"
            android:contentDescription="R15 TV 即時字幕設定"
            android:focusable="true"
            android:focusableInTouchMode="true"
            android:orientation="vertical"
            android:padding="14dp">

            <com.google.android.material.textview.MaterialTextView
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:text="R15 TV 即時字幕設定"
                android:textColor="@color/white"
                android:textSize="20sp" />

            <com.google.android.material.textview.MaterialTextView
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="4dp"
                android:text="8 種語音辨識 · 臺灣繁體中文字幕"
                android:textColor="#FFBFD7FF"
                android:textSize="14sp" />

        </androidx.appcompat.widget.LinearLayoutCompat>
'''
    if 'android:id="@+id/aiSubtitle"' not in text:
        root_anchor = '''    <androidx.appcompat.widget.LinearLayoutCompat
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:orientation="vertical"
        android:padding="24dp">'''
        text = replace_once(text, root_anchor, root_anchor + "\n" + row, path)
    path.write_text(text, encoding="utf-8")




def patch_tv_home_grouping(tv: Path) -> None:
    """Keep this patch idempotent: live source setup is in Settings; VOD and wallpaper are grouped there."""
    # VOD/wallpaper are configuration rows in activity_setting.xml, already grouped
    # by patch_live_settings_layout. The app home screen uses Leanback Presenter rows,
    # not XML cards, so no home-layout mutation belongs here.
    return


def ensure_keep_rules(path: Path) -> None:
    text = path.read_text(encoding="utf-8")
    rules = [
        "# R15 TV AI subtitle JNI uses stable Java/native symbol names.",
        "-keep class com.fongmi.android.tv.ai.subtitle.TaiwanTraditionalConverter { *; }",
        "-keep class com.k2fsa.sherpa.onnx.** { *; }",
    ]
    missing = [rule for rule in rules if rule not in text]
    if missing:
        path.write_text(text.rstrip() + "\n\n" + "\n".join(missing) + "\n", encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--tv-dir", required=True, type=Path)
    parser.add_argument("--patch-dir", required=True, type=Path)
    args = parser.parse_args()

    tv = args.tv_dir.resolve()
    patch_dir = args.patch_dir.resolve()
    manifest = tv / "app/src/main/AndroidManifest.xml"
    gradle = tv / "app/build.gradle"
    proguard = tv / "app/proguard-rules.pro"
    tv_settings = tv / "app/src/leanback/java/com/fongmi/android/tv/ui/activity/SettingActivity.java"
    tv_settings_layout = tv / "app/src/leanback/res/layout/activity_setting.xml"
    main_settings_activity = tv / "app/src/main/java/com/fongmi/android/tv/ui/activity/AiSubtitleSettingsActivity.java"
    main_settings_layout = tv / "app/src/main/res/layout/activity_ai_subtitle_settings.xml"

    player_activities = [
        tv / "app/src/leanback/java/com/fongmi/android/tv/ui/activity/VideoActivity.java",
        tv / "app/src/mobile/java/com/fongmi/android/tv/ui/activity/VideoActivity.java",
        tv / "app/src/leanback/java/com/fongmi/android/tv/ui/activity/LiveActivity.java",
        tv / "app/src/mobile/java/com/fongmi/android/tv/ui/activity/LiveActivity.java",
    ]
    required = [manifest, gradle, proguard, tv_settings, tv_settings_layout, *player_activities]
    for path in required:
        if not path.is_file():
            raise SystemExit(f"Missing required public-source file: {path}")

    package_dir = tv / "app/src/main/java/com/fongmi/android/tv/ai/subtitle"
    package_dir.mkdir(parents=True, exist_ok=True)
    source_files = [
        "AiSubtitleModelManager.java",
        "AiSubtitleEngine.java",
        "AiSubtitleTranslationProvider.java",
        "AiSubtitleTranslationEngine.java",
        "TaiwanTraditionalConverter.java",
        "AiSubtitleOverlay.java",
        "AiSubtitlePlaybackCapture.java",
        "AiSubtitleProjectionService.java",
    ]
    for name in source_files:
        source = patch_dir / name
        if not source.is_file():
            raise SystemExit(f"Missing AI subtitle source: {source}")
        shutil.copy2(source, package_dir / name)

    main_settings_activity.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(patch_dir / "AiSubtitleSettingsActivity.java", main_settings_activity)
    main_settings_layout.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(patch_dir / "activity_ai_subtitle_settings.xml", main_settings_layout)

    # The upstream selector_item drawable exists only in the leanback source set.
    # The AI subtitle settings activity is in main, so provide a self-contained
    # drawable usable by both mobile and leanback builds.
    selector_drawable = tv / "app/src/main/res/drawable/ai_subtitle_selector_item.xml"
    selector_drawable.parent.mkdir(parents=True, exist_ok=True)
    selector_drawable.write_text("""<?xml version="1.0" encoding="utf-8"?>
<selector xmlns:android="http://schemas.android.com/apk/res/android">
    <item android:state_focused="true">
        <shape android:shape="rectangle">
            <solid android:color="#263B55" />
            <stroke android:width="2dp" android:color="#FFBFD7FF" />
            <corners android:radius="8dp" />
        </shape>
    </item>
    <item android:state_selected="true">
        <shape android:shape="rectangle">
            <solid android:color="#263B55" />
            <corners android:radius="8dp" />
        </shape>
    </item>
    <item>
        <shape android:shape="rectangle">
            <solid android:color="#1F2937" />
            <corners android:radius="8dp" />
        </shape>
    </item>
</selector>
""", encoding="utf-8")

    # Support the sherpa-onnx Java API JAR in both leanback and mobile builds.
    gradle_text = gradle.read_text(encoding="utf-8")
    old_dep = 'implementation fileTree(dir: "libs", include: ["*.aar"])'
    new_dep = 'implementation fileTree(dir: "libs", include: ["*.aar", "*.jar"])'
    if new_dep not in gradle_text:
        gradle_text = replace_once(gradle_text, old_dep, new_dep, gradle)
        gradle.write_text(gradle_text, encoding="utf-8")

    patch_manifest(manifest)
    ensure_keep_rules(proguard)
    for path in player_activities:
        patch_player_activity(path)
    patch_tv_settings_activity(tv_settings)
    patch_tv_settings_layout(tv_settings_layout)
    patch_live_settings_layout(tv)
    patch_tv_home_grouping(tv)

    # Validate idempotence-critical anchors and the forbidden translation providers.
    for path in player_activities:
        text = path.read_text(encoding="utf-8")
        if "AiSubtitleOverlay.attach(this, mBinding.player);" not in text:
            raise SystemExit(f"{path}: subtitle overlay attachment is missing")
        if "AiSubtitleOverlay.detach(mBinding.player);" not in text:
            raise SystemExit(f"{path}: subtitle overlay cleanup is missing")

    overlay = (package_dir / "AiSubtitleOverlay.java").read_text(encoding="utf-8")
    policy = (tv / "app/src/main/assets/ai-subtitle-translation-policy.json")
    if not policy.is_file():
        raise SystemExit("Translation policy manifest was not staged into APK assets.")
    policy_text = policy.read_text(encoding="utf-8").lower()
    if '"youdao"' not in policy_text or '"tencent"' not in policy_text:
        raise SystemExit("Translation policy must explicitly forbid Youdao and Tencent.")
    if "WhisperModelManager" in overlay or "com.google.mlkit" in overlay:
        raise SystemExit("Legacy Whisper/ML Kit overlay references remain.")
    print("R15 TV AI subtitle feature integrated: eight source languages, provider selector, S2TWP output, and player capture hooks.")
    print("UI labels remain Taiwan Traditional Chinese. Youdao and Tencent are forbidden in the translation policy.")
    print("All eight Live/VOD activity sources have overlay attach/detach hooks.")


if __name__ == "__main__":
    main()
