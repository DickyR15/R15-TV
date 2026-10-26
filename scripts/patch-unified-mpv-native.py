#!/usr/bin/env python3
"""Install unified FongMi MPV/FFmpeg native libraries for both verified ABIs.

The final APK is intentionally built for armeabi-v7a only, but FongMi 5.8.3
runs its AAR integrity task against both armeabi-v7a and arm64-v8a. Therefore
both AAR ABI payloads must use one unified libffmpeg.so and the matching
official Media3 JNI wrapper. The upstream Gradle verifier remains enabled.
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
ABIS = {
    "armeabi-v7a": {"elf_class": 1, "machine": 40},
    "arm64-v8a": {"elf_class": 2, "machine": 183},
}


def validate_elf(data: bytes, name: str, abi: str) -> None:
    spec = ABIS[abi]
    minimum = 52 if spec["elf_class"] == 1 else 64
    if len(data) < minimum or data[:4] != b"\x7fELF":
        raise SystemExit(f"{abi}/{name}: missing/invalid ELF header")
    if (
        data[4] != spec["elf_class"]
        or data[5] != 1
        or struct.unpack_from("<H", data, 18)[0] != spec["machine"]
    ):
        raise SystemExit(f"{abi}/{name}: ELF architecture mismatch")


def read_apk_library(archive: zipfile.ZipFile, abi: str, name: str) -> bytes | None:
    target = f"lib/{abi}/{name}"
    matches = [entry for entry in archive.infolist() if entry.filename == target]
    if len(matches) > 1:
        raise SystemExit(f"{archive.filename}: duplicate native entry {target}")
    if not matches:
        return None
    data = archive.read(matches[0])
    validate_elf(data, name, abi)
    return data


def read_native_library(archive: zipfile.ZipFile, abi: str, name: str) -> bytes:
    target = f"{abi}/{name}"
    matches = [entry for entry in archive.infolist() if entry.filename == target]
    if len(matches) != 1:
        raise SystemExit(f"{archive.filename}: expected exactly one {target}, found {len(matches)}")
    data = archive.read(matches[0])
    validate_elf(data, name, abi)
    return data


def read_aar_library(path: Path, abi: str, name: str) -> bytes:
    target = f"jni/{abi}/{name}"
    with zipfile.ZipFile(path, "r") as archive:
        matches = [entry for entry in archive.infolist() if entry.filename == target]
        if len(matches) != 1:
            raise SystemExit(f"{path}: expected exactly one {target}, found {len(matches)}")
        data = archive.read(matches[0])
    validate_elf(data, name, abi)
    return data


def rewrite_aar(
    path: Path, abi: str, replacements: dict[str, bytes], remove_names: set[str]
) -> None:
    if not path.is_file():
        raise SystemExit(f"Required AAR not found: {path}")
    temp_path = path.with_suffix(path.suffix + ".tmp")
    replacement_paths = {f"jni/{abi}/{name}": data for name, data in replacements.items()}
    remove_paths = {f"jni/{abi}/{name}" for name in remove_names}
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
            if name not in written:
                info = zipfile.ZipInfo(name)
                info.compress_type = zipfile.ZIP_DEFLATED
                target.writestr(info, data)

    os.replace(temp_path, path)
    with zipfile.ZipFile(path, "r") as patched:
        names = set(patched.namelist())
        for name in replacements:
            if f"jni/{abi}/{name}" not in names:
                raise SystemExit(f"{path}: missing {abi} native entry {name}")
        remaining = sorted(names & {f"jni/{abi}/{name}" for name in SPLIT_FFMPEG})
        if remaining:
            raise SystemExit(f"{path}: split FFmpeg libraries remain for {abi}: {remaining}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--tv-dir", required=True, type=Path)
    parser.add_argument("--native-libs-zip", required=True, type=Path)
    parser.add_argument("--base-apk-v7a", required=True, type=Path)
    parser.add_argument("--base-apk-arm64", required=True, type=Path)
    args = parser.parse_args()

    bases = {
        "armeabi-v7a": args.base_apk_v7a,
        "arm64-v8a": args.base_apk_arm64,
    }
    app_libs = args.tv_dir / "app" / "libs"
    mpv_aar = app_libs / "lib-mpvplayer-release.aar"
    ffmpeg_aar = app_libs / "lib-decoder-ffmpeg-release.aar"

    with zipfile.ZipFile(args.native_libs_zip, "r") as native:
        for abi, base_path in bases.items():
            with zipfile.ZipFile(base_path, "r") as base:
                native_libs = {
                    name: read_native_library(native, abi, name)
                    for name in ("libmpv.so", "libplayer.so", "libffmpeg.so", "libc++_shared.so")
                }
                app_ffmpeg_jni = read_apk_library(base, abi, "libffmpegJNI.so")
                if app_ffmpeg_jni is None:
                    raise SystemExit(f"{base_path}: missing lib/{abi}/libffmpegJNI.so")
                app_ffmpeg_dovi = read_apk_library(base, abi, "libffmpegDoviJNI.so")

            # Keep the same C++ runtime in both AARs; upstream checks hashes.
            cxx_runtime = native_libs["libc++_shared.so"]
            rewrite_aar(
                mpv_aar,
                abi,
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
            rewrite_aar(ffmpeg_aar, abi, ffmpeg_replacements, ffmpeg_remove)

    print("Patched the FongMi 5.8.3 MPV/FFmpeg AARs for both ABIs:")
    print(f"  Native runtime source: {args.native_libs_zip}")
    print(f"  Media3 JNI source (v7a): {args.base_apk_v7a}")
    print(f"  Media3 JNI source (arm64): {args.base_apk_arm64}")
    print(f"  {mpv_aar}")
    print(f"  {ffmpeg_aar}")
    print("The Gradle native integrity task remains enabled.")


if __name__ == "__main__":
    main()
