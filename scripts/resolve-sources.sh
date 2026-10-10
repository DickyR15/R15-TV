#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
CONFIG=${CONFIG:-"$ROOT/config/sources.json"}
OUTPUT=${OUTPUT:-"$ROOT/build/source-refs.json"}

repository=$(jq -er '.sources.TV.repository' "$CONFIG")
branch=$(jq -er '.sources.TV.branch' "$CONFIG")
mkdir -p "$(dirname "$OUTPUT")"

api_url="https://api.github.com/repos/$repository/git/ref/heads/$branch"
response=$(curl --fail --silent --show-error --location \
  --header "Accept: application/vnd.github+json" \
  --header "X-GitHub-Api-Version: 2022-11-28" \
  "$api_url")
tv_sha=$(jq -er '.object.sha' <<< "$response")

if [[ ! "$tv_sha" =~ ^[0-9a-f]{40}$ ]]; then
  echo "Unable to resolve public source $repository:$branch" >&2
  exit 1
fi

jq -n \
  --arg repository "$repository" \
  --arg branch "$branch" \
  --arg sha "$tv_sha" \
  '{schema: 1, sources: {TV: {repository: $repository, branch: $branch, sha: $sha}}}' \
  > "$OUTPUT"

{
  echo "tv_sha=$tv_sha"
  echo "source_repository=$repository"
  echo "source_branch=$branch"
} >> "$GITHUB_OUTPUT"

echo "Resolved public source $repository:$branch at $tv_sha"
jq . "$OUTPUT"
