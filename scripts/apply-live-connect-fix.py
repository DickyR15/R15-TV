#!/usr/bin/env python3
"""Apply R15 TV home-connect wording and old-Android live playback compatibility fixes."""

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
    ET.register_namespace("xliff", "urn:oasis:names:tc:xliff:document:1.2")
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
    if "Android 9 and earlier can fail to initialize EGL" in text:
        return

    import_anchor = "package com.fongmi.android.tv.setting;\n"
    import_new = import_anchor + "\nimport android.os.Build;\n"
    if "import android.os.Build;" not in text:
        if text.count(import_anchor) != 1:
            raise SystemExit(f"{path}: cannot locate package declaration to add Build import")
        text = text.replace(import_anchor, import_new, 1)

    old = """    public static int getRender() {
        return Math.clamp(Prefers.getInt("render", RENDER_SURFACE), RENDER_SURFACE, RENDER_TEXTURE);
    }"""
    new = """    public static int getRender() {
        int render = Math.clamp(Prefers.getInt("render", RENDER_SURFACE), RENDER_SURFACE, RENDER_TEXTURE);
        // The uploaded Android 9 emulator log shows RenderThread aborting with
        // EGL_NOT_INITIALIZED when LiveActivity attaches a Surface renderer.
        // Prefer Texture rendering on API 28 and older; Android TV 10+ keeps
        // the user's selected renderer unchanged.
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P && render == RENDER_SURFACE) {
            render = RENDER_TEXTURE;
            Prefers.put("render", render);
            if (isExo() && DecodeSetting.isTunnel()) DecodeSetting.putTunnel(false);
        }
        return render;
    }"""
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one getRender() method, found {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


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
    gradle_properties = tv / "gradle.properties"
    icon_source = patch_dir / "ic_home_connect.xml"
    icon_target = tv / "app/src/leanback/res/drawable/ic_home_connect.xml"

    for path in (home_strings, func_java, player_setting, icon_source):
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
    enable_jetifier(gradle_properties)

    # Sanity checks: label, icon binding, fallback renderer and AndroidX bridge.
    if "<string name=\"home_push\">連線</string>" not in home_strings.read_text(encoding="utf-8"):
        raise SystemExit("home_push was not localized to 連線")
    if "R.drawable.ic_home_connect" not in func_java.read_text(encoding="utf-8"):
        raise SystemExit("home menu does not use the connect icon")
    if "RENDER_TEXTURE" not in player_setting.read_text(encoding="utf-8"):
        raise SystemExit("Texture renderer fallback was not applied")
    gradle_text = gradle_properties.read_text(encoding="utf-8")
    if "android.enableJetifier=true" not in gradle_text:
        raise SystemExit("Jetifier compatibility setting is missing")

    print("Applied home label/icon change and Android 9-or-earlier Texture renderer fallback.")
    print("Enabled Jetifier to rewrite legacy support-library references in AndroidX dependencies.")


if __name__ == "__main__":
    main()
