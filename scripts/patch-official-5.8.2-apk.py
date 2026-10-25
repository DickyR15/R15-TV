#!/usr/bin/env python3
"""Patch only app identity, launcher artwork, and zh-TW resources in official FongMi 5.8.2.

The playback bytecode and native libraries are intentionally left untouched.
"""
from __future__ import annotations

import argparse
import copy
import re
import shutil
import xml.etree.ElementTree as ET
from pathlib import Path

from opencc import OpenCC
from PIL import Image, ImageDraw, ImageFilter, ImageFont, ImageOps

ANDROID = "http://schemas.android.com/apk/res/android"
ANDROID_ATTR = f"{{{ANDROID}}}"
SOURCE_PACKAGE = "com.fongmi.android.tv"
TARGET_PACKAGE = "com.dicky.r15tv"
COMPONENTS = {"application", "activity", "activity-alias", "service", "receiver", "provider", "instrumentation"}
TEXT_ATTRS = {"text", "hint", "title", "summary", "contentDescription", "label", "description", "prompt"}
DENSITIES = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}


def local_name(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]


def register_namespaces(path: Path) -> None:
    for _, (prefix, uri) in ET.iterparse(path, events=("start-ns",)):
        ET.register_namespace(prefix or "", uri)


def parse_xml(path: Path) -> ET.ElementTree:
    register_namespaces(path)
    try:
        return ET.parse(path)
    except ET.ParseError as exc:
        raise SystemExit(f"Invalid decoded XML {path}: {exc}") from exc


def save_xml(path: Path, tree: ET.ElementTree) -> None:
    tree.write(path, encoding="utf-8", xml_declaration=True)


def qualify_class(name: str) -> str:
    if not name:
        return name
    if name.startswith("."):
        return SOURCE_PACKAGE + name
    if "." not in name:
        return SOURCE_PACKAGE + "." + name
    return name


def patch_manifest(decoded: Path, run_number: int) -> None:
    path = decoded / "AndroidManifest.xml"
    tree = parse_xml(path)
    root = tree.getroot()
    original_package = root.attrib.get("package", "")
    if original_package != SOURCE_PACKAGE:
        raise SystemExit(
            f"Unexpected official manifest package {original_package!r}; "
            f"expected {SOURCE_PACKAGE!r}. Refusing to patch an unknown APK."
        )

    root.set("package", TARGET_PACKAGE)
    root.set(ANDROID_ATTR + "versionCode", str(2_000_000_000 + (run_number % 99_000_000)))
    root.set(ANDROID_ATTR + "versionName", f"5.8.2-r15.{run_number}")

    for node in root.iter():
        tag = local_name(node.tag)
        for key, value in list(node.attrib.items()):
            attr = local_name(key)
            if key == ANDROID_ATTR + "name" and tag in COMPONENTS:
                node.set(key, qualify_class(value))
                continue
            if key in (ANDROID_ATTR + "targetActivity", ANDROID_ATTR + "parentActivityName"):
                node.set(key, qualify_class(value))
                continue

            # Keep app code/classes in their original package, but isolate providers,
            # custom permissions and task affinities under the R15 application ID.
            if SOURCE_PACKAGE in value and attr in {
                "authorities", "taskAffinity", "permission", "readPermission",
                "writePermission", "permissionGroup", "targetPackage",
            }:
                node.set(key, value.replace(SOURCE_PACKAGE, TARGET_PACKAGE))
            elif key == ANDROID_ATTR + "name" and tag not in COMPONENTS and value.startswith(SOURCE_PACKAGE):
                # Custom permission names and other package-qualified manifest names
                # are not Java component classes.
                node.set(key, value.replace(SOURCE_PACKAGE, TARGET_PACKAGE))

    application = next((n for n in list(root) if local_name(n.tag) == "application"), None)
    if application is None:
        raise SystemExit("Official APK manifest has no application node.")
    application.set(ANDROID_ATTR + "label", "@string/app_name")
    application.set(ANDROID_ATTR + "icon", "@mipmap/ic_launcher")
    application.set(ANDROID_ATTR + "roundIcon", "@mipmap/ic_launcher_round")
    application.set(ANDROID_ATTR + "banner", "@drawable/r15_tv_banner")
    save_xml(path, tree)

    verify = parse_xml(path).getroot()
    if verify.attrib.get("package") != TARGET_PACKAGE:
        raise SystemExit("Package ID patch verification failed.")
    for key in ("icon", "roundIcon", "banner", "label"):
        if not application.get(ANDROID_ATTR + key):
            raise SystemExit(f"Manifest application attribute {key} was not set.")


