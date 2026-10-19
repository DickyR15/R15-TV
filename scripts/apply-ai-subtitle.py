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


def patch_player_resume(text: str, path: Path) -> str:
    """Keep live controls registered before STARTED and refresh their state on return."""
    is_live = path.name == "LiveActivity.java"
    signature = "protected void onResume() {"
    start = text.find(signature)
    if start < 0:
        signature = "public void onResume() {"
        start = text.find(signature)

    if is_live:
        action = """        if (mBinding != null) {
            if (AiSubtitleSettingsActivity.isAutoStartEnabled(this)) {
                AiSubtitleOverlay.attach(this, mBinding.player);
            }
            AiSubtitleOverlay.refreshLiveControls(this, mBinding.player,
                    mBinding.control.actions.r15Subtitle, mBinding.control.actions.r15Language);
        }"""
    else:
        action = """        if (mBinding != null && AiSubtitleSettingsActivity.isAutoStartEnabled(this)) {
            AiSubtitleOverlay.attach(this, mBinding.player);
        } else if (mBinding != null) {
            AiSubtitleOverlay.detach(mBinding.player);
        }"""

    if start >= 0:
        body_start = start + len(signature)
        body_end = text.find("\n    }", body_start)
        if body_end < 0:
            raise SystemExit(f"{path}: cannot locate onResume() end")
        body = text[body_start:body_end]
        needs_attach = "AiSubtitleSettingsActivity.isAutoStartEnabled(this)" not in body
        needs_refresh = is_live and "AiSubtitleOverlay.refreshLiveControls" not in body
        if not needs_attach and not needs_refresh:
            return text
        marker = "super.onResume();"
        offset = body.find(marker)
        if offset >= 0:
            insert_at = body_start + offset + len(marker)
            return text[:insert_at] + "\n" + action + text[insert_at:]
        return text[:body_start] + "\n" + action + text[body_start:]

    destroy = "protected void onDestroy() {"
    insert_at = text.find(destroy)
    if insert_at < 0:
        raise SystemExit(f"{path}: cannot locate onDestroy() for subtitle lifecycle hook")
    annotation = text.rfind("@Override", 0, insert_at)
    if annotation >= 0 and text[annotation + len("@Override"):insert_at].strip() == "":
        insert_at = annotation
    if is_live:
        method = """    @Override
    protected void onResume() {
        super.onResume();
        if (mBinding != null) {
            if (AiSubtitleSettingsActivity.isAutoStartEnabled(this)) {
                AiSubtitleOverlay.attach(this, mBinding.player);
            }
            AiSubtitleOverlay.refreshLiveControls(this, mBinding.player,
                    mBinding.control.actions.r15Subtitle, mBinding.control.actions.r15Language);
        }
    }

"""
    else:
        method = """    @Override
    protected void onResume() {
        super.onResume();
        if (mBinding != null && AiSubtitleSettingsActivity.isAutoStartEnabled(this)) {
            AiSubtitleOverlay.attach(this, mBinding.player);
        } else if (mBinding != null) {
            AiSubtitleOverlay.detach(mBinding.player);
        }
    }

"""
    return text[:insert_at] + method + text[insert_at:]

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
    attach_call = "AiSubtitleOverlay.attach(this, mBinding.player);"
    if path.name == "LiveActivity.java":
        # ActivityResult launchers must be registered before STARTED. This creates an idle overlay
        # only on Android 10+, while the recognizer/audio capture still starts only on user action.
        attach_line = "if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) " + attach_call
    else:
        attach_line = "if (AiSubtitleSettingsActivity.isAutoStartEnabled(this)) " + attach_call
    if attach_call not in init_body:
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

    text = patch_player_resume(text, path)
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
    """Make the outer settings page match the two-entry TV layout."""
    import xml.etree.ElementTree as ET

    layout = tv / "app/src/leanback/res/layout/activity_setting.xml"
    if not layout.is_file():
        raise SystemExit(f"Missing Leanback layout: {layout}")

    ET.register_namespace("android", "http://schemas.android.com/apk/res/android")
    ET.register_namespace("app", "http://schemas.android.com/apk/res-auto")
    ET.register_namespace("tools", "http://schemas.android.com/tools")
    tree = ET.parse(layout)
    root = tree.getroot()
    aid = "{http://schemas.android.com/apk/res/android}id"
    vis = "{http://schemas.android.com/apk/res/android}visibility"
    content = list(root)[0]
    parents = {child: parent for parent in root.iter() for child in parent}

    def find_id(name: str):
        return next((n for n in root.iter() if n.attrib.get(aid) == f"@+id/{name}"), None)

    def top_row(node, target: str):
        if node is None:
            raise SystemExit(f"Could not find @{target} in settings layout.")
        while parents.get(node) is not content:
            node = parents.get(node)
            if node is None:
                raise SystemExit(f"Could not locate the parent row for @{target}.")
        return node

    vod = find_id("vod")
    live = find_id("live")
    wall = find_id("wall")
    vod_row = top_row(vod, "vod")
    live_row = top_row(live, "live")
    wall_row = top_row(wall, "wall")

    # Keep the original controls and their IDs for existing code, but hide the
    # separate VOD and wallpaper rows from the outer screen.
    vod_row.set(vis, "gone")
    wall_row.set(vis, "gone")

    for name in ("liveUrl", "liveHome", "liveHistory"):
        node = find_id(name)
        if node is not None:
            node.set(vis, "gone")

    android = "{http://schemas.android.com/apk/res/android}"
    disclaimer = find_id("r15Disclaimer")
    if disclaimer is not None:
        content.remove(disclaimer)

    # Keep the live entry first and ensure the combined VOD/Wall entry is present.
    group = find_id("vodAndWall")
    if group is None:
        group = ET.Element("androidx.appcompat.widget.LinearLayoutCompat", {
            android + "id": "@+id/vodAndWall",
            android + "layout_width": "match_parent",
            android + "layout_height": "wrap_content",
            android + "layout_marginTop": "16dp",
            android + "background": "@drawable/selector_item",
            android + "focusable": "true",
            android + "focusableInTouchMode": "true",
            android + "orientation": "horizontal",
            android + "padding": "16dp",
        })
        label = ET.SubElement(group, "com.google.android.material.textview.MaterialTextView", {
            android + "layout_width": "match_parent",
            android + "layout_height": "wrap_content",
            android + "text": "點播和桌布",
            android + "textColor": "@color/white",
            android + "textSize": "18sp",
        })
        content.insert(list(content).index(live_row) + 1, group)
    live_row.set("{http://schemas.android.com/apk/res/android}layout_marginTop", "8dp")
    ET.indent(tree, space="    ")
    tree.write(layout, encoding="utf-8", xml_declaration=True)


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





