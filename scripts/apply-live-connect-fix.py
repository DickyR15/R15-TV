#!/usr/bin/env python3
"""Apply R15 TV home-connect wording and old-Android live playback fixes."""

from __future__ import annotations

import argparse
from pathlib import Path
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


def patch_player_render(path: Path) -> None:
    text = path.read_text(encoding="utf-8")
    import_anchor = "package com.fongmi.android.tv.setting;\n"
    if "import android.os.Build;" not in text:
        if text.count(import_anchor) != 1:
            raise SystemExit(f"{path}: cannot locate package declaration to add Build import")
        text = text.replace(import_anchor, import_anchor + "\nimport android.os.Build;\n", 1)

    old = """    public static int getRender() {
        return Math.clamp(Prefers.getInt("render", RENDER_SURFACE), RENDER_SURFACE, RENDER_TEXTURE);
    }"""
    new = """    public static int getRender() {
        int render = Math.clamp(Prefers.getInt("render", RENDER_SURFACE), RENDER_SURFACE, RENDER_TEXTURE);
        // The Android 9 emulator logs show Android HWUI aborting while opening
        // LiveActivity. That flavor uses a software-rendered window on API 28
        // and below, so keep video output on SurfaceView (TextureView requires
        // hardware acceleration and can become blank in a software window).
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P && render != RENDER_SURFACE) {
            render = RENDER_SURFACE;
            Prefers.put("render", render);
        }
        return render;
    }"""
    if new not in text:
        count = text.count(old)
        if count != 1:
            raise SystemExit(f"{path}: expected one getRender() method, found {count}")
        text = text.replace(old, new, 1)
    path.write_text(text, encoding="utf-8")


def patch_default_wallpaper(path: Path) -> None:
    replace_exact(
        path,
        'return Math.clamp(Prefers.getInt("wall", 1), MIN_WALL, MAX_WALL);',
        'return Math.clamp(Prefers.getInt("wall", 4), MIN_WALL, MAX_WALL);',
    )


def set_bool_resource(path: Path, name: str, value: bool) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.exists():
        try:
            tree = ET.parse(path)
        except ET.ParseError as exc:
            raise SystemExit(f"Invalid boolean resource XML {path}: {exc}") from exc
        root = tree.getroot()
    else:
        root = ET.Element("resources")
        tree = ET.ElementTree(root)
    matches = [
        node for node in root.iter()
        if node.tag.rsplit("}", 1)[-1] == "bool" and node.attrib.get("name") == name
    ]
    if len(matches) > 1:
        raise SystemExit(f"{path}: duplicate bool resource {name}")
    node = matches[0] if matches else ET.SubElement(root, "bool", {"name": name})
    node.text = "true" if value else "false"
    ET.indent(tree, space="    ")
    tree.write(path, encoding="utf-8", xml_declaration=True)


def patch_live_hardware_acceleration(tv: Path) -> None:
    """Disable Android HWUI only for LiveActivity on API 28 and below."""
    for flavor in ("leanback", "mobile"):
        manifest = tv / "app" / "src" / flavor / "AndroidManifest.xml"
        replace_exact(
            manifest,
            '            android:name=".ui.activity.LiveActivity"\n',
            '            android:name=".ui.activity.LiveActivity"\n'
            '            android:hardwareAccelerated="@bool/r15_live_hardware_accelerated"\n',
        )
        values = tv / "app" / "src" / flavor / "res"
        # Resource qualifiers make the workaround specific to old Android.
        set_bool_resource(values / "values" / "r15_live_compat.xml", "r15_live_hardware_accelerated", False)
        set_bool_resource(values / "values-v29" / "r15_live_compat.xml", "r15_live_hardware_accelerated", True)


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
    player_setting = tv / "app/src/main/java/com/fongmi/android/tv/setting/PlayerSetting.java"
    wallpaper_setting = tv / "app/src/main/java/com/fongmi/android/tv/setting/Setting.java"
    gradle_properties = tv / "gradle.properties"
    icon_source = patch_dir / "ic_home_connect.xml"
    icon_target = tv / "app/src/leanback/res/drawable/ic_home_connect.xml"

    for path in (home_strings, func_java, player_setting, wallpaper_setting, icon_source):
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

    patch_player_render(player_setting)
    patch_default_wallpaper(wallpaper_setting)
    patch_live_hardware_acceleration(tv)
    enable_jetifier(gradle_properties)

    # Sanity checks: visible label/icon, wallpaper default, and API-qualified render policy.
    if "連線" not in home_strings.read_text(encoding="utf-8"):
        raise SystemExit("home_push was not localized to 連線")
    if "R.drawable.ic_home_connect" not in func_java.read_text(encoding="utf-8"):
        raise SystemExit("home menu does not use the connect icon")
    if 'Prefers.getInt("wall", 4)' not in wallpaper_setting.read_text(encoding="utf-8"):
        raise SystemExit("wallpaper_4 is not the default wallpaper")
    if "@bool/r15_live_hardware_accelerated" not in (tv / "app/src/leanback/AndroidManifest.xml").read_text(encoding="utf-8"):
        raise SystemExit("old-Android live rendering workaround is missing")
    if "android.enableJetifier=true" not in gradle_properties.read_text(encoding="utf-8"):
        raise SystemExit("Jetifier compatibility setting is missing")

    print("Applied home label/icon and wallpaper_4 (aurora) default.")
    print("LiveActivity uses software UI rendering on Android 9 and older and hardware rendering on API 29+.")
    print("Enabled Jetifier to rewrite legacy support-library references in AndroidX dependencies.")


if __name__ == "__main__":
    main()
