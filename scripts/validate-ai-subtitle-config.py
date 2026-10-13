#!/usr/bin/env python3
"""Validate pinned AutoBuild 170 ASR packs, latest runtime metadata, and provider allowlist."""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MODEL_MANIFEST = ROOT / "config" / "ai-subtitle-models.json"
TRANSLATION_POLICY = ROOT / "config" / "ai-subtitle-translation-policy.json"
VERSIONS = ROOT / "config" / "ai-subtitle-versions.json"

EXPECTED_LANGUAGES = ["yue", "de", "en", "fr", "es", "ja", "zh", "ko"]
EXPECTED_PROVIDERS = {"bing", "deepl", "google", "mtran", "openai"}
FORBIDDEN_PROVIDERS = {"youdao", "tencent"}
EXPECTED_SHERPA_VERSION = "1.13.8"
EXPECTED_OPENCC_VERSION = "1.4.2"


def fail(message: str) -> None:
    print(f"AI subtitle configuration validation failed: {message}", file=sys.stderr)
    raise SystemExit(1)


def load_json(path: Path) -> dict:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except Exception as error:
        fail(f"{path.relative_to(ROOT)} is not valid JSON: {error}")


def validate_models(versions: dict) -> None:
    manifest = load_json(MODEL_MANIFEST)
    base = manifest.get("base", {})
    if base.get("subtitleTarget") != "zh-TW":
        fail("subtitle target must remain zh-TW")
    if base.get("openccConversion") != "S2TWP":
        fail("Taiwan subtitle conversion must remain OpenCC S2TWP")
    if base.get("allowSimplifiedChineseOutput") is not False:
        fail("Simplified Chinese subtitle output must be disabled")

    runtime = base.get("speechRuntime", {})
    version_runtime = versions.get("speechRuntime", {})
    if runtime.get("name") != "sherpa-onnx" or runtime.get("version") != EXPECTED_SHERPA_VERSION:
        fail(f"model manifest must use sherpa-onnx {EXPECTED_SHERPA_VERSION}")
    if version_runtime.get("version") != EXPECTED_SHERPA_VERSION or version_runtime.get("commit") != runtime.get("commit"):
        fail("version manifest and model manifest disagree about sherpa-onnx")

    packs = manifest.get("languages")
    if not isinstance(packs, list):
        fail("languages must be a list")
    ids = [pack.get("id") for pack in packs]
    if ids != EXPECTED_LANGUAGES:
        fail(f"expected the eight languages in order {EXPECTED_LANGUAGES}, got {ids}")

    model_ids: set[str] = set()
    for pack in packs:
        model_id = pack.get("modelId")
        if not model_id or model_id in model_ids:
            fail(f"missing or duplicate model id: {model_id}")
        model_ids.add(model_id)
        files = pack.get("files")
        if not isinstance(files, list) or not files:
            fail(f"{model_id} has no model files")
        for entry in files:
            if not entry.get("name") or not entry.get("url", "").startswith("https://"):
                fail(f"{model_id} has a missing name or non-HTTPS URL")
            if not isinstance(entry.get("sizeBytes"), int) or entry["sizeBytes"] <= 0:
                fail(f"{model_id}/{entry.get('name')} has an invalid byte size")
            if not re.fullmatch(r"[0-9a-f]{64}", entry.get("sha256", "")):
                fail(f"{model_id}/{entry.get('name')} has an invalid SHA-256")

    vad = manifest.get("common", {}).get("vad", {})
    if not vad.get("url", "").startswith("https://") or not re.fullmatch(r"[0-9a-f]{64}", vad.get("sha256", "")):
        fail("common VAD model needs HTTPS URL and SHA-256")
    print(f"Validated {len(packs)} pinned ASR language packs and common VAD metadata.")


def validate_translation_policy(versions: dict) -> None:
    policy = load_json(TRANSLATION_POLICY)
    output = policy.get("outputRules", {})
    ui = policy.get("ui", {})
    if ui.get("locale") != "zh-TW" or ui.get("targetLanguage") != "zh-TW":
        fail("subtitle/translation UI target must remain zh-TW")
    if output.get("openccConversion") != "S2TWP":
        fail("translation output must pass through OpenCC S2TWP")
    if output.get("simplifiedChineseOutputOption") is not False:
        fail("Simplified Chinese output option must be disabled")

    providers = policy.get("allowedProviders", [])
    ids = {provider.get("id") for provider in providers}
    if ids != EXPECTED_PROVIDERS:
        fail(f"translation provider allowlist must be {sorted(EXPECTED_PROVIDERS)}, got {sorted(ids)}")
    if ids & FORBIDDEN_PROVIDERS:
        fail("a removed provider remains in the allowlist")
    forbidden = {provider.get("id") for provider in policy.get("forbiddenProviders", [])}
    if forbidden != FORBIDDEN_PROVIDERS:
        fail(f"explicit removal list must be {sorted(FORBIDDEN_PROVIDERS)}, got {sorted(forbidden)}")

    v = versions.get("traditionalChinese", {})
    if v.get("latestOfficialRelease") != EXPECTED_OPENCC_VERSION or v.get("conversion") != "s2twp":
        fail(f"version catalog must pin official OpenCC {EXPECTED_OPENCC_VERSION} with s2twp")
    version_providers = versions.get("translatorProviders", {})
    version_ids = {p.get("id") for p in version_providers.get("allowed", [])}
    if version_ids != EXPECTED_PROVIDERS:
        fail("version catalog and translation provider allowlist disagree")
    removed = {str(p).lower() for p in version_providers.get("removed", [])}
    if removed != FORBIDDEN_PROVIDERS:
        fail("version catalog must explicitly mark Youdao and Tencent removed")

    for provider in providers:
        if not provider.get("label"):
            fail(f"provider {provider.get('id')} has no Taiwan Traditional Chinese label")
    print("Validated provider allowlist: Bing, DeepL, Google, MTranServer, OpenAI-compatible; Youdao/Tencent excluded.")


def main() -> None:
    versions = load_json(VERSIONS)
    validate_models(versions)
    validate_translation_policy(versions)
    print(f"Verified runtime pins: sherpa-onnx {EXPECTED_SHERPA_VERSION}; official OpenCC {EXPECTED_OPENCC_VERSION}.")


if __name__ == "__main__":
    main()
