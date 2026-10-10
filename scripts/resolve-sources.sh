#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
CONFIG=${CONFIG:-"$ROOT/config/sources.json"}
OUTPUT=${OUTPUT:-"$ROOT/build/source-refs.json"}
GITHUB_OUTPUT_FILE=${GITHUB_OUTPUT:-/dev/null}

repository=$(jq -er '.sources.TV.repository' "$CONFIG")
branch=$(jq -er '.sources.TV.branch' "$CONFIG")

if [[ "$repository" != "FongMi/TV" ]]; then
  echo "Unexpected source repository: $repository (expected FongMi/TV)" >&2
  exit 1
fi

mkdir -p "$(dirname "$OUTPUT")"
sha=$(git ls-remote "https://github.com/${repository}.git" "refs/heads/${branch}" | awk 'NR == 1 {print $1}')
if [[ ! "$sha" =~ ^[0-9a-f]{40}$ ]]; then
  echo "Unable to resolve public source ${repository}:${branch}" >&2
  exit 1
fi

jq -n \
  --arg repository "$repository" \
  --arg branch "$branch" \
  --arg sha "$sha" \
  '{schema:1,sources:{TV:{repository:$repository,branch:$branch,sha:$sha}}}' \
  > "$OUTPUT"

{
  echo "tv_sha=$sha"
  echo "source_repository=$repository"
  echo "source_branch=$branch"
} | tee -a "$GITHUB_OUTPUT_FILE"

echo "Resolved public source: ${repository}:${branch} @ ${sha}"
