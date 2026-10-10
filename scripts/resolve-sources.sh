#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
CONFIG=${CONFIG:-"$ROOT/config/sources.json"}
LAST_ATTEMPT=${LAST_ATTEMPT:-"$ROOT/state/last-attempt.json"}
OUTPUT=${OUTPUT:-"$ROOT/build/source-refs.json"}
FORCE_REQUESTED=${FORCE_REQUESTED:-false}
R15TV_SOURCE_TOKEN=${R15TV_SOURCE_TOKEN:-}

mkdir -p "$(dirname "$OUTPUT")"

if [[ -z "$R15TV_SOURCE_TOKEN" ]]; then
  echo "R15TV_SOURCE_TOKEN is required to resolve upstream source inputs" >&2
  exit 1
fi

resolve_sha() {
  key=$1
  repository=$(jq -er --arg key "$key" '.sources[$key].repository' "$CONFIG")
  branch=$(jq -er --arg key "$key" '.sources[$key].branch' "$CONFIG")
  sha=$(curl --fail --silent --show-error --location \
    --header "Authorization: Bearer ${R15TV_SOURCE_TOKEN}" \
    --header "Accept: application/vnd.github+json" \
    --header "X-GitHub-Api-Version: 2022-11-28" \
    "https://api.github.com/repos/${repository}/git/ref/heads/${branch}" \
    | jq -er '.object.sha')
  if [[ ! "$sha" =~ ^[0-9a-f]{40}$ ]]; then
    echo "Unable to resolve ${repository}:${branch}" >&2
    exit 1
  fi
  printf '%s' "$sha"
}

tv_sha=$(resolve_sha TV)
media_sha=$(resolve_sha media)
vivid_sha=$(resolve_sha VividLib)
sherpa_sha=$(resolve_sha sherpa-onnx)

jq -n \
  --slurpfile config "$CONFIG" \
  --arg tv "$tv_sha" \
  --arg media "$media_sha" \
  --arg vivid "$vivid_sha" \
  --arg sherpa "$sherpa_sha" \
  '{schema: 1, sources: ($config[0].sources | .TV.sha=$tv | .media.sha=$media | .VividLib.sha=$vivid | .["sherpa-onnx"].sha=$sherpa)}' \
  > "$OUTPUT"

changed=()
for key in TV media VividLib sherpa-onnx; do
  old=$(jq -r --arg key "$key" '.sources[$key].sha // ""' "$LAST_ATTEMPT" 2>/dev/null || true)
  case "$key" in
    TV) current=$tv_sha ;;
    media) current=$media_sha ;;
    VividLib) current=$vivid_sha ;;
    sherpa-onnx) current=$sherpa_sha ;;
  esac
  if [[ "$old" != "$current" ]]; then
    changed+=("$key")
  fi
done

if [[ ${#changed[@]} -gt 0 ]]; then
  changed_csv=$(IFS=,; echo "${changed[*]}")
  source_changed=true
else
  changed_csv=none
  source_changed=false
fi

if [[ "$FORCE_REQUESTED" == true || "$source_changed" == true ]]; then
  should_build=true
else
  should_build=false
fi

{
  echo "tv_sha=$tv_sha"
  echo "media_sha=$media_sha"
  echo "vivid_sha=$vivid_sha"
  echo "sherpa_sha=$sherpa_sha"
  echo "source_changed=$source_changed"
  echo "changed_sources=$changed_csv"
  echo "should_build=$should_build"
} | tee -a "${GITHUB_OUTPUT:-/dev/null}"

jq . "$OUTPUT"