def patch_apktool_metadata(decoded: Path, run_number: int) -> None:
    """Apktool rebuilds version fields from apktool.yml, not only the decoded manifest."""
    path = decoded / "apktool.yml"
    if not path.is_file():
        raise SystemExit("apktool.yml is missing from the decoded official APK.")
    text = path.read_text(encoding="utf-8")
    version_code = 2_000_000_000 + (run_number % 99_000_000)
    version_name = f"5.8.2-r15.{run_number}"
    for key, value in (("versionCode", str(version_code)), ("versionName", version_name)):
        pattern = re.compile(rf"(?m)^(\\s*{key}:\\s*).*$")
        text, count = pattern.subn(lambda match: match.group(1) + "'" + value + "'", text, count=1)
        if count != 1:
            raise SystemExit(f"Could not update exactly one {key} entry in apktool.yml.")
    path.write_text(text, encoding="utf-8")
    verified = path.read_text(encoding="utf-8")
    if f"'{version_code}'" not in verified or f"'{version_name}'" not in verified:
        raise SystemExit("apktool.yml version metadata verification failed.")


def patch_app_name(res_root: Path) -> None:
    values = res_root / "values"
    values.mkdir(parents=True, exist_ok=True)
    strings_path = values / "strings.xml"
    if strings_path.exists():
        tree = parse_xml(strings_path)
        root = tree.getroot()
    else:
        root = ET.Element("resources")
        tree = ET.ElementTree(root)

    found = False
    for path in res_root.glob("values*/strings.xml"):
        tree_i = parse_xml(path)
        dirty = False
        for node in tree_i.getroot().iter():
            if local_name(node.tag) == "string" and node.attrib.get("name") == "app_name":
                node.text = "R15 TV"
                found = True
                dirty = True
        if dirty:
            save_xml(path, tree_i)

    for node in root.iter():
        if local_name(node.tag) == "string" and node.attrib.get("name") == "app_name":
            node.text = "R15 TV"
            found = True
    if not found:
        ET.SubElement(root, "string", {"name": "app_name"}).text = "R15 TV"
    save_xml(strings_path, tree)


def merge_cn_locale(res_root: Path) -> int:
    copied = 0
    for source_dir in sorted(res_root.glob("values-zh-rCN*")):
        target_name = source_dir.name.replace("values-zh-rCN", "values-zh-rTW", 1)
        target_dir = res_root / target_name
        target_dir.mkdir(parents=True, exist_ok=True)
        for source_file in source_dir.glob("*.xml"):
            target_file = target_dir / source_file.name
            source_tree = parse_xml(source_file)
            if not target_file.exists():
                target_tree = ET.ElementTree(copy.deepcopy(source_tree.getroot()))
                save_xml(target_file, target_tree)
                copied += len(list(target_tree.getroot()))
                continue
            target_tree = parse_xml(target_file)
            target_root = target_tree.getroot()
            keys = {
                (local_name(child.tag), child.attrib.get("name"))
                for child in list(target_root)
            }
            for child in list(source_tree.getroot()):
                item_key = (local_name(child.tag), child.attrib.get("name"))
                if item_key not in keys:
                    target_root.append(copy.deepcopy(child))
                    keys.add(item_key)
                    copied += 1
            save_xml(target_file, target_tree)
    return copied


def translate_tree(path: Path, converter: OpenCC, layout: bool = False) -> bool:
    tree = parse_xml(path)
    dirty = False
    for node in tree.getroot().iter():
        if node.text and re.search(r"[\u3400-\u4dbf\u4e00-\u9fff]", node.text):
            # Convert user-visible/resource text. Android resource names and references
            # live in attributes and are deliberately not touched here.
            if layout or local_name(node.tag) in {"string", "item", "title", "summary", "text", "hint"}:
                updated = converter.convert(node.text)
                if updated != node.text:
                    node.text = updated
                    dirty = True
        if layout:
            for key, value in list(node.attrib.items()):
                if local_name(key) in TEXT_ATTRS and re.search(r"[\u3400-\u4dbf\u4e00-\u9fff]", value):
                    updated = converter.convert(value)
                    if updated != value:
                        node.set(key, updated)
                        dirty = True
    if dirty:
        save_xml(path, tree)
    return dirty


