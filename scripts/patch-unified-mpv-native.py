#!/usr/bin/env python3
"""Install the matching unified MPV/FFmpeg native runtime for FongMi TV 5.8.3.

The pinned FongMi/mpv-android CI artifact contains libmpv, libplayer, the
unified libffmpeg, and libc++ for both supported ABIs. The official 5.8.3 TV
APK supplies the matching Media3 FFmpeg JNI wrappers. Gradle's native
integrity task remains enabled and verifies the final AAR dependency graph.
"""
from __future__ import annotations

import argparse
import os
import struct
import zipfile
from pathlib import Path

SPLIT_FFMPEG = {
    "libavcodec.so",
    "libavdevice.so",
    "libavfilter.so",
    "libavformat.so",
    "libavutil.so",
    "libswresample.so",
    "libswscale.so",
}
ABI = "armeabi-v7a"


def validate_elf(data: bytes, name: str) -> None:
    if len(data) < 52 or data[:4] != b"\x7fELF":
        raise SystemExit(f"{name}: missing/invalid ELF header")
    if data[4] != 1 or data[5] != 1 or struct.unpack_from("<H", data, 18)[0] != 40:
        raise SystemExit(f"{name}: source ELF is not 32-bit little-endian ARM")


def read_apk_library(archive: zipfile.ZipFile, name: str) -> bytes | None:
    target = f"lib/{ABI}/{name}"
    matches = [entry for entry in archive.infolist() if entry.filename == target]
    if len(matches) > 1:
        raise SystemExit(f"{archive.filename}: duplicate native entry {target}")
    if not matches:
        return None
    data = archive.read(matches[0])
    validate_elf(data, name)
    return data


def read_native_library(archive: zipfile.ZipFile, name: str) -> bytes:
    target = f"{ABI}/{name}"
    matches = [entry for entry in archive.infolist() if entry.filename == target]
    if len(matches) != 1:
        raise SystemExit(f"{archive.filename}: expected exactly one {target}, found {len(matches)}")
    data = archive.read(matches[0])
    validate_elf(data, name)
    return data


def read_aar_library(path: Path, name: str) -> bytes:
    target = f"jni/{ABI}/{name}"
    with zipfile.ZipFile(path, "r") as archive:
        matches = [entry for entry in archive.infolist() if entry.filename == target]
        if len(matches) != 1:
            raise SystemExit(f"{path}: expected exactly one {target}, found {len(matches)}")
        data = archive.read(matches[0])
    validate_elf(data, name)
    return data


def rewrite_aar(path: Path, replacements: dict[str, bytes], remove_names: set[str]) -> None:
    if not path.is_file():
        raise SystemExit(f"Required AAR not found: {path}")
    temp_path = path.with_suffix(path.suffix + ".tmp")
    replacement_paths = {f"jni/{ABI}/{name}": data for name, data in replacements.items()}
    remove_paths = {f"jni/{ABI}/{name}" for name in remove_names}
    written: set[str] = set()

    with zipfile.ZipFile(path, "r") as source, zipfile.ZipFile(temp_path, "w") as target:
        source_names = [info.filename for info in source.infolist()]
        duplicate_names = sorted({name for name in source_names if source_names.count(name) > 1})
        if duplicate_names:
            raise SystemExit(f"{path}: duplicate ZIP entries: {duplicate_names[:8]}")

        for info in source.infolist():
            name = info.filename
            if name in remove_paths:
                continue
            if name in replacement_paths:
                data = replacement_paths[name]
                written.add(name)
            else:
                data = source.read(info)
            target.writestr(info, data)

        for name, data in replacement_paths.items():
            if name in written:
                continue
            info = zipfile.ZipInfo(name)
            info.compress_type = zipfile.ZIP_DEFLATED
            target.writestr(info, data)

    os.replace(temp_path, path)
    with zipfile.ZipFile(path, "r") as patched:
        names = set(patched.namelist())
        for name in replacements:
            if f"jni/{ABI}/{name}" not in names:
                raise SystemExit(f"{path}: missing patched native entry {name}")
        remaining = sorted(names & {f"jni/{ABI}/{name}" for name in SPLIT_FFMPEG})
        if remaining:
            raise SystemExit(f"{path}: split FFmpeg libraries remain: {remaining}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--tv-dir", required=True, type=Path)
    parser.add_argument("--native-libs-zip", required=True, type=Path)
    parser.add_argument("--base-apk", required=True, type=Path)
    args = parser.parse_args()

    with zipfile.ZipFile(args.native_libs_zip, "r") as native, zipfile.ZipFile(args.base_apk, "r") as base:
        native_libs = {
            name: read_native_library(native, name)
            for name in ("libmpv.so", "libplayer.so", "libffmpeg.so", "libc++_shared.so")
        }
        app_ffmpeg_jni = read_apk_library(base, "libffmpegJNI.so")
        if app_ffmpeg_jni is None:
            raise SystemExit(f"{args.base_apk}: missing lib/{ABI}/libffmpegJNI.so")
        app_ffmpeg_dovi = read_apk_library(base, "libffmpegDoviJNI.so")

    app_libs = args.tv_dir / "app" / "libs"
    mpv_aar = app_libs / "lib-mpvplayer-release.aar"
    ffmpeg_aar = app_libs / "lib-decoder-ffmpeg-release.aar"

    # Use one matching libc++ and one unified FFmpeg core across both AARs.
    cxx_runtime = native_libs["libc++_shared.so"]
    rewrite_aar(
        mpv_aar,
        {
            "libmpv.so": native_libs["libmpv.so"],
            "libplayer.so": native_libs["libplayer.so"],
            "libc++_shared.so": cxx_runtime,
        },
        SPLIT_FFMPEG,
    )

    ffmpeg_replacements = {
        "libffmpeg.so": native_libs["libffmpeg.so"],
        "libffmpegJNI.so": app_ffmpeg_jni,
        "libc++_shared.so": cxx_runtime,
    }
    ffmpeg_remove = set(SPLIT_FFMPEG)
    if app_ffmpeg_dovi is not None:
        ffmpeg_replacements["libffmpegDoviJNI.so"] = app_ffmpeg_dovi
    else:
        ffmpeg_remove.add("libffmpegDoviJNI.so")
    rewrite_aar(ffmpeg_aar, ffmpeg_replacements, ffmpeg_remove)

    print("Patched MPV/FFmpeg AARs with pinned FongMi unified native artifact:")
    print(f"  Native artifact: {args.native_libs_zip}")
    print(f"  Matching Media3 JNI wrappers: {args.base_apk}")
    print(f"  {mpv_aar}")
    print(f"  {ffmpeg_aar}")
    print("The Gradle verifyLeanbackReleaseMpvNative task remains enabled.")


if __name__ == "__main__":
    main()
