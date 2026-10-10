#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
SOURCE_REFS=${SOURCE_REFS:?SOURCE_REFS is required}
BUILD_RESULT=${BUILD_RESULT:?BUILD_RESULT is required}
RUN_URL=${RUN_URL:?RUN_URL is required}

cp "$SOURCE_REFS" "$ROOT/state/last-attempt.json"
if [[ "$BUILD_RESULT" == success ]]; then
  cp "$SOURCE_REFS" "$ROOT/state/last-successful.json"
fi
jq -n \
  --arg result "$BUILD_RESULT" \
  --arg runUrl "$RUN_URL" \
  --arg recordedAt "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  '{result:$result, runUrl:$runUrl, recordedAt:$recordedAt}' \
  > "$ROOT/state/last-result.json"