def save_image(image: Image.Image, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    suffix = path.suffix.lower()
    if suffix == ".webp":
        image.save(path, "WEBP", quality=95, method=6)
    elif suffix in {".jpg", ".jpeg"}:
        image.save(path, "JPEG", quality=92, optimize=True)
    else:
        image.save(path, "PNG", optimize=True)


def make_brand_assets(decoded: Path, reference: Path) -> None:
    res = decoded / "res"
    if not reference.is_file():
        raise SystemExit(f"Missing R15 icon artwork: {reference}")
    with Image.open(reference) as src:
        artwork = src.convert("RGB").resize((512, 512), Image.Resampling.LANCZOS)

    # Remove all same-name raster variants first. Keeping both PNG and WEBP in one
    # density directory creates duplicate Android resource configurations.
    for path in list(res.rglob("*")):
        if not path.is_file():
            continue
        if path.stem in {"ic_launcher", "ic_launcher_round"} and path.suffix.lower() in {".png", ".webp", ".jpg", ".jpeg"}:
            path.unlink()

    # Adaptive icon XML depends on a foreground/background layout. Remove the overrides
    # so Android uses one patched raster resource per density.
    for path in list(res.rglob("ic_launcher.xml")) + list(res.rglob("ic_launcher_round.xml")):
        if path.parent.name.startswith("mipmap-anydpi"):
            path.unlink()

    for folder, size in DENSITIES.items():
        out = res / folder
        save_image(artwork.resize((size, size), Image.Resampling.LANCZOS), out / "ic_launcher.png")
        save_image(artwork.resize((size, size), Image.Resampling.LANCZOS), out / "ic_launcher_round.png")

    # Keep the Android 12+ splash graphic padded so the wordmark is not clipped.
    splash = Image.new("RGB", (512, 512), (7, 19, 44))
    art = artwork.resize((332, 332), Image.Resampling.LANCZOS)
    splash.paste(art, (90, 90))
    save_image(splash, res / "drawable-nodpi" / "ic_launcher_foreground.png")

    # Generate a wide Leanback banner: muted R15 artwork plus an unclipped wordmark.
    width, height = 1280, 720
    background = ImageOps.fit(artwork, (width, height), method=Image.Resampling.LANCZOS)
    background = background.filter(ImageFilter.GaussianBlur(radius=28))
    navy = Image.new("RGB", (width, height), (4, 10, 34))
    banner = Image.blend(background, navy, 0.63)
    foreground = artwork.resize((520, 520), Image.Resampling.LANCZOS)
    banner.paste(foreground, (52, 100))
    draw = ImageDraw.Draw(banner)
    font_path = Path("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf")
    font = ImageFont.truetype(str(font_path), 130) if font_path.exists() else ImageFont.load_default()
    draw.text((650, 280), "R15 TV", fill=(244, 249, 255), font=font, stroke_width=2, stroke_fill=(37, 140, 255))
    draw.rounded_rectangle((655, 435, 1180, 451), radius=8, fill=(40, 202, 255))
    save_image(banner, res / "drawable-nodpi" / "r15_tv_banner.png")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--decoded-dir", required=True, type=Path)
    parser.add_argument("--branding-image", required=True, type=Path)
    parser.add_argument("--run-number", type=int, default=1)
    args = parser.parse_args()

    decoded = args.decoded_dir.resolve()
    if not (decoded / "AndroidManifest.xml").is_file() or not (decoded / "res").is_dir():
        raise SystemExit(f"Not an apktool decoded directory: {decoded}")

    patch_manifest(decoded, args.run_number)
    patch_apktool_metadata(decoded, args.run_number)
    patch_app_name(decoded / "res")
    copied = merge_cn_locale(decoded / "res")
    converter = OpenCC("s2twp")
    translated_files = 0
    for path in (decoded / "res").glob("values-zh-rTW*/*.xml"):
        translated_files += translate_tree(path, converter)
    layout_files = 0
    for path in (decoded / "res").glob("layout*/*.xml"):
        layout_files += translate_tree(path, converter, layout=True)
    make_brand_assets(decoded, args.branding_image)

    summary = (
        f"Official APK patched: package={TARGET_PACKAGE}; version=5.8.2-r15.{args.run_number}; "
        f"merged {copied} zh-CN fallback entries; translated {translated_files} Traditional-Chinese "
        f"resource XML files and {layout_files} layout XML files; replaced launcher artwork and banner. "
        "Player DEX code and native libraries were not modified."
    )
    (decoded / "R15_APK_PATCH_INFO.txt").write_text(summary + "\n", encoding="utf-8")
    print(summary)


if __name__ == "__main__":
    main()