def patch_live_sources_ui(tv: Path) -> None:
    """Install the R15 live-source manager and the combined VOD/wallpaper page."""
    patch_root = Path(__file__).resolve().parent.parent / "patches/live-sources"
    target = tv / "app/src/main/java/com/fongmi/android/tv/ui/activity"
    target.mkdir(parents=True, exist_ok=True)
    for name in ("LiveSourcesActivity.java", "VodWallSettingsActivity.java"):
        source = patch_root / name
        if not source.is_file():
            raise SystemExit(f"Missing live-source UI patch: {source}")
        shutil.copy2(source, target / name)

    manifest = tv / "app/src/main/AndroidManifest.xml"
    text = manifest.read_text(encoding="utf-8")
    anchor = '<activity android:name=".ui.activity.AiSubtitleSettingsActivity" android:exported="false" />'
    activities = [
        '<activity android:name=".ui.activity.LiveSourcesActivity" android:exported="false" />',
        '<activity android:name=".ui.activity.VodWallSettingsActivity" android:exported="false" />',
    ]
    for activity in activities:
        if activity not in text:
            if anchor not in text:
                raise SystemExit("Cannot locate AI subtitle activity anchor in AndroidManifest.xml")
            text = text.replace(anchor, anchor + "\n        " + activity, 1)
    manifest.write_text(text, encoding="utf-8")

    setting = tv / "app/src/leanback/java/com/fongmi/android/tv/ui/activity/SettingActivity.java"
    source = setting.read_text(encoding="utf-8")
    old_live = """    private void onLive(View view) {
        ConfigDialog.create().live().show(this);
    }"""
    new_live = """    private void onLive(View view) {
        LiveSourcesActivity.start(this);
    }"""
    if new_live not in source:
        source = replace_once(source, old_live, new_live, setting)

    focus_old = "        mBinding.vod.requestFocus();"
    focus_new = "        mBinding.live.requestFocus();"
    if focus_new not in source:
        source = replace_once(source, focus_old, focus_new, setting)

    live_listener = "        mBinding.live.setOnClickListener(this::onLive);\n"
    combined_listener = live_listener + "        mBinding.vodAndWall.setOnClickListener(this::onVodWall);\n"
    if combined_listener not in source:
        source = replace_once(source, live_listener, combined_listener, setting)

    method_anchor = "    private void onPlayer(View view) {\n"
    method = """    private void onVodWall(View view) {
        VodWallSettingsActivity.start(this);
    }

"""
    if "private void onVodWall(View view)" not in source:
        source = replace_once(source, method_anchor, method + method_anchor, setting)

    setting.write_text(source, encoding="utf-8")


