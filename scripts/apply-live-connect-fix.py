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
    enable_jetifier(gradle_properties)

    # Sanity checks: menu label/icon, default wallpaper and duplicate-ID fix.
    if "連線" not in home_strings.read_text(encoding="utf-8"):
        raise SystemExit("home_push was not localized to 連線")
    if "R.drawable.ic_home_connect" not in func_java.read_text(encoding="utf-8"):
        raise SystemExit("home menu does not use the connect icon")
    if 'Prefers.getInt("wall", 4)' not in wallpaper_setting.read_text(encoding="utf-8"):
        raise SystemExit("wallpaper_4 is not the default wallpaper")
    if "android.enableJetifier=true" not in gradle_properties.read_text(encoding="utf-8"):
        raise SystemExit("Jetifier compatibility setting is missing")
    print("Applied home label/icon, Aurora default wallpaper and live-action duplicate-ID workaround.")
    print("Kept stock hardware acceleration and PlayerSetting renderer behavior; no old-Android renderer override is applied.")


if __name__ == "__main__":
    main()
