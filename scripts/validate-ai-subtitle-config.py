#!/usr/bin/env python3
"""Validate the pinned AutoBuild 170 ASR model and translation-provider policy."""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MODEL_MANIFEST = ROOT / "config" / "ai-subtitle-models.json"
TRANSLATION_POLICY = ROOT / "config" / "ai-subtitle-translation-policy.json"

EXPECTED_LANGUAGES = ["yue", "de", "en", "fr", "es", "ja", "zh", "ko"]
EXPECTED_PROVIDERS = {"bing", "deepl", "google", "mtran", "openai"}
FORBIDDEN_PROVIDERS = {"youdao", "tencent"}


def fail(message: str) -> None:
    print(f"AI subtitle configuration validation failed: {message}", file=sys.stderr)
    raise SystemExit(1)


def load_json(path: Path) -> dict:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except Exception as error:
        fail(f"{path.relative_to(ROOT)} is not valid JSON: {error}")


def validate_models() -> None:
    manifest = load_json(MODEL_MANIFEST)
    base = manifest.get("base", {})
    if base.get("subtitleTarget") != "zh-TW":
        fail("subtitle target must remain zh-TW")
    if base.get("openccConversion") != "S2TWP":
        fail("Taiwan subtitle conversion must remain OpenCC S2TWP")
    if base.get("allowSimplifiedChineseOutput") is not False:
        fail("Simplified Chinese subtitle output must be disabled")

    packs = manifest.get("languages")
    if not isinstance(packs, list):
        fail("languages must be a list")
    ids = [pack.get("id") for pack in packs]
    if ids != EXPECTED_LANGUAGES:
        fail(f"expected the eight languages in order {EXPECTED_LANGUAGES}, got {ids}")

    all_model_ids: set[str] = set()
    for pack in packs:
        model_id = pack.get("modelId")
        if not model_id or model_id in all_model_ids:
            fail(f"missing or duplicate model id: {model_id}")
        all_model_ids.add(model_id)
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
    print(f"Validated {len(packs)} language packs and all model file checksums/size metadata.")


def validate_translation_policy() -> None:
    policy = load_json(TRANSLATION_POLICY)
    output = policy.get("outputRules", {})
    ui = policy.get("ui", {})
    if ui.get("targetLanguage") != "zh-TW":
        fail("translation target must remain zh-TW")
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
    for provider in providers:
        if not provider.get("label"):
            fail(f"provider {provider.get('id')} has no Taiwan Traditional Chinese label")
    print("Validated translation allowlist: Bing, DeepL, Google, MTranServer, OpenAI-compatible; Youdao/Tencent excluded.")


if __name__ == "__main__":
    validate_models()
    validate_translation_policy()
