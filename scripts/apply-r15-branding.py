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
    icon_source = branding / "r15_tv_icon_reference.webp"
    if not icon_source.is_file():
        raise SystemExit(f"Missing requested R15 TV icon artwork: {icon_source}")

    # Restore the exact supplied R15 icon artwork, including its background.
    with Image.open(icon_source) as source:
        artwork = source.convert("RGB").resize((512, 512), Image.Resampling.LANCZOS)

    logo_dir = resources / "drawable-nodpi"
    logo_dir.mkdir(parents=True, exist_ok=True)
    artwork.save(logo_dir / "ic_logo.png", "PNG", optimize=True)

    density_sizes = {
        "mipmap-mdpi": 48,
        "mipmap-hdpi": 72,
        "mipmap-xhdpi": 96,
        "mipmap-xxhdpi": 144,
        "mipmap-xxxhdpi": 192,
    }
    launcher_resource_roots = [
        resources,
        tv / "app" / "src" / "mobile" / "res",
        tv / "app" / "src" / "leanback" / "res",
    ]
    for root in launcher_resource_roots:
        for directory_name, size in density_sizes.items():
            directory = root / directory_name
            directory.mkdir(parents=True, exist_ok=True)
            resized = artwork.resize((size, size), Image.Resampling.LANCZOS)
            resized.save(directory / "ic_launcher.png", "PNG", optimize=True)
            resized.save(directory / "ic_launcher_round.webp", "WEBP", quality=95, method=6)

        # Delete old adaptive-icon overrides in case those resources exist.
        anydpi = root / "mipmap-anydpi-v26"
        for name in ("ic_launcher.xml", "ic_launcher_round.xml"):
            path = anydpi / name
            if path.exists():
                path.unlink()

    # Preserve referenced resources: app/src/main/res/values*/ may still refer
    # to @drawable/ic_launcher_foreground, so do not delete its XML wrapper.
    for obsolete in (logo_dir / "r15_launcher_foreground.png",):
        if obsolete.exists():
            obsolete.unlink()


def patch_splash_icons(tv: Path) -> None:
    """Use the supplied R15 artwork on Android 12+ startup splash screens."""
    old = '<item name="windowSplashScreenAnimatedIcon">@drawable/ic_launcher_foreground</item>'
    new = '<item name="windowSplashScreenAnimatedIcon">@drawable/ic_logo</item>'
    for flavor in ("leanback", "mobile"):
        path = tv / "app" / "src" / flavor / "res" / "values" / "styles.xml"
        if not path.is_file():
            raise SystemExit(f"Missing expected splash theme resource: {path}")
        text = path.read_text(encoding="utf-8")
        if new in text:
            continue
        count = text.count(old)
        if count != 1:
            raise SystemExit(f"{path}: expected one stock splash icon reference, found {count}")
        path.write_text(text.replace(old, new, 1), encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--tv-dir", required=True, type=Path)
    parser.add_argument("--branding-dir", required=True, type=Path)
    args = parser.parse_args()
    stage_wallpapers(args.tv_dir, args.branding_dir)
    stage_icon(args.tv_dir, args.branding_dir)
    patch_splash_icons(args.tv_dir)
    print("Restored R15 TV launcher/homepage/splash icon artwork and staged four offline wallpapers.")


if __name__ == "__main__":
    main()
