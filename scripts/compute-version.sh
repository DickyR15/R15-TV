#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
TV_DIR=${TV_DIR:-"$ROOT/sources/TV"}
SOURCE_REFS=${SOURCE_REFS:-"$ROOT/build/source-refs.json"}
RUN_NUMBER=${GITHUB_RUN_NUMBER:-}
OUTPUT=${GITHUB_OUTPUT:-}
VERSION_FILE="$TV_DIR/version.properties"

test -s "$VERSION_FILE"
test -s "$SOURCE_REFS"

read_property() {
  local key=$1
  sed -n "s/^${key}=//p" "$VERSION_FILE" | tail -1 | tr -d '\r'
}

base_version_code=$(read_property VERSION_CODE)
base_version_name=$(read_property VERSION_NAME)
tv_sha=$(jq -r '.sources.TV.sha' "$SOURCE_REFS")

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
  echo "Derived Android versionCode ${version_code} exceeds 2,100,000,000" >&2
  exit 1
fi

version_name="${base_version_name}-autobuild.${RUN_NUMBER}+${tv_sha:0:7}"

{
  echo "base_version_code=${base_version_code}"
  echo "base_version_name=${base_version_name}"
  echo "version_code=${version_code}"
  echo "version_name=${version_name}"
} | if [[ -n "$OUTPUT" ]]; then tee -a "$OUTPUT"; else cat; fi
