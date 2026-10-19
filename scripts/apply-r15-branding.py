#!/usr/bin/env python3
"""Render and stage R15 TV's custom launcher icon and built-in wallpapers."""

from __future__ import annotations

import argparse
import io
import re
from pathlib import Path

import cairosvg
from PIL import Image, ImageDraw, ImageFilter, ImageFont, ImageOps


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
    stage_tv_banner(tv, artwork)

    # Android 12+ masks the animated splash icon to a circle. Keep the exact
    # full artwork for launcher/home use, but pad the splash-specific copy so
    # the R15 wordmark is not clipped by the system's circular safe area.
    corners = [
        artwork.getpixel((0, 0)),
        artwork.getpixel((artwork.width - 1, 0)),
        artwork.getpixel((0, artwork.height - 1)),
        artwork.getpixel((artwork.width - 1, artwork.height - 1)),
    ]
    splash_background = tuple(
        round(sum(pixel[channel] for pixel in corners) / len(corners))
        for channel in range(3)
    )
    splash_canvas = Image.new("RGB", (512, 512), splash_background)
    splash_size = 332
    splash_art = artwork.resize((splash_size, splash_size), Image.Resampling.LANCZOS)
    splash_offset = ((512 - splash_size) // 2, (512 - splash_size) // 2)
    splash_canvas.paste(splash_art, splash_offset)
    splash_canvas.save(logo_dir / "ic_splash_logo.png", "PNG", optimize=True)

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



def stage_tv_banner(tv: Path, artwork: Image.Image) -> None:
    """Create a full-bleed Google TV banner with a large R15 TV logo and wordmark."""
    banner_dir = tv / "app" / "src" / "leanback" / "res" / "drawable-nodpi"
    banner_dir.mkdir(parents=True, exist_ok=True)

    width, height = 1280, 720

    # Fill the entire banner with an enlarged, softened version of the supplied
    # brand artwork; this avoids the unused solid-color margins of the previous
    # banner while preserving the original logo itself in the foreground.
    background = ImageOps.fit(
        artwork, (width, height), method=Image.Resampling.LANCZOS, centering=(0.5, 0.5)
    ).filter(ImageFilter.GaussianBlur(radius=28))
    navy = Image.new("RGB", (width, height), (4, 10, 34))
    banner = Image.blend(background, navy, 0.60)
    draw = ImageDraw.Draw(banner)

    # Tighten the source bounds around bright logo strokes so the launcher icon
    # uses its full banner height instead of leaving the artwork's original padding.
    pixels = artwork.load()
    xs, ys = [], []
    for y in range(artwork.height):
        for x in range(artwork.width):
            r, g, b = pixels[x, y]
            hi, lo = max(r, g, b), min(r, g, b)
            if hi >= 112 and (hi - lo >= 48 or hi >= 205):
                xs.append(x)
                ys.append(y)
    if xs and ys:
        pad = max(4, int(min(artwork.width, artwork.height) * 0.018))
        box = (
            max(0, min(xs) - pad),
            max(0, min(ys) - pad),
            min(artwork.width, max(xs) + pad + 1),
            min(artwork.height, max(ys) + pad + 1),
        )
        logo_art = artwork.crop(box)
    else:
        logo_art = artwork

    # Large logo lockup: the logo runs almost edge-to-edge vertically.
    max_logo_h = height - 8
    max_logo_w = 720
    scale = min(max_logo_h / logo_art.height, max_logo_w / logo_art.width)
    logo = logo_art.resize(
        (max(1, round(logo_art.width * scale)), max(1, round(logo_art.height * scale))),
        Image.Resampling.LANCZOS,
    )
    logo_x = 8
    logo_y = (height - logo.height) // 2
    banner.paste(logo, (logo_x, logo_y))

    font_path = Path("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf")
    font_size = 168
    font = ImageFont.truetype(str(font_path), font_size) if font_path.is_file() else ImageFont.load_default()
    title = "R15 TV"
    while font_size > 70 and draw.textbbox((0, 0), title, font=font)[2] > width - 760:
        font_size -= 4
        if font_path.is_file():
            font = ImageFont.truetype(str(font_path), font_size)
        else:
            break
    draw.text((740, 258), title, font=font, fill=(244, 249, 255),
              stroke_width=2, stroke_fill=(37, 140, 255))
    draw.rounded_rectangle((744, 445, 1228, 459), radius=7, fill=(40, 202, 255))
    banner.save(banner_dir / "r15_tv_banner.png", "PNG", optimize=True)

    manifest = tv / "app" / "src" / "leanback" / "AndroidManifest.xml"
    if not manifest.is_file():
        raise SystemExit(f"Missing Leanback manifest: {manifest}")
    text = manifest.read_text(encoding="utf-8")
    old = 'android:banner="@mipmap/ic_banner"'
    new = 'android:banner="@drawable/r15_tv_banner"'
    if new not in text:
        count = text.count(old)
        if count != 1:
            raise SystemExit(f"{manifest}: expected one stock Android TV banner reference, found {count}")
        manifest.write_text(text.replace(old, new, 1), encoding="utf-8")
    if 'android:banner="@drawable/r15_tv_banner"' not in manifest.read_text(encoding="utf-8"):
        raise SystemExit("R15 TV banner was not connected to the Leanback application manifest")


def patch_application_icons(tv: Path) -> None:
    """Point Android's package/application icon directly at the staged R15 artwork."""
    manifest = tv / "app/src/main/AndroidManifest.xml"
    replace_exact(
        manifest,
        'android:icon="@mipmap/ic_launcher"',
        'android:icon="@drawable/ic_logo"',
    )
    replace_exact(
        manifest,
        'android:roundIcon="@mipmap/ic_launcher_round"',
        'android:roundIcon="@drawable/ic_logo"',
    )


def patch_splash_icons(tv: Path) -> None:
    """Use the supplied R15 artwork on Android 12+ startup splash screens."""
    old = '<item name="windowSplashScreenAnimatedIcon">@drawable/ic_launcher_foreground</item>'
    new = '<item name="windowSplashScreenAnimatedIcon">@drawable/ic_splash_logo</item>'
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
    patch_application_icons(args.tv_dir)
    patch_splash_icons(args.tv_dir)
    print("Restored R15 TV launcher/homepage/splash icons, replaced the Google TV banner artwork, and staged four offline wallpapers.")


if __name__ == "__main__":
    main()
