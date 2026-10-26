#!/usr/bin/env python3
"""Replace split FFmpeg native files with the matching FongMi 5.8.3 unified runtime.

The official 5.8.3 app APK supplies the Media3 FFmpeg JNI wrapper, while the
official MPV runtime APK supplies the unified FFmpeg core, MPV, player wrapper,
and shared C++ runtime. The Gradle VerifyMpvNativeTask remains enabled and is
the final dependency-integrity check.
"""
from __future__ import annotations

import argparse
import os
import struct
import tempfile
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


def validate_elf(data: bytes, name: str) -> None:
    if len(data) < 52 or data[:4] != b"\x7fELF":
        raise SystemExit(f"{name}: missing/invalid ELF header")
    # This pipeline produces only the Android armeabi-v7a APK.
    if data[4] != 1 or data[5] != 1 or struct.unpack_from("<H", data, 18)[0] != 40:
        raise SystemExit(f"{name}: source ELF is not 32-bit little-endian ARM")


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
    # Reopen to make sure the rewritten AAR is a readable ZIP and the intended
    # ABI now carries unified FFmpeg rather than the old split libraries.
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
    parser.add_argument("--runtime-apk", required=True, type=Path)
    parser.add_argument("--base-apk", required=True, type=Path)
    args = parser.parse_args()

    with zipfile.ZipFile(args.runtime_apk, "r") as runtime, zipfile.ZipFile(args.base_apk, "r") as base:
        runtime_libs: dict[str, bytes] = {}
        for name in ("libmpv.so", "libplayer.so", "libffmpeg.so", "libc++_shared.so"):
            data = read_apk_library(runtime, name)
            if data is None:
                raise SystemExit(f"{args.runtime_apk}: missing required library lib/{ABI}/{name}")
            runtime_libs[name] = data

        app_ffmpeg_jni = read_apk_library(base, "libffmpegJNI.so")
        if app_ffmpeg_jni is None:
            raise SystemExit(f"{args.base_apk}: missing lib/{ABI}/libffmpegJNI.so")
        app_ffmpeg_dovi = read_apk_library(base, "libffmpegDoviJNI.so")

    app_libs = args.tv_dir / "app" / "libs"
    mpv_aar = app_libs / "lib-mpvplayer-release.aar"
    ffmpeg_aar = app_libs / "lib-decoder-ffmpeg-release.aar"

    rewrite_aar(
        mpv_aar,
        {
            "libmpv.so": runtime_libs["libmpv.so"],
            "libplayer.so": runtime_libs["libplayer.so"],
            "libc++_shared.so": runtime_libs["libc++_shared.so"],
        },
        SPLIT_FFMPEG,
    )

    ffmpeg_replacements = {
        "libffmpeg.so": runtime_libs["libffmpeg.so"],
        "libffmpegJNI.so": app_ffmpeg_jni,
        "libc++_shared.so": runtime_libs["libc++_shared.so"],
    }
    ffmpeg_remove = set(SPLIT_FFMPEG)
    if app_ffmpeg_dovi is not None:
        ffmpeg_replacements["libffmpegDoviJNI.so"] = app_ffmpeg_dovi
    else:
        ffmpeg_remove.add("libffmpegDoviJNI.so")
    rewrite_aar(ffmpeg_aar, ffmpeg_replacements, ffmpeg_remove)

    print("Patched MPV/FFmpeg AARs with official FongMi Release 5.8.3 native runtime:")
    print(f"  MPV runtime source: {args.runtime_apk}")
    print(f"  FFmpeg JNI source: {args.base_apk}")
    print(f"  {mpv_aar}")
    print(f"  {ffmpeg_aar}")
    print("The Gradle verifyLeanbackReleaseMpvNative task remains enabled.")


if __name__ == "__main__":
    main()
