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

    # Use the user's exact artwork with its original dark background.
    with Image.open(icon_source) as source:
        artwork = source.convert("RGB").resize((512, 512), Image.Resampling.LANCZOS)

    logo_dir = resources / "drawable-nodpi"
    logo_dir.mkdir(parents=True, exist_ok=True)
    # Keep the image background on the homepage logo; do not remove it.
    artwork.save(logo_dir / "ic_logo.png", "PNG", optimize=True)

    density_sizes = {
        "mipmap-mdpi": 48,
        "mipmap-hdpi": 72,
        "mipmap-xhdpi": 96,
        "mipmap-xxhdpi": 144,
        "mipmap-xxxhdpi": 192,
    }
    # Update all variants. Android TV's launcher may resolve resources from the
    # Leanback source set instead of main/res.
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

        # An adaptive-icon XML took precedence over these raster assets and
        # displayed the obsolete blue-slash image. Remove that override.
        anydpi = root / "mipmap-anydpi-v26"
        for name in ("ic_launcher.xml", "ic_launcher_round.xml"):
            path = anydpi / name
            if path.exists():
                path.unlink()

    # Remove obsolete adaptive foreground assets from previous branding builds.
    for obsolete in (
        logo_dir / "r15_launcher_foreground.png",
        resources / "drawable" / "ic_launcher_foreground.xml",
    ):
        if obsolete.exists():
            obsolete.unlink()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--tv-dir", required=True, type=Path)
    parser.add_argument("--branding-dir", required=True, type=Path)
    args = parser.parse_args()
    stage_wallpapers(args.tv_dir, args.branding_dir)
    stage_icon(args.tv_dir, args.branding_dir)
    print("Restored the supplied R15 TV icon with its original background for launcher and homepage logo; staged four offline wallpapers.")


if __name__ == "__main__":
    main()
