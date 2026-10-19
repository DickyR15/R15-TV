#!/usr/bin/env python3
"""Render and stage R15 TV's custom launcher icon and built-in wallpapers."""

from __future__ import annotations

import argparse
import io
import re
from pathlib import Path

import cairosvg
from PIL import Image


WALLPAPERS = (
    ("wallpaper_1", "wallpaper_emerald.svg"),
    ("wallpaper_2", "wallpaper_midnight.svg"),
    ("wallpaper_3", "wallpaper_cosmic.svg"),
    ("wallpaper_4", "wallpaper_aurora.svg"),
)
ICON_BG = (7, 19, 44, 255)


def render_svg(source: Path, width: int, height: int) -> bytes:
    if not source.is_file():
        raise SystemExit(f"Missing R15 TV branding artwork: {source}")
    return cairosvg.svg2png(url=str(source), output_width=width, output_height=height)


def stage_wallpapers(tv: Path, branding: Path) -> None:
    for flavor in ("leanback", "mobile"):
        resource_dir = tv / "app" / "src" / flavor / "res" / "drawable-nodpi"
        resource_dir.mkdir(parents=True, exist_ok=True)
        for resource_name, svg_name in WALLPAPERS:
            png_data = render_svg(branding / svg_name, 1920, 1080)
            with Image.open(io.BytesIO(png_data)) as source:
                image = source.convert("RGB")
                destination = resource_dir / f"{resource_name}.jpg"
                image.save(destination, "JPEG", quality=88, optimize=True, progressive=True)
            # Remove original formats to avoid duplicate Android resources.
            for extension in ("webp", "png"):
                old = resource_dir / f"{resource_name}.{extension}"
                if old.exists():
                    old.unlink()


def stage_icon(tv: Path, branding: Path) -> None:
    resources = tv / "app" / "src" / "main" / "res"
    # A compact, transparent mark for Android's adaptive-icon foreground.
    png_data = render_svg(branding / "r15_tv_icon.svg", 512, 512)
    with Image.open(io.BytesIO(png_data)) as source:
        foreground = source.convert("RGBA")
    foreground_dir = resources / "drawable-nodpi"
    foreground_dir.mkdir(parents=True, exist_ok=True)
    foreground.save(foreground_dir / "r15_launcher_foreground.png", "PNG", optimize=True)

    # The home-header logo is a separate transparent asset, not a square composite.
    logo_data = render_svg(branding / "r15_tv_logo.svg", 512, 512)
    with Image.open(io.BytesIO(logo_data)) as source:
        logo = source.convert("RGBA")
    logo.save(foreground_dir / "ic_logo.png", "PNG", optimize=True)

    # Legacy launcher icons need a background; adaptive icons use the separate
    # transparent foreground above and apply the launcher mask themselves.
    full = Image.new("RGBA", foreground.size, ICON_BG)
    full.alpha_composite(foreground)

    density_sizes = {
        "mipmap-mdpi": 48,
        "mipmap-hdpi": 72,
        "mipmap-xhdpi": 96,
        "mipmap-xxhdpi": 144,
        "mipmap-xxxhdpi": 192,
    }
    for directory_name, size in density_sizes.items():
        directory = resources / directory_name
        directory.mkdir(parents=True, exist_ok=True)
        resized = full.resize((size, size), Image.Resampling.LANCZOS).convert("RGB")
        resized.save(directory / "ic_launcher.png", "PNG", optimize=True)
        # Keep the round legacy icon valid and visually consistent.
        resized.save(directory / "ic_launcher_round.webp", "WEBP", quality=92, method=6)

    values_file = resources / "values" / "colors.xml"
    colors = values_file.read_text(encoding="utf-8")
    if 'name="r15_launcher_background"' not in colors:
        colors = re.sub(
            r"</resources>\s*$",
            '    <color name="r15_launcher_background">#07132C</color>\n</resources>\n',
            colors,
        )
        values_file.write_text(colors, encoding="utf-8")

    foreground_xml = resources / "drawable" / "ic_launcher_foreground.xml"
    foreground_xml.write_text(
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<bitmap xmlns:android="http://schemas.android.com/apk/res/android"\n'
        '    android:src="@drawable/r15_launcher_foreground"\n'
        '    android:gravity="center" />\n',
        encoding="utf-8",
    )
    adaptive = (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
        '    <background android:drawable="@color/r15_launcher_background" />\n'
        '    <foreground android:drawable="@drawable/ic_launcher_foreground" />\n'
        '</adaptive-icon>\n'
    )
    anydpi = resources / "mipmap-anydpi-v26"
    anydpi.mkdir(parents=True, exist_ok=True)
    (anydpi / "ic_launcher.xml").write_text(adaptive, encoding="utf-8")
    (anydpi / "ic_launcher_round.xml").write_text(adaptive, encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--tv-dir", required=True, type=Path)
    parser.add_argument("--branding-dir", required=True, type=Path)
    args = parser.parse_args()
    stage_wallpapers(args.tv_dir, args.branding_dir)
    stage_icon(args.tv_dir, args.branding_dir)
    print("Staged custom R15 launcher icon and four offline built-in wallpapers for Leanback and Mobile.")


if __name__ == "__main__":
    main()