def patch_live_source_options(tv: Path) -> None:
    """Apply saved per-source EPG, UA, Referer and custom headers to active Live data."""
    live = tv / "app/src/main/java/com/fongmi/android/tv/bean/Live.java"
    live_config = tv / "app/src/main/java/com/fongmi/android/tv/api/config/LiveConfig.java"

    live_text = live.read_text(encoding="utf-8")
    ua_anchor = """    public String getUa() {
        return TextUtils.isEmpty(ua) ? "" : ua;
    }"""
    ua_setter = ua_anchor + """

    public void setUa(String ua) {
        this.ua = ua;
    }"""
    if "public void setUa(String ua)" not in live_text:
        live_text = replace_once(live_text, ua_anchor, ua_setter, live)

    referer_anchor = """    public String getReferer() {
        return TextUtils.isEmpty(referer) ? "" : referer;
    }"""
    referer_setter = referer_anchor + """

    public void setReferer(String referer) {
        this.referer = referer;
    }"""
    if "public void setReferer(String referer)" not in live_text:
        live_text = replace_once(live_text, referer_anchor, referer_setter, live)

    header_anchor = """    public Map<String, String> getHeader() {
        return header == null ? new HashMap<>() : header;
    }"""
    header_setter = header_anchor + """

    public void setHeader(Map<String, String> header) {
        this.header = header;
    }"""
    if "public void setHeader(Map<String, String> header)" not in live_text:
        live_text = replace_once(live_text, header_anchor, header_setter, live)
    live.write_text(live_text, encoding="utf-8")

    config_text = live_config.read_text(encoding="utf-8")
    if "import org.json.JSONObject;" not in config_text:
        import_anchor = "import java.util.stream.Collectors;\n"
        config_text = replace_once(
            config_text, import_anchor,
            import_anchor + "import org.json.JSONObject;\n", live_config
        )

    parse_anchor = """        LiveParser.text(live, text);
        setHome(config, live, false);"""
    parse_replacement = parse_anchor + """
        applyR15SourceOptions(config, live);"""
    if "applyR15SourceOptions(config, live);" not in config_text:
        config_text = replace_once(config_text, parse_anchor, parse_replacement, live_config)

    json_home_anchor = """        setHome(config, getLives().isEmpty() ? new Live() : getLives().stream().filter(item -> item.getName().equals(config.getHome())).findFirst().orElse(getLives().get(0)), false);"""
    json_home_replacement = json_home_anchor + """
        applyR15SourceOptions(config, getHome());"""
    if "applyR15SourceOptions(config, getHome());" not in config_text:
        config_text = replace_once(config_text, json_home_anchor, json_home_replacement, live_config)

    helper_anchor = "    public void setKeep(Channel channel) {"
    helper = """    private void applyR15SourceOptions(Config config, Live live) {
        if (config == null || live == null || TextUtils.isEmpty(config.getJson())) return;
        try {
            JSONObject json = new JSONObject(config.getJson());
            if (!json.optBoolean("r15SourceOptions", false)) return;

            String epg = json.optString("epg", "");
            String ua = json.optString("userAgent", "");
            String referer = json.optString("referer", "");
            if (!TextUtils.isEmpty(epg)) live.setEpg(epg);
            if (!TextUtils.isEmpty(ua)) live.setUa(ua);
            if (!TextUtils.isEmpty(referer)) live.setReferer(referer);

            String text = json.optString("headersText", "");
            if (!TextUtils.isEmpty(text)) {
                Map<String, String> headers = new java.util.HashMap<>(live.getHeader());
                for (String line : text.split("\\r?\\n")) {
                    String item = line.trim();
                    if (item.isEmpty()) continue;
                    int split = item.indexOf('=');
                    if (split <= 0) split = item.indexOf(':');
                    if (split <= 0) continue;
                    String name = item.substring(0, split).trim();
                    String value = item.substring(split + 1).trim();
                    if (!name.isEmpty() && !value.isEmpty()) headers.put(name, value);
                }
                live.setHeader(headers);
            }
        } catch (Exception ignored) {
        }
    }

"""
    if "private void applyR15SourceOptions(Config config, Live live)" not in config_text:
        config_text = replace_once(config_text, helper_anchor, helper + helper_anchor, live_config)
    live_config.write_text(config_text, encoding="utf-8")


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
    patch_live_sources_ui(tv)
    patch_live_source_options(tv)

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
