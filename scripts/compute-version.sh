#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
TV_DIR=${TV_DIR:-"$ROOT/sources/TV"}
SOURCE_REFS=${SOURCE_REFS:-"$ROOT/build/source-refs.json"}
RUN_NUMBER=${GITHUB_RUN_NUMBER:-}
OUTPUT=${GITHUB_OUTPUT:-}

test -s "$TV_DIR/app/build.gradle"
test -s "$SOURCE_REFS"

base_version_code=$(sed -nE 's/^[[:space:]]*versionCode[[:space:]]+([0-9]+).*$/\1/p' "$TV_DIR/app/build.gradle" | head -1 | tr -d '\r')
base_version_name=$(sed -nE 's/^[[:space:]]*versionName[[:space:]]+"([^"]+)".*$/\1/p' "$TV_DIR/app/build.gradle" | head -1 | tr -d '\r')
tv_sha=$(jq -er '.sources.TV.sha' "$SOURCE_REFS")

[[ "$base_version_code" =~ ^[1-9][0-9]*$ ]]
[[ "$RUN_NUMBER" =~ ^[1-9][0-9]*$ ]]
[[ "$tv_sha" =~ ^[0-9a-f]{40}$ ]]
test -n "$base_version_name"

if (( RUN_NUMBER >= 1000000 )); then
  echo "GitHub run number must remain below 1,000,000 for this version scheme" >&2
  exit 1
fi

version_code=$((base_version_code * 1000000 + RUN_NUMBER))
if (( version_code > 2100000000 )); then
  echo "Derived Android versionCode $version_code exceeds Android's supported range" >&2
  exit 1
fi

revision=$(printf '%s' "$tv_sha" | cut -c1-7)
version_name="$base_version_name-r15.$RUN_NUMBER+$revision"

{
  echo "base_version_code=$base_version_code"
  echo "base_version_name=$base_version_name"
  echo "version_code=$version_code"
  echo "version_name=$version_name"
} | if [[ -n "$OUTPUT" ]]; then tee -a "$OUTPUT"; else cat; fi
