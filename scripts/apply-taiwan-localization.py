#!/usr/bin/env python3
"""Apply R15 TV's Taiwan Traditional Chinese locale to a pinned FongMi source tree."""
from __future__ import annotations
import argparse
import copy
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

try:
    from opencc import OpenCC
except ImportError as exc:
    raise SystemExit("Missing OpenCC Python package; install opencc-python-reimplemented.") from exc

CJK = re.compile(r"[\u3400-\u4dbf\u4e00-\u9fff]")
TEXT_ATTRS = {"text", "hint", "title", "summary", "contentDescription", "label", "description", "prompt"}


def local_name(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]


def convert_text(node: ET.Element, converter: OpenCC) -> None:
    if node.text and CJK.search(node.text):
        node.text = converter.convert(node.text)
    if node.tail and CJK.search(node.tail):
        node.tail = converter.convert(node.tail)
    for key, value in list(node.attrib.items()):
        if local_name(key) in TEXT_ATTRS and CJK.search(value):
            node.attrib[key] = converter.convert(value)
    for child in list(node):
        convert_text(child, converter)


def parse(path: Path) -> ET.ElementTree:
    try:
        return ET.parse(path)
    except ET.ParseError as exc:
        raise SystemExit(f"Invalid Android XML {path}: {exc}") from exc


def write(path: Path, tree: ET.ElementTree) -> None:
    ET.indent(tree, space="    ")
    tree.write(path, encoding="utf-8", xml_declaration=True)


def key(node: ET.Element):
    name = node.attrib.get("name")
    return (local_name(node.tag), name) if name else None


def merge_cn_fallbacks(cn_dir: Path, tw_dir: Path) -> int:
    tw_dir.mkdir(parents=True, exist_ok=True)
    copied = 0
    for src in cn_dir.glob("*.xml"):
        target = tw_dir / src.name
        src_tree = parse(src)
        if not target.exists():
            target_tree = ET.ElementTree(copy.deepcopy(src_tree.getroot()))
            write(target, target_tree)
            copied += len(list(target_tree.getroot()))
            continue
        target_tree = parse(target)
        target_root = target_tree.getroot()
        keys = {k for child in list(target_root) if (k := key(child)) is not None}
        for child in list(src_tree.getroot()):
            item_key = key(child)
            if item_key is not None and item_key not in keys:
                target_root.append(copy.deepcopy(child))
                keys.add(item_key)
                copied += 1
        write(target, target_tree)
    return copied


def set_app_name(main_dir: Path) -> int:
    changed_files = 0
    for path in main_dir.rglob("*.xml"):
        tree = parse(path)
        dirty = False
        for node in tree.getroot().iter():
            if local_name(node.tag) == "string" and node.attrib.get("name") == "app_name":
                if (node.text or "").strip() != "R15 TV":
                    node.text = "R15 TV"
                    dirty = True
        if dirty:
            write(path, tree)
            changed_files += 1
    return changed_files


def force_zh_tw(app_java: Path) -> bool:
    source = app_java.read_text(encoding="utf-8")
    old = """    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        Init.set(base);
    }"""
    new = """    @Override
    protected void attachBaseContext(Context base) {
        // Always render the UI in Taiwan Traditional Chinese, independent of device locale.
        android.content.res.Configuration configuration =
                new android.content.res.Configuration(base.getResources().getConfiguration());
        configuration.setLocale(new java.util.Locale("zh", "TW"));
        Context localized = base.createConfigurationContext(configuration);
        super.attachBaseContext(localized);
        Init.set(localized);
    }"""
    if new in source:
        return False
    if source.count(old) != 1:
        raise SystemExit(f"{app_java}: expected exactly one App.attachBaseContext anchor")
    app_java.write_text(source.replace(old, new, 1), encoding="utf-8")
    return True


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--tv-dir", required=True, type=Path)
    args = parser.parse_args()
    tv = args.tv_dir.resolve()
    res = tv / "app/src/main/res"
    cn_dir = res / "values-zh-rCN"
    tw_dir = res / "values-zh-rTW"
    app_java = tv / "app/src/main/java/com/fongmi/android/tv/App.java"
    for path in (res, cn_dir, tw_dir, app_java):
        if not path.exists():
            raise SystemExit(f"Missing expected source path: {path}")

    converter = OpenCC("s2twp")
    merged = merge_cn_fallbacks(cn_dir, tw_dir)
    converted = 0
    # Normalize Simplified Chinese locale resources, Taiwan locale resources and
    # hard-coded Chinese labels inside layouts, so a locale fallback cannot show CN text.
    for path in res.rglob("*.xml"):
        relative = path.relative_to(res).as_posix()
        if not ("zh-rCN" in relative or "zh-rTW" in relative or "/layout" in f"/{relative}"):
            continue
        tree = parse(path)
        before = ET.tostring(tree.getroot(), encoding="unicode")
        convert_text(tree.getroot(), converter)
        after = ET.tostring(tree.getroot(), encoding="unicode")
        if before != after:
            write(path, tree)
            converted += 1

    app_names = set_app_name(tv / "app/src/main")
    locale_changed = force_zh_tw(app_java)
    print(
        f"R15 TV localization applied: {merged} CN fallback resources merged; "
        f"{converted} XML files normalized with OpenCC s2twp; "
        f"{app_names} XML files set app_name=R15 TV; "
        f"app locale {'updated to zh-TW' if locale_changed else 'already zh-TW'}."
    )


if __name__ == "__main__":
    main()
